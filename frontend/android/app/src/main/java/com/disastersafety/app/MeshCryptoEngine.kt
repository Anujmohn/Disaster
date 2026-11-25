package com.disastersafety.app

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard-compliant cryptographic engine for offline mesh communication.
 * - Key Agreement: ECDH on NIST P-256 (secp256r1)
 * - Key Derivation: RFC 5869 HKDF-SHA-256
 * - Authenticated Encryption: AES-256-GCM (12-byte random nonce, 128-bit auth tag)
 * - Integrity Verification: Constant-time HMAC-SHA-256 for authenticated delivery ACKs
 */
object MeshCryptoEngine {

    private const val EC_CURVE = "secp256r1"
    private const val AES_GCM_ALGO = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val HKDF_ALGO = "HmacSHA256"

    private val SALT = "disaster-mesh-e2ee-v1".toByteArray(StandardCharsets.UTF_8)
    private val AES_INFO = "disaster-mesh-aes-gcm-key".toByteArray(StandardCharsets.UTF_8)
    private val ACK_INFO = "disaster-mesh-ack-auth-key".toByteArray(StandardCharsets.UTF_8)

    private val secureRandom = SecureRandom()

    data class SessionKeys(
        val aesKey: ByteArray,
        val ackKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SessionKeys
            return aesKey.contentEquals(other.aesKey) && ackKey.contentEquals(other.ackKey)
        }

        override fun hashCode(): Int {
            var result = aesKey.contentHashCode()
            result = 31 * result + ackKey.contentHashCode()
            return result
        }
    }

    data class EncryptedMessageData(
        val ciphertextBase64: String,
        val nonceBase64: String
    )

    private fun b64Encode(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun b64Decode(str: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(str.trim())
        } catch (_: Throwable) {
            android.util.Base64.decode(str.trim(), android.util.Base64.NO_WRAP)
        }
    }

    // -------------------------------------------------------------------------
    // 1. Key Generation & Serialization
    // -------------------------------------------------------------------------

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE)
        kpg.initialize(ecSpec, secureRandom)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String {
        return b64Encode(publicKey.encoded)
    }

    fun decodePublicKey(base64: String): PublicKey {
        val bytes = b64Decode(base64)
        val spec = X509EncodedKeySpec(bytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(spec)
    }

    fun encodePrivateKey(privateKey: PrivateKey): String {
        return b64Encode(privateKey.encoded)
    }

    fun decodePrivateKey(base64: String): PrivateKey {
        val bytes = b64Decode(base64)
        val spec = PKCS8EncodedKeySpec(bytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(spec)
    }

    // -------------------------------------------------------------------------
    // 2. Identity Fingerprint Calculation
    // -------------------------------------------------------------------------

    fun computeFingerprint(publicKey: PublicKey): String {
        return computeFingerprintFromBytes(publicKey.encoded)
    }

    fun computeFingerprintFromBase64(publicKeyBase64: String): String {
        val bytes = b64Decode(publicKeyBase64)
        return computeFingerprintFromBytes(bytes)
    }

    private fun computeFingerprintFromBytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val hex = StringBuilder()
        for (i in 0 until 16) { // 16 bytes = 32 hex chars formatted into 8 groups of 4
            hex.append(String.format(Locale.US, "%02X", digest[i]))
            if (i % 2 == 1 && i < 15) {
                hex.append(":")
            }
        }
        return hex.toString()
    }

    // -------------------------------------------------------------------------
    // 3. Diffie-Hellman Key Agreement & HKDF-SHA-256
    // -------------------------------------------------------------------------

    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun deriveSessionKeys(sharedSecret: ByteArray, sessionSalt: ByteArray? = null): SessionKeys {
        val salt = sessionSalt ?: SALT
        val aesKey = hkdf(sharedSecret, salt, AES_INFO, 32)
        val ackKey = hkdf(sharedSecret, salt, ACK_INFO, 32)
        return SessionKeys(aesKey, ackKey)
    }

    fun computeSessionSalt(initiatorNonce: String, responderNonce: String): ByteArray {
        val combined = "disaster-mesh-session:$initiatorNonce:$responderNonce"
        return combined.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * RFC 5869 HKDF-SHA-256 implementation
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HKDF_ALGO)
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, HKDF_ALGO))
        val prk = mac.doFinal(ikm)

        val expandMac = Mac.getInstance(HKDF_ALGO)
        expandMac.init(SecretKeySpec(prk, HKDF_ALGO))
        expandMac.update(info)
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(length)
    }

    // -------------------------------------------------------------------------
    // 4. Authenticated Encryption & Decryption (AES-256-GCM with AAD)
    // -------------------------------------------------------------------------

    fun computeMetadataAad(id: String, senderId: String, recipientId: String, type: String, createdAt: Long, expiresAt: Long): ByteArray {
        val metaStr = "$id|$senderId|$recipientId|$type|$createdAt|$expiresAt"
        return metaStr.toByteArray(StandardCharsets.UTF_8)
    }

    fun computePayloadHash(payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(payload.toByteArray(StandardCharsets.UTF_8))
        val hex = StringBuilder()
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b))
        }
        return hex.toString()
    }

    fun encrypt(plaintext: String, aesKey: ByteArray, aad: ByteArray? = null): EncryptedMessageData {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_ALGO)
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)

        val ciphertextBase64 = b64Encode(ciphertextWithTag)
        val nonceBase64 = b64Encode(iv)

        return EncryptedMessageData(ciphertextBase64, nonceBase64)
    }

    fun decrypt(ciphertextBase64: String, nonceBase64: String, aesKey: ByteArray, aad: ByteArray? = null): String {
        val ciphertextWithTag = b64Decode(ciphertextBase64)
        val iv = b64Decode(nonceBase64)

        val cipher = Cipher.getInstance(AES_GCM_ALGO)
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        val decryptedBytes = cipher.doFinal(ciphertextWithTag)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    // -------------------------------------------------------------------------
    // 5. Authenticated Delivery Acknowledgment (HMAC-SHA-256)
    // -------------------------------------------------------------------------

    fun computeAckAuthTag(
        ackKey: ByteArray,
        originalMessageId: String,
        senderId: String,
        recipientId: String,
        payloadHash: String = ""
    ): String {
        val mac = Mac.getInstance(HKDF_ALGO)
        mac.init(SecretKeySpec(ackKey, HKDF_ALGO))
        val dataStr = if (payloadHash.isEmpty()) {
            "$originalMessageId:$senderId:$recipientId"
        } else {
            "$originalMessageId:$senderId:$recipientId:$payloadHash"
        }
        val tagBytes = mac.doFinal(dataStr.toByteArray(StandardCharsets.UTF_8))
        return b64Encode(tagBytes)
    }

    fun verifyAckAuthTag(
        ackKey: ByteArray,
        originalMessageId: String,
        senderId: String,
        recipientId: String,
        receivedTagBase64: String,
        payloadHash: String = ""
    ): Boolean {
        return try {
            val expectedTag = computeAckAuthTag(ackKey, originalMessageId, senderId, recipientId, payloadHash)
            val expectedBytes = b64Decode(expectedTag)
            val receivedBytes = b64Decode(receivedTagBase64)
            if (MessageDigest.isEqual(expectedBytes, receivedBytes)) {
                true
            } else if (payloadHash.isNotEmpty()) {
                // Backward-compatible fallback for legacy unhashed tags
                val legacyTag = computeAckAuthTag(ackKey, originalMessageId, senderId, recipientId, "")
                MessageDigest.isEqual(b64Decode(legacyTag), receivedBytes)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // 5b. Authenticated Key Exchange & Custody ACK Helpers
    // -------------------------------------------------------------------------

    fun buildKeyExchangeSignable(
        id: String,
        senderId: String,
        recipientId: String,
        publicKeyBase64: String,
        timestamp: Long,
        sessionNonce: String = "",
        protocolVersion: String = "1.0"
    ): String {
        return if (sessionNonce.isNotEmpty()) {
            "KEY_EXCHANGE_V2:$id:$senderId:$recipientId:$publicKeyBase64:$sessionNonce:$protocolVersion:$timestamp"
        } else {
            "KEY_EXCHANGE:$id:$senderId:$recipientId:$publicKeyBase64:$timestamp"
        }
    }

    fun buildCustodyAckSignable(
        messageId: String,
        senderId: String,
        upstreamNodeId: String,
        custodyNodeId: String,
        payloadHash: String,
        timestamp: Long,
        expiresAt: Long = 0L,
        attemptId: String = ""
    ): String {
        return if (attemptId.isNotEmpty()) {
            "CUSTODY_V3:$messageId:$senderId:$upstreamNodeId:$custodyNodeId:$payloadHash:$attemptId:$timestamp:$expiresAt"
        } else {
            "CUSTODY_V2:$messageId:$senderId:$upstreamNodeId:$custodyNodeId:$payloadHash:$timestamp:$expiresAt"
        }
    }

    // -------------------------------------------------------------------------
    // 6. Public Broadcast Digital Signatures (ECDSA with SHA-256)
    // -------------------------------------------------------------------------

    private const val SIGNATURE_ALGO = "SHA256withECDSA"

    fun buildBroadcastSignablePayload(
        id: String,
        senderId: String,
        type: String,
        priority: String,
        text: String,
        createdAt: Long,
        expiresAt: Long,
        emergencyType: String? = null,
        locationStr: String? = null
    ): String {
        return "$id|$senderId|$type|$priority|$text|$createdAt|$expiresAt|${emergencyType ?: ""}|${locationStr ?: ""}"
    }

    fun signData(data: String, privateKey: PrivateKey): String {
        val sig = Signature.getInstance(SIGNATURE_ALGO)
        sig.initSign(privateKey, secureRandom)
        sig.update(data.toByteArray(StandardCharsets.UTF_8))
        val signatureBytes = sig.sign()
        return b64Encode(signatureBytes)
    }

    fun verifySignature(data: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance(SIGNATURE_ALGO)
            sig.initVerify(publicKey)
            sig.update(data.toByteArray(StandardCharsets.UTF_8))
            val signatureBytes = b64Decode(signatureBase64)
            sig.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun verifySignatureWithBase64Key(data: String, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val publicKey = decodePublicKey(publicKeyBase64)
            verifySignature(data, signatureBase64, publicKey)
        } catch (_: Exception) {
            false
        }
    }
}
