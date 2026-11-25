package com.disastersafety.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Mesh Participant Directory & Multi-Hop Routing Table.
 *
 * Tracks directly connected neighbors and remote participants discovered
 * through multi-hop presence announcements, maintaining next-hop routes,
 * hop counts, reachability states, and expiration.
 */
class MeshParticipantDirectory(
    private val staleTimeoutMs: Long = DEFAULT_STALE_MS,
    private val expireTimeoutMs: Long = DEFAULT_EXPIRE_MS
) {
    companion object {
        const val DEFAULT_STALE_MS = 45_000L
        const val DEFAULT_EXPIRE_MS = 180_000L

        const val STATUS_DIRECT = "direct"
        const val STATUS_MESH = "mesh"
        const val STATUS_UNREACHABLE = "unreachable"
    }

    data class ParticipantRecord(
        val deviceId: String,
        val displayName: String,
        val publicKeyBase64: String,
        val fingerprint: String,
        val nextHopDeviceId: String,
        val hopCount: Int,
        val transport: String,
        val lastSeen: Long,
        val status: String
    ) {
        fun isReachable(): Boolean = status == STATUS_DIRECT || status == STATUS_MESH
    }

    private val participants = ConcurrentHashMap<String, ParticipantRecord>()

    /**
     * Records or updates a participant learned from a direct link or multi-hop announcement.
     *
     * @param deviceId Origin participant's persistent device ID
     * @param displayName Human-readable device model/name
     * @param publicKeyBase64 Origin participant's EC public key
     * @param fingerprint Authenticated key fingerprint
     * @param nextHopDeviceId Direct neighbor ID through which this participant is reached
     * @param hopCount Number of hops (1 = direct, 2+ = multi-hop)
     * @param transport Transport used on the first hop ("ble" or "wifi_direct")
     * @param now Current timestamp in ms
     */
    fun upsertParticipant(
        deviceId: String,
        displayName: String,
        publicKeyBase64: String,
        fingerprint: String,
        nextHopDeviceId: String,
        hopCount: Int,
        transport: String,
        now: Long = System.currentTimeMillis()
    ): ParticipantRecord {
        val safeHopCount = hopCount.coerceAtLeast(1)
        val newStatus = if (safeHopCount == 1) STATUS_DIRECT else STATUS_MESH
        val resolvedName = displayName.ifBlank { deviceId }

        val existing = participants[deviceId]
        val shouldReplaceRoute = existing == null ||
            !existing.isReachable() ||
            safeHopCount < existing.hopCount ||
            (safeHopCount == existing.hopCount && existing.nextHopDeviceId == nextHopDeviceId) ||
            (now - existing.lastSeen > staleTimeoutMs / 2)

        val record = if (shouldReplaceRoute || existing == null) {
            ParticipantRecord(
                deviceId = deviceId,
                displayName = resolvedName,
                publicKeyBase64 = publicKeyBase64.ifBlank { existing?.publicKeyBase64 ?: "" },
                fingerprint = fingerprint.ifBlank { existing?.fingerprint ?: "" },
                nextHopDeviceId = nextHopDeviceId,
                hopCount = safeHopCount,
                transport = transport,
                lastSeen = now,
                status = newStatus
            )
        } else {
            // Keep better existing route, just refresh metadata/timestamp if same next hop
            existing.copy(
                displayName = resolvedName,
                publicKeyBase64 = publicKeyBase64.ifBlank { existing.publicKeyBase64 },
                fingerprint = fingerprint.ifBlank { existing.fingerprint },
                lastSeen = maxOf(existing.lastSeen, now)
            )
        }

        participants[deviceId] = record
        return record
    }

    /**
     * Marks all participants whose next-hop route goes through [disconnectedNeighborId]
     * as unreachable.
     */
    fun markNeighborDisconnected(disconnectedNeighborId: String, now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        for ((id, rec) in participants) {
            if (rec.nextHopDeviceId == disconnectedNeighborId && rec.isReachable()) {
                participants[id] = rec.copy(status = STATUS_UNREACHABLE, lastSeen = minOf(rec.lastSeen, now))
                changed = true
            }
        }
        return changed
    }

    /**
     * Updates stale participants to [STATUS_UNREACHABLE] and removes expired entries.
     */
    fun pruneAndRefresh(
        connectedDirectNeighborIds: Set<String>,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        var changed = false
        val iterator = participants.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val rec = entry.value
            val age = now - rec.lastSeen

            if (age > expireTimeoutMs) {
                iterator.remove()
                changed = true
                continue
            }

            val nextHopAlive = connectedDirectNeighborIds.contains(rec.nextHopDeviceId)
            val shouldBeUnreachable = !nextHopAlive || age > staleTimeoutMs

            if (shouldBeUnreachable && rec.isReachable()) {
                entry.setValue(rec.copy(status = STATUS_UNREACHABLE))
                changed = true
            } else if (connectedDirectNeighborIds.contains(rec.deviceId) && rec.status != STATUS_DIRECT) {
                entry.setValue(rec.copy(status = STATUS_DIRECT, hopCount = 1, nextHopDeviceId = rec.deviceId))
                changed = true
            }
        }
        return changed
    }

    /**
     * Returns the active route to [targetDeviceId] if reachable, or null if no valid route exists.
     */
    fun getActiveRoute(targetDeviceId: String, now: Long = System.currentTimeMillis()): ParticipantRecord? {
        val rec = participants[targetDeviceId] ?: return null
        if (!rec.isReachable()) return null
        if (now - rec.lastSeen > staleTimeoutMs) return null
        return rec
    }

    fun getParticipant(deviceId: String): ParticipantRecord? = participants[deviceId]

    /**
     * Returns all known participants sorted by reachability, then hop count, then display name.
     */
    fun listParticipants(): List<ParticipantRecord> {
        return participants.values.sortedWith(
            compareBy<ParticipantRecord>(
                {
                    when (it.status) {
                        STATUS_DIRECT -> 0
                        STATUS_MESH -> 1
                        else -> 2
                    }
                },
                { it.hopCount },
                { it.displayName }
            )
        )
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (p in listParticipants()) {
            val obj = JSONObject()
            obj.put("deviceId", p.deviceId)
            obj.put("displayName", p.displayName)
            obj.put("fingerprint", p.fingerprint)
            obj.put("nextHopDeviceId", p.nextHopDeviceId)
            obj.put("hopCount", p.hopCount)
            obj.put("transport", p.transport)
            obj.put("lastSeen", p.lastSeen)
            obj.put("status", p.status)
            arr.put(obj)
        }
        return arr
    }
}
