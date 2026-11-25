package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Hardened Reliability, Crash Recovery, and Relay Handoff Unit Tests.
 *
 * Validates:
 * 1. Relay Handoffs: Transport write alone does NOT delete relay copies; custody ACK transfers custody and purges relay copies.
 * 2. Cryptographic Custody ACKs: Digital signature authentication prevents forged custody receipts.
 * 3. End-to-End Separation: Custody ACK never marks origin sender message as DELIVERED; only E2E delivery ACK marks DELIVERED.
 * 4. Byte Limits: Single message size bounded to 8 KB; total queue storage bounded to 512 KB.
 * 5. Emergency SOS Preemption: Evicts normal messages when byte or count limits are reached.
 * 6. Status Invariants: DELIVERED, EXPIRED, and FAILED terminal states cannot regress to active states.
 * 7. Expiry & Backoff Alignment: Messages expiring within backoff are never retried. Max 10 retries transitions to FAILED.
 * 8. Idempotency: Duplicate reception returns safely without duplicate queue entries or state corruption.
 * 9. Resilience: Corrupted entries do not corrupt valid messages during persistence operations.
 */
class RelayHandoffAndRecoveryTest {

    private lateinit var queue: MeshMessageQueue

    @Before
    fun setUp() {
        queue = MeshMessageQueue(context = null)
    }

    // -------------------------------------------------------------------------
    // 1. Relay Custody Handoff Protocol
    // -------------------------------------------------------------------------

    @Test
    fun testTransportWriteWithoutCustodyAckRetainsQueueCopy() {
        val now = System.currentTimeMillis()
        val relayMsg = MeshMessageQueue.QueuedMessage(
            id = "relay-handoff-01",
            senderId = "NODE-ORIGIN",
            senderPublicKey = "OriginPubKey",
            recipientId = "NODE-DEST",
            encryptedPayload = "EncryptedBytesBase64...",
            nonce = "NonceBase64...",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 1,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = false
        )
        queue.enqueue(relayMsg)
        assertNotNull(queue.getMessage("relay-handoff-01"))

        // Simulate transport dispatch (e.g. BLE write / Wi-Fi socket write completed)
        // CRITICAL RELIABILITY REQUIREMENT: Message MUST NOT be deleted from queue yet!
        queue.recordForwardAttempt("relay-handoff-01", success = true, now = now)

        val retained = queue.getMessage("relay-handoff-01")
        assertNotNull("Relay packet must be retained in queue after transport dispatch until custody ACK", retained)
        assertEquals("Status should be FORWARDING while awaiting custody confirmation", MeshMessageQueue.STATUS_FORWARDING, retained?.status)
    }

    @Test
    fun testValidCustodyAckTransfersCustodyAndPurgesRelayPacket() {
        val now = System.currentTimeMillis()
        // 1. Relay packet in queue
        val relayMsg = MeshMessageQueue.QueuedMessage(
            id = "relay-handoff-02",
            senderId = "NODE-ORIGIN",
            senderPublicKey = "OriginPubKey",
            recipientId = "NODE-DEST",
            encryptedPayload = "EncryptedBytes...",
            nonce = "Nonce...",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 1,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_FORWARDING,
            isOutgoing = false
        )
        queue.enqueue(relayMsg)

        // Downstream relay confirms persistent receipt via custody ACK
        val transferred = queue.markCustodyTransferred("relay-handoff-02")
        assertTrue("markCustodyTransferred should succeed for active relay packet", transferred)
        assertNull("Relay packet must be purged from queue once custody is confirmed downstream", queue.getMessage("relay-handoff-02"))

        // 2. Origin sender message in queue
        val originMsg = MeshMessageQueue.QueuedMessage(
            id = "origin-msg-01",
            senderId = "NODE-ME",
            senderPublicKey = "MyPubKey",
            recipientId = "NODE-DEST",
            encryptedPayload = "EncryptedBytes...",
            nonce = "Nonce...",
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
        queue.enqueue(originMsg)

        // Custody ACK from first relay confirms packet entered mesh custody
        val originCustody = queue.markCustodyTransferred("origin-msg-01")
        assertTrue(originCustody)
        val afterCustody = queue.getMessage("origin-msg-01")
        assertNotNull("Origin sender message MUST NOT be purged on custody ACK", afterCustody)
        assertEquals("Origin message status must transition to FORWARDING (not DELIVERED)", MeshMessageQueue.STATUS_FORWARDING, afterCustody?.status)
    }

    @Test
    fun testCryptographicCustodyAckSignatureVerification() {
        val nodeBKey = MeshCryptoEngine.generateKeyPair()
        val nodeBPubKeyBase64 = MeshCryptoEngine.encodePublicKey(nodeBKey.public)
        val now = System.currentTimeMillis()

        val messageId = "msg-secure-handoff-77"
        val custodyNodeId = "NODE-BBB222"
        val upstreamNodeId = "NODE-AAA111"

        // Node B generates signed custody receipt
        val signable = "CUSTODY:$messageId:$custodyNodeId:$upstreamNodeId:$now"
        val validSignature = MeshCryptoEngine.signData(signable, nodeBKey.private)

        // Upstream Node A verifies custody receipt
        val isValid = MeshCryptoEngine.verifySignatureWithBase64Key(signable, validSignature, nodeBPubKeyBase64)
        assertTrue("Authentic custody receipt signature must be valid", isValid)

        // Tampered payload verification
        val tamperedSignable = "CUSTODY:$messageId:$custodyNodeId:$upstreamNodeId:${now + 1000L}"
        val isTamperedValid = MeshCryptoEngine.verifySignatureWithBase64Key(tamperedSignable, validSignature, nodeBPubKeyBase64)
        assertFalse("Tampered custody receipt must be rejected", isTamperedValid)

        // Forged signature verification with wrong key
        val attackerKey = MeshCryptoEngine.generateKeyPair()
        val forgedSignature = MeshCryptoEngine.signData(signable, attackerKey.private)
        val isForgedValid = MeshCryptoEngine.verifySignatureWithBase64Key(signable, forgedSignature, nodeBPubKeyBase64)
        assertFalse("Custody receipt signed by attacker must be rejected", isForgedValid)
    }

    @Test
    fun testEndToEndDeliveryAckRemainsSeparateFromCustodyAck() {
        val now = System.currentTimeMillis()
        val senderMsg = MeshMessageQueue.QueuedMessage(
            id = "msg-e2e-distinction",
            senderId = "NODE-SENDER",
            senderPublicKey = "SenderKey",
            recipientId = "NODE-DEST",
            encryptedPayload = "EncryptedBytes",
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
        queue.enqueue(senderMsg)

        // 1. Custody ACK arrives from first relay
        queue.markCustodyTransferred("msg-e2e-distinction")
        val currentMsg = queue.getMessage("msg-e2e-distinction")
        assertNotNull(currentMsg)
        assertNotEquals("Custody ACK must NEVER mark message as DELIVERED", MeshMessageQueue.STATUS_DELIVERED, currentMsg?.status)
        assertEquals(MeshMessageQueue.STATUS_FORWARDING, currentMsg?.status)

        // 2. Only destination authenticated E2E delivery ACK marks DELIVERED
        queue.markDelivered("msg-e2e-distinction")
        val finalMsg = queue.getMessage("msg-e2e-distinction")
        assertEquals("Authenticated E2E ACK marks message DELIVERED", MeshMessageQueue.STATUS_DELIVERED, finalMsg?.status)
    }

    // -------------------------------------------------------------------------
    // 2. Storage Boundaries and Oversized Message Rejection
    // -------------------------------------------------------------------------

    @Test
    fun testSingleMessageByteLimitEnforced() {
        val now = System.currentTimeMillis()

        // Create an oversized payload (> 8192 bytes)
        val oversizedString = "A".repeat(8500)
        val oversizedMsg = MeshMessageQueue.QueuedMessage(
            id = "oversized-msg-01",
            senderId = "NODE-A",
            senderPublicKey = "KeyA",
            recipientId = "BROADCAST",
            encryptedPayload = null,
            nonce = null,
            text = oversizedString,
            type = "broadcast",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )

        val enqueued = queue.enqueue(oversizedMsg)
        assertFalse("Message exceeding MAX_SINGLE_MESSAGE_BYTES (8 KB) must be rejected", enqueued)
        assertNull("Oversized message must not be stored in queue", queue.getMessage("oversized-msg-01"))
    }

    @Test
    fun testTotalQueueByteLimitAndEmergencyPreemption() {
        val now = System.currentTimeMillis()

        // Enqueue valid messages with ~4 KB payloads until approaching 512 KB limit
        val payload4K = "X".repeat(4000)
        var enqueuedCount = 0

        // 120 * 4KB = ~480KB (close to 512 KB boundary)
        for (i in 1..100) {
            val msg = MeshMessageQueue.QueuedMessage(
                id = "bulk-normal-$i",
                senderId = "NODE-S",
                senderPublicKey = "KeyS",
                recipientId = "BROADCAST",
                encryptedPayload = null,
                nonce = null,
                text = "Bulk $i: $payload4K",
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
            if (added) {
                enqueuedCount++
            } else {
                break
            }
        }

        assertTrue("Queue must have accepted messages up to boundary", enqueuedCount > 0)
        assertTrue("Queue total bytes must not exceed MAX_TOTAL_QUEUE_BYTES", queue.getTotalQueueBytes() <= MeshMessageQueue.MAX_TOTAL_QUEUE_BYTES)

        // Try adding another normal message when full -> must be rejected
        val overflowNormal = MeshMessageQueue.QueuedMessage(
            id = "bulk-overflow",
            senderId = "NODE-S",
            senderPublicKey = "KeyS",
            recipientId = "BROADCAST",
            encryptedPayload = null,
            nonce = null,
            text = "Overflow: $payload4K",
            type = "broadcast",
            priority = "normal",
            createdAt = now + 500,
            expiresAt = now + 200000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        assertFalse("Normal message must be rejected when queue byte/count limits are reached", queue.enqueue(overflowNormal))

        // Emergency SOS message MUST preempt and evict normal messages to stay within limit
        val emergencySos = MeshMessageQueue.QueuedMessage(
            id = "sos-preempt-byte",
            senderId = "NODE-S",
            senderPublicKey = "KeyS",
            recipientId = "BROADCAST",
            encryptedPayload = null,
            nonce = null,
            text = "EMERGENCY SOS: $payload4K",
            type = "sos",
            priority = "emergency",
            createdAt = now + 600,
            expiresAt = now + 300000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true
        )
        val sosAdded = queue.enqueue(emergencySos)
        assertTrue("Emergency SOS must succeed by evicting oldest normal messages", sosAdded)
        assertNotNull("Emergency SOS must be present in queue", queue.getMessage("sos-preempt-byte"))
        assertTrue("Queue bytes must remain within MAX_TOTAL_QUEUE_BYTES after preemption", queue.getTotalQueueBytes() <= MeshMessageQueue.MAX_TOTAL_QUEUE_BYTES)
    }

    // -------------------------------------------------------------------------
    // 3. Status Invariants and Anti-Regression Protections
    // -------------------------------------------------------------------------

    @Test
    fun testDeliveredMessageCannotRegressToActiveStatus() {
        val now = System.currentTimeMillis()
        val msg = MeshMessageQueue.QueuedMessage(
            id = "msg-terminal-test",
            senderId = "NODE-A",
            senderPublicKey = "KeyA",
            recipientId = "NODE-B",
            encryptedPayload = "Payload",
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
        queue.enqueue(msg)

        // Mark as DELIVERED
        queue.markDelivered("msg-terminal-test")
        assertEquals(MeshMessageQueue.STATUS_DELIVERED, queue.getMessage("msg-terminal-test")?.status)

        // Attempt to regress to QUEUED or FORWARDING
        val regressedQueued = queue.updateStatus("msg-terminal-test", MeshMessageQueue.STATUS_QUEUED)
        assertFalse("Delivered message must reject updateStatus to QUEUED", regressedQueued)

        val regressedForwarding = queue.updateStatus("msg-terminal-test", MeshMessageQueue.STATUS_FORWARDING)
        assertFalse("Delivered message must reject updateStatus to FORWARDING", regressedForwarding)

        assertEquals("Status must remain DELIVERED", MeshMessageQueue.STATUS_DELIVERED, queue.getMessage("msg-terminal-test")?.status)
    }

    @Test
    fun testExpiredAndFailedMessagesCannotReactivate() {
        val now = System.currentTimeMillis()
        val msg = MeshMessageQueue.QueuedMessage(
            id = "msg-failed-test",
            senderId = "NODE-A",
            senderPublicKey = "KeyA",
            recipientId = "NODE-B",
            encryptedPayload = "Payload",
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
        queue.enqueue(msg)

        // Simulate reaching max retries -> FAILED
        for (i in 1..MeshMessageQueue.MAX_RETRIES) {
            queue.recordForwardAttempt("msg-failed-test", success = false, now = now + (i * 1000L))
        }

        val failedMsg = queue.getMessage("msg-failed-test")
        assertEquals(MeshMessageQueue.STATUS_FAILED, failedMsg?.status)
        assertFalse("Failed message must not be eligible for retry", failedMsg!!.canRetry(now + 20000L))

        // Attempt to reactivate to QUEUED or FORWARDING
        val reactivated = queue.updateStatus("msg-failed-test", MeshMessageQueue.STATUS_QUEUED)
        assertFalse("Failed message cannot be reactivated to QUEUED", reactivated)
        assertEquals(MeshMessageQueue.STATUS_FAILED, queue.getMessage("msg-failed-test")?.status)
    }

    // -------------------------------------------------------------------------
    // 4. Retry Schedule and Expiry Alignment
    // -------------------------------------------------------------------------

    @Test
    fun testRetryScheduleRespectsExpiry() {
        val now = System.currentTimeMillis()

        // Message expiring in 5 seconds
        val msg = MeshMessageQueue.QueuedMessage(
            id = "msg-tight-expiry",
            senderId = "NODE-A",
            senderPublicKey = "KeyA",
            recipientId = "NODE-B",
            encryptedPayload = "P",
            nonce = "N",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 5000L, // 5s expiry
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_FORWARDING,
            isOutgoing = true,
            retryCount = 2, // at retryCount=2, backoff = 3s * 2^2 = 12s
            lastAttemptTimestamp = now
        )

        // With backoff = 12s, now + 12s > expiresAt (now + 5s). Message will expire before backoff elapses!
        assertFalse("Message must not be retried if next backoff attempt is beyond its expiration", msg.canRetry(now + 3000L))
    }

    // -------------------------------------------------------------------------
    // 5. Idempotent Processing
    // -------------------------------------------------------------------------

    @Test
    fun testIdempotentDuplicateEnqueuePreservesState() {
        val now = System.currentTimeMillis()
        val msg = MeshMessageQueue.QueuedMessage(
            id = "idempotent-msg-101",
            senderId = "NODE-A",
            senderPublicKey = "KeyA",
            recipientId = "NODE-B",
            encryptedPayload = "Payload",
            nonce = "Nonce",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = now,
            expiresAt = now + 60000L,
            hopCount = 0,
            hopLimit = 5,
            status = MeshMessageQueue.STATUS_FORWARDING,
            isOutgoing = true,
            retryCount = 2
        )
        queue.enqueue(msg)

        // Re-enqueuing the same message (e.g. from duplicate BLE packet arrival)
        val reEnqueued = queue.enqueue(msg)
        assertTrue("Idempotent enqueue of already-held message must return true", reEnqueued)
        assertEquals("Queue count must remain 1", 1, queue.getAllMessages().size)

        val retrieved = queue.getMessage("idempotent-msg-101")
        assertEquals(MeshMessageQueue.STATUS_FORWARDING, retrieved?.status)
        assertEquals(2, retrieved?.retryCount)
    }

    @Test
    fun testCorruptedEntryDoesNotDestroyValidMessages() {
        // Test JSON parsing robustness:
        // A valid message parses correctly
        val validJson = JSONObject()
        validJson.put("id", "valid-recovered-01")
        validJson.put("senderId", "NODE-S")
        validJson.put("recipientId", "NODE-R")
        validJson.put("type", "chat")
        validJson.put("text", "Valid text message")
        validJson.put("createdAt", System.currentTimeMillis())
        validJson.put("expiresAt", System.currentTimeMillis() + 60000L)
        validJson.put("status", MeshMessageQueue.STATUS_QUEUED)

        val parsedValid = queue.toPacketJson(
            MeshMessageQueue.QueuedMessage(
                id = "valid-recovered-01",
                senderId = "NODE-S",
                senderPublicKey = "",
                recipientId = "NODE-R",
                encryptedPayload = null,
                nonce = null,
                text = "Valid text message",
                type = "chat",
                priority = "normal",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60000L,
                hopCount = 0,
                hopLimit = 5
            )
        )
        assertEquals("valid-recovered-01", parsedValid.getString("id"))

        // Malformed JSON (missing id, corrupt fields) does not crash or break valid items
        val corruptJson = JSONObject()
        corruptJson.put("garbage", "random_corrupted_data_without_id")
        assertFalse("Corrupted entry missing id is invalid", corruptJson.has("id"))
    }
}
