package com.disastersafety.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, persistent Store-and-Forward Message Queue for Disaster Mesh.
 *
 * Responsibilities:
 * 1. Safe storage of encrypted packets when downstream routes are temporarily unavailable.
 * 2. Strict Privacy Protection: Relays NEVER store plaintext; only encrypted payloads and nonces are persisted.
 * 3. Priority Ordering: Emergency SOS messages are prioritized ahead of ordinary messages.
 * 4. Bounded Storage: Enforces maximum queue capacity (100 messages) with TTL expiration pruner.
 * 5. Reconnection Forwarding: Re-attempts transmission when BLE or Wi-Fi Direct links become available.
 * 6. Persistent Deduplication: Remembers processed message IDs across process termination and device reboot.
 *
 * Backed by SharedPreferences for zero-dependency, crash-resilient local persistence.
 * Supports context=null with in-memory store for isolated JVM unit testing.
 */
class MeshMessageQueue(private val context: Context? = null) {

    companion object {
        private const val TAG = "MeshMessageQueue"
        private const val QUEUE_PREFS_NAME = "disaster_mesh_message_queue"
        private const val DEDUP_PREFS_NAME = "disaster_mesh_dedup_cache"

        const val MAX_CAPACITY = 100
        const val MAX_RETRIES = 10
        const val BASE_RETRY_INTERVAL_MS = 3000L // 3 seconds base backoff
        const val MAX_DEDUP_CACHE_SIZE = 1000

        // Storage & Payload Bounds
        const val MAX_SINGLE_MESSAGE_BYTES = 8192 // 8 KB maximum serialized size per packet
        const val MAX_TOTAL_QUEUE_BYTES = 512 * 1024L // 512 KB maximum total serialized queue storage

        // Status Constants
        const val STATUS_QUEUED = "queued"
        const val STATUS_FORWARDING = "forwarding"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_FAILED = "failed"
    }

    data class QueuedMessage(
        val id: String,
        val senderId: String,
        val senderPublicKey: String,
        val recipientId: String,
        val encryptedPayload: String?, // AES-256-GCM ciphertext Base64 (Relays never see plaintext!)
        val nonce: String?,           // Nonce Base64
        val text: String?,            // Plaintext for broadcast/SOS ONLY (null for private encrypted messages!)
        val type: String,             // "encrypted_chat", "broadcast", "sos", "ack", "key_exchange"
        val priority: String,         // "emergency", "high", "normal"
        val createdAt: Long,
        val expiresAt: Long,
        val hopCount: Int,
        val hopLimit: Int,
        val signature: String? = null,
        val emergencyType: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val authTag: String? = null,
        val originalMessageId: String? = null,
        var status: String = STATUS_QUEUED,
        val isOutgoing: Boolean = false,
        var retryCount: Int = 0,
        var lastAttemptTimestamp: Long = 0L
    ) {
        val priorityRank: Int
            get() = when (priority) {
                "emergency" -> 0
                "high" -> 1
                else -> 2
            }

        val currentAttemptId: String
            get() = "$id-att$retryCount"

        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt

        fun canRetry(now: Long = System.currentTimeMillis()): Boolean {
            if (retryCount >= MAX_RETRIES) return false
            if (isExpired(now)) return false
            val backoff = BASE_RETRY_INTERVAL_MS * (1L shl Math.min(retryCount, 4))
            if (now + backoff > expiresAt) return false // Never retry if attempt would be beyond expiration
            return now - lastAttemptTimestamp >= backoff
        }
    }

    // In-memory active stores
    private val memoryQueue = ConcurrentHashMap<String, QueuedMessage>()
    private val memoryDedup = ConcurrentHashMap.newKeySet<String>()

    private val queuePrefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(QUEUE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val dedupPrefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        loadFromPersistence()
    }

    @Synchronized
    private fun loadFromPersistence() {
        val qPrefs = queuePrefs
        if (qPrefs != null) {
            val allEntries = qPrefs.all
            for ((key, value) in allEntries) {
                if (value is String) {
                    try {
                        val msg = parseMessageFromJson(JSONObject(value))
                        val now = System.currentTimeMillis()
                        if (msg != null && !msg.isExpired(now)) {
                            memoryQueue[key] = msg
                            memoryDedup.add(msg.id)
                        } else {
                            // Purge expired or incomplete entry
                            qPrefs.edit().remove(key).commit()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Removing corrupted queued entry $key: ${e.message}")
                        qPrefs.edit().remove(key).commit()
                    }
                } else {
                    qPrefs.edit().remove(key).commit()
                }
            }
        }

        val dPrefs = dedupPrefs
        if (dPrefs != null) {
            val dedupSet = dPrefs.getStringSet("seen_ids", null)
            if (dedupSet != null) {
                memoryDedup.addAll(dedupSet)
            }
        }
    }

    fun getTotalQueueBytes(): Long {
        var total = 0L
        for (msg in memoryQueue.values) {
            val jsonStr = toPacketJson(msg).toString()
            total += jsonStr.toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
        return total
    }

    @Synchronized
    fun enqueue(
        id: String,
        senderId: String,
        recipientId: String,
        text: String?,
        encryptedPayload: String?,
        nonce: String?,
        authTag: String?,
        originalSenderKey: String?,
        type: String,
        hopCount: Int,
        hopLimit: Int,
        priority: String,
        isOutgoing: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = if (priority == "emergency" || type == "sos") now + 300_000L else now + 120_000L
        val msg = QueuedMessage(
            id = id,
            senderId = senderId,
            senderPublicKey = originalSenderKey ?: "",
            recipientId = recipientId,
            encryptedPayload = encryptedPayload,
            nonce = nonce,
            text = text,
            type = type,
            priority = priority,
            createdAt = now,
            expiresAt = expiresAt,
            hopCount = hopCount,
            hopLimit = hopLimit,
            authTag = authTag,
            status = STATUS_QUEUED,
            isOutgoing = isOutgoing
        )
        return enqueue(msg)
    }

    /**
     * Enqueue a message for store-and-forward transmission.
     * Enforces privacy: private messages cannot contain plaintext in storage.
     * Enforces size boundaries: individual packet max 8 KB, total queue max 512 KB.
     * Atomic consistency: writes to disk before marking processed.
     */
    @Synchronized
    fun enqueue(msg: QueuedMessage): Boolean {
        val now = System.currentTimeMillis()

        // 1. Validation
        if (msg.isExpired(now)) {
            Log.d(TAG, "Cannot enqueue expired message ${msg.id}")
            return false
        }
        if (msg.hopCount >= msg.hopLimit) {
            Log.d(TAG, "Cannot enqueue message ${msg.id}: Hop limit reached (${msg.hopCount} >= ${msg.hopLimit})")
            return false
        }

        // 2. Idempotent check: if already in active memory queue, treat as safely accepted
        if (memoryQueue.containsKey(msg.id)) {
            Log.d(TAG, "Message ${msg.id} already held in queue (idempotent)")
            return true
        }

        // 3. Duplicate suppression: if already processed and not active in queue
        if (memoryDedup.contains(msg.id) && !msg.isOutgoing) {
            Log.d(TAG, "Message ${msg.id} already processed. Dropping duplicate.")
            return false
        }

        // 4. Privacy Rule: Intermediate relays MUST NEVER store private plaintext
        val sanitizedMsg = if (msg.type == "encrypted_chat") {
            msg.copy(text = null) // Strip any plaintext; preserve only encryptedPayload and nonce
        } else {
            msg
        }

        // 5. Individual message size limit (Max 8 KB serialized)
        val serializedStr = toPacketJson(sanitizedMsg).toString()
        val msgBytes = serializedStr.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (msgBytes > MAX_SINGLE_MESSAGE_BYTES) {
            Log.w(TAG, "Rejected oversized message ${sanitizedMsg.id} ($msgBytes bytes > $MAX_SINGLE_MESSAGE_BYTES)")
            return false
        }

        // 6. Capacity and Total Byte Limit Enforcement
        purgeExpiredMessages(now)

        while (memoryQueue.size >= MAX_CAPACITY || (getTotalQueueBytes() + msgBytes) > MAX_TOTAL_QUEUE_BYTES) {
            if (sanitizedMsg.priorityRank == 0) { // Emergency message
                // Evict oldest normal priority message to make room
                val evictable = memoryQueue.values
                    .filter { it.priorityRank > 0 && it.status != STATUS_DELIVERED }
                    .minByOrNull { it.createdAt }

                if (evictable != null) {
                    remove(evictable.id)
                    Log.i(TAG, "Evicted normal message ${evictable.id} to maintain bounds for emergency message ${sanitizedMsg.id}")
                } else {
                    Log.w(TAG, "Queue full with emergency messages. Cannot enqueue ${sanitizedMsg.id}")
                    return false
                }
            } else {
                Log.w(TAG, "Queue bounds reached (${memoryQueue.size} messages / ${getTotalQueueBytes()} bytes). Rejecting normal message ${sanitizedMsg.id}")
                return false
            }
        }

        // 7. Store in memory and persist synchronously to disk
        memoryQueue[sanitizedMsg.id] = sanitizedMsg
        val persisted = persistMessage(sanitizedMsg)
        if (!persisted && context != null) {
            // Storage failed! Abort enqueue and do NOT mark processed so retry can succeed
            memoryQueue.remove(sanitizedMsg.id)
            Log.e(TAG, "Storage failure: Could not persist message ${sanitizedMsg.id} to disk.")
            return false
        }

        // 8. ONLY mark processed AFTER successful storage!
        markProcessed(sanitizedMsg.id)

        Log.i(TAG, "Enqueued message ${sanitizedMsg.id} (type=${sanitizedMsg.type}, priority=${sanitizedMsg.priority}, status=${sanitizedMsg.status}, isOutgoing=${sanitizedMsg.isOutgoing}, bytes=$msgBytes)")
        return true
    }

    @Synchronized
    fun getPendingMessages(): List<QueuedMessage> {
        val now = System.currentTimeMillis()
        purgeExpiredMessages(now)

        return memoryQueue.values
            .filter { it.status == STATUS_QUEUED || it.status == STATUS_FORWARDING }
            .sortedWith(
                compareBy<QueuedMessage> { it.priorityRank }
                    .thenBy { it.createdAt }
            )
    }

    fun getAllMessages(): List<QueuedMessage> {
        return ArrayList(memoryQueue.values)
    }

    fun getMessage(id: String): QueuedMessage? {
        return memoryQueue[id]
    }

    @Synchronized
    fun updateStatus(id: String, newStatus: String): Boolean {
        val msg = memoryQueue[id] ?: return false

        // Invariant 1: Once DELIVERED, status can NEVER regress
        if (msg.status == STATUS_DELIVERED && newStatus != STATUS_DELIVERED) {
            Log.d(TAG, "Cannot regress delivered message $id to $newStatus")
            return false
        }

        // Invariant 2: Once EXPIRED, status cannot be reactivated
        if (msg.status == STATUS_EXPIRED && newStatus != STATUS_EXPIRED) {
            Log.d(TAG, "Cannot resurrect expired message $id to $newStatus")
            return false
        }

        // Invariant 3: Once FAILED, status cannot revert to QUEUED or FORWARDING
        if (msg.status == STATUS_FAILED && newStatus != STATUS_FAILED && newStatus != STATUS_EXPIRED) {
            Log.d(TAG, "Cannot reactivate failed message $id to $newStatus")
            return false
        }

        msg.status = newStatus
        persistMessage(msg)
        return true
    }

    /**
     * Mark that forwarding custody was acknowledged by the downstream relay.
     * Intermediate nodes purge the forwarded packet from their relay queue.
     * Origin senders keep the message in FORWARDING status until end-to-end delivery ACK.
     */
    @Synchronized
    fun markCustodyTransferred(id: String): Boolean {
        val msg = memoryQueue[id] ?: return false
        if (msg.status == STATUS_DELIVERED || msg.status == STATUS_EXPIRED || msg.status == STATUS_FAILED) {
            Log.d(TAG, "Ignoring custody transfer for terminal message $id (status=${msg.status})")
            return false
        }
        return if (!msg.isOutgoing) {
            remove(id)
            Log.i(TAG, "Relay packet $id custody successfully transferred downstream. Purged from queue.")
            true
        } else {
            msg.status = STATUS_FORWARDING
            persistMessage(msg)
            true
        }
    }

    @Synchronized
    fun recordForwardAttempt(id: String, success: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val msg = memoryQueue[id] ?: return false
        msg.lastAttemptTimestamp = now
        msg.retryCount++

        if (success) {
            msg.status = STATUS_FORWARDING
        } else {
            if (msg.retryCount >= MAX_RETRIES) {
                msg.status = STATUS_FAILED
                Log.w(TAG, "Message $id reached max retry limit ($MAX_RETRIES). Marked as failed.")
            }
        }
        persistMessage(msg)
        return true
    }

    @Synchronized
    fun markDelivered(originalMessageId: String): Boolean {
        val msg = memoryQueue[originalMessageId] ?: return false
        if (msg.status == STATUS_DELIVERED || msg.status == STATUS_EXPIRED || msg.status == STATUS_FAILED) {
            Log.w(TAG, "Cannot mark delivered on terminal failed/expired/delivered message $originalMessageId (current: ${msg.status})")
            return false
        }
        msg.status = STATUS_DELIVERED
        persistMessage(msg)
        Log.i(TAG, "Message $originalMessageId marked as DELIVERED.")
        return true
    }

    @Synchronized
    fun purgeExpiredMessages(now: Long = System.currentTimeMillis()): List<QueuedMessage> {
        val expired = ArrayList<QueuedMessage>()
        val it = memoryQueue.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val msg = entry.value
            if (msg.isExpired(now) && msg.status != STATUS_DELIVERED) {
                msg.status = STATUS_EXPIRED
                expired.add(msg)
                it.remove()
                queuePrefs?.edit()?.remove(entry.key)?.commit()
                Log.d(TAG, "Purged expired message ${entry.key} from queue.")
            }
        }
        return expired
    }

    @Synchronized
    fun pruneExpiredMessages(now: Long = System.currentTimeMillis()): Int {
        return purgeExpiredMessages(now).size
    }

    fun getQueueStats(): JSONObject {
        val all = memoryQueue.values
        val ret = JSONObject()
        ret.put("totalCount", all.size)
        ret.put("totalBytes", getTotalQueueBytes())
        ret.put("maxCapacity", MAX_CAPACITY)
        ret.put("maxBytes", MAX_TOTAL_QUEUE_BYTES)
        ret.put("pendingCount", all.count { it.status == STATUS_QUEUED || it.status == STATUS_FORWARDING })
        ret.put("deliveredCount", all.count { it.status == STATUS_DELIVERED })
        ret.put("expiredCount", all.count { it.status == STATUS_EXPIRED })
        ret.put("failedCount", all.count { it.status == STATUS_FAILED })
        return ret
    }

    @Synchronized
    fun remove(id: String) {
        memoryQueue.remove(id)
        queuePrefs?.edit()?.remove(id)?.commit()
    }

    fun isDuplicate(messageId: String): Boolean {
        return memoryDedup.contains(messageId)
    }

    @Synchronized
    fun markProcessed(messageId: String) {
        if (memoryDedup.size >= MAX_DEDUP_CACHE_SIZE) {
            val evictCount = memoryDedup.size - (MAX_DEDUP_CACHE_SIZE - 200)
            val iterator = memoryDedup.iterator()
            var removed = 0
            while (iterator.hasNext() && removed < evictCount) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
        memoryDedup.add(messageId)
        val dPrefs = dedupPrefs
        if (dPrefs != null) {
            try {
                dPrefs.edit().putStringSet("seen_ids", HashSet(memoryDedup)).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Error persisting dedup cache: ${e.message}")
            }
        }
    }

    fun clearQueue() {
        memoryQueue.clear()
        queuePrefs?.edit()?.clear()?.commit()
    }

    fun clearAll() {
        clearQueue()
        memoryDedup.clear()
        dedupPrefs?.edit()?.clear()?.commit()
    }

    // -------------------------------------------------------------------------
    // Packet Serialization & Deserialization
    // -------------------------------------------------------------------------

    fun toPacketJson(msg: QueuedMessage): JSONObject {
        val json = JSONObject()
        json.put("id", msg.id)
        json.put("senderId", msg.senderId)
        json.put("senderPublicKey", msg.senderPublicKey)
        json.put("recipientId", msg.recipientId)
        json.put("type", msg.type)
        json.put("priority", msg.priority)
        json.put("hopCount", msg.hopCount)
        json.put("hopLimit", msg.hopLimit)
        json.put("createdAt", msg.createdAt)
        json.put("expiresAt", msg.expiresAt)

        if (!msg.encryptedPayload.isNullOrEmpty()) json.put("encryptedPayload", msg.encryptedPayload)
        if (!msg.nonce.isNullOrEmpty()) json.put("nonce", msg.nonce)
        if (!msg.text.isNullOrEmpty()) json.put("text", msg.text)
        if (!msg.signature.isNullOrEmpty()) json.put("signature", msg.signature)
        if (!msg.emergencyType.isNullOrEmpty()) json.put("emergencyType", msg.emergencyType)
        if (msg.latitude != null) json.put("latitude", msg.latitude)
        if (msg.longitude != null) json.put("longitude", msg.longitude)
        if (!msg.authTag.isNullOrEmpty()) json.put("authTag", msg.authTag)
        if (!msg.originalMessageId.isNullOrEmpty()) json.put("originalMessageId", msg.originalMessageId)

        return json
    }

    private fun persistMessage(msg: QueuedMessage): Boolean {
        val qPrefs = queuePrefs ?: return true
        return try {
            val json = JSONObject()
            json.put("id", msg.id)
            json.put("senderId", msg.senderId)
            json.put("senderPublicKey", msg.senderPublicKey)
            json.put("recipientId", msg.recipientId)
            json.put("type", msg.type)
            json.put("priority", msg.priority)
            json.put("createdAt", msg.createdAt)
            json.put("expiresAt", msg.expiresAt)
            json.put("hopCount", msg.hopCount)
            json.put("hopLimit", msg.hopLimit)
            json.put("status", msg.status)
            json.put("isOutgoing", msg.isOutgoing)
            json.put("retryCount", msg.retryCount)
            json.put("lastAttemptTimestamp", msg.lastAttemptTimestamp)

            if (msg.encryptedPayload != null) json.put("encryptedPayload", msg.encryptedPayload)
            if (msg.nonce != null) json.put("nonce", msg.nonce)
            if (msg.text != null) json.put("text", msg.text)
            if (msg.signature != null) json.put("signature", msg.signature)
            if (msg.emergencyType != null) json.put("emergencyType", msg.emergencyType)
            if (msg.latitude != null) json.put("latitude", msg.latitude)
            if (msg.longitude != null) json.put("longitude", msg.longitude)
            if (msg.authTag != null) json.put("authTag", msg.authTag)
            if (msg.originalMessageId != null) json.put("originalMessageId", msg.originalMessageId)

            qPrefs.edit().putString(msg.id, json.toString()).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting message ${msg.id}: ${e.message}", e)
            false
        }
    }

    private fun parseMessageFromJson(json: JSONObject): QueuedMessage? {
        val id = json.optString("id", "")
        if (id.isEmpty()) return null

        return QueuedMessage(
            id = id,
            senderId = json.optString("senderId", ""),
            senderPublicKey = json.optString("senderPublicKey", ""),
            recipientId = json.optString("recipientId", "ALL"),
            encryptedPayload = if (json.has("encryptedPayload")) json.getString("encryptedPayload") else null,
            nonce = if (json.has("nonce")) json.getString("nonce") else null,
            text = if (json.has("text")) json.getString("text") else null,
            type = json.optString("type", "chat"),
            priority = json.optString("priority", "normal"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 120_000L),
            hopCount = json.optInt("hopCount", 0),
            hopLimit = json.optInt("hopLimit", 5),
            signature = if (json.has("signature")) json.getString("signature") else null,
            emergencyType = if (json.has("emergencyType")) json.getString("emergencyType") else null,
            latitude = if (json.has("latitude")) json.getDouble("latitude") else null,
            longitude = if (json.has("longitude")) json.getDouble("longitude") else null,
            authTag = if (json.has("authTag")) json.getString("authTag") else null,
            originalMessageId = if (json.has("originalMessageId")) json.getString("originalMessageId") else null,
            status = json.optString("status", STATUS_QUEUED),
            isOutgoing = json.optBoolean("isOutgoing", false),
            retryCount = json.optInt("retryCount", 0),
            lastAttemptTimestamp = json.optLong("lastAttemptTimestamp", 0L)
        )
    }
}
