package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Focused Unit Tests for Store-and-Forward Messaging, Offline Queueing, and Delivery Recovery.
 * Validates:
 * 1. Outgoing messages enqueued as QUEUED when no direct routes are connected.
 * 2. Strict Privacy Protection: Relay nodes NEVER store plaintext (text is null, only ciphertext/nonce stored).
 * 3. Link Reconnection: Forwarding attempts transition outgoing status to FORWARDING and remove forwarded relay items.
 * 4. Capacity Limit & SOS Preemption: Max 100 messages bounded capacity; Emergency SOS evicts oldest normal message and preempts dispatch order.
 * 5. TTL Expiration Pruning: Expired packets are purged and not forwarded.
 * 6. Authenticated Delivery ACK: Destination HMAC-SHA256 ACK marks message DELIVERED (relays never emit false ACKs).
 * 7. Deduplication: Message IDs are tracked to suppress duplicate processing across retries and restarts.
 */
class StoreAndForwardQueueTest {

    private lateinit var queue: MeshMessageQueue

    @Before
    fun setUp() {
        // Construct in-memory queue without Android context for JVM unit test isolation
        queue = MeshMessageQueue(context = null)
    }

    // -------------------------------------------------------------------------
    // Test 1: Outgoing Message Enqueued as QUEUED When Disconnected
    // -------------------------------------------------------------------------
    @Test
    fun testOutgoingMessageEnqueuedWhenDisconnected() {
        val now = System.currentTimeMillis()
        val msg = MeshMessageQueue.QueuedMessage(
            id = "msg-out-001",
            senderId = "NODE-AAA111",
            senderPublicKey = "Base64SenderPubKey",
            recipientId = "NODE-DDD444",
            encryptedPayload = "CiphertextBase64...",
            nonce = "NonceBase64...",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 86400000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )

        val enqueued = queue.enqueue(msg)
        assertTrue("Outgoing message must be successfully enqueued", enqueued)

        val retrieved = queue.getMessage("msg-out-001")
        assertNotNull("Message must be retrievable from queue", retrieved)
        assertEquals("msg-out-001", retrieved?.id)
        assertEquals(MeshMessageQueue.STATUS_QUEUED, retrieved?.status)
        assertTrue("isOutgoing must be true", retrieved?.isOutgoing == true)

        val pending = queue.getPendingMessages()
        assertEquals(1, pending.size)
        assertEquals("msg-out-001", pending[0].id)
    }

    // -------------------------------------------------------------------------
    // Test 2: Relay Message Privacy Enforcement (Zero Plaintext Stored)
    // -------------------------------------------------------------------------
    @Test
    fun testRelayMessagePrivacyEnforcement() {
        val now = System.currentTimeMillis()
        // An intermediate relay node enqueues an incoming encrypted packet for forwarding
        // Even if raw text accidentally reached the enqueue call, the queue MUST sanitize it to null
        val enqueued = queue.enqueue(
            id = "relay-packet-999",
            senderId = "NODE-AAA111",
            recipientId = "NODE-DDD444",
            text = "Confidential Plaintext that must NEVER be persisted on relay nodes",
            encryptedPayload = "AQIDBAUGBwgJCgsMDQ4PEA==",
            nonce = "ESIzRFVmd4g=",
            authTag = null,
            originalSenderKey = "Base64OriginalSenderKey",
            type = "encrypted_chat",
            hopCount = 2,
            hopLimit = 5,
            priority = "normal",
            isOutgoing = false
        )

        assertTrue("Relay message must be accepted into queue", enqueued)

        val stored = queue.getMessage("relay-packet-999")
        assertNotNull("Relay packet must be stored", stored)

        // Strict Privacy Rule Assertion
        assertNull("Relay message MUST NEVER store plaintext! (text must be null)", stored?.text)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEA==", stored?.encryptedPayload)
        assertEquals("ESIzRFVmd4g=", stored?.nonce)
        assertEquals("NODE-DDD444", stored?.recipientId)
        assertFalse("isOutgoing must be false for relayed packets", stored?.isOutgoing == true)
    }

    // -------------------------------------------------------------------------
    // Test 3: Link Reconnection Drainage and Status Transition to FORWARDING
    // -------------------------------------------------------------------------
    @Test
    fun testReconnectionDrainAndStatusTransition() {
        val now = System.currentTimeMillis()

        // 1. Enqueue outgoing message
        val outMsg = MeshMessageQueue.QueuedMessage(
            id = "out-retry-01",
            senderId = "NODE-LOCAL",
            senderPublicKey = "Base64Key",
            recipientId = "NODE-PEER",
            encryptedPayload = "EncryptedPayload",
            nonce = "Nonce",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        queue.enqueue(outMsg)

        // Verify initial retry readiness
        assertTrue("Message should be ready for initial dispatch attempt", outMsg.canRetry(now))

        // 2. Simulate successful dispatch on reconnection
        queue.recordForwardAttempt("out-retry-01", success = true, now = now)

        val afterAttempt = queue.getMessage("out-retry-01")
        assertNotNull(afterAttempt)
        assertEquals("Status must transition to FORWARDING on successful dispatch", MeshMessageQueue.STATUS_FORWARDING, afterAttempt?.status)
        assertEquals(1, afterAttempt?.retryCount)

        // 3. Enqueue a RELAY message and simulate dispatch
        queue.enqueue(
            id = "relay-drain-01",
            senderId = "NODE-AAA",
            recipientId = "NODE-BBB",
            text = null,
            encryptedPayload = "Payload",
            nonce = "Nonce",
            authTag = null,
            originalSenderKey = null,
            type = "encrypted_chat",
            hopCount = 1,
            hopLimit = 5,
            priority = "normal",
            isOutgoing = false
        )
        assertNotNull("Relay packet should exist before dispatch", queue.getMessage("relay-drain-01"))

        // RELIABLE HANDOFF: Transport write alone does NOT delete the relay packet!
        // It transitions to FORWARDING so that it survives if the downstream link drops
        queue.recordForwardAttempt("relay-drain-01", success = true, now = now)
        assertNotNull("Relay packet must be retained in queue until custody ACK is received", queue.getMessage("relay-drain-01"))
        assertEquals(MeshMessageQueue.STATUS_FORWARDING, queue.getMessage("relay-drain-01")?.status)

        // Once the downstream relay returns verified custody ACK, custody is transferred and packet is purged
        val handedOff = queue.markCustodyTransferred("relay-drain-01")
        assertTrue("markCustodyTransferred must return true", handedOff)
        assertNull("Relay packet must be purged from queue once downstream custody is confirmed", queue.getMessage("relay-drain-01"))
    }

    // -------------------------------------------------------------------------
    // Test 4: Capacity Limit (100) and Emergency SOS Eviction / Priority Ordering
    // -------------------------------------------------------------------------
    @Test
    fun testCapacityLimitAndEmergencySosEviction() {
        val now = System.currentTimeMillis()

        // 1. Fill queue to maximum capacity (100 normal messages)
        for (i in 1..MeshMessageQueue.MAX_CAPACITY) {
            val msg = MeshMessageQueue.QueuedMessage(
                id = "norm-msg-$i",
                senderId = "SENDER",
                senderPublicKey = "KEY",
                recipientId = "RECIP",
                encryptedPayload = null,
                nonce = null,
                text = "Message $i",
                type = "broadcast",
                priority = "normal",
                createdAt = now + i,
                expiresAt = now + 100000L + i,
                hopCount = 0,
                hopLimit = 5,
                status = MeshMessageQueue.STATUS_QUEUED,
                isOutgoing = true
            )
            val added = queue.enqueue(msg)
            assertTrue("Message $i must be enqueued", added)
        }

        assertEquals(MeshMessageQueue.MAX_CAPACITY, queue.getQueueStats().optInt("totalCount"))

        // 2. 101st normal message must be rejected (queue full)
        val overflowNormal = MeshMessageQueue.QueuedMessage(
            id = "norm-msg-overflow",
            senderId = "SENDER",
            senderPublicKey = "KEY",
            recipientId = "RECIP",
            encryptedPayload = null,
            nonce = null,
            text = "Overflow",
            type = "broadcast",
            priority = "normal",
            createdAt = now + 200,
            expiresAt = now + 200000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        val overflowAdded = queue.enqueue(overflowNormal)
        assertFalse("101st normal message must be rejected when capacity is full", overflowAdded)

        // 3. Emergency SOS message must PREEMPT and EVICT the oldest normal message
        val emergencySos = MeshMessageQueue.QueuedMessage(
            id = "sos-emergency-001",
            senderId = "SENDER",
            senderPublicKey = "KEY",
            recipientId = "BROADCAST",
            encryptedPayload = null,
            nonce = null,
            text = "MAYDAY MAYDAY",
            type = "sos",
            priority = "emergency",
            createdAt = now + 300,
            expiresAt = now + 300000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        val sosAdded = queue.enqueue(emergencySos)
        assertTrue("Emergency SOS must be accepted by evicting oldest normal message", sosAdded)
        assertEquals(MeshMessageQueue.MAX_CAPACITY, queue.getQueueStats().optInt("totalCount"))

        // Verify oldest normal message (norm-msg-1) was evicted
        assertNull("Oldest normal message must have been evicted for SOS", queue.getMessage("norm-msg-1"))

        // Verify Emergency SOS is prioritized first in pending queue
        val pending = queue.getPendingMessages()
        assertEquals("sos-emergency-001", pending.first().id)
        assertEquals("emergency", pending.first().priority)
    }

    // -------------------------------------------------------------------------
    // Test 5: TTL Expiration Pruning
    // -------------------------------------------------------------------------
    @Test
    fun testTtlExpirationPruner() {
        val now = System.currentTimeMillis()

        // 1. Cannot enqueue already expired message
        val alreadyExpired = MeshMessageQueue.QueuedMessage(
            id = "expired-immediate",
            senderId = "S",
            senderPublicKey = "K",
            recipientId = "R",
            encryptedPayload = null,
            nonce = null,
            text = "Already Expired",
            type = "broadcast",
            priority = "normal",
            createdAt = now - 5000L,
            expiresAt = now - 1000L, // in the past
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        val added = queue.enqueue(alreadyExpired)
        assertFalse("Cannot enqueue already-expired message", added)

        // 2. Enqueue message that expires in 500ms
        val shortLived = MeshMessageQueue.QueuedMessage(
            id = "short-lived-01",
            senderId = "S",
            senderPublicKey = "K",
            recipientId = "R",
            encryptedPayload = null,
            nonce = null,
            text = "Short lived",
            type = "broadcast",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 500L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        queue.enqueue(shortLived)
        assertEquals(1, queue.getPendingMessages().size)

        // Advance time past expiration
        val futureTime = now + 600L
        val prunedCount = queue.pruneExpiredMessages(futureTime)
        assertEquals(1, prunedCount)

        // Expired message must either be purged or marked STATUS_EXPIRED
        val expiredMsg = queue.getMessage("short-lived-01")
        assertTrue("Message must be either removed or marked EXPIRED", expiredMsg == null || expiredMsg.status == MeshMessageQueue.STATUS_EXPIRED)

        // Must no longer appear in pending queue
        val pendingAfterPrune = queue.getPendingMessages()
        assertTrue("Pending queue must be empty after pruning expired items", pendingAfterPrune.none { it.id == "short-lived-01" })
    }

    // -------------------------------------------------------------------------
    // Test 6: Destination Authenticated Delivery ACK
    // -------------------------------------------------------------------------
    @Test
    fun testAuthenticatedDeliveryAckMarksDelivered() {
        val now = System.currentTimeMillis()
        val outMsg = MeshMessageQueue.QueuedMessage(
            id = "msg-e2e-ack-test",
            senderId = "NODE-SENDER",
            senderPublicKey = "KeySender",
            recipientId = "NODE-DEST",
            encryptedPayload = "EncryptedBytes",
            nonce = "NonceBytes",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        queue.enqueue(outMsg)

        // Simulate forward dispatch
        queue.recordForwardAttempt("msg-e2e-ack-test", success = true, now = now)
        assertEquals(MeshMessageQueue.STATUS_FORWARDING, queue.getMessage("msg-e2e-ack-test")?.status)

        // Destination node computes HMAC-SHA256 authTag and returns ACK
        // Sender validates auth tag and invokes markDelivered
        val marked = queue.markDelivered("msg-e2e-ack-test")
        assertTrue("markDelivered should return true for known queued message", marked)

        val deliveredMsg = queue.getMessage("msg-e2e-ack-test")
        assertNotNull(deliveredMsg)
        assertEquals("Status must be updated to DELIVERED", MeshMessageQueue.STATUS_DELIVERED, deliveredMsg?.status)

        // Delivered messages must no longer be in pending dispatch queue
        val pending = queue.getPendingMessages()
        assertTrue("Delivered message must not be pending dispatch", pending.none { it.id == "msg-e2e-ack-test" })
    }

    // -------------------------------------------------------------------------
    // Test 7: Deduplication Prevents Duplicate Processing
    // -------------------------------------------------------------------------
    @Test
    fun testPersistentDeduplicationPreventsDuplicateProcessing() {
        val messageId = "dedup-unique-msg-777"

        // Initially not a duplicate
        assertFalse("Message must not initially be considered duplicate", queue.isDuplicate(messageId))

        // Mark as processed
        queue.markProcessed(messageId)

        // Now must be detected as duplicate
        assertTrue("Message must now be recognized as duplicate", queue.isDuplicate(messageId))

        // Attempting to enqueue this message ID must be dropped
        val now = System.currentTimeMillis()
        val duplicateMsg = MeshMessageQueue.QueuedMessage(
            id = messageId,
            senderId = "NODE-S",
            senderPublicKey = "KEY",
            recipientId = "NODE-R",
            encryptedPayload = null,
            nonce = null,
            text = "Duplicate payload",
            type = "broadcast",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = false
        )
        val added = queue.enqueue(duplicateMsg)
        assertFalse("Duplicate message must be rejected from enqueue", added)
    }
}
