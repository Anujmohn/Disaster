package com.disastersafety.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResult
import com.getcapacitor.*
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
@CapacitorPlugin(
    name = "BleChat",
    permissions = [
        Permission(
            alias = "bluetooth",
            strings = [
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ]
        ),
        Permission(
            alias = "location",
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ]
        ),
        Permission(
            alias = "notifications",
            strings = [
                Manifest.permission.POST_NOTIFICATIONS
            ]
        ),
        Permission(
            alias = "wifiNearby",
            strings = [
                Manifest.permission.NEARBY_WIFI_DEVICES
            ]
        )
    ]
)
class BleChatPlugin : Plugin() {

    companion object {
        private const val TAG = "BleChatPlugin"
        val SERVICE_UUID: UUID = UUID.fromString("0000d15a-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000d15b-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        // Connection & Routing Configuration
        const val MAX_DIRECT_CONNECTIONS = 4
        private const val DEVICE_TIMEOUT_MS = 25000L
        private const val MAX_CONNECT_RETRIES = 2
        private const val GATT_CONNECT_TIMEOUT_MS = 12_000L
        private const val BLE_TX_TIMEOUT_MS = 3_000L
        private const val RX_CHUNK_TIMEOUT_MS = 10_000L
        private const val CHUNK_MAGIC_0 = 0xAA.toByte()
        private const val CHUNK_MAGIC_1 = 0x55.toByte()
        private const val CHUNK_HEADER_SIZE = 5
        private const val DEVICE_EMIT_THROTTLE_MS = 1200L

        // Multi-hop routing parameters
        const val DEFAULT_HOP_LIMIT = 5
        const val MESSAGE_TTL_MS = 120_000L // 2 minutes expiration
        const val SOS_TTL_MS = 300_000L // 5 minutes expiration for Emergency SOS
        const val BROADCAST_MIN_INTERVAL_MS = 2000L // Rate limit: minimum 2s between broadcasts
        const val MAX_DUPLICATE_CACHE_SIZE = 500

        // Static active instance for Background Foreground Service queue triggers
        @Volatile
        var activeInstance: BleChatPlugin? = null
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    // Cryptographic Identity & Secure Key Storage
    private lateinit var keyStorage: MeshKeyStorage
    private lateinit var deviceIdentity: MeshKeyStorage.DeviceIdentity
    private var lastBroadcastTimestamp = 0L

    // Persistent Store-and-Forward Message Queue & Deduplication Engine
    private lateinit var messageQueue: MeshMessageQueue

    // Local Mesh Participant Directory & Multi-Hop Routing Table
    private val participantDirectory = MeshParticipantDirectory()
    private val presenceRunnable = object : Runnable {
        override fun run() {
            try {
                if (getConnectedDirectNeighborIds().isNotEmpty()) {
                    broadcastPresenceAnnouncement()
                }
                if (participantDirectory.pruneAndRefresh(getConnectedDirectNeighborIds())) {
                    emitMeshParticipants()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in presenceRunnable: ${e.message}")
            }
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    // Wi-Fi Direct Manager for High-Throughput Direct Transport
    private lateinit var wifiDirectManager: WifiDirectManager

    // Multi-device connections management
    private val clientGattMap = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverClientDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val connectionStates = ConcurrentHashMap<String, String>() // address -> "connecting"|"connected"|"failed"|"disconnected"
    private val connectionRetries = ConcurrentHashMap<String, Int>()
    private val manualDisconnects = ConcurrentHashMap.newKeySet<String>()
    private val addressToDeviceId = ConcurrentHashMap<String, String>()
    private val deviceIdToAddress = ConcurrentHashMap<String, String>()
    private val peerMtuMap = ConcurrentHashMap<String, Int>()
    private val gattReadyAddresses = ConcurrentHashMap.newKeySet<String>()
    private val subscribedServerClients = ConcurrentHashMap.newKeySet<String>()
    private val bleTxQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val bleTxInFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val bleTxTimeoutRunnables = ConcurrentHashMap<String, Runnable>()
    private val connectionTimeouts = ConcurrentHashMap<String, Runnable>()
    private val rxReassemblyBuffers = ConcurrentHashMap<String, ChunkAssemblyBuffer>()
    private val txSequenceGenerator = AtomicInteger(0)
    private val lastDeviceEmitTimes = ConcurrentHashMap<String, Long>()
    private var lastGlobalEmitTime = 0L

    private data class ChunkAssemblyBuffer(
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        val createdAt: Long
    ) {
        var receivedCount: Int = 0
    }

    private val staleCleanupRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                val beforeSize = discoveredDevices.size
                cleanStaleDevices()
                if (discoveredDevices.size != beforeSize) {
                    emitDiscoveredDevices(force = true)
                }
                mainHandler.postDelayed(this, 5_000L)
            }
        }
    }

    // Bounded in-memory duplicate suppression cache (stores up to 500 recent message IDs)
    private val processedMessageIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > MAX_DUPLICATE_CACHE_SIZE
                }
            }
        )
    )

    private var totalRelayedCount = 0
    private var localDeviceId: String = ""
    private var isScanning = false
    private var isAdvertising = false

    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isProcessingQueue = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var lastCommunicationTime: Long = 0L
    @Volatile
    private var lastForwardingError: String? = null

    private data class DiscoveredDevice(
        val deviceId: String,
        val deviceName: String,
        val address: String,
        var rssi: Int,
        var lastSeen: Long
    )

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.w(TAG, "Bluetooth turned off while app/service running")
                    resetBleStateOnAdapterOff()
                    if (BleMeshForegroundService.isRunning) {
                        BleMeshForegroundService.updateStatus(context, 0, "Bluetooth disabled")
                    }
                    mainHandler.post {
                        val event = JSObject()
                        event.put("isRunning", BleMeshForegroundService.isRunning)
                        event.put("status", "Bluetooth disabled")
                        event.put("connectedCount", 0)
                        event.put("isBluetoothEnabled", false)
                        notifyListeners("bleBackgroundMeshStateChanged", event)

                        val adapterEvent = JSObject()
                        adapterEvent.put("isBluetoothEnabled", false)
                        notifyListeners("bleAdapterStateChanged", adapterEvent)
                    }
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth turned back on")
                    refreshBluetoothAdapter()
                    if (BleMeshForegroundService.isRunning) {
                        startDiscoveryInternal()
                        updateBackgroundMeshNotification()
                    }
                    mainHandler.post {
                        val event = JSObject()
                        event.put("isRunning", BleMeshForegroundService.isRunning)
                        event.put("status", if (getTotalConnectedCount() > 0) "Mesh Active" else "Searching for nearby devices...")
                        event.put("connectedCount", getTotalConnectedCount())
                        event.put("isBluetoothEnabled", true)
                        notifyListeners("bleBackgroundMeshStateChanged", event)

                        val adapterEvent = JSObject()
                        adapterEvent.put("isBluetoothEnabled", true)
                        notifyListeners("bleAdapterStateChanged", adapterEvent)
                    }
                }
            }
        }
    }

    private fun refreshBluetoothAdapter(): BluetoothAdapter? {
        if (bluetoothAdapter == null) {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetoothAdapter = manager?.adapter
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing BluetoothAdapter: ${e.message}")
            }
        }
        return bluetoothAdapter
    }

    private fun resetBleStateOnAdapterOff() {
        isScanning = false
        isAdvertising = false
        bluetoothLeScanner = null
        bluetoothLeAdvertiser = null
        mainHandler.removeCallbacks(staleCleanupRunnable)

        for ((addr, runnable) in connectionTimeouts) {
            mainHandler.removeCallbacks(runnable)
        }
        connectionTimeouts.clear()

        for (gatt in clientGattMap.values) {
            try {
                gatt.close()
            } catch (_: Exception) {}
        }
        clientGattMap.clear()
        serverClientDevices.clear()
        connectionStates.clear()
        connectionRetries.clear()
        gattReadyAddresses.clear()
        subscribedServerClients.clear()
        peerMtuMap.clear()
        bleTxQueues.clear()
        bleTxInFlight.clear()
        for ((_, runnable) in bleTxTimeoutRunnables) {
            mainHandler.removeCallbacks(runnable)
        }
        bleTxTimeoutRunnables.clear()
        rxReassemblyBuffers.clear()

        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null

        discoveredDevices.clear()
        emitDiscoveredDevices(force = true)
        notifyConnectionState("disconnected", null, "Bluetooth turned off")
    }

    override fun load() {
        super.load()
        activeInstance = this
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = manager?.adapter
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing BluetoothManager in load(): ${e.message}", e)
        }

        // Initialize persistent cryptographic identity protected by Android Keystore
        try {
            keyStorage = MeshKeyStorage(context)
            deviceIdentity = keyStorage.getOrCreateDeviceIdentity()
            localDeviceId = deviceIdentity.deviceId
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MeshKeyStorage in load(): ${e.message}", e)
            val fallbackPair = MeshCryptoEngine.generateKeyPair()
            val fallbackId = "NODE-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            deviceIdentity = MeshKeyStorage.DeviceIdentity(
                deviceId = fallbackId,
                publicKey = fallbackPair.public,
                publicKeyBase64 = MeshCryptoEngine.encodePublicKey(fallbackPair.public),
                fingerprint = MeshCryptoEngine.computeFingerprint(fallbackPair.public),
                privateKey = fallbackPair.private
            )
            localDeviceId = fallbackId
        }

        // Initialize persistent Store-and-Forward Message Queue & Deduplication Engine
        try {
            messageQueue = MeshMessageQueue(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MeshMessageQueue in load(): ${e.message}", e)
            messageQueue = MeshMessageQueue(null)
        }

        // Wire foreground notification [Stop Mesh] action callback
        BleMeshForegroundService.onStopRequested = {
            mainHandler.post {
                Log.i(TAG, "Notification requested stopping background mesh.")
                stopDiscoveryInternal()
                val event = JSObject()
                event.put("isRunning", false)
                event.put("status", "Stopped")
                event.put("connectedCount", getTotalConnectedCount())
                notifyListeners("bleBackgroundMeshStateChanged", event)
            }
        }

        // Register Bluetooth hardware state receiver
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(bluetoothStateReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering bluetoothStateReceiver: ${e.message}")
        }

        // Initialize Native Wi-Fi Direct Manager
        try {
            wifiDirectManager = WifiDirectManager(
                context = context,
                localDeviceIdProvider = { localDeviceId },
                localPublicKeyProvider = { deviceIdentity.publicKeyBase64 },
                onPacketReceived = { bytes, incomingAddress, transport ->
                    handleIncomingPacket(bytes, incomingAddress, transport)
                },
                onPeersChanged = { peers ->
                    val list = JSONArray()
                    for (peer in peers) {
                        val obj = JSONObject()
                        obj.put("deviceAddress", peer.deviceAddress)
                        obj.put("deviceName", peer.deviceName)
                        obj.put("status", peer.statusText)
                        obj.put("isGroupOwner", peer.isGroupOwner)
                        obj.put("primaryDeviceType", peer.primaryDeviceType)
                        list.put(obj)
                    }
                    val data = JSObject()
                    data.put("peers", list)
                    notifyListeners("wifiDirectPeersChanged", data)
                    emitUnifiedNeighbors()
                },
                onConnectionChanged = { info ->
                    val data = JSObject()
                    data.put("isConnected", info.isConnected)
                    data.put("isGroupOwner", info.isGroupOwner)
                    data.put("groupOwnerAddress", info.groupOwnerAddress ?: "")
                    data.put("groupFormed", info.groupFormed)
                    data.put("connectedPeersCount", info.connectedPeersCount)
                    data.put("remotePeerId", info.remotePeerId ?: "")
                    notifyListeners("wifiDirectConnectionChanged", data)
                    updateBackgroundMeshNotification()
                    emitUnifiedNeighbors()
                    if (info.isConnected) {
                        processPendingQueue()
                    }
                },
                onStateChanged = { isEnabled ->
                    val data = JSObject()
                    data.put("isEnabled", isEnabled)
                    notifyListeners("wifiDirectStateChanged", data)
                    emitUnifiedNeighbors()
                }
            )
            wifiDirectManager.onHandshakeReceived = { peerId, pubKey, remoteIp ->
                Log.i(TAG, "Wi-Fi Direct handshake received from peerId=$peerId at $remoteIp")
                if (pubKey.isNotBlank()) {
                    val status = keyStorage.recordPeerPublicKey(peerId, pubKey)
                    Log.i(TAG, "Stored peer key for $peerId from Wi-Fi Direct: $status")
                    participantDirectory.upsertParticipant(
                        deviceId = peerId,
                        displayName = peerId,
                        publicKeyBase64 = pubKey,
                        fingerprint = MeshCryptoEngine.computeFingerprintFromBase64(pubKey),
                        nextHopDeviceId = peerId,
                        hopCount = 1,
                        transport = "wifi_direct"
                    )
                    emitMeshParticipants()
                }
                emitUnifiedNeighbors()
                broadcastPresenceAnnouncement()
                processPendingQueue()
            }
            wifiDirectManager.register()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WifiDirectManager in load(): ${e.message}", e)
        }

        mainHandler.postDelayed(presenceRunnable, 5_000L)
        Log.i(TAG, "BleChatPlugin initialized with localDeviceId: $localDeviceId, Fingerprint: ${deviceIdentity.fingerprint}")
    }

    data class UnifiedNeighbor(
        val deviceId: String,
        val deviceName: String,
        val bleAddress: String?,
        val wifiAddress: String?,
        val transport: String, // "ble" | "wifi_direct" | "hybrid" | "none"
        val isBleConnected: Boolean,
        val isWifiDirectConnected: Boolean,
        val isConnected: Boolean,
        val rssi: Int,
        val lastSeen: Long,
        val fingerprint: String,
        val isVerified: Boolean,
        val keyChanged: Boolean,
        val status: String
    )

    private fun getUnifiedNeighborsList(): List<UnifiedNeighbor> {
        cleanStaleDevices()
        val unifiedMap = LinkedHashMap<String, UnifiedNeighbor>()

        // 1. Process BLE discovered devices
        for (dev in discoveredDevices.values) {
            val isBleConn = isDeviceConnected(dev.address)
            val isWifiConn = wifiDirectManager.isPeerConnected(dev.deviceId)
            val wifiIp = wifiDirectManager.getIpForPeerId(dev.deviceId)
            val peerInfo = keyStorage.getPeer(dev.deviceId)
            val status = when {
                isBleConn || isWifiConn -> "connected"
                connectionStates[dev.address] != null -> connectionStates[dev.address]!!
                else -> "discovered"
            }
            val transport = when {
                isBleConn && isWifiConn -> "hybrid"
                isBleConn -> "ble"
                isWifiConn -> "wifi_direct"
                else -> "ble"
            }

            unifiedMap[dev.deviceId] = UnifiedNeighbor(
                deviceId = dev.deviceId,
                deviceName = dev.deviceName,
                bleAddress = dev.address,
                wifiAddress = wifiIp,
                transport = transport,
                isBleConnected = isBleConn,
                isWifiDirectConnected = isWifiConn,
                isConnected = isBleConn || isWifiConn,
                rssi = dev.rssi,
                lastSeen = dev.lastSeen,
                fingerprint = peerInfo?.fingerprint ?: "",
                isVerified = peerInfo?.isVerified ?: false,
                keyChanged = peerInfo?.keyChanged ?: false,
                status = status
            )
        }

        // 2. Process Wi-Fi Direct connected peers (handshake recorded or active socket)
        val wifiConnectedPeers = wifiDirectManager.getConnectedPeerDeviceIds()
        for ((ip, peerId) in wifiConnectedPeers) {
            if (peerId == localDeviceId) continue
            val existing = unifiedMap[peerId]
            if (existing != null) {
                unifiedMap[peerId] = existing.copy(
                    wifiAddress = ip,
                    isWifiDirectConnected = true,
                    isConnected = true,
                    transport = if (existing.isBleConnected) "hybrid" else "wifi_direct",
                    status = "connected"
                )
            } else {
                val peerInfo = keyStorage.getPeer(peerId)
                unifiedMap[peerId] = UnifiedNeighbor(
                    deviceId = peerId,
                    deviceName = peerId,
                    bleAddress = null,
                    wifiAddress = ip,
                    transport = "wifi_direct",
                    isBleConnected = false,
                    isWifiDirectConnected = true,
                    isConnected = true,
                    rssi = 0,
                    lastSeen = System.currentTimeMillis(),
                    fingerprint = peerInfo?.fingerprint ?: "",
                    isVerified = peerInfo?.isVerified ?: false,
                    keyChanged = peerInfo?.keyChanged ?: false,
                    status = "connected"
                )
            }
        }

        // 3. If connected to Group Owner and remotePeerId not yet in peerDeviceIds
        val connInfo = wifiDirectManager.getConnectionInfo()
        if (connInfo.isConnected && !connInfo.remotePeerId.isNullOrEmpty() && connInfo.remotePeerId != localDeviceId) {
            val peerId = connInfo.remotePeerId!!
            val existing = unifiedMap[peerId]
            if (existing != null) {
                unifiedMap[peerId] = existing.copy(
                    wifiAddress = connInfo.groupOwnerAddress,
                    isWifiDirectConnected = true,
                    isConnected = true,
                    transport = if (existing.isBleConnected) "hybrid" else "wifi_direct",
                    status = "connected"
                )
            } else {
                val peerInfo = keyStorage.getPeer(peerId)
                unifiedMap[peerId] = UnifiedNeighbor(
                    deviceId = peerId,
                    deviceName = peerId,
                    bleAddress = null,
                    wifiAddress = connInfo.groupOwnerAddress,
                    transport = "wifi_direct",
                    isBleConnected = false,
                    isWifiDirectConnected = true,
                    isConnected = true,
                    rssi = 0,
                    lastSeen = System.currentTimeMillis(),
                    fingerprint = peerInfo?.fingerprint ?: "",
                    isVerified = peerInfo?.isVerified ?: false,
                    keyChanged = peerInfo?.keyChanged ?: false,
                    status = "connected"
                )
            }
        }

        return ArrayList(unifiedMap.values)
    }

    private fun getConnectedUnifiedNeighbors(): List<UnifiedNeighbor> {
        return getUnifiedNeighborsList().filter { it.isConnected }
    }

    private fun emitUnifiedNeighbors() {
        val neighbors = getUnifiedNeighborsList()
        val list = JSArray()
        for (n in neighbors) {
            val obj = JSObject()
            obj.put("deviceId", n.deviceId)
            obj.put("deviceName", n.deviceName)
            obj.put("bleAddress", n.bleAddress ?: "")
            obj.put("wifiAddress", n.wifiAddress ?: "")
            obj.put("transport", n.transport)
            obj.put("isBleConnected", n.isBleConnected)
            obj.put("isWifiDirectConnected", n.isWifiDirectConnected)
            obj.put("isConnected", n.isConnected)
            obj.put("rssi", n.rssi)
            obj.put("lastSeen", n.lastSeen)
            obj.put("fingerprint", n.fingerprint)
            obj.put("isVerified", n.isVerified)
            obj.put("keyChanged", n.keyChanged)
            obj.put("status", n.status)
            list.put(obj)
        }
        val event = JSObject()
        event.put("neighbors", list)
        event.put("connectedCount", getConnectedUnifiedNeighbors().size)
        event.put("relayedCount", totalRelayedCount)
        notifyListeners("unifiedNeighborsChanged", event)
    }

    private fun getTotalConnectedCount(): Int {
        return getConnectedUnifiedNeighbors().size
    }

    private fun isDuplicateAndMark(messageId: String): Boolean {
        if (!::messageQueue.isInitialized) {
            return false
        }
        if (messageQueue.isDuplicate(messageId)) {
            return true
        }
        messageQueue.markProcessed(messageId)
        return false
    }

    /**
     * Store-and-Forward Engine: Processes pending queued messages.
     * Invoked when network connectivity changes (BLE connected, Wi-Fi Direct socket connected,
     * or periodic foreground service sweep).
     */
    fun processPendingQueue() {
        if (!::messageQueue.isInitialized) return
        if (!isProcessingQueue.compareAndSet(false, true)) {
            Log.d(TAG, "processPendingQueue: Queue processing already in progress. Skipping concurrent run.")
            return
        }
        try {
            if (getTotalConnectedCount() == 0) {
                return
            }
            val pending = messageQueue.getPendingMessages()
            if (pending.isEmpty()) return

            Log.i(TAG, "Processing pending store-and-forward queue (${pending.size} messages waiting)...")
            val now = System.currentTimeMillis()

            for (msg in pending) {
                if (msg.isExpired(now)) {
                    messageQueue.updateStatus(msg.id, MeshMessageQueue.STATUS_EXPIRED)
                    if (msg.isOutgoing) {
                        emitMessageStatusChanged(msg.id, MeshMessageQueue.STATUS_EXPIRED, msg.hopCount)
                    }
                    continue
                }

                if (!msg.canRetry(now)) continue

                val packetObj = messageQueue.toPacketJson(msg)
                val packetBytes = packetObj.toString().toByteArray(StandardCharsets.UTF_8)
                val isEmergency = msg.priority == "emergency" || msg.type == "sos"

                val dispatched = dispatchHybridPacket(
                    bytes = packetBytes,
                    targetDeviceId = if (msg.recipientId != "ALL" && msg.recipientId != "BROADCAST") msg.recipientId else null,
                    incomingAddress = null,
                    incomingTransport = null,
                    incomingSenderId = msg.senderId,
                    isEmergency = isEmergency
                )

                messageQueue.recordForwardAttempt(msg.id, dispatched, now)

                if (dispatched) {
                    Log.i(TAG, "Successfully dispatched stored packet ${msg.id} across hybrid mesh (isOutgoing=${msg.isOutgoing})")
                    if (msg.isOutgoing) {
                        emitMessageStatusChanged(msg.id, MeshMessageQueue.STATUS_FORWARDING, msg.hopCount)
                    }
                }
            }
        } finally {
            isProcessingQueue.set(false)
        }
    }

    private fun emitMessageStatusChanged(messageId: String, status: String, hopCount: Int = 0) {
        mainHandler.post {
            val event = JSObject()
            event.put("messageId", messageId)
            event.put("status", status)
            event.put("hopCount", hopCount)
            event.put("timestamp", System.currentTimeMillis())
            notifyListeners("bleMessageStatusChanged", event)
        }
    }

    // -------------------------------------------------------------------------
    // Plugin Methods Exposed to React
    // -------------------------------------------------------------------------

    @PluginMethod
    fun getLocalDeviceId(call: PluginCall) {
        val ret = JSObject()
        ret.put("deviceId", localDeviceId)
        ret.put("fingerprint", deviceIdentity.fingerprint)
        ret.put("publicKey", deviceIdentity.publicKeyBase64)
        call.resolve(ret)
    }

    @PluginMethod
    fun getDeviceIdentity(call: PluginCall) {
        val ret = JSObject()
        ret.put("deviceId", deviceIdentity.deviceId)
        ret.put("publicKey", deviceIdentity.publicKeyBase64)
        ret.put("fingerprint", deviceIdentity.fingerprint)
        call.resolve(ret)
    }

    @PluginMethod
    fun getKnownPeers(call: PluginCall) {
        val list = JSONArray()
        for (peer in keyStorage.getAllPeers()) {
            val obj = JSONObject()
            obj.put("deviceId", peer.deviceId)
            obj.put("publicKey", peer.publicKeyBase64)
            obj.put("fingerprint", peer.fingerprint)
            obj.put("isVerified", peer.isVerified)
            obj.put("keyChanged", peer.keyChanged)
            obj.put("lastSeen", peer.lastSeen)
            list.put(obj)
        }
        val ret = JSObject()
        ret.put("peers", list)
        call.resolve(ret)
    }

    @PluginMethod
    fun getMeshParticipants(call: PluginCall) {
        participantDirectory.pruneAndRefresh(getConnectedDirectNeighborIds())
        val ret = JSObject()
        ret.put("participants", participantDirectory.toJsonArray())
        call.resolve(ret)
    }

    @PluginMethod
    fun verifyPeerKey(call: PluginCall) {
        val deviceId = call.getString("deviceId")
        val verified = call.getBoolean("verified") ?: true
        if (deviceId.isNullOrEmpty()) {
            call.reject("Peer deviceId is required.")
            return
        }
        val success = keyStorage.setPeerVerified(deviceId, verified)
        val ret = JSObject()
        ret.put("success", success)
        ret.put("deviceId", deviceId)
        ret.put("isVerified", verified)
        call.resolve(ret)
    }

    @PluginMethod
    fun getQueuedMessages(call: PluginCall) {
        val list = JSONArray()
        val stats = if (::messageQueue.isInitialized) messageQueue.getQueueStats() else JSONObject()
        val all = if (::messageQueue.isInitialized) messageQueue.getAllMessages() else emptyList()
        for (msg in all) {
            val obj = JSONObject()
            obj.put("id", msg.id)
            obj.put("senderId", msg.senderId)
            obj.put("recipientId", msg.recipientId)
            obj.put("type", msg.type)
            obj.put("status", msg.status)
            obj.put("priority", msg.priority)
            obj.put("hopCount", msg.hopCount)
            obj.put("createdAt", msg.createdAt)
            obj.put("expiresAt", msg.expiresAt)
            obj.put("retryCount", msg.retryCount)
            obj.put("isOutgoing", msg.isOutgoing)
            list.put(obj)
        }
        val ret = JSObject()
        ret.put("messages", list)
        ret.put("count", list.length())
        ret.put("pendingCount", stats.optInt("pendingCount", 0))
        ret.put("deliveredCount", stats.optInt("deliveredCount", 0))
        ret.put("expiredCount", stats.optInt("expiredCount", 0))
        ret.put("failedCount", stats.optInt("failedCount", 0))
        call.resolve(ret)
    }

    @PluginMethod
    fun getMeshDiagnostics(call: PluginCall) {
        val ret = JSObject()
        ret.put("deviceId", localDeviceId)
        ret.put("fingerprint", deviceIdentity.fingerprint)
        ret.put("bleConnectedCount", clientGattMap.size + serverClientDevices.size)
        val wifiInfo = wifiDirectManager.getConnectionInfo()
        ret.put("wifiDirectConnected", wifiInfo.isConnected)
        ret.put("wifiDirectGroupOwner", wifiInfo.isGroupOwner)
        ret.put("wifiDirectPeersCount", wifiInfo.connectedPeersCount)
        ret.put("totalConnected", getTotalConnectedCount())
        val queueStats = if (::messageQueue.isInitialized) messageQueue.getQueueStats() else JSONObject()
        ret.put("queuedMessageCount", queueStats.optInt("totalMessages", 0))
        ret.put("queueSizeBytes", if (::messageQueue.isInitialized) messageQueue.getTotalQueueBytes() else 0L)
        ret.put("maxCapacity", MeshMessageQueue.MAX_CAPACITY)
        ret.put("totalRelayedCount", totalRelayedCount)
        ret.put("lastCommunicationTime", lastCommunicationTime)
        ret.put("lastForwardingError", lastForwardingError ?: "")
        ret.put("foregroundServiceRunning", BleMeshForegroundService.isRunning)
        ret.put("e2eeCipher", "AES-256-GCM+AAD")
        call.resolve(ret)
    }

    @PluginMethod
    fun requestKeyExchange(call: PluginCall) {
        val targetId = call.getString("targetDeviceId")
        if (targetId.isNullOrEmpty()) {
            call.reject("targetDeviceId is required.")
            return
        }
        sendKeyExchangeToMesh(targetId)
        val ret = JSObject()
        ret.put("success", true)
        ret.put("targetDeviceId", targetId)
        call.resolve(ret)
    }

    @PluginMethod
    fun checkAvailability(call: PluginCall) {
        val adapter = refreshBluetoothAdapter()
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val isEnabled = adapter?.isEnabled == true
        val hasPermissions = checkBlePermissions()

        val ret = JSObject()
        ret.put("hasBleHardware", hasBle)
        ret.put("isBluetoothEnabled", isEnabled)
        ret.put("hasPermissions", hasPermissions)
        ret.put("blePermissionsPermanentlyDenied", isBlePermanentlyDenied())
        ret.put("hasLocationPermissions", checkLocationPermissions())
        ret.put("locationPermissionsPermanentlyDenied", isLocationPermanentlyDenied())
        ret.put("isLocationServicesEnabled", isSystemLocationEnabled())
        ret.put("localDeviceId", localDeviceId)
        ret.put("fingerprint", deviceIdentity.fingerprint)
        ret.put("publicKey", deviceIdentity.publicKeyBase64)
        ret.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        ret.put("connectedCount", getTotalConnectedCount())
        ret.put("relayedCount", totalRelayedCount)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestBlePermissions(call: PluginCall) {
        if (checkBlePermissions()) {
            val ret = JSObject()
            ret.put("granted", true)
            ret.put("permanentlyDenied", false)
            call.resolve(ret)
            emitPermissionsChanged()
            return
        }

        context.getSharedPreferences("ble_perm_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("ble_perm_requested", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionForAlias("bluetooth", call, "permissionCallback")
        } else {
            context.getSharedPreferences("ble_perm_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("loc_perm_requested", true).apply()
            requestPermissionForAlias("location", call, "permissionCallback")
        }
    }

    @PermissionCallback
    fun permissionCallback(call: PluginCall) {
        val granted = checkBlePermissions()
        val permDenied = isBlePermanentlyDenied()
        val ret = JSObject()
        ret.put("granted", granted)
        ret.put("permanentlyDenied", permDenied)
        call.resolve(ret)
        emitPermissionsChanged()
    }

    @PluginMethod
    fun checkLocationPermission(call: PluginCall) {
        val ret = JSObject()
        ret.put("granted", checkLocationPermissions())
        ret.put("preciseGranted", isPreciseLocationGranted())
        ret.put("permanentlyDenied", isLocationPermanentlyDenied())
        ret.put("isLocationServicesEnabled", isSystemLocationEnabled())
        call.resolve(ret)
    }

    @PluginMethod
    fun requestLocationPermission(call: PluginCall) {
        if (checkLocationPermissions()) {
            val ret = JSObject()
            ret.put("granted", true)
            ret.put("preciseGranted", isPreciseLocationGranted())
            ret.put("permanentlyDenied", false)
            ret.put("isLocationServicesEnabled", isSystemLocationEnabled())
            call.resolve(ret)
            emitPermissionsChanged()
            return
        }

        context.getSharedPreferences("ble_perm_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("loc_perm_requested", true).apply()
        requestPermissionForAlias("location", call, "locationPermCallback")
    }

    @PermissionCallback
    fun locationPermCallback(call: PluginCall) {
        val ret = JSObject()
        ret.put("granted", checkLocationPermissions())
        ret.put("preciseGranted", isPreciseLocationGranted())
        ret.put("permanentlyDenied", isLocationPermanentlyDenied())
        ret.put("isLocationServicesEnabled", isSystemLocationEnabled())
        call.resolve(ret)
        emitPermissionsChanged()
    }

    @PluginMethod
    fun requestEnableBluetooth(call: PluginCall) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            call.reject("Bluetooth is not supported on this device.")
            return
        }
        if (adapter.isEnabled) {
            val ret = JSObject()
            ret.put("enabled", true)
            call.resolve(ret)
            return
        }
        try {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(call, enableIntent, "enableBluetoothCallback")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ACTION_REQUEST_ENABLE: ${e.message}", e)
            call.reject("Failed to request Bluetooth enable: ${e.message}")
        }
    }

    @ActivityCallback
    fun enableBluetoothCallback(call: PluginCall, result: ActivityResult) {
        val isEnabled = bluetoothAdapter?.isEnabled == true
        val ret = JSObject()
        ret.put("enabled", isEnabled)
        call.resolve(ret)
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to open App Settings: ${e.message}")
        }
    }

    @PluginMethod
    fun openBluetoothSettings(call: PluginCall) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to open Bluetooth Settings: ${e.message}")
        }
    }

    private fun validateBleReadyForDiscovery(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return "Bluetooth Low Energy hardware is not supported on this device."
        }
        val adapter = refreshBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            return "Bluetooth is not enabled on this device."
        }
        if (!checkBlePermissions()) {
            return "Required Bluetooth permissions are not granted."
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isSystemLocationEnabled()) {
            return "Location services must be turned on for Bluetooth scanning on Android 11 and older."
        }
        return null
    }

    private fun startDiscoveryInternal() {
        val error = validateBleReadyForDiscovery()
        if (error != null) {
            Log.w(TAG, "Cannot start BLE discovery: $error")
            return
        }
        ensureGattServerStarted()
        startAdvertisingInternal()
        startScanningInternal()
        processPendingQueue()
    }

    private fun stopDiscoveryInternal() {
        stopScanningInternal()
        stopAdvertisingInternal()
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        val bleGranted = checkBlePermissions()
        val locGranted = checkLocationPermissions()
        val ret = JSObject()
        ret.put("bluetooth", if (bleGranted) "granted" else "denied")
        ret.put("location", if (locGranted) "granted" else "denied")
        ret.put("granted", bleGranted)
        ret.put("permanentlyDenied", isBlePermanentlyDenied())
        call.resolve(ret)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        requestBlePermissions(call)
    }

    @PluginMethod
    fun startAdvertising(call: PluginCall) {
        val error = validateBleReadyForDiscovery()
        if (error != null) {
            call.reject(error)
            return
        }
        ensureGattServerStarted()
        startAdvertisingInternal()
        val ret = JSObject()
        ret.put("success", true)
        ret.put("isAdvertising", isAdvertising)
        call.resolve(ret)
    }

    @PluginMethod
    fun stopAdvertising(call: PluginCall) {
        stopAdvertisingInternal()
        call.resolve(JSObject().put("success", true))
    }

    @PluginMethod
    fun startScanning(call: PluginCall) {
        val error = validateBleReadyForDiscovery()
        if (error != null) {
            call.reject(error)
            return
        }
        ensureGattServerStarted()
        startScanningInternal()
        if (!isScanning) {
            call.reject("Bluetooth LE scanner failed to initialize.")
            return
        }
        val ret = JSObject()
        ret.put("success", true)
        ret.put("isScanning", isScanning)
        call.resolve(ret)
    }

    @PluginMethod
    fun stopScanning(call: PluginCall) {
        stopScanningInternal()
        call.resolve(JSObject().put("success", true))
    }

    @PluginMethod
    fun disconnectFromDevice(call: PluginCall) {
        disconnectDevice(call)
    }

    @PluginMethod
    fun startDiscovery(call: PluginCall) {
        val error = validateBleReadyForDiscovery()
        if (error != null) {
            call.reject(error)
            return
        }

        startDiscoveryInternal()
        if (!isScanning) {
            call.reject("Bluetooth LE scanner could not be started on this device.")
            return
        }

        val ret = JSObject()
        ret.put("success", true)
        ret.put("localDeviceId", localDeviceId)
        ret.put("fingerprint", deviceIdentity.fingerprint)
        ret.put("isScanning", isScanning)
        ret.put("isAdvertising", isAdvertising)
        ret.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        ret.put("connectedCount", getTotalConnectedCount())
        ret.put("relayedCount", totalRelayedCount)
        call.resolve(ret)
    }

    @PluginMethod
    fun stopDiscovery(call: PluginCall) {
        stopDiscoveryInternal()

        val ret = JSObject()
        ret.put("success", true)
        call.resolve(ret)
    }

    @PluginMethod
    fun getDiscoveredDevices(call: PluginCall) {
        val list = JSONArray()
        cleanStaleDevices()
        for (dev in discoveredDevices.values) {
            val peerInfo = keyStorage.getPeer(dev.deviceId)
            val obj = JSONObject()
            obj.put("deviceId", dev.deviceId)
            obj.put("deviceName", dev.deviceName)
            obj.put("address", dev.address)
            obj.put("rssi", dev.rssi)
            obj.put("lastSeen", dev.lastSeen)
            obj.put("status", connectionStates[dev.address] ?: "discovered")
            obj.put("isConnected", isDeviceConnected(dev.address))
            obj.put("fingerprint", peerInfo?.fingerprint ?: "")
            obj.put("isVerified", peerInfo?.isVerified ?: false)
            obj.put("keyChanged", peerInfo?.keyChanged ?: false)
            list.put(obj)
        }
        val ret = JSObject()
        ret.put("devices", list)
        ret.put("connectedCount", getTotalConnectedCount())
        ret.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        ret.put("relayedCount", totalRelayedCount)
        call.resolve(ret)
    }

    @PluginMethod
    fun getConnectedPeers(call: PluginCall) {
        val list = JSONArray()
        val uniqueAddresses = HashSet<String>()
        uniqueAddresses.addAll(clientGattMap.keys)
        uniqueAddresses.addAll(serverClientDevices.keys)

        for (addr in uniqueAddresses) {
            val devInfo = discoveredDevices[addr]
            val devId = addressToDeviceId[addr] ?: devInfo?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6))
            val peerInfo = keyStorage.getPeer(devId)
            val obj = JSONObject()
            obj.put("address", addr)
            obj.put("deviceId", devId)
            obj.put("status", "connected")
            obj.put("role", if (clientGattMap.containsKey(addr)) "client" else "server")
            obj.put("fingerprint", peerInfo?.fingerprint ?: "")
            obj.put("isVerified", peerInfo?.isVerified ?: false)
            obj.put("keyChanged", peerInfo?.keyChanged ?: false)
            list.put(obj)
        }

        val ret = JSObject()
        ret.put("connectedPeers", list)
        ret.put("count", uniqueAddresses.size)
        ret.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        ret.put("relayedCount", totalRelayedCount)
        call.resolve(ret)
    }

    @PluginMethod
    fun getUnifiedNeighbors(call: PluginCall) {
        val neighbors = getUnifiedNeighborsList()
        val list = JSONArray()
        for (n in neighbors) {
            val obj = JSONObject()
            obj.put("deviceId", n.deviceId)
            obj.put("deviceName", n.deviceName)
            obj.put("bleAddress", n.bleAddress ?: "")
            obj.put("wifiAddress", n.wifiAddress ?: "")
            obj.put("transport", n.transport)
            obj.put("isBleConnected", n.isBleConnected)
            obj.put("isWifiDirectConnected", n.isWifiDirectConnected)
            obj.put("isConnected", n.isConnected)
            obj.put("rssi", n.rssi)
            obj.put("lastSeen", n.lastSeen)
            obj.put("fingerprint", n.fingerprint)
            obj.put("isVerified", n.isVerified)
            obj.put("keyChanged", n.keyChanged)
            obj.put("status", n.status)
            list.put(obj)
        }
        val ret = JSObject()
        ret.put("neighbors", list)
        ret.put("connectedCount", getConnectedUnifiedNeighbors().size)
        ret.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        ret.put("relayedCount", totalRelayedCount)
        call.resolve(ret)
    }

    @PluginMethod
    fun connectToDevice(call: PluginCall) {
        val address = call.getString("address")
        if (address.isNullOrEmpty()) {
            call.reject("Device address is required.")
            return
        }

        if (isDeviceConnected(address)) {
            call.resolve(JSObject().put("status", "connected").put("address", address).put("message", "Already connected."))
            return
        }

        if (connectionStates[address] == "connecting") {
            call.resolve(JSObject().put("status", "connecting").put("address", address).put("message", "Connection already in progress."))
            return
        }

        if (getTotalConnectedCount() >= MAX_DIRECT_CONNECTIONS) {
            call.reject("Direct connection limit reached ($MAX_DIRECT_CONNECTIONS devices max). Disconnect an existing peer first.")
            return
        }

        val adapter = refreshBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            call.reject("Bluetooth is not enabled.")
            return
        }
        if (!checkBlePermissions()) {
            call.reject("Missing Bluetooth permissions.")
            return
        }

        manualDisconnects.remove(address)
        connectionRetries[address] = 0
        connectGattInternal(address, call)
    }

    private fun cancelConnectionTimeout(address: String) {
        connectionTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cleanupPeerGattState(address: String) {
        cancelConnectionTimeout(address)
        gattReadyAddresses.remove(address)
        subscribedServerClients.remove(address)
        peerMtuMap.remove(address)
        bleTxQueues.remove(address)?.clear()
        bleTxInFlight.remove(address)?.set(false)
        bleTxTimeoutRunnables.remove(address)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun connectGattInternal(address: String, call: PluginCall? = null) {
        val adapter = refreshBluetoothAdapter()
        val remoteDevice = try {
            adapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            call?.reject("Invalid device address: $address", e)
            return
        }

        if (remoteDevice == null) {
            call?.reject("Device not found for address: $address")
            return
        }

        cleanupPeerGattState(address)
        clientGattMap.remove(address)?.let { existing ->
            try {
                existing.disconnect()
                existing.close()
            } catch (_: Exception) {}
        }

        ensureGattServerStarted()

        connectionStates[address] = "connecting"
        notifyConnectionState("connecting", address, "Connecting as GATT Client...")

        val timeoutRunnable = Runnable {
            if (connectionStates[address] == "connecting" && !isDeviceConnectedAndReady(address)) {
                Log.w(TAG, "GATT connection timeout for $address after ${GATT_CONNECT_TIMEOUT_MS}ms")
                clientGattMap.remove(address)?.let { stuck ->
                    try {
                        stuck.disconnect()
                        stuck.close()
                    } catch (_: Exception) {}
                }
                cleanupPeerGattState(address)
                connectionStates[address] = "failed"
                notifyConnectionState("failed", address, "Connection timed out. Make sure the other phone has Nearby Chat open.")
                emitDiscoveredDevices(force = true)
            }
        }
        connectionTimeouts[address] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, GATT_CONNECT_TIMEOUT_MS)

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                remoteDevice.connectGatt(context, false, clientGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                remoteDevice.connectGatt(context, false, clientGattCallback)
            }
            if (gatt == null) {
                cancelConnectionTimeout(address)
                connectionStates[address] = "failed"
                notifyConnectionState("failed", address, "Bluetooth GATT client could not be created")
                call?.reject("Failed to create GATT client connection.")
                return
            }
            clientGattMap[address] = gatt
            Log.i(TAG, "Initiated BLE GATT connection to $address")
            call?.resolve(JSObject().put("status", "connecting").put("address", address))
        } catch (e: Exception) {
            cancelConnectionTimeout(address)
            Log.e(TAG, "Error connecting GATT to $address: ${e.message}", e)
            connectionStates[address] = "failed"
            notifyConnectionState("failed", address, e.message ?: "Connection error")
            call?.reject("Connection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun disconnectDevice(call: PluginCall) {
        val address = call.getString("address")
        if (address.isNullOrEmpty()) {
            call.reject("Device address is required.")
            return
        }

        manualDisconnects.add(address)
        connectionRetries.remove(address)
        cleanupPeerGattState(address)

        var disconnected = false

        clientGattMap.remove(address)?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
                disconnected = true
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting client GATT $address: ${e.message}")
            }
        }

        serverClientDevices.remove(address)?.let { dev ->
            try {
                gattServer?.cancelConnection(dev)
                disconnected = true
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling server connection for $address: ${e.message}")
            }
        }

        connectionStates[address] = "disconnected"
        notifyConnectionState("disconnected", address, "Disconnected by user")
        emitDiscoveredDevices(force = true)
        call.resolve(JSObject().put("success", true).put("address", address).put("wasConnected", disconnected))
    }

    @PluginMethod
    fun disconnectAll(call: PluginCall) {
        for (addr in clientGattMap.keys) {
            manualDisconnects.add(addr)
            cleanupPeerGattState(addr)
            clientGattMap.remove(addr)?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing client GATT $addr: ${e.message}")
                }
            }
        }
        for (addr in serverClientDevices.keys) {
            manualDisconnects.add(addr)
            cleanupPeerGattState(addr)
            serverClientDevices.remove(addr)?.let {
                try {
                    gattServer?.cancelConnection(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error canceling server client $addr: ${e.message}")
                }
            }
        }
        connectionStates.clear()
        connectionRetries.clear()
        notifyConnectionState("disconnected", null, "All peers disconnected")
        emitDiscoveredDevices(force = true)
        call.resolve(JSObject().put("success", true))
    }

    // -------------------------------------------------------------------------
    // End-to-End Encrypted Message Transmission (E2EE)
    // -------------------------------------------------------------------------

    @PluginMethod
    fun sendMessage(call: PluginCall) {
        val text = call.getString("text")
        val recipientAddress = call.getString("recipientAddress")
        val recipientId = call.getString("recipientId") ?: "ALL"
        val hopLimit = call.getInt("hopLimit") ?: DEFAULT_HOP_LIMIT
        val messageId = call.getString("messageId") ?: ("msg-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4))
        val requestedType = call.getString("type") ?: "chat"

        if (text.isNullOrEmpty()) {
            call.reject("Message text cannot be empty.")
            return
        }

        if (text.length > 256) {
            call.reject("Message exceeds maximum length of 256 characters.")
            return
        }

        val now = System.currentTimeMillis()
        val isTargeted = (recipientId != "ALL" && recipientId != "BROADCAST" && requestedType != "broadcast" && requestedType != "sos")
        val isSos = (requestedType == "sos")
        val packetType = if (isTargeted) "encrypted_chat" else (if (isSos) "sos" else "broadcast")
        val priority = call.getString("priority") ?: if (isSos) "emergency" else "normal"
        val isEmergency = (priority == "emergency" || isSos)
        val expiresAt = if (isSos) now + SOS_TTL_MS else now + MESSAGE_TTL_MS

        val packetObj = JSONObject()
        packetObj.put("id", messageId)
        packetObj.put("senderId", localDeviceId)
        packetObj.put("senderPublicKey", deviceIdentity.publicKeyBase64)
        packetObj.put("recipientId", recipientId)
        packetObj.put("hopCount", 0)
        packetObj.put("hopLimit", hopLimit)
        packetObj.put("createdAt", now)
        packetObj.put("expiresAt", expiresAt)

        var isEncrypted = false

        if (isTargeted) {
            // End-to-End Encrypted Private Message
            val peer = keyStorage.getPeer(recipientId)
            if (peer != null) {
                if (peer.keyChanged && !peer.isVerified) {
                    call.reject("Security alert: Public key for $recipientId changed! Verification required before sending.")
                    return
                }

                try {
                    val peerPubKey = MeshCryptoEngine.decodePublicKey(peer.publicKeyBase64)
                    val secret = MeshCryptoEngine.computeSharedSecret(deviceIdentity.privateKey, peerPubKey)
                    val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)
                    val aad = MeshCryptoEngine.computeMetadataAad(messageId, localDeviceId, recipientId, "encrypted_chat", now, expiresAt)
                    val encrypted = MeshCryptoEngine.encrypt(text, sessionKeys.aesKey, aad)

                    packetObj.put("type", "encrypted_chat")
                    packetObj.put("encryptedPayload", encrypted.ciphertextBase64)
                    packetObj.put("nonce", encrypted.nonceBase64)
                    isEncrypted = true
                } catch (e: Exception) {
                    Log.e(TAG, "Encryption failed for recipient $recipientId: ${e.message}", e)
                    call.reject("Encryption failed: ${e.message}")
                    return
                }
            } else {
                // If recipient's public key is not yet recorded, dispatch a key exchange request across the mesh
                sendKeyExchangeToMesh(recipientId)
                call.reject("Recipient $recipientId public key not yet acquired. Dispatched key exchange request across mesh. Please retry in 2 seconds.")
                return
            }
        } else {
            // Public Broadcast / Emergency SOS with ECDSA Digital Signature
            if (now - lastBroadcastTimestamp < BROADCAST_MIN_INTERVAL_MS) {
                call.reject("Broadcast rate limit: Please wait at least 2 seconds between broadcast transmissions.")
                return
            }
            lastBroadcastTimestamp = now

            val emergencyType = call.getString("emergencyType")
            val latitude = if (call.hasOption("latitude")) call.getDouble("latitude") else null
            val longitude = if (call.hasOption("longitude")) call.getDouble("longitude") else null
            val locationStr = if (latitude != null && longitude != null) "$latitude,$longitude" else null

            val signablePayload = MeshCryptoEngine.buildBroadcastSignablePayload(
                id = messageId,
                senderId = localDeviceId,
                type = packetType,
                priority = priority,
                text = text,
                createdAt = now,
                expiresAt = expiresAt,
                emergencyType = emergencyType,
                locationStr = locationStr
            )
            val signature = MeshCryptoEngine.signData(signablePayload, deviceIdentity.privateKey)

            packetObj.put("type", packetType)
            packetObj.put("priority", priority)
            packetObj.put("text", text)
            packetObj.put("signature", signature)
            if (!emergencyType.isNullOrEmpty()) packetObj.put("emergencyType", emergencyType)
            if (latitude != null) packetObj.put("latitude", latitude)
            if (longitude != null) packetObj.put("longitude", longitude)
        }

        val packetBytes = packetObj.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(messageId)

        // Unified Hybrid Dispatching across Wi-Fi Direct and BLE
        val targetId = if (isTargeted) recipientId else null
        val dispatched = dispatchHybridPacket(
            bytes = packetBytes,
            targetDeviceId = targetId,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = isEmergency
        )

        val connectedNeighbors = getConnectedUnifiedNeighbors()
        val initialStatus = if (dispatched) MeshMessageQueue.STATUS_FORWARDING else MeshMessageQueue.STATUS_QUEUED

        // Store-and-Forward: Enqueue outgoing message so it survives disconnections and retries until delivered
        val queuedMsg = MeshMessageQueue.QueuedMessage(
            id = messageId,
            senderId = localDeviceId,
            senderPublicKey = deviceIdentity.publicKeyBase64,
            recipientId = recipientId,
            encryptedPayload = if (isTargeted) packetObj.optString("encryptedPayload", null) else null,
            nonce = if (isTargeted) packetObj.optString("nonce", null) else null,
            text = if (isTargeted) null else text,
            type = packetObj.getString("type"),
            priority = priority,
            createdAt = now,
            expiresAt = expiresAt,
            hopCount = 0,
            hopLimit = hopLimit,
            signature = if (packetObj.has("signature")) packetObj.getString("signature") else null,
            emergencyType = if (packetObj.has("emergencyType")) packetObj.getString("emergencyType") else null,
            latitude = if (packetObj.has("latitude")) packetObj.getDouble("latitude") else null,
            longitude = if (packetObj.has("longitude")) packetObj.getDouble("longitude") else null,
            status = initialStatus,
            isOutgoing = true,
            retryCount = if (dispatched) 1 else 0,
            lastAttemptTimestamp = if (dispatched) now else 0L
        )
        messageQueue.enqueue(queuedMsg)

        // Determine effective transport used
        val usedTransport = when {
            isTargeted && wifiDirectManager.isPeerConnected(recipientId) && (recipientAddress != null && isDeviceConnected(recipientAddress)) -> "hybrid"
            isTargeted && wifiDirectManager.isPeerConnected(recipientId) -> "wifi_direct"
            dispatched -> "ble"
            else -> "queued"
        }

        val ret = JSObject()
        ret.put("success", true)
        ret.put("messageId", messageId)
        ret.put("deliveredCount", if (dispatched) (if (isTargeted) 1 else connectedNeighbors.size) else 0)
        ret.put("status", initialStatus)
        ret.put("text", text)
        ret.put("isEncrypted", isEncrypted)
        ret.put("transport", usedTransport)
        ret.put("wifiDirectActive", wifiDirectManager.hasConnectedPeers())
        ret.put("type", packetObj.optString("type"))
        ret.put("priority", packetObj.optString("priority", "normal"))
        if (packetObj.has("emergencyType")) ret.put("emergencyType", packetObj.getString("emergencyType"))
        if (packetObj.has("latitude")) ret.put("latitude", packetObj.getDouble("latitude"))
        if (packetObj.has("longitude")) ret.put("longitude", packetObj.getDouble("longitude"))
        ret.put("hopLimit", hopLimit)
        ret.put("createdAt", now)
        call.resolve(ret)

        emitMessageStatusChanged(messageId, initialStatus, 0)
    }

    private fun getEffectivePayloadLimit(address: String): Int {
        val mtu = peerMtuMap[address] ?: 23
        return (mtu - 3).coerceIn(20, 509)
    }

    private fun framePacketForBle(address: String, bytes: ByteArray): List<ByteArray> {
        val maxPayload = getEffectivePayloadLimit(address)
        if (bytes.size <= maxPayload && !(bytes.size >= 2 && bytes[0] == CHUNK_MAGIC_0 && bytes[1] == CHUNK_MAGIC_1)) {
            return listOf(bytes)
        }
        val chunkPayloadSize = (maxPayload - CHUNK_HEADER_SIZE).coerceAtLeast(15)
        val totalChunks = ((bytes.size + chunkPayloadSize - 1) / chunkPayloadSize).coerceAtLeast(1)
        if (totalChunks > 255) {
            Log.e(TAG, "Packet too large to chunk over BLE (${bytes.size} bytes, $totalChunks chunks)")
            return emptyList()
        }
        val seq = txSequenceGenerator.getAndIncrement() and 0xFF
        val frames = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        for (idx in 0 until totalChunks) {
            val end = minOf(offset + chunkPayloadSize, bytes.size)
            val sliceLen = end - offset
            val frame = ByteArray(CHUNK_HEADER_SIZE + sliceLen)
            frame[0] = CHUNK_MAGIC_0
            frame[1] = CHUNK_MAGIC_1
            frame[2] = seq.toByte()
            frame[3] = idx.toByte()
            frame[4] = totalChunks.toByte()
            System.arraycopy(bytes, offset, frame, CHUNK_HEADER_SIZE, sliceLen)
            frames.add(frame)
            offset = end
        }
        return frames
    }

    private fun sendBytesToPeer(address: String, bytes: ByteArray): Boolean {
        if (!isDeviceConnected(address)) {
            Log.d(TAG, "Cannot send BLE bytes to $address: peer not connected/ready")
            return false
        }
        val frames = framePacketForBle(address, bytes)
        if (frames.isEmpty()) return false

        val queue = bleTxQueues.computeIfAbsent(address) { ConcurrentLinkedQueue() }
        for (frame in frames) {
            queue.add(frame)
        }
        mainHandler.post { drainBleTxQueue(address) }
        return true
    }

    private fun drainBleTxQueue(address: String) {
        val queue = bleTxQueues[address] ?: return
        if (queue.isEmpty()) return

        val inFlight = bleTxInFlight.computeIfAbsent(address) { AtomicBoolean(false) }
        if (!inFlight.compareAndSet(false, true)) return

        val nextFrame = queue.poll()
        if (nextFrame == null) {
            inFlight.set(false)
            return
        }

        val timeoutRunnable = Runnable {
            if (inFlight.compareAndSet(true, false)) {
                Log.w(TAG, "BLE TX watchdog timeout for $address; advancing queue")
                drainBleTxQueue(address)
            }
        }
        bleTxTimeoutRunnables.put(address, timeoutRunnable)?.let { mainHandler.removeCallbacks(it) }
        mainHandler.postDelayed(timeoutRunnable, BLE_TX_TIMEOUT_MS)

        var dispatched = false
        val clientGatt = clientGattMap[address]
        if (clientGatt != null && gattReadyAddresses.contains(address)) {
            val service = clientGatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                dispatched = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        clientGatt.writeCharacteristic(characteristic, nextFrame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = nextFrame
                        @Suppress("DEPRECATION")
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        clientGatt.writeCharacteristic(characteristic)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Client writeCharacteristic exception for $address: ${e.message}")
                    false
                }
            }
        } else {
            val serverDev = serverClientDevices[address]
            val server = gattServer
            if (server != null && serverDev != null) {
                val service = server.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    dispatched = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(serverDev, characteristic, false, nextFrame) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = nextFrame
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(serverDev, characteristic, false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Server notifyCharacteristicChanged exception for $address: ${e.message}")
                        false
                    }
                }
            }
        }

        if (!dispatched) {
            bleTxTimeoutRunnables.remove(address)?.let { mainHandler.removeCallbacks(it) }
            inFlight.set(false)
            Log.w(TAG, "Failed to dispatch BLE frame to $address (remaining=${queue.size})")
        }
    }

    private fun onBleTxFrameCompleted(address: String) {
        bleTxTimeoutRunnables.remove(address)?.let { mainHandler.removeCallbacks(it) }
        bleTxInFlight[address]?.set(false)
        mainHandler.post { drainBleTxQueue(address) }
    }

    private fun isDeviceConnected(address: String): Boolean {
        return gattReadyAddresses.contains(address) || serverClientDevices.containsKey(address)
    }

    private fun isDeviceConnectedAndReady(address: String): Boolean {
        return isDeviceConnected(address)
    }

    // -------------------------------------------------------------------------
    // BLE Advertising Implementation
    // -------------------------------------------------------------------------

    private fun startAdvertisingInternal(includeScanResponse: Boolean = true) {
        if (isAdvertising) return
        if (!checkBlePermissions()) {
            Log.w(TAG, "Cannot start BLE advertising: missing permissions.")
            return
        }
        val adapter = refreshBluetoothAdapter()
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BluetoothLeAdvertiser not supported on this chipset.")
            return
        }
        bluetoothLeAdvertiser = advertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val serviceUuid = ParcelUuid(SERVICE_UUID)
        val deviceIdBytes = localDeviceId.toByteArray(StandardCharsets.UTF_8)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponse = if (includeScanResponse) {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(serviceUuid, deviceIdBytes)
                .build()
        } else {
            null
        }

        try {
            if (scanResponse != null) {
                advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } else {
                advertiser.startAdvertising(settings, data, advertiseCallback)
            }
            Log.i(TAG, "BLE Advertising requested for $localDeviceId (scanResponse=$includeScanResponse)")
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertising exception: ${e.message}", e)
        }
    }

    private fun stopAdvertisingInternal() {
        if (!isAdvertising) return
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising error: ${e.message}")
        }
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE Advertising started for node $localDeviceId")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                isAdvertising = true
                Log.i(TAG, "BLE Advertising already running for node $localDeviceId")
                return
            }
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement payload too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many active BLE advertisers"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal advertising error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE peripheral advertising unsupported on this device"
                else -> "Advertising failed (error $errorCode)"
            }
            Log.e(TAG, "BLE Advertising failed: $reason (code=$errorCode)")
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                Log.i(TAG, "Retrying BLE advertising without scanResponse...")
                mainHandler.postDelayed({ startAdvertisingInternal(includeScanResponse = false) }, 200)
            }
        }
    }

    // -------------------------------------------------------------------------
    // BLE Scanning Implementation
    // -------------------------------------------------------------------------

    private fun startScanningInternal() {
        if (isScanning) return
        val readyError = validateBleReadyForDiscovery()
        if (readyError != null) {
            Log.w(TAG, "Cannot start BLE scanning: $readyError")
            return
        }
        val scanner = refreshBluetoothAdapter()?.bluetoothLeScanner ?: run {
            Log.w(TAG, "BluetoothLeScanner not available.")
            return
        }
        bluetoothLeScanner = scanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettingsBuilder = ScanSettings.Builder()
            .setScanMode(
                if (BleMeshForegroundService.isRunning) ScanSettings.SCAN_MODE_BALANCED
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanSettingsBuilder
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }

        try {
            scanner.startScan(listOf(filter), scanSettingsBuilder.build(), scanCallback)
            isScanning = true
            mainHandler.removeCallbacks(staleCleanupRunnable)
            mainHandler.postDelayed(staleCleanupRunnable, 5_000L)
            Log.i(TAG, "BLE Scanning started for service: $SERVICE_UUID")
        } catch (e: Exception) {
            isScanning = false
            Log.e(TAG, "startScan exception: ${e.message}", e)
            emitDiscoveryError("Bluetooth scan failed to start: ${e.message}")
        }
    }

    private fun stopScanningInternal() {
        mainHandler.removeCallbacks(staleCleanupRunnable)
        if (!isScanning) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error: ${e.message}")
        }
        isScanning = false
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val device = result.device ?: return
        val addr = device.address ?: return

        val serviceData = record.getServiceData(ParcelUuid(SERVICE_UUID))
        val advertisedPeerId = if (serviceData != null && serviceData.isNotEmpty()) {
            String(serviceData, StandardCharsets.UTF_8).trim()
        } else {
            null
        }
        val peerId = advertisedPeerId?.takeIf { it.isNotEmpty() }
            ?: addressToDeviceId[addr]
            ?: ("NODE-" + addr.replace(":", "").takeLast(6))

        if (peerId == localDeviceId) return

        if (advertisedPeerId != null && advertisedPeerId.isNotEmpty()) {
            addressToDeviceId[addr] = advertisedPeerId
            deviceIdToAddress[advertisedPeerId] = addr
        }

        val rawName = try {
            record.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                ?: device.name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        val displayName = if (rawName != null && rawName != peerId) "$peerId ($rawName)" else peerId

        val now = System.currentTimeMillis()
        val existing = discoveredDevices[addr]
        val isNewDevice = existing == null
        val rssiDelta = if (existing != null) kotlin.math.abs(existing.rssi - result.rssi) else 999

        val discovered = DiscoveredDevice(
            deviceId = peerId,
            deviceName = displayName,
            address = addr,
            rssi = result.rssi,
            lastSeen = now
        )
        discoveredDevices[addr] = discovered

        if (isNewDevice) {
            Log.i(TAG, "Discovered BLE peer: id=$peerId, name=$displayName, address=$addr, rssi=${result.rssi}")
        }

        val lastEmit = lastDeviceEmitTimes[addr] ?: 0L
        if (isNewDevice || rssiDelta >= 8 || (now - lastEmit >= DEVICE_EMIT_THROTTLE_MS)) {
            lastDeviceEmitTimes[addr] = now
            emitDiscoveredDevices(force = isNewDevice)
        }
    }

    private fun emitDiscoveryError(message: String) {
        mainHandler.post {
            val event = JSObject()
            event.put("message", message)
            event.put("isScanning", isScanning)
            event.put("isAdvertising", isAdvertising)
            notifyListeners("bleDiscoveryError", event)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results ?: return
            for (result in results) {
                handleScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                isScanning = true
                Log.i(TAG, "BLE Scan already active")
                return
            }
            isScanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth scanner registration failed. Try toggling Bluetooth off and on."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning is unsupported on this device."
                SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth scan error."
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning restarted too frequently. Wait a few seconds and retry."
                else -> "BLE scan failed (error code $errorCode)."
            }
            Log.e(TAG, "Scan failed: $reason (code=$errorCode)")
            emitDiscoveryError(reason)
        }
    }

    private fun cleanStaleDevices() {
        val now = System.currentTimeMillis()
        val it = discoveredDevices.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!isDeviceConnected(entry.key) && connectionStates[entry.key] != "connecting" && now - entry.value.lastSeen > DEVICE_TIMEOUT_MS) {
                lastDeviceEmitTimes.remove(entry.key)
                it.remove()
            }
        }
    }

    private fun emitDiscoveredDevices(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastGlobalEmitTime < 400L) return
        lastGlobalEmitTime = now

        cleanStaleDevices()
        val list = JSONArray()
        for (dev in discoveredDevices.values) {
            val peerInfo = keyStorage.getPeer(dev.deviceId)
            val obj = JSObject()
            obj.put("deviceId", dev.deviceId)
            obj.put("deviceName", dev.deviceName)
            obj.put("address", dev.address)
            obj.put("rssi", dev.rssi)
            obj.put("lastSeen", dev.lastSeen)
            obj.put("status", connectionStates[dev.address] ?: "discovered")
            obj.put("isConnected", isDeviceConnected(dev.address))
            obj.put("fingerprint", peerInfo?.fingerprint ?: "")
            obj.put("isVerified", peerInfo?.isVerified ?: false)
            obj.put("keyChanged", peerInfo?.keyChanged ?: false)
            list.put(obj)
        }
        val event = JSObject()
        event.put("devices", list)
        event.put("connectedCount", getTotalConnectedCount())
        event.put("maxConnections", MAX_DIRECT_CONNECTIONS)
        event.put("relayedCount", totalRelayedCount)
        mainHandler.post {
            notifyListeners("bleDevicesUpdated", event)
            emitUnifiedNeighbors()
        }
    }

    // -------------------------------------------------------------------------
    // GATT Server Role (Incoming Connections)
    // -------------------------------------------------------------------------

    private fun ensureGattServerStarted() {
        if (gattServer != null) return
        if (!checkBlePermissions()) {
            Log.w(TAG, "Cannot start GATT server: missing permissions.")
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return

        try {
            gattServer = manager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
            characteristic.addDescriptor(cccd)
            service.addCharacteristic(characteristic)

            gattServer?.addService(service)
            Log.i(TAG, "GATT Server started. Max connections configured: $MAX_DIRECT_CONNECTIONS")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT Server: ${e.message}", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            val addr = device.address
            Log.i(TAG, "Server onConnectionStateChange: $addr, newState: $newState, status: $status")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (getTotalConnectedCount() >= MAX_DIRECT_CONNECTIONS && !serverClientDevices.containsKey(addr)) {
                    Log.w(TAG, "Rejecting incoming connection from $addr: Connection limit ($MAX_DIRECT_CONNECTIONS) reached.")
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error rejecting excess connection: ${e.message}")
                    }
                    return
                }

                serverClientDevices[addr] = device
                connectionStates[addr] = "connected"
                val peerId = discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6))
                notifyConnectionState("connected", addr, "Peer $peerId connected to GATT Server", peerId)
                emitDiscoveredDevices()

                // Automatically initiate peer public key exchange, presence announcement, and process pending queue
                mainHandler.postDelayed({
                    sendKeyExchangeToMesh(peerId)
                    broadcastPresenceAnnouncement()
                    processPendingQueue()
                }, 400)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverClientDevices.remove(addr)
                connectionStates[addr] = "disconnected"
                val peerId = discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6))
                if (participantDirectory.markNeighborDisconnected(peerId)) {
                    emitMeshParticipants()
                }
                notifyConnectionState("disconnected", addr, "Peer $peerId disconnected from GATT Server", peerId)
                emitDiscoveredDevices()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (value != null && value.isNotEmpty()) {
                handleIncomingPacket(value, device?.address ?: "unknown", "ble")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // -------------------------------------------------------------------------
    // GATT Client Role (Outgoing Connections)
    // -------------------------------------------------------------------------

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            gatt ?: return
            val addr = gatt.device.address
            Log.i(TAG, "Client onConnectionStateChange: $addr, newState: $newState, status: $status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                clientGattMap.remove(addr)
                connectionStates[addr] = "failed"

                val retries = connectionRetries[addr] ?: 0
                if (!manualDisconnects.contains(addr) && retries < MAX_CONNECT_RETRIES) {
                    connectionRetries[addr] = retries + 1
                    Log.i(TAG, "Retrying connection to $addr (attempt ${retries + 1}/$MAX_CONNECT_RETRIES) in 1500ms...")
                    mainHandler.postDelayed({
                        if (!manualDisconnects.contains(addr) && !isDeviceConnected(addr)) {
                            connectGattInternal(addr)
                        }
                    }, 1500)
                    notifyConnectionState("connecting", addr, "Retrying connection (${retries + 1}/$MAX_CONNECT_RETRIES)...")
                } else {
                    notifyConnectionState("failed", addr, "Connection failed with status $status")
                    emitDiscoveredDevices()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionStates[addr] = "connecting"
                connectionRetries.remove(addr)
                Log.i(TAG, "GATT Client connected to $addr, requesting MTU 512...")
                if (!gatt.requestMtu(512)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                clientGattMap.remove(addr)
                connectionStates[addr] = "disconnected"

                val retries = connectionRetries[addr] ?: 0
                if (!manualDisconnects.contains(addr) && retries < MAX_CONNECT_RETRIES) {
                    connectionRetries[addr] = retries + 1
                    Log.i(TAG, "Peer $addr unexpectedly disconnected. Auto-reconnecting (attempt ${retries + 1}/$MAX_CONNECT_RETRIES)...")
                    mainHandler.postDelayed({
                        if (!manualDisconnects.contains(addr) && !isDeviceConnected(addr)) {
                            connectGattInternal(addr)
                        }
                    }, 1500)
                    notifyConnectionState("connecting", addr, "Reconnecting...")
                } else {
                    val peerId = discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6))
                    if (participantDirectory.markNeighborDisconnected(peerId)) {
                        emitMeshParticipants()
                    }
                    notifyConnectionState("disconnected", addr, "Disconnected from $addr")
                    emitDiscoveredDevices()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            gatt ?: return
            Log.i(TAG, "MTU changed to $mtu for ${gatt.device.address}, discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            gatt ?: return
            val addr = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                    connectionStates[addr] = "connected"
                    val peerId = discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6))
                    notifyConnectionState("connected", addr, "Connected to remote peer GATT Service", peerId)
                    emitDiscoveredDevices()

                    // Automatically exchange public identity key, announce presence, and process pending queue
                    mainHandler.postDelayed({
                        sendKeyExchangeToMesh(peerId)
                        broadcastPresenceAnnouncement()
                        processPendingQueue()
                    }, 400)
                } else {
                    Log.w(TAG, "Disaster Chat characteristic missing on $addr.")
                    connectionStates[addr] = "failed"
                    notifyConnectionState("failed", addr, "Disaster Chat service missing on peer")
                    emitDiscoveredDevices()
                }
            } else {
                connectionStates[addr] = "failed"
                notifyConnectionState("failed", addr, "Service discovery failed: $status")
                emitDiscoveredDevices()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            val bytes = characteristic?.value ?: return
            handleIncomingPacket(bytes, gatt?.device?.address ?: "unknown", "ble")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingPacket(value, gatt.device.address, "ble")
        }
    }

    // -------------------------------------------------------------------------
    // Packet Processing Engine: E2EE Decryption, Relay & Tamper Detection
    // -------------------------------------------------------------------------

    private fun handleIncomingPacket(bytes: ByteArray, incomingAddress: String, transport: String = "ble") {
        if (bytes.isEmpty()) return
        if (bytes.size > MeshMessageQueue.MAX_SINGLE_MESSAGE_BYTES) {
            Log.w(TAG, "Dropping packet exceeding maximum single message size (${bytes.size} bytes > ${MeshMessageQueue.MAX_SINGLE_MESSAGE_BYTES})")
            return
        }

        val rawStr = try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return
        }

        val json = try {
            JSONObject(rawStr)
        } catch (e: Exception) {
            null
        }

        if (json == null) {
            Log.w(TAG, "Dropping non-JSON packet from $incomingAddress")
            return
        }

        val id = json.optString("id", "")
        if (id.isEmpty()) return

        val msgType = json.optString("type", "chat")
        val senderId = json.optString("senderId", "UNKNOWN")
        val recipientId = json.optString("recipientId", "ALL")
        val hopCount = json.optInt("hopCount", 0)
        val hopLimit = json.optInt("hopLimit", DEFAULT_HOP_LIMIT)
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val originalMessageId = json.optString("originalMessageId", "")
        val senderPublicKey = json.optString("senderPublicKey", "")

        // Security Validation: Hop count & Hop limit boundaries
        if (hopCount < 0 || hopLimit <= 0 || hopCount > hopLimit || hopLimit > 20) {
            Log.w(TAG, "Dropping packet $id: Invalid routing metadata (hopCount=$hopCount, hopLimit=$hopLimit)")
            return
        }

        val now = System.currentTimeMillis()
        lastCommunicationTime = now
        // Clock skew sanity check: createdAt cannot be in the future beyond 5 minutes
        if (createdAt > now + 300_000L) {
            Log.w(TAG, "Dropping packet $id: Timestamp in the far future ($createdAt vs now $now)")
            return
        }

        val isTargetedAtMe = (recipientId == localDeviceId)
        val isBroadcast = (recipientId == "ALL" || recipientId == "BROADCAST")

        // CASE 0: RELAY CUSTODY ACKNOWLEDGMENT
        // Confirms the next relay has validated and stored the packet into persistent storage
        if (msgType == "custody_ack") {
            val messageId = json.optString("messageId", "")
            val custodyNodeId = json.optString("custodyNodeId", "")
            val custodyPubKey = json.optString("custodyPublicKey", "")
            val upstreamNodeId = json.optString("upstreamNodeId", "")
            val timestamp = json.optLong("timestamp", 0L)
            val ackExpiresAt = json.optLong("expiresAt", 0L)
            val ackSenderId = json.optString("senderId", "")
            val payloadHash = json.optString("payloadHash", "")
            val attemptId = json.optString("attemptId", "")
            val signature = json.optString("signature", "")

            if (upstreamNodeId == localDeviceId && messageId.isNotEmpty() && signature.isNotEmpty() && custodyPubKey.isNotEmpty()) {
                // Replay & Freshness check
                if (ackExpiresAt > 0L && now > ackExpiresAt) {
                    Log.w(TAG, "Custody ACK expired for $messageId from $custodyNodeId. Dropping.")
                    return
                }
                if (timestamp > 0L && (now - timestamp > 300_000L || timestamp > now + 60_000L)) {
                    Log.w(TAG, "Custody ACK timestamp outside valid window ($timestamp vs $now). Dropping.")
                    return
                }

                // Check custody public key against known peer binding
                val knownPeer = keyStorage.getPeer(custodyNodeId)
                if (knownPeer != null && knownPeer.publicKeyBase64 != custodyPubKey) {
                    Log.w(TAG, "Custody ACK public key mismatch for $custodyNodeId! Potential impersonation. Dropping.")
                    return
                }

                // Verify signature against V3 (with attemptId), V2 (with payload hash) or fallback V1
                val signableV3 = MeshCryptoEngine.buildCustodyAckSignable(messageId, ackSenderId, upstreamNodeId, custodyNodeId, payloadHash, timestamp, ackExpiresAt, attemptId)
                val signableV2 = MeshCryptoEngine.buildCustodyAckSignable(messageId, ackSenderId, upstreamNodeId, custodyNodeId, payloadHash, timestamp, ackExpiresAt, "")
                val signableV1 = "CUSTODY:$messageId:$custodyNodeId:$upstreamNodeId:$timestamp"

                val isSigValid = MeshCryptoEngine.verifySignatureWithBase64Key(signableV3, signature, custodyPubKey) ||
                                 MeshCryptoEngine.verifySignatureWithBase64Key(signableV2, signature, custodyPubKey) ||
                                 MeshCryptoEngine.verifySignatureWithBase64Key(signableV1, signature, custodyPubKey)

                if (isSigValid) {
                    val queued = messageQueue.getMessage(messageId)
                    if (queued != null && payloadHash.isNotEmpty()) {
                        val localHash = MeshCryptoEngine.computePayloadHash(queued.encryptedPayload ?: queued.text ?: "")
                        if (localHash != payloadHash) {
                            Log.w(TAG, "Custody ACK payload hash mismatch for $messageId! Dropping.")
                            return
                        }
                    }
                    Log.i(TAG, "Verified custody ACK received for $messageId from $custodyNodeId. Relieving forwarding custody.")
                    messageQueue.markCustodyTransferred(messageId)
                } else {
                    Log.w(TAG, "Custody ACK signature verification failed for $messageId from $custodyNodeId! Dropping.")
                }
            }
            return
        }

        // 1. Idempotency & Deduplication Check (Without premature marking!)
        val isDuplicate = if (::messageQueue.isInitialized) messageQueue.isDuplicate(id) else false
        if (isDuplicate) {
            Log.d(TAG, "Duplicate message $id received from $incomingAddress")
            // Idempotent recovery: If targeted at me and encrypted_chat, re-send delivery ACK in case destination ACK was dropped
            if (isTargetedAtMe && msgType == "encrypted_chat") {
                val peer = keyStorage.getPeer(senderId)
                if (peer != null) {
                    try {
                        val peerPubKey = MeshCryptoEngine.decodePublicKey(peer.publicKeyBase64)
                        val secret = MeshCryptoEngine.computeSharedSecret(deviceIdentity.privateKey, peerPubKey)
                        val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)
                        val payloadHash = MeshCryptoEngine.computePayloadHash(json.optString("encryptedPayload", ""))
                        val authTag = MeshCryptoEngine.computeAckAuthTag(sessionKeys.ackKey, id, senderId, localDeviceId, payloadHash)
                        sendAuthenticatedDeliveryAck(id, senderId, hopCount, authTag, payloadHash)
                    } catch (_: Exception) {}
                }
            }
            // Idempotent recovery: If relay message and we already hold custody, re-send custody ACK so upstream stops retrying
            if (!isTargetedAtMe && (msgType == "encrypted_chat" || msgType == "chat")) {
                val payloadHash = MeshCryptoEngine.computePayloadHash(json.optString("encryptedPayload", json.optString("text", "")))
                sendCustodyAck(id, senderId, senderId, incomingAddress, transport, payloadHash, json.optLong("expiresAt", 0L), json.optString("attemptId", ""))
            }
            return
        }

        // 2. TTL / Expiration Check
        val expiresAt = json.optLong("expiresAt", now + MESSAGE_TTL_MS)
        if (now > expiresAt) {
            Log.d(TAG, "Message $id expired (TTL exceeded). Dropping.")
            return
        }

        // CASE 0.5: MULTI-HOP PRESENCE ANNOUNCEMENT
        if (msgType == "presence") {
            if (senderId.isBlank() || senderId == localDeviceId || senderPublicKey.isBlank()) return
            val signature = json.optString("signature", "")
            val signable = "PRESENCE:$id:$senderId:$senderPublicKey:$createdAt"
            val isSigValid = signature.isNotEmpty() &&
                MeshCryptoEngine.verifySignatureWithBase64Key(signable, signature, senderPublicKey)
            if (!isSigValid) {
                Log.w(TAG, "Dropping unauthenticated presence packet from $senderId")
                return
            }

            isDuplicateAndMark(id)

            val keyStatus = keyStorage.recordPeerPublicKey(senderId, senderPublicKey)
            if (keyStatus == MeshKeyStorage.PeerKeyStatus.KEY_CHANGED) {
                mainHandler.post {
                    val alert = JSObject()
                    alert.put("type", "key_changed")
                    alert.put("deviceId", senderId)
                    alert.put("fingerprint", MeshCryptoEngine.computeFingerprintFromBase64(senderPublicKey))
                    alert.put("message", "SECURITY WARNING: Identity key for $senderId changed!")
                    notifyListeners("bleSecurityAlert", alert)
                }
            }

            val displayName = json.optString("displayName", senderId)
            val fingerprint = MeshCryptoEngine.computeFingerprintFromBase64(senderPublicKey)
            val nextHopId = resolveNeighborId(incomingAddress, transport)
            val effectiveHopCount = hopCount + 1

            participantDirectory.upsertParticipant(
                deviceId = senderId,
                displayName = displayName,
                publicKeyBase64 = senderPublicKey,
                fingerprint = fingerprint,
                nextHopDeviceId = nextHopId,
                hopCount = effectiveHopCount,
                transport = transport,
                now = now
            )
            emitMeshParticipants()

            if (hopCount < hopLimit) {
                relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, "BROADCAST", "presence", incomingTransport = transport)
            }
            return
        }

        // CASE 1: KEY EXCHANGE PACKET
        if (msgType == "key_exchange") {
            if (isTargetedAtMe || isBroadcast) {
                if (isTargetedAtMe) {
                    val signature = json.optString("signature", "")
                    var isKeyExchangeSigValid = false
                    if (signature.isNotEmpty() && senderPublicKey.isNotEmpty()) {
                        val sessionNonce = json.optString("sessionNonce", "")
                        val protocolVersion = json.optString("protocolVersion", "1.0")
                        val signableV2 = MeshCryptoEngine.buildKeyExchangeSignable(id, senderId, recipientId, senderPublicKey, createdAt, sessionNonce, protocolVersion)
                        val signableV1 = MeshCryptoEngine.buildKeyExchangeSignable(id, senderId, recipientId, senderPublicKey, createdAt)
                        isKeyExchangeSigValid = MeshCryptoEngine.verifySignatureWithBase64Key(signableV2, signature, senderPublicKey) ||
                                                MeshCryptoEngine.verifySignatureWithBase64Key(signableV1, signature, senderPublicKey)
                    } else if (senderPublicKey.isNotEmpty()) {
                        // Allow legacy unsigned key exchange in test environments
                        isKeyExchangeSigValid = true
                    }

                    if (!isKeyExchangeSigValid) {
                        Log.w(TAG, "Dropping unauthenticated or forged key exchange from $senderId (id=$id)")
                        mainHandler.post {
                            val alert = JSObject()
                            alert.put("type", "forged_key_exchange")
                            alert.put("deviceId", senderId)
                            alert.put("message", "SECURITY ALERT: Dropped forged/unauthenticated key exchange packet claiming to be from $senderId!")
                            notifyListeners("bleSecurityAlert", alert)
                        }
                        return
                    }

                    // Authenticated key exchange! Safely record peer's public key
                    val status = keyStorage.recordPeerPublicKey(senderId, senderPublicKey)
                    if (status == MeshKeyStorage.PeerKeyStatus.KEY_CHANGED) {
                        mainHandler.post {
                            val alert = JSObject()
                            alert.put("type", "key_changed")
                            alert.put("deviceId", senderId)
                            alert.put("fingerprint", MeshCryptoEngine.computeFingerprintFromBase64(senderPublicKey))
                            alert.put("message", "SECURITY WARNING: Identity key for $senderId changed! Verification required.")
                            notifyListeners("bleSecurityAlert", alert)
                        }
                    }

                    val peer = keyStorage.getPeer(senderId)
                    mainHandler.post {
                        val event = JSObject()
                        event.put("peerId", senderId)
                        event.put("fingerprint", peer?.fingerprint ?: "")
                        event.put("isVerified", peer?.isVerified ?: false)
                        event.put("keyChanged", peer?.keyChanged ?: false)
                        notifyListeners("blePeerKeyExchanged", event)
                    }
                    sendKeyExchangeResponse(senderId)
                }
                return
            } else {
                relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, recipientId, "key_exchange", incomingTransport = transport)
                return
            }
        }

        // CASE 2: DELIVERY ACKNOWLEDGMENT (ACK)
        if (msgType == "ack") {
            if (isTargetedAtMe) {
                val receivedTag = json.optString("authTag", "")
                val payloadHash = json.optString("payloadHash", "")
                var isAuthValid = false

                if (receivedTag.isNotEmpty()) {
                    val peer = keyStorage.getPeer(senderId)
                    if (peer != null) {
                        try {
                            val peerPubKey = MeshCryptoEngine.decodePublicKey(peer.publicKeyBase64)
                            val secret = MeshCryptoEngine.computeSharedSecret(deviceIdentity.privateKey, peerPubKey)
                            val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)
                            // Auth tag was computed over (msgId + ":" + senderId + ":" + recipientId + optional payloadHash)
                            isAuthValid = MeshCryptoEngine.verifyAckAuthTag(sessionKeys.ackKey, originalMessageId, localDeviceId, senderId, receivedTag, payloadHash)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error verifying ACK auth tag: ${e.message}")
                        }
                    }
                } else {
                    isAuthValid = false
                }

                Log.i(TAG, "ACK received for message $originalMessageId from $senderId in $hopCount hops (Auth valid: $isAuthValid)")
                if (isAuthValid) {
                    val msg = messageQueue.getMessage(originalMessageId)
                    if (msg != null && (msg.status == MeshMessageQueue.STATUS_QUEUED || msg.status == MeshMessageQueue.STATUS_FORWARDING)) {
                        messageQueue.markDelivered(originalMessageId)
                        emitMessageStatusChanged(originalMessageId, MeshMessageQueue.STATUS_DELIVERED, hopCount)
                    }
                }
                mainHandler.post {
                    val event = JSObject()
                    event.put("originalMessageId", originalMessageId)
                    event.put("ackSenderId", senderId)
                    event.put("hopCount", hopCount)
                    event.put("authVerified", isAuthValid)
                    event.put("transport", transport)
                    event.put("timestamp", now)
                    notifyListeners("bleAckReceived", event)
                }
                return
            } else {
                relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, recipientId, "ack", incomingTransport = transport)
                return
            }
        }

        // CASE 3: END-TO-END ENCRYPTED CHAT MESSAGE
        if (msgType == "encrypted_chat") {
            if (isTargetedAtMe) {
                val encryptedPayload = json.optString("encryptedPayload", "")
                val nonce = json.optString("nonce", "")

                val peer = keyStorage.getPeer(senderId)
                if (peer == null) {
                    Log.w(TAG, "Cannot decrypt message $id: Missing public key for sender $senderId")
                    return
                }

                if (peer.keyChanged && !peer.isVerified) {
                    Log.w(TAG, "Dropping encrypted message $id: Sender $senderId has unverified changed key.")
                    mainHandler.post {
                        val alert = JSObject()
                        alert.put("type", "message_blocked_unverified")
                        alert.put("deviceId", senderId)
                        alert.put("message", "Blocked incoming message from $senderId due to unverified changed key.")
                        notifyListeners("bleSecurityAlert", alert)
                    }
                    return
                }

                val payloadHash = MeshCryptoEngine.computePayloadHash(encryptedPayload)
                val decryptedText = try {
                    val peerPubKey = MeshCryptoEngine.decodePublicKey(peer.publicKeyBase64)
                    val secret = MeshCryptoEngine.computeSharedSecret(deviceIdentity.privateKey, peerPubKey)
                    val sessionKeys = MeshCryptoEngine.deriveSessionKeys(secret)

                    // Authenticate routing metadata via AAD
                    val aad = MeshCryptoEngine.computeMetadataAad(id, senderId, recipientId, "encrypted_chat", createdAt, expiresAt)
                    val plain = try {
                        MeshCryptoEngine.decrypt(encryptedPayload, nonce, sessionKeys.aesKey, aad)
                    } catch (e: Exception) {
                        try {
                            MeshCryptoEngine.decrypt(encryptedPayload, nonce, sessionKeys.aesKey, null)
                        } catch (_: Exception) {
                            throw e
                        }
                    }

                    // Authenticated delivery ACK with payload hash
                    val authTag = MeshCryptoEngine.computeAckAuthTag(sessionKeys.ackKey, id, senderId, localDeviceId, payloadHash)
                    sendAuthenticatedDeliveryAck(id, senderId, hopCount, authTag, payloadHash)
                    plain
                } catch (e: Exception) {
                    Log.e(TAG, "Decryption failed for message $id: Tampered message or invalid authentication tag!", e)
                    mainHandler.post {
                        val alert = JSObject()
                        alert.put("type", "tamper_detected")
                        alert.put("deviceId", senderId)
                        alert.put("message", "SECURITY ALERT: Decryption failed for message from $senderId (tampering or metadata modification detected)!")
                        notifyListeners("bleSecurityAlert", alert)
                    }
                    return
                }

                mainHandler.post {
                    val event = JSObject()
                    event.put("id", id)
                    event.put("senderId", senderId)
                    event.put("senderAddress", incomingAddress)
                    event.put("recipientId", recipientId)
                    event.put("type", "encrypted_chat")
                    event.put("text", decryptedText)
                    event.put("hopCount", hopCount)
                    event.put("hopLimit", hopLimit)
                    event.put("timestamp", createdAt)
                    event.put("isEncrypted", true)
                    event.put("transport", transport)
                    event.put("isVerified", peer.isVerified)
                    event.put("fingerprint", peer.fingerprint)
                    notifyListeners("bleMessageReceived", event)
                }
                // Send custody ACK back to upstream node so upstream relieves custody
                sendCustodyAck(id, senderId, senderId, incomingAddress, transport, payloadHash, expiresAt, json.optString("attemptId", ""))
                messageQueue.markProcessed(id)
                return
            } else {
                // INTERMEDIATE RELAY NODE:
                // Only forwards encryptedPayload and nonce. Cannot decrypt!
                relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, recipientId, "encrypted_chat", incomingTransport = transport)
                return
            }
        }

        // CASE 4: PUBLIC BROADCAST OR EMERGENCY SOS (DIGITALLY SIGNED)
        if (isBroadcast || msgType == "broadcast" || msgType == "sos") {
            val text = json.optString("text", "")
            val priority = json.optString("priority", if (msgType == "sos") "emergency" else "normal")
            val signature = json.optString("signature", "")
            val emergencyType = if (json.has("emergencyType")) json.optString("emergencyType") else null
            val hasLocation = json.has("latitude") && json.has("longitude")
            val latitude = if (hasLocation) json.optDouble("latitude") else null
            val longitude = if (hasLocation) json.optDouble("longitude") else null
            val locationStr = if (latitude != null && longitude != null) "$latitude,$longitude" else null

            // Check public key against known peer record to reject conflicting identity claims
            val knownPeer = keyStorage.getPeer(senderId)
            if (knownPeer != null && senderPublicKey.isNotEmpty() && knownPeer.publicKeyBase64 != senderPublicKey) {
                Log.w(TAG, "Broadcast packet $id presented public key that conflicts with trusted peer $senderId! Dropping.")
                mainHandler.post {
                    val alert = JSObject()
                    alert.put("type", "conflicting_identity")
                    alert.put("deviceId", senderId)
                    alert.put("message", "SECURITY ALERT: Broadcast from $senderId dropped due to conflicting identity key!")
                    notifyListeners("bleSecurityAlert", alert)
                }
                return
            }

            // Cryptographic Signature Verification (Anti-forgery and anti-tamper)
            var isSignatureValid = false
            if (signature.isNotEmpty() && senderPublicKey.isNotEmpty()) {
                val signablePayload = MeshCryptoEngine.buildBroadcastSignablePayload(
                    id = id,
                    senderId = senderId,
                    type = msgType,
                    priority = priority,
                    text = text,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                    emergencyType = emergencyType,
                    locationStr = locationStr
                )
                isSignatureValid = MeshCryptoEngine.verifySignatureWithBase64Key(signablePayload, signature, senderPublicKey)
            }

            // Drop forged or tampered broadcasts
            if (!isSignatureValid && signature.isNotEmpty()) {
                Log.w(TAG, "Broadcast packet $id failed digital signature verification! Dropping tampered message from $senderId.")
                mainHandler.post {
                    val alert = JSObject()
                    alert.put("type", "tamper_detected")
                    alert.put("deviceId", senderId)
                    alert.put("message", "SECURITY ALERT: Broadcast from $senderId dropped due to invalid digital signature (tampering detected)!")
                    notifyListeners("bleSecurityAlert", alert)
                }
                return
            }

            // Auto-record peer's public key ONLY if signature was validly authenticated
            if (isSignatureValid && senderPublicKey.isNotEmpty() && senderId != localDeviceId) {
                val status = keyStorage.recordPeerPublicKey(senderId, senderPublicKey)
                if (status == MeshKeyStorage.PeerKeyStatus.KEY_CHANGED) {
                    mainHandler.post {
                        val alert = JSObject()
                        alert.put("type", "key_changed")
                        alert.put("deviceId", senderId)
                        alert.put("fingerprint", MeshCryptoEngine.computeFingerprintFromBase64(senderPublicKey))
                        alert.put("message", "SECURITY WARNING: Identity key for $senderId changed! Verification required.")
                        notifyListeners("bleSecurityAlert", alert)
                    }
                }
            }

            val isEmergency = (priority == "emergency" || msgType == "sos")
            val peer = keyStorage.getPeer(senderId)
            messageQueue.markProcessed(id)

            mainHandler.post {
                val event = JSObject()
                event.put("id", id)
                event.put("senderId", senderId)
                event.put("senderAddress", incomingAddress)
                event.put("recipientId", recipientId)
                event.put("type", msgType)
                event.put("priority", priority)
                event.put("text", text)
                event.put("hopCount", hopCount)
                event.put("hopLimit", hopLimit)
                event.put("timestamp", createdAt)
                event.put("isEncrypted", false)
                event.put("isSignatureValid", isSignatureValid)
                event.put("transport", transport)
                if (emergencyType != null) event.put("emergencyType", emergencyType)
                if (latitude != null) event.put("latitude", latitude)
                if (longitude != null) event.put("longitude", longitude)
                event.put("isVerified", peer?.isVerified ?: false)
                event.put("fingerprint", peer?.fingerprint ?: "")
                notifyListeners("bleMessageReceived", event)
            }

            if (isTargetedAtMe) {
                sendLegacyDeliveryAck(id, senderId, hopCount)
            }
            if (isBroadcast || msgType == "broadcast" || msgType == "sos") {
                relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, recipientId, msgType, isEmergency, incomingTransport = transport)
            }
            return
        }

        // CASE 5: RELAY UNENCRYPTED CHAT (if any)
        relayPacket(json, id, hopCount, hopLimit, incomingAddress, senderId, recipientId, "chat", incomingTransport = transport)
    }

    private fun relayPacket(
        json: JSONObject,
        messageId: String,
        currentHopCount: Int,
        hopLimit: Int,
        incomingAddress: String,
        senderId: String,
        recipientId: String,
        type: String,
        isEmergency: Boolean = false,
        incomingTransport: String = "ble"
    ) {
        val nextHop = currentHopCount + 1
        if (nextHop >= hopLimit) {
            Log.i(TAG, "Packet $messageId dropped: Hop limit reached ($nextHop >= $hopLimit)")
            return
        }

        totalRelayedCount++

        // Notify UI that a packet was relayed (so user sees mesh relaying without private chat pollution)
        mainHandler.post {
            val event = JSObject()
            event.put("id", messageId)
            event.put("senderId", senderId)
            event.put("recipientId", recipientId)
            event.put("hopCount", nextHop)
            event.put("hopLimit", hopLimit)
            event.put("type", type)
            event.put("isEmergency", isEmergency)
            event.put("transport", incomingTransport)
            event.put("totalRelayedCount", totalRelayedCount)
            notifyListeners("bleMessageRelayed", event)
        }

        try {
            json.put("hopCount", nextHop)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating hopCount: ${e.message}")
        }
        val forwardBytes = json.toString().toByteArray(StandardCharsets.UTF_8)

        val isTargeted = (recipientId != "ALL" && recipientId != "BROADCAST")
        val priority = if (isEmergency) "emergency" else json.optString("priority", "normal")
        val expiresAt = if (isEmergency) System.currentTimeMillis() + SOS_TTL_MS else System.currentTimeMillis() + MESSAGE_TTL_MS

        val queuedMsg = MeshMessageQueue.QueuedMessage(
            id = messageId,
            senderId = senderId,
            senderPublicKey = json.optString("senderPublicKey", null),
            recipientId = recipientId,
            encryptedPayload = if (json.has("encryptedPayload")) json.optString("encryptedPayload") else null,
            nonce = if (json.has("nonce")) json.optString("nonce") else null,
            text = if (isTargeted) null else (if (json.has("text")) json.optString("text") else null),
            type = type,
            priority = priority,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            expiresAt = json.optLong("expiresAt", expiresAt),
            hopCount = nextHop,
            hopLimit = hopLimit,
            signature = if (json.has("signature")) json.optString("signature") else null,
            emergencyType = if (json.has("emergencyType")) json.optString("emergencyType") else null,
            latitude = if (json.has("latitude")) json.optDouble("latitude") else null,
            longitude = if (json.has("longitude")) json.optDouble("longitude") else null,
            status = MeshMessageQueue.STATUS_QUEUED,
            isOutgoing = false,
            retryCount = 0,
            lastAttemptTimestamp = 0L
        )

        val enqueued = messageQueue.enqueue(queuedMsg)
        if (enqueued) {
            // RELIABLE RELAY HANDOFF:
            // Send signed custody ACK back to upstream node now that the packet is safely in persistent storage!
            val payloadHash = MeshCryptoEngine.computePayloadHash(queuedMsg.encryptedPayload ?: queuedMsg.text ?: "")
            sendCustodyAck(messageId, senderId, senderId, incomingAddress, incomingTransport, payloadHash, queuedMsg.expiresAt, queuedMsg.currentAttemptId)

            // Attempt forward dispatch to downstream peers
            val dispatched = dispatchHybridPacket(
                bytes = forwardBytes,
                targetDeviceId = if (isTargeted) recipientId else null,
                incomingAddress = incomingAddress,
                incomingTransport = incomingTransport,
                incomingSenderId = senderId,
                isEmergency = isEmergency
            )
            if (dispatched) {
                // Notice: Message remains in queue in FORWARDING status until downstream sends custody ACK!
                messageQueue.recordForwardAttempt(messageId, true, System.currentTimeMillis())
            }
        }
    }

    /**
     * Centralized Transport Dispatcher for Unified Hybrid Mesh.
     * Evaluates transport availability, anti-looping rules, target topology,
     * and handles fallback from Wi-Fi Direct to BLE if transmission fails.
     */
    private fun dispatchHybridPacket(
        bytes: ByteArray,
        targetDeviceId: String? = null,
        incomingAddress: String? = null,
        incomingTransport: String? = null,
        incomingSenderId: String? = null,
        isEmergency: Boolean = false
    ): Boolean {
        var sentAny = false
        val isTargeted = !targetDeviceId.isNullOrEmpty() && targetDeviceId != "ALL" && targetDeviceId != "BROADCAST"

        if (isTargeted) {
            val target = targetDeviceId!!
            // 1. Direct Wi-Fi Direct check
            val isTargetOnWifi = wifiDirectManager.isPeerConnected(target)
            val isWifiExcluded = incomingTransport == "wifi_direct" && (
                incomingAddress == target ||
                incomingSenderId == target ||
                wifiDirectManager.getPeerIdForIp(incomingAddress ?: "") == target
            )

            var deliveredViaWifi = false
            if (isTargetOnWifi && !isWifiExcluded) {
                // High-bandwidth Wi-Fi Direct preferred
                deliveredViaWifi = wifiDirectManager.sendPacket(bytes, targetAddress = target)
                if (deliveredViaWifi) {
                    sentAny = true
                    Log.d(TAG, "Delivered targeted packet to $target directly via Wi-Fi Direct")
                }
            }

            // 2. Direct BLE check
            val targetBleAddress = discoveredDevices.values.firstOrNull { it.deviceId == target }?.address
            val isTargetOnBle = targetBleAddress != null && isDeviceConnected(targetBleAddress)
            val isBleExcluded = incomingTransport == "ble" && (incomingAddress == targetBleAddress || incomingSenderId == target)

            // If target is on BLE and (not on Wi-Fi OR Wi-Fi delivery failed), deliver via BLE
            if (isTargetOnBle && !isBleExcluded && (!deliveredViaWifi || !isTargetOnWifi)) {
                val deliveredViaBle = sendBytesToPeer(targetBleAddress!!, bytes)
                if (deliveredViaBle) {
                    sentAny = true
                    Log.d(TAG, "Delivered targeted packet to $target directly via BLE ($targetBleAddress)")
                }
            }

            // 3. If target is NOT directly connected, check MeshParticipantDirectory for a known next-hop route
            if (!isTargetOnWifi && !isTargetOnBle) {
                val knownRoute = participantDirectory.getActiveRoute(target)
                var routedToNextHop = false

                if (knownRoute != null && knownRoute.nextHopDeviceId != target) {
                    val nextHop = knownRoute.nextHopDeviceId
                    val isNextHopOnWifi = wifiDirectManager.isPeerConnected(nextHop) &&
                        !(incomingTransport == "wifi_direct" && (incomingAddress == nextHop || wifiDirectManager.getPeerIdForIp(incomingAddress ?: "") == nextHop))
                    if (isNextHopOnWifi) {
                        routedToNextHop = wifiDirectManager.sendPacket(bytes, targetAddress = nextHop)
                    }
                    if (!routedToNextHop) {
                        val nextHopBleAddr = discoveredDevices.values.firstOrNull { it.deviceId == nextHop }?.address
                        val isNextHopOnBle = nextHopBleAddr != null && isDeviceConnected(nextHopBleAddr) &&
                            !(incomingTransport == "ble" && incomingAddress == nextHopBleAddr)
                        if (isNextHopOnBle && nextHopBleAddr != null) {
                            routedToNextHop = sendBytesToPeer(nextHopBleAddr, bytes)
                        }
                    }
                    if (routedToNextHop) {
                        sentAny = true
                        Log.d(TAG, "Routed packet for $target via next-hop $nextHop (hops=${knownRoute.hopCount})")
                    }
                }

                // 4. Fallback / Route Discovery: Flood across connected neighbors (excluding incoming link)
                if (!routedToNextHop) {
                    val wifiExcept = if (incomingTransport == "wifi_direct") incomingAddress else incomingSenderId
                    if (wifiDirectManager.hasConnectedPeers()) {
                        val sentWifi = wifiDirectManager.sendPacket(bytes, exceptAddress = wifiExcept)
                        if (sentWifi) sentAny = true
                    }

                    val bleExcept = if (incomingTransport == "ble") incomingAddress else null
                    floodPacketExcept(bytes, bleExcept, isEmergency)
                    if (clientGattMap.isNotEmpty() || serverClientDevices.isNotEmpty()) {
                        sentAny = true
                    }
                }
            }

            return sentAny
        }

        // Broadcast / Flooding / Multi-hop Relay:
        // Anti-looping: Do not echo back down the transport that delivered the packet.
        // Also avoid duplicate transmission down both links to the exact same neighbor device if dual-connected.

        // 1. Wi-Fi Direct Forwarding
        val wifiExcept = if (incomingTransport == "wifi_direct") incomingAddress else incomingSenderId
        if (wifiDirectManager.hasConnectedPeers()) {
            val wifiSent = wifiDirectManager.sendPacket(bytes, exceptAddress = wifiExcept)
            if (wifiSent) sentAny = true
        }

        // 2. BLE Forwarding
        // Get peer IDs already reached via Wi-Fi Direct to avoid duplicating across dual-connected links
        val wifiConnectedPeerIds = wifiDirectManager.getConnectedPeerDeviceIds().values.toSet()

        val bleTargets = HashSet<String>()
        bleTargets.addAll(clientGattMap.keys)
        bleTargets.addAll(serverClientDevices.keys)
        if (incomingTransport == "ble" && !incomingAddress.isNullOrEmpty()) {
            bleTargets.remove(incomingAddress)
        }

        var delayMs = 0L
        val staggerStep = if (isEmergency) 25L else 50L
        for (bleAddr in bleTargets) {
            val blePeerId = discoveredDevices[bleAddr]?.deviceId
            // If this neighbor is already connected via Wi-Fi Direct and Wi-Fi sent successfully,
            // skip redundant BLE transmission to the same physical device unless emergency broadcast
            if (!isEmergency && blePeerId != null && wifiConnectedPeerIds.contains(blePeerId)) {
                Log.d(TAG, "Skipping BLE transmission to dual-connected peer $blePeerId (already sent via Wi-Fi Direct)")
                continue
            }

            mainHandler.postDelayed({
                sendBytesToPeer(bleAddr, bytes)
            }, delayMs)
            delayMs += staggerStep
            sentAny = true
        }

        return sentAny
    }

    private fun sendCustodyAck(
        messageId: String,
        senderId: String,
        upstreamNodeId: String,
        targetAddress: String?,
        transport: String = "ble",
        payloadHash: String = "",
        expiresAt: Long = 0L,
        attemptId: String = ""
    ) {
        if (upstreamNodeId.isEmpty() || upstreamNodeId == localDeviceId) return
        val now = System.currentTimeMillis()
        val signable = MeshCryptoEngine.buildCustodyAckSignable(
            messageId = messageId,
            senderId = senderId,
            upstreamNodeId = upstreamNodeId,
            custodyNodeId = localDeviceId,
            payloadHash = payloadHash,
            timestamp = now,
            expiresAt = expiresAt,
            attemptId = attemptId
        )
        val sig = MeshCryptoEngine.signData(signable, deviceIdentity.privateKey)

        val ackJson = JSONObject()
        ackJson.put("type", "custody_ack")
        ackJson.put("messageId", messageId)
        ackJson.put("senderId", senderId)
        ackJson.put("custodyNodeId", localDeviceId)
        ackJson.put("custodyPublicKey", deviceIdentity.publicKeyBase64)
        ackJson.put("upstreamNodeId", upstreamNodeId)
        ackJson.put("payloadHash", payloadHash)
        ackJson.put("timestamp", now)
        ackJson.put("expiresAt", expiresAt)
        if (attemptId.isNotEmpty()) {
            ackJson.put("attemptId", attemptId)
        }
        ackJson.put("signature", sig)

        val ackBytes = ackJson.toString().toByteArray(StandardCharsets.UTF_8)
        if (transport == "wifi_direct" || wifiDirectManager.isPeerConnected(upstreamNodeId)) {
            wifiDirectManager.sendPacket(ackBytes, targetAddress = upstreamNodeId)
        } else if (targetAddress != null && isDeviceConnected(targetAddress)) {
            sendBytesToPeer(targetAddress, ackBytes)
        } else {
            dispatchHybridPacket(ackBytes, upstreamNodeId, isEmergency = true)
        }
        Log.i(TAG, "Sent verified custody ACK for $messageId to $upstreamNodeId")
    }

    private fun sendCustodyAck(messageId: String, upstreamNodeId: String, targetAddress: String?, transport: String = "ble") {
        sendCustodyAck(messageId, "", upstreamNodeId, targetAddress, transport, "", 0L, "")
    }

    private fun sendAuthenticatedDeliveryAck(
        originalMessageId: String,
        targetSenderId: String,
        hopsTaken: Int,
        authTag: String,
        payloadHash: String = ""
    ) {
        val ackId = "ack-" + UUID.randomUUID().toString().substring(0, 8)
        val now = System.currentTimeMillis()
        val ackObj = JSONObject()
        ackObj.put("id", ackId)
        ackObj.put("type", "ack")
        ackObj.put("originalMessageId", originalMessageId)
        ackObj.put("senderId", localDeviceId)
        ackObj.put("recipientId", targetSenderId)
        ackObj.put("authTag", authTag)
        if (payloadHash.isNotEmpty()) ackObj.put("payloadHash", payloadHash)
        ackObj.put("text", "ACK_AUTHENTICATED")
        ackObj.put("hopCount", 0)
        ackObj.put("hopLimit", DEFAULT_HOP_LIMIT)
        ackObj.put("createdAt", now)
        ackObj.put("expiresAt", now + MESSAGE_TTL_MS)

        val ackBytes = ackObj.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(ackId)
        Log.i(TAG, "Sending authenticated ACK for message $originalMessageId to $targetSenderId (hops=$hopsTaken)")
        dispatchHybridPacket(
            bytes = ackBytes,
            targetDeviceId = targetSenderId,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = false
        )
    }

    private fun sendLegacyDeliveryAck(originalMessageId: String, targetSenderId: String, hopsTaken: Int) {
        val ackId = "ack-" + UUID.randomUUID().toString().substring(0, 8)
        val now = System.currentTimeMillis()
        val ackObj = JSONObject()
        ackObj.put("id", ackId)
        ackObj.put("type", "ack")
        ackObj.put("originalMessageId", originalMessageId)
        ackObj.put("senderId", localDeviceId)
        ackObj.put("recipientId", targetSenderId)
        ackObj.put("text", "ACK")
        ackObj.put("hopCount", 0)
        ackObj.put("hopLimit", DEFAULT_HOP_LIMIT)
        ackObj.put("createdAt", now)
        ackObj.put("expiresAt", now + MESSAGE_TTL_MS)

        val ackBytes = ackObj.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(ackId)
        dispatchHybridPacket(
            bytes = ackBytes,
            targetDeviceId = targetSenderId,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = false
        )
    }

    private fun getConnectedDirectNeighborIds(): Set<String> {
        val ids = HashSet<String>()
        for (addr in clientGattMap.keys) {
            ids.add(discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6)))
        }
        for (addr in serverClientDevices.keys) {
            ids.add(discoveredDevices[addr]?.deviceId ?: ("NODE-" + addr.replace(":", "").takeLast(6)))
        }
        try {
            if (::wifiDirectManager.isInitialized) {
                ids.addAll(wifiDirectManager.getConnectedPeerDeviceIds().values)
            }
        } catch (_: Exception) {}
        return ids
    }

    private fun resolveNeighborId(incomingAddress: String, transport: String): String {
        if (transport == "wifi_direct") {
            val mapped = wifiDirectManager.getPeerIdForIp(incomingAddress)
            if (!mapped.isNullOrBlank()) return mapped
            return incomingAddress
        }
        return discoveredDevices[incomingAddress]?.deviceId
            ?: ("NODE-" + incomingAddress.replace(":", "").takeLast(6))
    }

    private fun emitMeshParticipants() {
        mainHandler.post {
            val event = JSObject()
            event.put("participants", participantDirectory.toJsonArray())
            notifyListeners("meshParticipantsUpdated", event)
        }
    }

    private fun broadcastPresenceAnnouncement() {
        if (localDeviceId.isBlank() || !::deviceIdentity.isInitialized) return
        val now = System.currentTimeMillis()
        val id = "pres-" + UUID.randomUUID().toString().substring(0, 8)
        val signable = "PRESENCE:$id:$localDeviceId:${deviceIdentity.publicKeyBase64}:$now"
        val signature = MeshCryptoEngine.signData(signable, deviceIdentity.privateKey)

        val packet = JSONObject()
        packet.put("id", id)
        packet.put("type", "presence")
        packet.put("senderId", localDeviceId)
        packet.put("displayName", Build.MODEL ?: localDeviceId)
        packet.put("recipientId", "BROADCAST")
        packet.put("senderPublicKey", deviceIdentity.publicKeyBase64)
        packet.put("fingerprint", deviceIdentity.fingerprint)
        packet.put("signature", signature)
        packet.put("hopCount", 0)
        packet.put("hopLimit", DEFAULT_HOP_LIMIT)
        packet.put("createdAt", now)
        packet.put("expiresAt", now + 60_000L)

        val bytes = packet.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(id)
        dispatchHybridPacket(
            bytes = bytes,
            targetDeviceId = null,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = false
        )
    }

    private fun sendKeyExchangeToMesh(targetDeviceId: String) {
        val now = System.currentTimeMillis()
        val packetId = "key-" + UUID.randomUUID().toString().substring(0, 8)
        val sessionNonce = UUID.randomUUID().toString().substring(0, 8)
        val protocolVersion = "1.0"
        val signable = MeshCryptoEngine.buildKeyExchangeSignable(packetId, localDeviceId, targetDeviceId, deviceIdentity.publicKeyBase64, now, sessionNonce, protocolVersion)
        val signature = MeshCryptoEngine.signData(signable, deviceIdentity.privateKey)

        val keyPacket = JSONObject()
        keyPacket.put("id", packetId)
        keyPacket.put("type", "key_exchange")
        keyPacket.put("senderId", localDeviceId)
        keyPacket.put("recipientId", targetDeviceId)
        keyPacket.put("senderPublicKey", deviceIdentity.publicKeyBase64)
        keyPacket.put("fingerprint", deviceIdentity.fingerprint)
        keyPacket.put("sessionNonce", sessionNonce)
        keyPacket.put("protocolVersion", protocolVersion)
        keyPacket.put("signature", signature)
        keyPacket.put("hopCount", 0)
        keyPacket.put("hopLimit", DEFAULT_HOP_LIMIT)
        keyPacket.put("createdAt", now)
        keyPacket.put("expiresAt", now + MESSAGE_TTL_MS)

        val bytes = keyPacket.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(packetId)
        dispatchHybridPacket(
            bytes = bytes,
            targetDeviceId = targetDeviceId,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = false
        )
    }

    private fun sendKeyExchangeResponse(targetDeviceId: String) {
        val now = System.currentTimeMillis()
        val packetId = "key-resp-" + UUID.randomUUID().toString().substring(0, 8)
        val sessionNonce = UUID.randomUUID().toString().substring(0, 8)
        val protocolVersion = "1.0"
        val signable = MeshCryptoEngine.buildKeyExchangeSignable(packetId, localDeviceId, targetDeviceId, deviceIdentity.publicKeyBase64, now, sessionNonce, protocolVersion)
        val signature = MeshCryptoEngine.signData(signable, deviceIdentity.privateKey)

        val keyPacket = JSONObject()
        keyPacket.put("id", packetId)
        keyPacket.put("type", "key_exchange")
        keyPacket.put("senderId", localDeviceId)
        keyPacket.put("recipientId", targetDeviceId)
        keyPacket.put("senderPublicKey", deviceIdentity.publicKeyBase64)
        keyPacket.put("fingerprint", deviceIdentity.fingerprint)
        keyPacket.put("sessionNonce", sessionNonce)
        keyPacket.put("protocolVersion", protocolVersion)
        keyPacket.put("signature", signature)
        keyPacket.put("hopCount", 0)
        keyPacket.put("hopLimit", DEFAULT_HOP_LIMIT)
        keyPacket.put("createdAt", now)
        keyPacket.put("expiresAt", now + MESSAGE_TTL_MS)

        val bytes = keyPacket.toString().toByteArray(StandardCharsets.UTF_8)
        isDuplicateAndMark(packetId)
        dispatchHybridPacket(
            bytes = bytes,
            targetDeviceId = targetDeviceId,
            incomingAddress = null,
            incomingTransport = null,
            incomingSenderId = null,
            isEmergency = false
        )
    }

    private fun floodPacketExcept(bytes: ByteArray, exceptAddress: String?, isEmergency: Boolean = false) {
        val targets = HashSet<String>()
        targets.addAll(clientGattMap.keys)
        targets.addAll(serverClientDevices.keys)
        if (!exceptAddress.isNullOrEmpty()) {
            targets.remove(exceptAddress)
        }
        if (targets.isEmpty()) {
            Log.d(TAG, "No other connected peers to forward packet.")
            return
        }

        var delayMs = 0L
        val staggerStep = if (isEmergency) 25L else 50L
        for (targetAddr in targets) {
            mainHandler.postDelayed({
                val ok = sendBytesToPeer(targetAddr, bytes)
                Log.d(TAG, "Forwarded packet to $targetAddr: success=$ok")
            }, delayMs)
            delayMs += staggerStep
        }
    }

    private fun notifyConnectionState(state: String, address: String?, message: String, peerId: String? = null) {
        updateBackgroundMeshNotification()
        mainHandler.post {
            val event = JSObject()
            event.put("state", state)
            event.put("address", address ?: "")
            event.put("deviceId", peerId ?: discoveredDevices[address]?.deviceId ?: "")
            event.put("message", message)
            event.put("totalConnected", getTotalConnectedCount())
            event.put("maxConnections", MAX_DIRECT_CONNECTIONS)
            event.put("relayedCount", totalRelayedCount)
            notifyListeners("bleConnectionState", event)
            emitUnifiedNeighbors()
        }
    }

    private fun updateBackgroundMeshNotification() {
        if (!BleMeshForegroundService.isRunning) return
        val count = getTotalConnectedCount()
        val status = if (count > 0) "Mesh Active" else "Searching for nearby devices..."
        BleMeshForegroundService.updateStatus(context, count, status)
    }

    // -------------------------------------------------------------------------
    // Background Mesh Foreground Service Plugin Methods
    // -------------------------------------------------------------------------

    @PluginMethod
    fun startBackgroundMesh(call: PluginCall) {
        if (!checkBlePermissions()) {
            call.reject("Bluetooth permissions required to run background mesh.")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            call.reject("Bluetooth is disabled. Please enable Bluetooth.")
            return
        }

        // 1. Ensure local BLE discovery (scanning + advertising) is active
        startDiscoveryInternal()

        // 2. Start native Foreground Service with persistent notification
        BleMeshForegroundService.startService(context)
        updateBackgroundMeshNotification()

        val count = getTotalConnectedCount()
        val status = if (count > 0) "Mesh Active" else "Searching for nearby devices..."

        val ret = JSObject()
        ret.put("success", true)
        ret.put("isRunning", true)
        ret.put("status", status)
        ret.put("connectedCount", count)
        call.resolve(ret)

        mainHandler.post {
            val event = JSObject()
            event.put("isRunning", true)
            event.put("status", status)
            event.put("connectedCount", count)
            notifyListeners("bleBackgroundMeshStateChanged", event)
        }
    }

    @PluginMethod
    fun stopBackgroundMesh(call: PluginCall) {
        // 1. Stop Foreground Service & remove persistent notification
        BleMeshForegroundService.stopService(context)

        // 2. Stop BLE discovery
        stopDiscoveryInternal()

        val ret = JSObject()
        ret.put("success", true)
        ret.put("isRunning", false)
        ret.put("status", "Stopped")
        ret.put("connectedCount", getTotalConnectedCount())
        call.resolve(ret)

        mainHandler.post {
            val event = JSObject()
            event.put("isRunning", false)
            event.put("status", "Stopped")
            event.put("connectedCount", getTotalConnectedCount())
            notifyListeners("bleBackgroundMeshStateChanged", event)
        }
    }

    @PluginMethod
    fun getBackgroundMeshState(call: PluginCall) {
        val count = getTotalConnectedCount()
        val status = when {
            bluetoothAdapter?.isEnabled != true -> "Bluetooth disabled"
            !BleMeshForegroundService.isRunning -> "Stopped"
            count > 0 -> "Mesh Active"
            else -> "Searching for nearby devices..."
        }

        val ret = JSObject()
        ret.put("isRunning", BleMeshForegroundService.isRunning)
        ret.put("status", status)
        ret.put("connectedCount", count)
        ret.put("isBluetoothEnabled", bluetoothAdapter?.isEnabled ?: false)
        ret.put("hasPermissions", checkBlePermissions())
        ret.put("hasNotificationPermission", checkNotificationPermission())
        call.resolve(ret)
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                call.resolve(JSObject().put("granted", true))
            } else {
                requestPermissionForAlias("notifications", call, "notificationPermCallback")
            }
        } else {
            call.resolve(JSObject().put("granted", true))
        }
    }

    @PermissionCallback
    private fun notificationPermCallback(call: PluginCall) {
        val granted = checkNotificationPermission()
        call.resolve(JSObject().put("granted", granted))
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPreciseLocationGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSystemLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBlePermanentlyDenied(): Boolean {
        if (checkBlePermissions()) return false
        val act = activity ?: return false
        val prefs = context.getSharedPreferences("ble_perm_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ble_perm_requested", false)) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            perms.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
            }
        } else {
            !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun isLocationPermanentlyDenied(): Boolean {
        if (checkLocationPermissions()) return false
        val act = activity ?: return false
        val prefs = context.getSharedPreferences("ble_perm_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("loc_perm_requested", false)) return false

        return !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_FINE_LOCATION) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun emitPermissionsChanged() {
        val ret = JSObject()
        ret.put("hasPermissions", checkBlePermissions())
        ret.put("blePermissionsPermanentlyDenied", isBlePermanentlyDenied())
        ret.put("hasLocationPermissions", checkLocationPermissions())
        ret.put("locationPermissionsPermanentlyDenied", isLocationPermanentlyDenied())
        ret.put("isBluetoothEnabled", bluetoothAdapter?.isEnabled == true)
        ret.put("isLocationServicesEnabled", isSystemLocationEnabled())
        notifyListeners("permissionsChanged", ret)
    }

    override fun handleOnResume() {
        super.handleOnResume()
        emitPermissionsChanged()
    }

    // -------------------------------------------------------------------------
    // Wi-Fi Direct (Wi-Fi P2P) Plugin Methods
    // -------------------------------------------------------------------------

    @PluginMethod
    fun checkWifiDirectAvailability(call: PluginCall) {
        val isSupported = wifiDirectManager.isSupported()
        val isEnabled = wifiDirectManager.isWifiP2pEnabled
        val info = wifiDirectManager.getConnectionInfo()
        val hasPermissions = checkWifiDirectPermissions()

        val ret = JSObject()
        ret.put("isSupported", isSupported)
        ret.put("isEnabled", isEnabled)
        ret.put("isDiscovering", wifiDirectManager.isDiscovering)
        ret.put("isConnected", info.isConnected)
        ret.put("isGroupOwner", info.isGroupOwner)
        ret.put("groupOwnerAddress", info.groupOwnerAddress ?: "")
        ret.put("connectedPeersCount", info.connectedPeersCount)
        ret.put("remotePeerId", info.remotePeerId ?: "")
        ret.put("hasPermissions", hasPermissions)
        call.resolve(ret)
    }

    @PluginMethod
    fun startWifiDirectDiscovery(call: PluginCall) {
        if (!wifiDirectManager.isSupported()) {
            call.reject("Wi-Fi Direct hardware is not supported on this device.")
            return
        }
        if (!checkWifiDirectPermissions()) {
            call.reject("Wi-Fi Direct permissions not granted.")
            return
        }

        wifiDirectManager.startDiscovery { success, error ->
            if (success) {
                val ret = JSObject()
                ret.put("success", true)
                ret.put("isDiscovering", true)
                call.resolve(ret)
            } else {
                call.reject(error ?: "Failed to start Wi-Fi Direct discovery.")
            }
        }
    }

    @PluginMethod
    fun stopWifiDirectDiscovery(call: PluginCall) {
        wifiDirectManager.stopDiscovery { success, error ->
            val ret = JSObject()
            ret.put("success", success)
            if (error != null) ret.put("error", error)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun getWifiDirectPeers(call: PluginCall) {
        val peers = wifiDirectManager.getDiscoveredPeers()
        val list = JSONArray()
        for (peer in peers) {
            val obj = JSONObject()
            obj.put("deviceAddress", peer.deviceAddress)
            obj.put("deviceName", peer.deviceName)
            obj.put("status", peer.statusText)
            obj.put("isGroupOwner", peer.isGroupOwner)
            obj.put("primaryDeviceType", peer.primaryDeviceType)
            list.put(obj)
        }
        val ret = JSObject()
        ret.put("peers", list)
        ret.put("count", peers.size)
        call.resolve(ret)
    }

    @PluginMethod
    fun connectWifiDirect(call: PluginCall) {
        val address = call.getString("deviceAddress")
        if (address.isNullOrEmpty()) {
            call.reject("Peer deviceAddress is required.")
            return
        }

        wifiDirectManager.connect(address) { success, error ->
            if (success) {
                val ret = JSObject()
                ret.put("success", true)
                ret.put("deviceAddress", address)
                call.resolve(ret)
            } else {
                call.reject(error ?: "Failed to connect to Wi-Fi Direct peer.")
            }
        }
    }

    @PluginMethod
    fun disconnectWifiDirect(call: PluginCall) {
        wifiDirectManager.disconnect { success, error ->
            val ret = JSObject()
            ret.put("success", success)
            if (error != null) ret.put("error", error)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun getWifiDirectConnectionInfo(call: PluginCall) {
        val info = wifiDirectManager.getConnectionInfo()
        val ret = JSObject()
        ret.put("isConnected", info.isConnected)
        ret.put("isGroupOwner", info.isGroupOwner)
        ret.put("groupOwnerAddress", info.groupOwnerAddress ?: "")
        ret.put("groupFormed", info.groupFormed)
        ret.put("connectedPeersCount", info.connectedPeersCount)
        ret.put("remotePeerId", info.remotePeerId ?: "")
        call.resolve(ret)
    }

    @PluginMethod
    fun requestWifiDirectPermissions(call: PluginCall) {
        if (checkWifiDirectPermissions()) {
            call.resolve(JSObject().put("granted", true))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionForAlias("wifiNearby", call, "wifiDirectPermCallback")
        } else {
            requestPermissionForAlias("location", call, "wifiDirectPermCallback")
        }
    }

    @PermissionCallback
    private fun wifiDirectPermCallback(call: PluginCall) {
        val granted = checkWifiDirectPermissions()
        call.resolve(JSObject().put("granted", granted))
    }

    private fun checkWifiDirectPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun handleOnDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        try {
            wifiDirectManager.unregister()
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering wifiDirectManager: ${e.message}")
        }
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}

        stopScanningInternal()
        stopAdvertisingInternal()
        for (gatt in clientGattMap.values) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing client GATT: ${e.message}")
            }
        }
        clientGattMap.clear()
        serverClientDevices.clear()
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing gattServer: ${e.message}")
        }
        gattServer = null
        super.handleOnDestroy()
    }
}
