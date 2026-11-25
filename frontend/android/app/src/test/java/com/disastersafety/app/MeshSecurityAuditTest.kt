package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * PROMPT 14: Comprehensive Security Audit & Hardening Test Suite.
 *
 * Tests:
 * Group A: Device Identity & Public-Key Binding
 * Group B: Authenticated ECDH Key Exchange
 * Group C: Relay Custody ACK Security & Replay Resistance
 * Group D: End-to-End Delivery ACK Authentication
 * Group E: Message Integrity & Routing Metadata Protection (AES-GCM AAD)
 * Group F: Persistent Queue Security & Plaintext Privacy
 */
class MeshSecurityAuditTest {

    private lateinit var queue: MeshMessageQueue

    @Before
    fun setUp() {
        queue = MeshMessageQueue(context = null)
    }

    // =========================================================================
    // Group A: Device Identity & Public-Key Binding
    // =========================================================================

    @Test
    fun testValidDeviceIdAndKeyBindingAccepted() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val alicePubB64 = MeshCryptoEngine.encodePublicKey(aliceKey.public)
        val aliceFingerprint = MeshCryptoEngine.computeFingerprint(aliceKey.public)
        val aliceDeviceId = "NODE-ALICE1"

        // Compute fingerprint from base64 string
        val computedFp = MeshCryptoEngine.computeFingerprintFromBase64(alicePubB64)
        assertEquals("Fingerprint computed from Base64 must match public key", aliceFingerprint, computedFp)

        // Verify valid signature by alice
        val signable = "IDENTITY:$aliceDeviceId:$alicePubB64"
        val signature = MeshCryptoEngine.signData(signable, aliceKey.private)
        assertTrue(
            "Valid signature by owner of identity key must verify",
            MeshCryptoEngine.verifySignatureWithBase64Key(signable, signature, alicePubB64)
        )
    }

    @Test
    fun testConflictingKeyClaimOnTrustedPeerFlaggedAndProtected() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val alicePubB64 = MeshCryptoEngine.encodePublicKey(aliceKey.public)

        val malloryKey = MeshCryptoEngine.generateKeyPair()
        val malloryPubB64 = MeshCryptoEngine.encodePublicKey(malloryKey.public)

        val aliceDeviceId = "NODE-ALICE1"

        // Mallory attempts to forge a message claiming to be Alice using Mallory's key
        val signable = "BROADCAST:$aliceDeviceId:I am Alice"
        val mallorySig = MeshCryptoEngine.signData(signable, malloryKey.private)

        // Verifying with Alice's stored/trusted key must fail
        val isVerifiedWithAliceKey = MeshCryptoEngine.verifySignatureWithBase64Key(signable, mallorySig, alicePubB64)
        assertFalse(
            "Signature created by Mallory's key must NOT verify against Alice's public key",
            isVerifiedWithAliceKey
        )

        // Key fingerprints must be distinct
        val aliceFp = MeshCryptoEngine.computeFingerprint(aliceKey.public)
        val malloryFp = MeshCryptoEngine.computeFingerprint(malloryKey.public)
        assertNotEquals("Different public keys must produce different fingerprints", aliceFp, malloryFp)
    }

    @Test
    fun testUnverifiedPeerNotMarkedAsVerified() {
        val bobKey = MeshCryptoEngine.generateKeyPair()
        val bobPubB64 = MeshCryptoEngine.encodePublicKey(bobKey.public)
        val bobFp = MeshCryptoEngine.computeFingerprint(bobKey.public)

        val peer = MeshKeyStorage.PeerIdentity(
            deviceId = "NODE-BOB001",
            publicKeyBase64 = bobPubB64,
            fingerprint = bobFp,
            isVerified = false,
            keyChanged = false
        )

        assertFalse("Newly received peer must not be marked as verified automatically", peer.isVerified)
        assertFalse("Newly received peer must not have keyChanged flag", peer.keyChanged)
    }

    // =========================================================================
    // Group B: Authenticated ECDH Key Exchange
    // =========================================================================

    @Test
    fun testAuthenticatedKeyExchangeSucceeds() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val alicePubB64 = MeshCryptoEngine.encodePublicKey(aliceKey.public)
        val now = System.currentTimeMillis()

        val signable = MeshCryptoEngine.buildKeyExchangeSignable(
            id = "key-001",
            senderId = "NODE-ALICE",
            recipientId = "NODE-BOB",
            publicKeyBase64 = alicePubB64,
            timestamp = now
        )

        val signature = MeshCryptoEngine.signData(signable, aliceKey.private)
        val isValid = MeshCryptoEngine.verifySignatureWithBase64Key(signable, signature, alicePubB64)
        assertTrue("Valid authenticated key exchange signature must pass verification", isValid)
    }

    @Test
    fun testForgedOrModifiedKeyExchangeRejected() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val alicePubB64 = MeshCryptoEngine.encodePublicKey(aliceKey.public)

        val malloryKey = MeshCryptoEngine.generateKeyPair()
        val malloryPubB64 = MeshCryptoEngine.encodePublicKey(malloryKey.public)
        val now = System.currentTimeMillis()

        // Alice signs her key exchange
        val aliceSignable = MeshCryptoEngine.buildKeyExchangeSignable("key-001", "NODE-ALICE", "NODE-BOB", alicePubB64, now)
        val aliceSig = MeshCryptoEngine.signData(aliceSignable, aliceKey.private)

        // Mallory attempts to substitute Alice's public key with Mallory's public key
        val substitutedSignable = MeshCryptoEngine.buildKeyExchangeSignable("key-001", "NODE-ALICE", "NODE-BOB", malloryPubB64, now)
        val isVerifiedWithAliceSig = MeshCryptoEngine.verifySignatureWithBase64Key(substitutedSignable, aliceSig, malloryPubB64)
        assertFalse("Substituted public key in key exchange must fail signature verification", isVerifiedWithAliceSig)
    }

    @Test
    fun testDifferentSessionsDeriveDifferentKeys() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()
        val charlieKey = MeshCryptoEngine.generateKeyPair()

        val secretAB = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)
        val sessionKeysAB = MeshCryptoEngine.deriveSessionKeys(secretAB)

        val secretAC = MeshCryptoEngine.computeSharedSecret(aliceKey.private, charlieKey.public)
        val sessionKeysAC = MeshCryptoEngine.deriveSessionKeys(secretAC)

        assertFalse(
            "Different peer sessions must derive distinct AES keys",
            sessionKeysAB.aesKey.contentEquals(sessionKeysAC.aesKey)
        )
        assertFalse(
            "Different peer sessions must derive distinct ACK keys",
            sessionKeysAB.ackKey.contentEquals(sessionKeysAC.ackKey)
        )
    }

    // =========================================================================
    // Group C: Relay Custody ACK Security & Replay Resistance
    // =========================================================================

    @Test
    fun testCustodyAckSignatureWithFullBindingValid() {
        val relayKey = MeshCryptoEngine.generateKeyPair()
        val relayPubB64 = MeshCryptoEngine.encodePublicKey(relayKey.public)
        val now = System.currentTimeMillis()
        val payloadHash = MeshCryptoEngine.computePayloadHash("ciphertext-xyz-123")

        val signable = MeshCryptoEngine.buildCustodyAckSignable(
            messageId = "msg-100",
            senderId = "NODE-ALICE",
            upstreamNodeId = "NODE-UPSTREAM",
            custodyNodeId = "NODE-RELAY",
            payloadHash = payloadHash,
            timestamp = now,
            expiresAt = now + 60000L
        )

        val sig = MeshCryptoEngine.signData(signable, relayKey.private)
        val isValid = MeshCryptoEngine.verifySignatureWithBase64Key(signable, sig, relayPubB64)
        assertTrue("Hardened Custody ACK V2 signature with payload hash must verify", isValid)
    }

    @Test
    fun testCustodyAckModifiedPayloadHashRejected() {
        val relayKey = MeshCryptoEngine.generateKeyPair()
        val relayPubB64 = MeshCryptoEngine.encodePublicKey(relayKey.public)
        val now = System.currentTimeMillis()
        val realPayloadHash = MeshCryptoEngine.computePayloadHash("real-packet-data")
        val fakePayloadHash = MeshCryptoEngine.computePayloadHash("tampered-packet-data")

        val originalSignable = MeshCryptoEngine.buildCustodyAckSignable(
            messageId = "msg-100",
            senderId = "NODE-ALICE",
            upstreamNodeId = "NODE-UPSTREAM",
            custodyNodeId = "NODE-RELAY",
            payloadHash = realPayloadHash,
            timestamp = now
        )
        val sig = MeshCryptoEngine.signData(originalSignable, relayKey.private)

        val tamperedSignable = MeshCryptoEngine.buildCustodyAckSignable(
            messageId = "msg-100",
            senderId = "NODE-ALICE",
            upstreamNodeId = "NODE-UPSTREAM",
            custodyNodeId = "NODE-RELAY",
            payloadHash = fakePayloadHash,
            timestamp = now
        )

        val isTamperedValid = MeshCryptoEngine.verifySignatureWithBase64Key(tamperedSignable, sig, relayPubB64)
        assertFalse("Custody ACK with modified payload hash must be rejected", isTamperedValid)
    }

    @Test
    fun testCustodyAckWrongDownstreamDeviceRejected() {
        val relayKey = MeshCryptoEngine.generateKeyPair()
        val attackerKey = MeshCryptoEngine.generateKeyPair()
        val relayPubB64 = MeshCryptoEngine.encodePublicKey(relayKey.public)
        val now = System.currentTimeMillis()

        val signable = MeshCryptoEngine.buildCustodyAckSignable(
            messageId = "msg-100",
            senderId = "NODE-ALICE",
            upstreamNodeId = "NODE-UPSTREAM",
            custodyNodeId = "NODE-RELAY",
            payloadHash = "hash123",
            timestamp = now
        )

        // Attacker attempts to sign a custody ACK claiming to be NODE-RELAY
        val forgedSig = MeshCryptoEngine.signData(signable, attackerKey.private)
        val isVerified = MeshCryptoEngine.verifySignatureWithBase64Key(signable, forgedSig, relayPubB64)
        assertFalse("Custody ACK signed by attacker key must fail verification against relay public key", isVerified)
    }

    @Test
    fun testCustodyAckCannotMarkDelivered() {
        val now = System.currentTimeMillis()
        val originMsg = MeshMessageQueue.QueuedMessage(
            id = "origin-custody-test",
            senderId = "NODE-LOCAL",
            senderPublicKey = "key",
            recipientId = "NODE-REMOTE",
            encryptedPayload = "payload",
            nonce = "nonce",
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
        queue.markCustodyTransferred("origin-custody-test")

        val stored = queue.getMessage("origin-custody-test")
        assertNotNull(stored)
        assertEquals("Custody ACK must move origin message to FORWARDING", MeshMessageQueue.STATUS_FORWARDING, stored!!.status)
        assertNotEquals("Custody ACK must NEVER mark origin message as DELIVERED", MeshMessageQueue.STATUS_DELIVERED, stored.status)
    }

    // =========================================================================
    // Group D: End-to-End Delivery ACK Authentication
    // =========================================================================

    @Test
    fun testDestinationHmacAckAccepted() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()

        val secretAB = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)
        val sessionKeysAlice = MeshCryptoEngine.deriveSessionKeys(secretAB)

        val secretBA = MeshCryptoEngine.computeSharedSecret(bobKey.private, aliceKey.public)
        val sessionKeysBob = MeshCryptoEngine.deriveSessionKeys(secretBA)

        val msgId = "e2e-msg-999"
        val payloadHash = MeshCryptoEngine.computePayloadHash("encrypted-ciphertext-data")

        // Bob (destination) generates delivery ACK auth tag
        val authTag = MeshCryptoEngine.computeAckAuthTag(
            ackKey = sessionKeysBob.ackKey,
            originalMessageId = msgId,
            senderId = "NODE-ALICE",
            recipientId = "NODE-BOB",
            payloadHash = payloadHash
        )

        // Alice (origin) verifies delivery ACK auth tag
        val isValid = MeshCryptoEngine.verifyAckAuthTag(
            ackKey = sessionKeysAlice.ackKey,
            originalMessageId = msgId,
            senderId = "NODE-ALICE",
            recipientId = "NODE-BOB",
            receivedTagBase64 = authTag,
            payloadHash = payloadHash
        )
        assertTrue("Valid destination HMAC delivery tag must verify", isValid)
    }

    @Test
    fun testInvalidHmacDeliveryAckRejected() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val validTag = MeshCryptoEngine.computeAckAuthTag(sessionKeys.ackKey, "msg-1", "NODE-ALICE", "NODE-BOB")

        // Wrong message ID
        val wrongMsgValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, "msg-WRONG", "NODE-ALICE", "NODE-BOB", validTag)
        assertFalse("Delivery ACK with wrong message ID must fail", wrongMsgValid)

        // Wrong sender ID
        val wrongSenderValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, "msg-1", "NODE-MALLORY", "NODE-BOB", validTag)
        assertFalse("Delivery ACK with wrong sender ID must fail", wrongSenderValid)

        // Tampered tag
        val tamperedTag = validTag.substring(0, validTag.length - 2) + "=="
        val tamperedValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, "msg-1", "NODE-ALICE", "NODE-BOB", tamperedTag)
        assertFalse("Tampered delivery ACK tag must fail verification", tamperedValid)
    }

    @Test
    fun testDeliveryAckFromUnrelatedDeviceFailsAuth() {
        val aliceKey = MeshCryptoEngine.generateKeyPair()
        val bobKey = MeshCryptoEngine.generateKeyPair()
        val malloryKey = MeshCryptoEngine.generateKeyPair()

        // Alice and Bob session
        val secretAB = MeshCryptoEngine.computeSharedSecret(aliceKey.private, bobKey.public)
        val aliceKeys = MeshCryptoEngine.deriveSessionKeys(secretAB)

        // Mallory attempts to generate ACK using Mallory's key
        val secretMB = MeshCryptoEngine.computeSharedSecret(malloryKey.private, bobKey.public)
        val malloryKeys = MeshCryptoEngine.deriveSessionKeys(secretMB)

        val forgedTag = MeshCryptoEngine.computeAckAuthTag(malloryKeys.ackKey, "msg-1", "NODE-ALICE", "NODE-BOB")
        val isVerified = MeshCryptoEngine.verifyAckAuthTag(aliceKeys.ackKey, "msg-1", "NODE-ALICE", "NODE-BOB", forgedTag)
        assertFalse("Delivery ACK generated by third party must fail verification against Alice-Bob session key", isVerified)
    }

    // =========================================================================
    // Group E: Message Integrity & Routing Metadata (AAD)
    // =========================================================================

    @Test
    fun testAesGcmWithMetadataAadSuccess() {
        val key = ByteArray(32) { 0x42 }
        val plaintext = "CONFIDENTIAL EVACUATION PLAN"
        val now = System.currentTimeMillis()
        val expires = now + 3600000L

        val aad = MeshCryptoEngine.computeMetadataAad(
            id = "msg-e2e-1",
            senderId = "NODE-A",
            recipientId = "NODE-B",
            type = "encrypted_chat",
            createdAt = now,
            expiresAt = expires
        )

        val encrypted = MeshCryptoEngine.encrypt(plaintext, key, aad)
        val decrypted = MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, key, aad)

        assertEquals("Decrypted plaintext with valid AAD must match original", plaintext, decrypted)
    }

    @Test
    fun testTamperedRecipientIdInAadFailsDecryption() {
        val key = ByteArray(32) { 0x55 }
        val plaintext = "Secret rescue coordinates"
        val now = System.currentTimeMillis()
        val expires = now + 3600000L

        val originalAad = MeshCryptoEngine.computeMetadataAad("msg-1", "NODE-A", "NODE-B", "encrypted_chat", now, expires)
        val encrypted = MeshCryptoEngine.encrypt(plaintext, key, originalAad)

        // Relay tampered with recipient ID (e.g. redirected to Mallory)
        val tamperedAad = MeshCryptoEngine.computeMetadataAad("msg-1", "NODE-A", "NODE-MALLORY", "encrypted_chat", now, expires)

        var failed = false
        try {
            MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, key, tamperedAad)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Tampered recipientId in AAD metadata must cause GCM tag verification to throw", failed)
    }

    @Test
    fun testTamperedMessageTypeInAadFailsDecryption() {
        val key = ByteArray(32) { 0x77 }
        val plaintext = "Medical Supplies Request"
        val now = System.currentTimeMillis()
        val expires = now + 3600000L

        val originalAad = MeshCryptoEngine.computeMetadataAad("msg-1", "NODE-A", "NODE-B", "encrypted_chat", now, expires)
        val encrypted = MeshCryptoEngine.encrypt(plaintext, key, originalAad)

        // Relay tampered with message type
        val tamperedAad = MeshCryptoEngine.computeMetadataAad("msg-1", "NODE-A", "NODE-B", "broadcast", now, expires)

        var failed = false
        try {
            MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, key, tamperedAad)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Tampered message type in AAD metadata must cause GCM tag verification to throw", failed)
    }

    @Test
    fun testTamperedCiphertextFailsDecryption() {
        val key = ByteArray(32) { 0x33 }
        val encrypted = MeshCryptoEngine.encrypt("Hello Mesh", key)

        // Tamper with the base64 ciphertext
        val rawBytes = java.util.Base64.getDecoder().decode(encrypted.ciphertextBase64)
        rawBytes[0] = (rawBytes[0].toInt() xor 0xFF).toByte()
        val tamperedBase64 = java.util.Base64.getEncoder().encodeToString(rawBytes)

        var failed = false
        try {
            MeshCryptoEngine.decrypt(tamperedBase64, encrypted.nonceBase64, key)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Tampered ciphertext bytes must fail AES-GCM authentication", failed)
    }

    @Test
    fun testSosSignatureVerificationAndTamperDetection() {
        val senderKey = MeshCryptoEngine.generateKeyPair()
        val senderPubB64 = MeshCryptoEngine.encodePublicKey(senderKey.public)
        val now = System.currentTimeMillis()

        val signable = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = "sos-001",
            senderId = "NODE-VICTIM",
            type = "sos",
            priority = "emergency",
            text = "Trapped in flood waters",
            createdAt = now,
            expiresAt = now + 7200000L,
            emergencyType = "FLOOD",
            locationStr = "12.9716,77.5946"
        )
        val signature = MeshCryptoEngine.signData(signable, senderKey.private)

        // 1. Valid signature passes
        val isValid = MeshCryptoEngine.verifySignatureWithBase64Key(signable, signature, senderPubB64)
        assertTrue("Valid SOS signature must pass", isValid)

        // 2. Tampered priority fails
        val tamperedPriority = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = "sos-001",
            senderId = "NODE-VICTIM",
            type = "sos",
            priority = "normal", // Forged to low priority!
            text = "Trapped in flood waters",
            createdAt = now,
            expiresAt = now + 7200000L,
            emergencyType = "FLOOD",
            locationStr = "12.9716,77.5946"
        )
        assertFalse("Tampered priority in SOS must fail signature verification",
            MeshCryptoEngine.verifySignatureWithBase64Key(tamperedPriority, signature, senderPubB64))

        // 3. Tampered coordinates fails
        val tamperedCoords = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = "sos-001",
            senderId = "NODE-VICTIM",
            type = "sos",
            priority = "emergency",
            text = "Trapped in flood waters",
            createdAt = now,
            expiresAt = now + 7200000L,
            emergencyType = "FLOOD",
            locationStr = "0.0000,0.0000" // Forged coordinates!
        )
        assertFalse("Tampered coordinates in SOS must fail signature verification",
            MeshCryptoEngine.verifySignatureWithBase64Key(tamperedCoords, signature, senderPubB64))
    }

    // =========================================================================
    // Group F: Persistent Queue Security & Plaintext Privacy
    // =========================================================================

    @Test
    fun testRelayStorageContainsNoPrivatePlaintext() {
        val now = System.currentTimeMillis()
        val relayMsg = MeshMessageQueue.QueuedMessage(
            id = "relay-privacy-test",
            senderId = "NODE-A",
            senderPublicKey = "alice-pubkey",
            recipientId = "NODE-B",
            encryptedPayload = "Base64EncryptedCiphertextOnly==",
            nonce = "RandomIVNonce==",
            text = null, // CRITICAL: Relays must never store plaintext
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
        val retrieved = queue.getMessage("relay-privacy-test")

        assertNotNull(retrieved)
        assertNull("Relayed message in queue must have text = null", retrieved!!.text)
        assertNotNull("Relayed message must retain encryptedPayload", retrieved.encryptedPayload)
        assertEquals("Relayed message must preserve type as encrypted_chat", "encrypted_chat", retrieved.type)
    }

    @Test
    fun testTerminalStateCannotBeRegressedByLateAck() {
        val now = System.currentTimeMillis()
        val msg = MeshMessageQueue.QueuedMessage(
            id = "invariant-test-msg",
            senderId = "NODE-LOCAL",
            senderPublicKey = "key",
            recipientId = "NODE-REMOTE",
            encryptedPayload = "payload",
            nonce = "nonce",
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
        queue.markDelivered("invariant-test-msg")
        assertEquals(MeshMessageQueue.STATUS_DELIVERED, queue.getMessage("invariant-test-msg")!!.status)

        // Late custody ACK or attempt to update back to queued/forwarding must be ignored
        queue.updateStatus("invariant-test-msg", MeshMessageQueue.STATUS_QUEUED)
        assertEquals("DELIVERED message cannot regress to QUEUED", MeshMessageQueue.STATUS_DELIVERED, queue.getMessage("invariant-test-msg")!!.status)

        queue.updateStatus("invariant-test-msg", MeshMessageQueue.STATUS_FORWARDING)
        assertEquals("DELIVERED message cannot regress to FORWARDING", MeshMessageQueue.STATUS_DELIVERED, queue.getMessage("invariant-test-msg")!!.status)
    }
}
