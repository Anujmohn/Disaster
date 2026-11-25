package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

class MeshCryptoEngineTest {

    @Test
    fun testEcKeyPairGenerationAndSerialization() {
        val keyPair = MeshCryptoEngine.generateKeyPair()
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)

        val pubB64 = MeshCryptoEngine.encodePublicKey(keyPair.public)
        val decodedPub = MeshCryptoEngine.decodePublicKey(pubB64)
        assertArrayEquals(keyPair.public.encoded, decodedPub.encoded)

        val privB64 = MeshCryptoEngine.encodePrivateKey(keyPair.private)
        val decodedPriv = MeshCryptoEngine.decodePrivateKey(privB64)
        assertArrayEquals(keyPair.private.encoded, decodedPriv.encoded)
    }

    @Test
    fun testEcdhSharedSecretMatches() {
        // Alice generates keypair
        val alicePair = MeshCryptoEngine.generateKeyPair()
        // Bob generates keypair
        val bobPair = MeshCryptoEngine.generateKeyPair()

        // Alice computes shared secret using Bob's public key
        val aliceSharedSecret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        // Bob computes shared secret using Alice's public key
        val bobSharedSecret = MeshCryptoEngine.computeSharedSecret(bobPair.private, alicePair.public)

        assertNotNull(aliceSharedSecret)
        assertNotNull(bobSharedSecret)
        assertEquals(32, aliceSharedSecret.size)
        assertArrayEquals("Both parties must arrive at the identical ECDH shared secret", aliceSharedSecret, bobSharedSecret)
    }

    @Test
    fun testHkdfKeyDerivation() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val sharedSecret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)

        val aliceKeys = MeshCryptoEngine.deriveSessionKeys(sharedSecret)
        val bobKeys = MeshCryptoEngine.deriveSessionKeys(sharedSecret)

        assertEquals(32, aliceKeys.aesKey.size)
        assertEquals(32, aliceKeys.ackKey.size)
        assertArrayEquals(aliceKeys.aesKey, bobKeys.aesKey)
        assertArrayEquals(aliceKeys.ackKey, bobKeys.ackKey)

        // Domain separation check: AES key and ACK key must be cryptographically distinct
        assertFalse(aliceKeys.aesKey.contentEquals(aliceKeys.ackKey))
    }

    @Test
    fun testAesGcmEncryptAndDecryptSuccess() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val plaintext = "Urgent: Safe evacuation point established at High School Sector 4."
        val encrypted = MeshCryptoEngine.encrypt(plaintext, sessionKeys.aesKey)

        assertNotNull(encrypted.ciphertextBase64)
        assertNotNull(encrypted.nonceBase64)
        assertNotEquals(plaintext, encrypted.ciphertextBase64)

        // Bob decrypts with derived key
        val decrypted = MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, sessionKeys.aesKey)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testTamperedCiphertextFailsDecryption() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val plaintext = "Evacuate south towards river bridge."
        val encrypted = MeshCryptoEngine.encrypt(plaintext, sessionKeys.aesKey)

        // Tamper with the raw ciphertext bytes to test AEAD authentication failure
        val rawBytes = java.util.Base64.getDecoder().decode(encrypted.ciphertextBase64)
        rawBytes[0] = (rawBytes[0].toInt() xor 0x01).toByte()
        val tamperedCiphertext = java.util.Base64.getEncoder().encodeToString(rawBytes)

        try {
            MeshCryptoEngine.decrypt(tamperedCiphertext, encrypted.nonceBase64, sessionKeys.aesKey)
            fail("Decryption of tampered ciphertext must throw an authentication exception!")
        } catch (e: Exception) {
            // Expected: AEADBadTagException or GeneralSecurityException
            assertTrue(e is AEADBadTagException || e is GeneralSecurityException)
        }
    }

    @Test
    fun testTamperedNonceFailsDecryption() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val plaintext = "Authentic disaster advisory."
        val encrypted = MeshCryptoEngine.encrypt(plaintext, sessionKeys.aesKey)

        // Tamper with the raw nonce bytes to test AEAD authentication failure
        val rawNonce = java.util.Base64.getDecoder().decode(encrypted.nonceBase64)
        rawNonce[0] = (rawNonce[0].toInt() xor 0x01).toByte()
        val tamperedNonce = java.util.Base64.getEncoder().encodeToString(rawNonce)

        try {
            MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, tamperedNonce, sessionKeys.aesKey)
            fail("Decryption with tampered nonce must fail authentication!")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException || e is GeneralSecurityException)
        }
    }

    @Test
    fun testWrongRecipientCannotDecrypt() {
        // Alice encrypts for Bob
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val evePair = MeshCryptoEngine.generateKeyPair() // Eve (Device C / Relay)

        val aliceBobSecret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val aliceBobKeys = MeshCryptoEngine.deriveSessionKeys(aliceBobSecret)

        val secretMessage = "Confidential medical supplies location: Building 7"
        val encrypted = MeshCryptoEngine.encrypt(secretMessage, aliceBobKeys.aesKey)

        // Eve attempts to decrypt Alice's message using Eve's private key and Alice's public key
        val eveAliceSecret = MeshCryptoEngine.computeSharedSecret(evePair.private, alicePair.public)
        val eveKeys = MeshCryptoEngine.deriveSessionKeys(eveAliceSecret)

        try {
            MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, eveKeys.aesKey)
            fail("Eve must NOT be able to decrypt a message encrypted for Bob!")
        } catch (e: Exception) {
            // Expected: AEADBadTagException because key mismatch causes authentication tag failure
            assertTrue(e is AEADBadTagException || e is GeneralSecurityException)
        }
    }

    @Test
    fun testAuthenticatedAckVerification() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val bobPair = MeshCryptoEngine.generateKeyPair()
        val secret = MeshCryptoEngine.computeSharedSecret(alicePair.private, bobPair.public)
        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

        val messageId = "msg-1732400000000-abcd"
        val senderId = "NODE-ALICE"
        val recipientId = "NODE-BOB"

        // Bob generates delivery ACK authentication tag
        val validAckTag = MeshCryptoEngine.computeAckAuthTag(sessionKeys.ackKey, messageId, senderId, recipientId)
        assertNotNull(validAckTag)

        // Alice verifies Bob's ACK
        val isVerified = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, messageId, senderId, recipientId, validAckTag)
        assertTrue("Genuine ACK authentication tag must verify successfully", isVerified)

        // Relay Eve attempts to forge an ACK without the shared secret
        val fakeKey = ByteArray(32) { 0xFF.toByte() }
        val forgedAckTag = MeshCryptoEngine.computeAckAuthTag(fakeKey, messageId, senderId, recipientId)
        val isForgedVerified = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, messageId, senderId, recipientId, forgedAckTag)
        assertFalse("Forged ACK authentication tag must be rejected", isForgedVerified)

        // Tampering with messageId inside ACK must also fail
        val isTamperedMsgIdVerified = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, "msg-tampered-id", senderId, recipientId, validAckTag)
        assertFalse("ACK with tampered messageId must be rejected", isTamperedMsgIdVerified)
    }

    @Test
    fun testFingerprintFormatAndDeterminism() {
        val pair = MeshCryptoEngine.generateKeyPair()
        val fp1 = MeshCryptoEngine.computeFingerprint(pair.public)
        val fp2 = MeshCryptoEngine.computeFingerprint(pair.public)

        assertEquals("Fingerprint computation must be deterministic", fp1, fp2)

        // Format check: 8 blocks of 4 hex digits separated by colons (XXXX:XXXX:XXXX:XXXX:XXXX:XXXX:XXXX:XXXX)
        val blocks = fp1.split(":")
        assertEquals("Fingerprint must contain exactly 8 blocks", 8, blocks.size)
        for (b in blocks) {
            assertEquals("Each block must contain 4 hex characters", 4, b.length)
            assertTrue("Block must be valid hex", b.all { it in '0'..'9' || it in 'A'..'F' })
        }
    }

    @Test
    fun testBroadcastSignatureAndVerificationSuccess() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val alicePubKeyB64 = MeshCryptoEngine.encodePublicKey(alicePair.public)

        val id = "broadcast-1732400000000-001"
        val senderId = "NODE-ALICE"
        val type = "broadcast"
        val priority = "normal"
        val text = "Emergency food and water distribution at Central Square."
        val now = System.currentTimeMillis()
        val expiresAt = now + 120_000L

        val canonicalData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = text,
            createdAt = now,
            expiresAt = expiresAt
        )

        val signature = MeshCryptoEngine.signData(canonicalData, alicePair.private)
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        // 1. Direct PublicKey verification
        val isVerifiedDirect = MeshCryptoEngine.verifySignature(canonicalData, signature, alicePair.public)
        assertTrue("Valid ECDSA signature must verify successfully with PublicKey", isVerifiedDirect)

        // 2. Base64 encoded public key verification
        val isVerifiedB64 = MeshCryptoEngine.verifySignatureWithBase64Key(canonicalData, signature, alicePubKeyB64)
        assertTrue("Valid ECDSA signature must verify successfully with Base64 key string", isVerifiedB64)
    }

    @Test
    fun testTamperedBroadcastTextFailsVerification() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val alicePubKeyB64 = MeshCryptoEngine.encodePublicKey(alicePair.public)

        val id = "broadcast-1732400000000-002"
        val senderId = "NODE-ALICE"
        val type = "broadcast"
        val priority = "normal"
        val originalText = "Evacuate North towards the football stadium."
        val tamperedText = "All clear: Return to houses immediately."
        val now = System.currentTimeMillis()
        val expiresAt = now + 120_000L

        val originalData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = originalText,
            createdAt = now,
            expiresAt = expiresAt
        )
        val signature = MeshCryptoEngine.signData(originalData, alicePair.private)

        // Attacker relays tampered text with Alice's original signature
        val tamperedData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = tamperedText,
            createdAt = now,
            expiresAt = expiresAt
        )

        val isTamperedVerified = MeshCryptoEngine.verifySignatureWithBase64Key(tamperedData, signature, alicePubKeyB64)
        assertFalse("Tampered broadcast text must be rejected by digital signature verification", isTamperedVerified)
    }

    @Test
    fun testTamperedBroadcastPriorityFailsVerification() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val alicePubKeyB64 = MeshCryptoEngine.encodePublicKey(alicePair.public)

        val id = "broadcast-1732400000000-003"
        val senderId = "NODE-ALICE"
        val text = "Community shelter capacity update."
        val now = System.currentTimeMillis()
        val expiresAt = now + 120_000L

        // Alice sent as normal priority
        val normalData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = "broadcast",
            priority = "normal",
            text = text,
            createdAt = now,
            expiresAt = expiresAt
        )
        val signature = MeshCryptoEngine.signData(normalData, alicePair.private)

        // Rogue relay attempts to escalate priority to emergency to spam the mesh
        val forgedPriorityData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = "broadcast",
            priority = "emergency",
            text = text,
            createdAt = now,
            expiresAt = expiresAt
        )

        val isPriorityTamperVerified = MeshCryptoEngine.verifySignatureWithBase64Key(forgedPriorityData, signature, alicePubKeyB64)
        assertFalse("Forged emergency priority elevation must fail signature verification", isPriorityTamperVerified)
    }

    @Test
    fun testForgedSignatureFromDifferentKeyFails() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val malloryPair = MeshCryptoEngine.generateKeyPair()
        val alicePubKeyB64 = MeshCryptoEngine.encodePublicKey(alicePair.public)

        val id = "broadcast-1732400000000-004"
        val senderId = "NODE-ALICE" // Mallory impersonates Alice as senderId
        val type = "broadcast"
        val priority = "normal"
        val text = "Fake broadcast spoofing Alice's identity."
        val now = System.currentTimeMillis()
        val expiresAt = now + 120_000L

        val data = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = text,
            createdAt = now,
            expiresAt = expiresAt
        )

        // Mallory signs with her own private key
        val mallorySignature = MeshCryptoEngine.signData(data, malloryPair.private)

        // Nodes verify with Alice's known public key
        val isVerified = MeshCryptoEngine.verifySignatureWithBase64Key(data, mallorySignature, alicePubKeyB64)
        assertFalse("Signature signed by an impersonator's private key must fail verification against genuine public key", isVerified)
    }

    @Test
    fun testSosBroadcastWithCoordinatesSignatureAndVerification() {
        val alicePair = MeshCryptoEngine.generateKeyPair()
        val alicePubKeyB64 = MeshCryptoEngine.encodePublicKey(alicePair.public)

        val id = "sos-1732400000000-005"
        val senderId = "NODE-ALICE"
        val type = "sos"
        val priority = "emergency"
        val text = "CRITICAL: 2 persons trapped under debris, medical support needed."
        val now = System.currentTimeMillis()
        val expiresAt = now + 300_000L
        val emergencyType = "TRAPPED"
        val coordinates = "37.77492,-122.41941"

        val originalSosData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = text,
            createdAt = now,
            expiresAt = expiresAt,
            emergencyType = emergencyType,
            locationStr = coordinates
        )

        val signature = MeshCryptoEngine.signData(originalSosData, alicePair.private)
        assertNotNull(signature)

        // Genuine verification
        val isVerified = MeshCryptoEngine.verifySignatureWithBase64Key(originalSosData, signature, alicePubKeyB64)
        assertTrue("Genuine emergency SOS with coordinates must verify successfully", isVerified)

        // Attacker alters GPS coordinates to mislead search & rescue teams
        val tamperedCoordsData = MeshCryptoEngine.buildBroadcastSignablePayload(
            id = id,
            senderId = senderId,
            type = type,
            priority = priority,
            text = text,
            createdAt = now,
            expiresAt = expiresAt,
            emergencyType = emergencyType,
            locationStr = "37.80000,-122.40000" // Tampered location
        )

        val isTamperedCoordsVerified = MeshCryptoEngine.verifySignatureWithBase64Key(tamperedCoordsData, signature, alicePubKeyB64)
        assertFalse("Tampered emergency coordinates must fail signature verification", isTamperedCoordsVerified)
    }
}
