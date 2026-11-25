package com.disastersafety.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MeshKeyStorage(private val context: Context) {

    companion object {
        private const val TAG = "MeshKeyStorage"
        private const val PREFS_NAME = "mesh_crypto_secure_store"
        private const val PEERS_PREFS_NAME = "mesh_known_peers_store"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "DisasterMeshMasterKeyV1"
        private const val PREF_DEVICE_ID = "local_device_id"
        private const val PREF_PUBLIC_KEY = "local_public_key"
        private const val PREF_ENCRYPTED_PRIVATE_KEY = "enc_private_key"
        private const val PREF_PRIVATE_KEY_IV = "enc_private_key_iv"
    }

    data class DeviceIdentity(
        val deviceId: String,
        val publicKey: PublicKey,
        val publicKeyBase64: String,
        val fingerprint: String,
        val privateKey: PrivateKey
    )

    data class PeerIdentity(
        val deviceId: String,
        val publicKeyBase64: String,
        val fingerprint: String,
        var isVerified: Boolean = false,
        var keyChanged: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis(),
        val conflictingKey: String? = null,
        val conflictingFingerprint: String? = null
    )

    enum class PeerKeyStatus {
        UNCHANGED,
        NEW_PEER,
        KEY_CHANGED
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val peerPrefs: SharedPreferences = context.getSharedPreferences(PEERS_PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedIdentity: DeviceIdentity? = null

    // -------------------------------------------------------------------------
    // Device Identity Management
    // -------------------------------------------------------------------------

    fun getOrCreateDeviceIdentity(): DeviceIdentity {
        cachedIdentity?.let { return it }

        synchronized(this) {
            cachedIdentity?.let { return it }

            val existingDeviceId = prefs.getString(PREF_DEVICE_ID, null)
            val existingPubKeyB64 = prefs.getString(PREF_PUBLIC_KEY, null)
            val existingEncPrivKey = prefs.getString(PREF_ENCRYPTED_PRIVATE_KEY, null)
            val existingIv = prefs.getString(PREF_PRIVATE_KEY_IV, null)

            if (!existingDeviceId.isNullOrEmpty() &&
                !existingPubKeyB64.isNullOrEmpty() &&
                !existingEncPrivKey.isNullOrEmpty() &&
                !existingIv.isNullOrEmpty()
            ) {
                try {
                    val pubKey = MeshCryptoEngine.decodePublicKey(existingPubKeyB64)
                    val privKeyBytes = decryptWithMasterKey(existingEncPrivKey, existingIv)
                    val privKeyB64 = java.util.Base64.getEncoder().encodeToString(privKeyBytes)
                    val privKey = MeshCryptoEngine.decodePrivateKey(privKeyB64)
                    val fingerprint = MeshCryptoEngine.computeFingerprint(pubKey)

                    val identity = DeviceIdentity(existingDeviceId, pubKey, existingPubKeyB64, fingerprint, privKey)
                    cachedIdentity = identity
                    Log.i(TAG, "Loaded existing device identity for $existingDeviceId (Fingerprint: $fingerprint)")
                    return identity
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load stored device identity: ${e.message}. Rebuilding keypair.", e)
                }
            }

            // Generate fresh keypair on first run
            val newDeviceId = "NODE-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            val keyPair = MeshCryptoEngine.generateKeyPair()
            val pubKeyB64 = MeshCryptoEngine.encodePublicKey(keyPair.public)
            val privKeyBytes = keyPair.private.encoded
            val fingerprint = MeshCryptoEngine.computeFingerprint(keyPair.public)

            val (encPrivKey, iv) = encryptWithMasterKey(privKeyBytes)

            prefs.edit()
                .putString(PREF_DEVICE_ID, newDeviceId)
                .putString(PREF_PUBLIC_KEY, pubKeyB64)
                .putString(PREF_ENCRYPTED_PRIVATE_KEY, encPrivKey)
                .putString(PREF_PRIVATE_KEY_IV, iv)
                .apply()

            val identity = DeviceIdentity(newDeviceId, keyPair.public, pubKeyB64, fingerprint, keyPair.private)
            cachedIdentity = identity
            Log.i(TAG, "Generated and stored new device identity: $newDeviceId (Fingerprint: $fingerprint)")
            return identity
        }
    }

    // -------------------------------------------------------------------------
    // Android KeyStore Master Key Protection
    // -------------------------------------------------------------------------

    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }

            keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable in this environment. Using secure fallback.", e)
            val fallbackAlias = "fallback_master_seed"
            var seedB64 = prefs.getString(fallbackAlias, null)
            if (seedB64.isNullOrEmpty()) {
                val seed = ByteArray(32)
                java.security.SecureRandom().nextBytes(seed)
                seedB64 = java.util.Base64.getEncoder().encodeToString(seed)
                prefs.edit().putString(fallbackAlias, seedB64).apply()
            }
            SecretKeySpec(java.util.Base64.getDecoder().decode(seedB64), "AES")
        }
    }

    private fun encryptWithMasterKey(data: ByteArray): Pair<String, String> {
        val masterKey = getOrCreateMasterKey()
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(data)

        val encB64 = java.util.Base64.getEncoder().encodeToString(ciphertext)
        val ivB64 = java.util.Base64.getEncoder().encodeToString(iv)
        return Pair(encB64, ivB64)
    }

    private fun decryptWithMasterKey(ciphertextB64: String, ivB64: String): ByteArray {
        val masterKey = getOrCreateMasterKey()
        val ciphertext = java.util.Base64.getDecoder().decode(ciphertextB64)
        val iv = java.util.Base64.getDecoder().decode(ivB64)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    // -------------------------------------------------------------------------
    // Peer Public Key & Verification Management
    // -------------------------------------------------------------------------

    fun recordPeerPublicKey(deviceId: String, publicKeyBase64: String): PeerKeyStatus {
        val existingJsonStr = peerPrefs.getString(deviceId, null)
        val fingerprint = MeshCryptoEngine.computeFingerprintFromBase64(publicKeyBase64)
        val now = System.currentTimeMillis()

        if (existingJsonStr != null) {
            try {
                val json = JSONObject(existingJsonStr)
                val storedPubKeyB64 = json.getString("publicKey")

                if (storedPubKeyB64 == publicKeyBase64) {
                    // Key matches existing record
                    json.put("lastSeen", now)
                    peerPrefs.edit().putString(deviceId, json.toString()).apply()
                    return PeerKeyStatus.UNCHANGED
                } else {
                    // CRITICAL: Key has changed! Potential MITM or conflicting identity claim
                    Log.w(TAG, "SECURITY ALERT: Peer $deviceId presented a different public key! (Stored: ${json.optString("fingerprint")}, New: $fingerprint)")
                    val wasVerified = json.optBoolean("isVerified", false)
                    json.put("keyChanged", true)
                    json.put("lastSeen", now)
                    json.put("conflictingKey", publicKeyBase64)
                    json.put("conflictingFingerprint", fingerprint)

                    if (!wasVerified) {
                        // If peer was never verified, update the active key but keep keyChanged flag
                        json.put("publicKey", publicKeyBase64)
                        json.put("fingerprint", fingerprint)
                    } else {
                        // CRITICAL: Do NOT silently overwrite a verified peer's public key with an untrusted claim!
                        // The verified key remains active until user explicit confirmation or revocation.
                        Log.w(TAG, "Refusing to silently overwrite previously verified key for $deviceId. Marked as conflicting.")
                    }

                    peerPrefs.edit().putString(deviceId, json.toString()).apply()
                    return PeerKeyStatus.KEY_CHANGED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing peer record: ${e.message}")
            }
        }

        // New peer
        val newObj = JSONObject()
        newObj.put("deviceId", deviceId)
        newObj.put("publicKey", publicKeyBase64)
        newObj.put("fingerprint", fingerprint)
        newObj.put("isVerified", false)
        newObj.put("keyChanged", false)
        newObj.put("lastSeen", now)
        peerPrefs.edit().putString(deviceId, newObj.toString()).apply()
        return PeerKeyStatus.NEW_PEER
    }

    fun getPeer(deviceId: String): PeerIdentity? {
        val jsonStr = peerPrefs.getString(deviceId, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            PeerIdentity(
                deviceId = json.getString("deviceId"),
                publicKeyBase64 = json.getString("publicKey"),
                fingerprint = json.getString("fingerprint"),
                isVerified = json.optBoolean("isVerified", false),
                keyChanged = json.optBoolean("keyChanged", false),
                lastSeen = json.optLong("lastSeen", System.currentTimeMillis()),
                conflictingKey = if (json.has("conflictingKey")) json.getString("conflictingKey") else null,
                conflictingFingerprint = if (json.has("conflictingFingerprint")) json.getString("conflictingFingerprint") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getAllPeers(): List<PeerIdentity> {
        val list = mutableListOf<PeerIdentity>()
        for (entry in peerPrefs.all) {
            val jsonStr = entry.value as? String ?: continue
            try {
                val json = JSONObject(jsonStr)
                list.add(
                    PeerIdentity(
                        deviceId = json.getString("deviceId"),
                        publicKeyBase64 = json.getString("publicKey"),
                        fingerprint = json.getString("fingerprint"),
                        isVerified = json.optBoolean("isVerified", false),
                        keyChanged = json.optBoolean("keyChanged", false),
                        lastSeen = json.optLong("lastSeen", System.currentTimeMillis()),
                        conflictingKey = if (json.has("conflictingKey")) json.getString("conflictingKey") else null,
                        conflictingFingerprint = if (json.has("conflictingFingerprint")) json.getString("conflictingFingerprint") else null
                    )
                )
            } catch (_: Exception) {
            }
        }
        return list
    }

    fun setPeerVerified(deviceId: String, verified: Boolean): Boolean {
        val jsonStr = peerPrefs.getString(deviceId, null) ?: return false
        return try {
            val json = JSONObject(jsonStr)
            if (verified && json.has("conflictingKey")) {
                // User explicitly confirmed new key: promote conflicting key to active key
                val newKey = json.getString("conflictingKey")
                val newFp = json.getString("conflictingFingerprint")
                json.put("publicKey", newKey)
                json.put("fingerprint", newFp)
                json.remove("conflictingKey")
                json.remove("conflictingFingerprint")
            }
            json.put("isVerified", verified)
            json.put("keyChanged", false) // Reset keyChanged warning once user manually verifies
            peerPrefs.edit().putString(deviceId, json.toString()).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPeerKeyValid(deviceId: String, candidateKeyBase64: String): Boolean {
        val peer = getPeer(deviceId) ?: return true // Unknown peer yet
        return peer.publicKeyBase64 == candidateKeyBase64
    }
}
