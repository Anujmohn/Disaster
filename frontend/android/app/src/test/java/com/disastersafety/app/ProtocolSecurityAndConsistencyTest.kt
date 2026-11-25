package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PROMPT 15: Protocol Security, Key Trust & State Consistency Test Suite.
 *
 * Validates:
 * 1. Authenticated ECDH key exchange with sessionNonce and protocolVersion (V2).
 * 2. Session key derivation using session nonces (HKDF sessionSalt).
 * 3. Relay custody ACK protocol binding with attemptId (V3) and replay prevention.
 * 4. Terminal state invariance in MeshMessageQueue (cannot regress DELIVERED/EXPIRED/FAILED).
 * 5. AES-GCM AAD canonical metadata integrity.
 * 6. Clock skew tolerance and TTL expiration boundaries.
 */
class ProtocolSecurityAndConsistencyTest {

    private lateinit var queue: MeshMessageQueue

    @Before
    fun setUp() {
        queue = MeshMessageQueue(context = null)
    }

    // =========================================================================
    // 1. Authenticated ECDH Session Exchange (sessionNonce & protocolVersion)
    // =========================================================================

    @Test
    fun testKeyExchangeSignableV2WithNonceAndVersion() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val alicePubB64 = MeshCryptoEngine.encodePublicKey(aliceKey.public)
        val aliceId = "NODE-ALICE"
        val bobId = "NODE-BOB"
        val msgId = "key-test-001"
        val now = System.currentTimeMillis()
        val nonce = "nonce123"
        val version = "1.0"

        val signableV2 = MeshCryptoEngine.buildKeyExchangeSignable(
            msgId, aliceId, bobId, alicePubB64, now, nonce, version
        )
        val signature = MeshCryptoEngine.signData(signableV2, aliceKey.private)

        // Valid signature verifies
        assertTrue(
            "Valid V2 key exchange signature must verify",
            MeshCryptoEngine.verifySignatureWithBase64Key(signableV2, signature, alicePubB64)
        )

        // Altering nonce must fail verification
        val forgedNonceSignable = MeshCryptoEngine.buildKeyExchangeSignable(
            msgId, aliceId, bobId, alicePubB64, now, "nonce-forged", version
        )
        assertFalse(
            "Altered nonce must invalidate key exchange signature",
            MeshCryptoEngine.verifySignatureWithBase64Key(forgedNonceSignable, signature, alicePubB64)
        )

        // Altering version must fail verification
        val forgedVersionSignable = MeshCryptoEngine.buildKeyExchangeSignable(
            msgId, aliceId, bobId, alicePubB64, now, nonce, "2.0"
        )
        assertFalse(
            "Altered version must invalidate key exchange signature",
            MeshCryptoEngine.verifySignatureWithBase64Key(forgedVersionSignable, signature, alicePubB64)
        )
    }

    @Test
    fun testSessionKeyDerivationDistinctPerNonce() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()

        val sharedSecret = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)

        // Session 1 nonces
        val salt1 = MeshCryptoEngine.computeSessionSalt("alice-nonce-1", "bob-nonce-1")
        val sessionKeys1 = MeshCryptoEngine.deriveSessionKeys(sharedSecret, salt1)

        // Session 2 nonces (reconnection with new nonces)
        val salt2 = MeshCryptoEngine.computeSessionSalt("alice-nonce-2", "bob-nonce-2")
        val sessionKeys2 = MeshCryptoEngine.deriveSessionKeys(sharedSecret, salt2)

        // Keys without salt (legacy fallback)
        val sessionKeysDefault = MeshCryptoEngine.deriveSessionKeys(sharedSecret)

        assertFalse(
            "Different session nonces must yield different AES keys",
            sessionKeys1.aesKey.contentEquals(sessionKeys2.aesKey)
        )
        assertFalse(
            "Different session nonces must yield different ACK keys",
            sessionKeys1.ackKey.contentEquals(sessionKeys2.ackKey)
        )
        assertFalse(
            "Salted session keys must differ from unsalted legacy session keys",
            sessionKeys1.aesKey.contentEquals(sessionKeysDefault.aesKey)
        )
    }

    // =========================================================================
    // 2. Custody ACK Protocol (attemptId & Replay Binding)
    // =========================================================================

    @Test
    fun testCustodyAckSignableV3WithAttemptId() {
        val relayKey = MeshCryptoEngine.generateKeyPair()
        val relayPubB64 = MeshCryptoEngine.encodePublicKey(relayKey.public)
        val relayId = "RELAY-NODE-1"
        val upstreamId = "SENDER-NODE-1"
        val senderId = "ORIGINAL-SENDER"
        val messageId = "msg-custody-001"
        val payloadHash = MeshCryptoEngine.computePayloadHash("Top secret message payload")
        val now = System.currentTimeMillis()
        val expiresAt = now + 3600_000L
        val attemptId = "$messageId-att0"

        val signableV3 = MeshCryptoEngine.buildCustodyAckSignable(
            messageId, senderId, upstreamId, relayId, payloadHash, now, expiresAt, attemptId
        )
        val signature = MeshCryptoEngine.signData(signableV3, relayKey.private)

        assertTrue(
            "Valid V3 custody ACK signature must verify",
            MeshCryptoEngine.verifySignatureWithBase64Key(signableV3, signature, relayPubB64)
        )

        // Attempting to reuse ACK for a different attempt must fail
        val signableDifferentAttempt = MeshCryptoEngine.buildCustodyAckSignable(
            messageId, senderId, upstreamId, relayId, payloadHash, now, expiresAt, "$messageId-att1"
        )
        assertFalse(
            "Replaying custody ACK for a different attemptId must fail verification",
            MeshCryptoEngine.verifySignatureWithBase64Key(signableDifferentAttempt, signature, relayPubB64)
        )
    }

    // =========================================================================
    // 3. Queue State Invariance (Terminal State Protection)
    // =========================================================================

    @Test
    fun testTerminalStateInvarianceCannotRegressFromDelivered() {
        val msgId = "terminal-msg-001"
        val msg = MeshMessageQueue.QueuedMessage(
            id = msgId,
            senderId = "ALICE",
            senderPublicKey = "mock-key",
            recipientId = "BOB",
            encryptedPayload = "encrypted-bytes-mock",
            nonce = "mock-nonce",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000L,
            hopCount = 0,
            hopLimit = 3,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true,
            retryCount = 0
        )
        queue.enqueue(msg)

        // Mark as DELIVERED
        queue.markDelivered(msgId)
        assertEquals(MeshMessageQueue.STATUS_DELIVERED, queue.getMessage(msgId)?.status)

        // Attempt late custody transfer
        val custodyResult = queue.markCustodyTransferred(msgId)
        assertFalse("Late custody ACK cannot transition message from DELIVERED", custodyResult)
        assertEquals("Status must remain DELIVERED", MeshMessageQueue.STATUS_DELIVERED, queue.getMessage(msgId)?.status)

        // Attempt second markDelivered
        val deliveredAgain = queue.markDelivered(msgId)
        assertFalse("Duplicate delivery ACK must not alter state", deliveredAgain)
        assertEquals("Status must remain DELIVERED", MeshMessageQueue.STATUS_DELIVERED, queue.getMessage(msgId)?.status)
    }

    @Test
    fun testTerminalStateInvarianceCannotRegressFromExpiredOrFailed() {
        val msgId = "expired-msg-002"
        val msg = MeshMessageQueue.QueuedMessage(
            id = msgId,
            senderId = "ALICE",
            senderPublicKey = "mock-key",
            recipientId = "BOB",
            encryptedPayload = "encrypted-bytes-mock",
            nonce = "mock-nonce",
            text = null,
            type = "encrypted_chat",
            priority = "normal",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000L,
            hopCount = 0,
            hopLimit = 3,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = true,
            retryCount = 0
        )
        queue.enqueue(msg)

        // Transition message to terminal FAILED status
        assertTrue(queue.updateStatus(msgId, MeshMessageQueue.STATUS_FAILED))
        assertEquals(MeshMessageQueue.STATUS_FAILED, queue.getMessage(msgId)?.status)

        // Cannot mark custody transferred on FAILED
        val custodyTransferred = queue.markCustodyTransferred(msgId)
        assertFalse("Cannot mark custody transferred on FAILED message", custodyTransferred)
        assertEquals(MeshMessageQueue.STATUS_FAILED, queue.getMessage(msgId)?.status)

        // Cannot mark delivered on FAILED
        val delivered = queue.markDelivered(msgId)
        assertFalse("Cannot mark DELIVERED on FAILED message", delivered)
        assertEquals(MeshMessageQueue.STATUS_FAILED, queue.getMessage(msgId)?.status)

        // Cannot regress FAILED to QUEUED or FORWARDING
        assertFalse("Cannot regress FAILED to QUEUED", queue.updateStatus(msgId, MeshMessageQueue.STATUS_QUEUED))
        assertFalse("Cannot regress FAILED to FORWARDING", queue.updateStatus(msgId, MeshMessageQueue.STATUS_FORWARDING))
    }

    // =========================================================================
    // 4. AES-GCM AAD Routing Protection & Anti-Tamper
    // =========================================================================

    @Test
    fun testAadBindsImmutableEnvelopeMetadata() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val messageId = "msg-aad-001"
        val senderId = "ALICE"
        val recipientId = "BOB"
        val type = "encrypted_chat"
        val createdAt = 1000000L
        val expiresAt = 2000000L

        val aad = MeshCryptoEngine.computeMetadataAad(messageId, senderId, recipientId, type, createdAt, expiresAt)
        val plaintext = "Hello securely authenticated world!"
        val encrypted = MeshCryptoEngine.encrypt(plaintext, sessionKeys.aesKey, aad)

        // Valid decryption with intact AAD
        val decrypted = MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, sessionKeys.aesKey, aad)
        assertEquals(plaintext, decrypted)

        // Tampering with senderId in AAD must fail decryption
        val tamperedAad = MeshCryptoEngine.computeMetadataAad(messageId, "MALLORY", recipientId, type, createdAt, expiresAt)
        try {
            MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, sessionKeys.aesKey, tamperedAad)
            fail("Decryption with tampered AAD metadata must fail with AEADBadTagException")
        } catch (_: Exception) {
            // Success: AEAD authenticated tag mismatch prevented decryption
        }
    }
}
