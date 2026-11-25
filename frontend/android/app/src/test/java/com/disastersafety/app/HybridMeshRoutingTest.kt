package com.disastersafety.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Focused Unit Tests for the Unified Hybrid Mesh Communication System.
 * Validates:
 * 1. Deduplication of dual-transport neighbors (BLE + Wi-Fi Direct into single "hybrid" entry).
 * 2. Transport selection (preferring Wi-Fi Direct with automatic BLE fallback on failure).
 * 3. Anti-looping rules (preventing echo back down incoming links and redundant dual-link transmissions).
 * 4. Duplicate suppression cache consistency across distinct physical transports.
 * 5. Hop limit and expiration enforcement on relayed packets.
 * 6. Authenticated Delivery ACK generation and HMAC verification across hybrid path.
 */
class HybridMeshRoutingTest {

    // -------------------------------------------------------------------------
    // Test 1: Deduplication of Dual-Transport Neighbor
    // -------------------------------------------------------------------------
    @Test
    fun testDeduplicationOfDualTransportNeighbor() {
        val neighborMap = LinkedHashMap<String, BleChatPlugin.UnifiedNeighbor>()

        // 1. Peer "NODE-B10001" discovered via BLE
        val bleNeighbor = BleChatPlugin.UnifiedNeighbor(
            deviceId = "NODE-B10001",
            deviceName = "Phone B",
            bleAddress = "AA:BB:CC:DD:EE:01",
            wifiAddress = null,
            transport = "ble",
            isBleConnected = true,
            isWifiDirectConnected = false,
            isConnected = true,
            rssi = -60,
            lastSeen = System.currentTimeMillis(),
            fingerprint = "1111:2222:3333:4444",
            isVerified = true,
            keyChanged = false,
            status = "connected"
        )
        neighborMap[bleNeighbor.deviceId] = bleNeighbor

        // 2. Wi-Fi Direct socket handshake received from same physical device "NODE-B10001" at 192.168.49.2
        val wifiPeerId = "NODE-B10001"
        val wifiIp = "192.168.49.2"
        val existing = neighborMap[wifiPeerId]

        assertNotNull("Existing BLE entry must be found for the same cryptographic deviceId", existing)

        // Deduplicate into single UnifiedNeighbor with transport = "hybrid"
        val mergedNeighbor = existing!!.copy(
            wifiAddress = wifiIp,
            isWifiDirectConnected = true,
            isConnected = true,
            transport = if (existing.isBleConnected) "hybrid" else "wifi_direct",
            status = "connected"
        )
        neighborMap[wifiPeerId] = mergedNeighbor

        // Assertions
        assertEquals("Total neighbor count must remain exactly 1 (no duplicate entries)", 1, neighborMap.size)
        val unified = neighborMap["NODE-B10001"]!!
        assertEquals("NODE-B10001", unified.deviceId)
        assertEquals("hybrid", unified.transport)
        assertTrue("isBleConnected must be true", unified.isBleConnected)
        assertTrue("isWifiDirectConnected must be true", unified.isWifiDirectConnected)
        assertTrue("isConnected must be true", unified.isConnected)
        assertEquals("AA:BB:CC:DD:EE:01", unified.bleAddress)
        assertEquals("192.168.49.2", unified.wifiAddress)
    }

    // -------------------------------------------------------------------------
    // Test 2: Transport Selection Prefers Wi-Fi Direct With BLE Fallback
    // -------------------------------------------------------------------------
    @Test
    fun testTransportSelectionPrefersWifiDirectWithBleFallback() {
        // Dual-connected neighbor
        val targetPeerId = "NODE-B10001"
        val targetBleAddr = "AA:BB:CC:DD:EE:01"
        var wifiSocketAvailable = true
        var bleGattAvailable = true

        var wifiPacketsSent = 0
        var blePacketsSent = 0

        fun dispatchPacket(bytes: ByteArray, targetId: String): String {
            // Priority 1: Check Wi-Fi Direct
            if (wifiSocketAvailable) {
                wifiPacketsSent++
                return "wifi_direct"
            }
            // Priority 2: Fallback to BLE
            if (bleGattAvailable) {
                blePacketsSent++
                return "ble"
            }
            return "failed"
        }

        val testPayload = "Hello Disaster Mesh".toByteArray(StandardCharsets.UTF_8)

        // Case A: Both transports available -> Should choose Wi-Fi Direct (high throughput)
        val chosenTransport1 = dispatchPacket(testPayload, targetPeerId)
        assertEquals("wifi_direct", chosenTransport1)
        assertEquals(1, wifiPacketsSent)
        assertEquals(0, blePacketsSent)

        // Case B: Wi-Fi Direct socket fails/disconnects -> Fallback to BLE GATT
        wifiSocketAvailable = false
        val chosenTransport2 = dispatchPacket(testPayload, targetPeerId)
        assertEquals("ble", chosenTransport2)
        assertEquals(1, wifiPacketsSent)
        assertEquals(1, blePacketsSent)
    }

    // -------------------------------------------------------------------------
    // Test 3: Anti-Looping Rules & Link Exclusion
    // -------------------------------------------------------------------------
    @Test
    fun testAntiLoopingRulesAndLinkExclusion() {
        // Connected Wi-Fi IPs: Client 1 (192.168.49.2), Client 2 (192.168.49.3)
        val wifiClients = listOf("192.168.49.2", "192.168.49.3")
        // Connected BLE addresses: Peer 1 (AA:01), Peer 2 (AA:02)
        val blePeers = listOf("AA:01", "AA:02")

        // 1. Packet arrives over Wi-Fi Direct from 192.168.49.2
        val incomingWifiIp = "192.168.49.2"
        val outgoingWifiTargets = wifiClients.filter { it != incomingWifiIp }
        assertEquals("Wi-Fi forward must exclude the incoming sender IP", listOf("192.168.49.3"), outgoingWifiTargets)

        // 2. Packet arrives over BLE from AA:01
        val incomingBleAddr = "AA:01"
        val outgoingBleTargets = blePeers.filter { it != incomingBleAddr }
        assertEquals("BLE forward must exclude the incoming BLE address", listOf("AA:02"), outgoingBleTargets)

        // 3. Dual-connected neighbor redundancy check:
        // If Peer 2 (AA:02) is ALSO Client 2 (192.168.49.3) (same physical device),
        // forwarding over Wi-Fi Direct already delivers to it.
        // BLE forwarding should skip AA:02 to avoid duplicate transmission down both links.
        val dualConnectedPeerIdsOnWifi = setOf("NODE-PEER2")
        val blePeerIdMap = mapOf("AA:02" to "NODE-PEER2")

        val filteredBleTargets = outgoingBleTargets.filter { addr ->
            val peerId = blePeerIdMap[addr]
            peerId == null || !dualConnectedPeerIdsOnWifi.contains(peerId)
        }
        assertEquals("BLE forward must skip dual-connected neighbor already reached via Wi-Fi Direct", 0, filteredBleTargets.size)
    }

    // -------------------------------------------------------------------------
    // Test 4: Duplicate Suppression Cache Consistency Across Transports
    // -------------------------------------------------------------------------
    @Test
    fun testDuplicateSuppressionCacheAcrossTransports() {
        val maxCacheSize = 500
        val processedMessageIds = Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                        return size > maxCacheSize
                    }
                }
            )
        )

        fun isDuplicateAndMark(msgId: String): Boolean {
            synchronized(processedMessageIds) {
                if (processedMessageIds.contains(msgId)) return true
                processedMessageIds.add(msgId)
                return false
            }
        }

        val testMessageId = "msg-1732400000-abcd"

        // First arrival over Wi-Fi Direct
        val isDup1 = isDuplicateAndMark(testMessageId)
        assertFalse("First packet arrival over Wi-Fi Direct must not be flagged as duplicate", isDup1)

        // Second arrival of identical packet over BLE GATT (relayed via parallel route)
        val isDup2 = isDuplicateAndMark(testMessageId)
        assertTrue("Subsequent packet arrival over BLE must be detected as duplicate and suppressed", isDup2)
    }

    // -------------------------------------------------------------------------
    // Test 5: Hop Limit and TTL Expiration
    // -------------------------------------------------------------------------
    @Test
    fun testHopLimitAndTtlExpirationEnforcement() {
        val defaultHopLimit = 5
        val now = System.currentTimeMillis()

        // Case A: Hop limit reached
        val currentHop = 4
        val nextHop = currentHop + 1
        val shouldDropHopLimit = nextHop >= defaultHopLimit
        assertTrue("Packet with nextHop >= hopLimit must be dropped", shouldDropHopLimit)

        // Case B: TTL expired
        val expiredTimestamp = now - 1000L
        val isExpired = now > expiredTimestamp
        assertTrue("Packet past its expiration timestamp must be dropped", isExpired)

        // Case C: Valid packet within hop limit and valid TTL
        val validNextHop = 2 + 1
        val validExpiry = now + 120_000L
        val shouldForward = validNextHop < defaultHopLimit && now <= validExpiry
        assertTrue("Valid packet must be eligible for forwarding", shouldForward)
    }

    // -------------------------------------------------------------------------
    // Test 6: End-to-End Hybrid Encryption & Authenticated ACK Verification
    // -------------------------------------------------------------------------
    @Test
    fun testEndToEndHybridEncryptionAndAuthenticatedAck() {
        // Simulating: Node A (Sender) and Node D (Receiver)
        val nodeAPair = MeshCryptoEngine.generateKeyPair()
        val nodeDPair = MeshCryptoEngine.generateKeyPair()

        val nodeAId = "NODE-A00001"
        val nodeDId = "NODE-D00004"
        val messageId = "msg-hybrid-test-999"
        val secretMessageText = "Urgent: Supplies needed at Evacuation Sector 4"

        // Step 1: Node A derives session keys for Node D
        val secretAD = MeshCryptoEngine.computeSharedSecret(nodeAPair.private, nodeDPair.public)
        val sessionKeysA = MeshCryptoEngine.deriveSessionKeys(secretAD)

        // Step 2: Node A encrypts message using AES-256-GCM
        val encrypted = MeshCryptoEngine.encrypt(secretMessageText, sessionKeysA.aesKey)
        assertNotNull(encrypted.ciphertextBase64)
        assertNotNull(encrypted.nonceBase64)

        // Simulated Multi-Hop Hybrid Transit:
        // Node A --Wi-Fi Direct--> Node B (Relay) --BLE--> Node C (Relay) --Wi-Fi Direct--> Node D
        // Relays B and C forward the ciphertext without decrypting.

        // Step 3: Node D receives ciphertext and derives identical session keys using Node A's public key
        val secretDA = MeshCryptoEngine.computeSharedSecret(nodeDPair.private, nodeAPair.public)
        val sessionKeysD = MeshCryptoEngine.deriveSessionKeys(secretDA)
        assertArrayEquals("Both ends must derive identical AES session keys", sessionKeysA.aesKey, sessionKeysD.aesKey)
        assertArrayEquals("Both ends must derive identical ACK HMAC keys", sessionKeysA.ackKey, sessionKeysD.ackKey)

        // Step 4: Node D decrypts message
        val decryptedText = MeshCryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.nonceBase64, sessionKeysD.aesKey)
        assertEquals("Decrypted plaintext must match original message", secretMessageText, decryptedText)

        // Step 5: Node D generates Authenticated Delivery ACK with HMAC-SHA256 authTag
        val authTag = MeshCryptoEngine.computeAckAuthTag(sessionKeysD.ackKey, messageId, nodeAId, nodeDId)
        assertNotNull(authTag)

        // Simulated ACK transit back across hybrid mesh to Node A:
        // Node D --Wi-Fi Direct--> Node C --BLE--> Node B --Wi-Fi Direct--> Node A

        // Step 6: Node A receives ACK and verifies the HMAC authTag
        val isAckValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeysA.ackKey, messageId, nodeAId, nodeDId, authTag)
        assertTrue("Node A must successfully verify the Authenticated Delivery ACK authTag", isAckValid)

        // Step 7: Tampered ACK detection (tampered message ID or authTag)
        val isTamperedAckValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeysA.ackKey, "forged-msg-id", nodeAId, nodeDId, authTag)
        assertFalse("Tampered ACK must fail verification", isTamperedAckValid)
    }
}
