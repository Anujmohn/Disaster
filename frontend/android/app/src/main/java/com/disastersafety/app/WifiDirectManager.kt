package com.disastersafety.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class WifiDirectManager(
    private val context: Context,
    private val localDeviceIdProvider: () -> String,
    private val localPublicKeyProvider: () -> String,
    private val onPacketReceived: (bytes: ByteArray, incomingAddress: String, transport: String) -> Unit,
    private val onPeersChanged: (peers: List<WifiDirectPeer>) -> Unit,
    private val onConnectionChanged: (info: WifiDirectConnectionInfo) -> Unit,
    private val onStateChanged: (isEnabled: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "WifiDirectManager"
        const val PORT = 8888
        private const val SOCKET_TIMEOUT_MS = 10000
    }

    data class WifiDirectPeer(
        val deviceAddress: String,
        val deviceName: String,
        val status: Int,
        val statusText: String,
        val isGroupOwner: Boolean,
        val primaryDeviceType: String = ""
    )

    data class WifiDirectConnectionInfo(
        val isConnected: Boolean,
        val isGroupOwner: Boolean,
        val groupOwnerAddress: String?,
        val groupFormed: Boolean,
        val connectedPeersCount: Int,
        val remotePeerId: String? = null
    )

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    var isWifiP2pEnabled = false
        private set

    var isDiscovering = false
        private set

    private val discoveredPeersList = CopyOnWriteArrayList<WifiP2pDevice>()
    private var currentConnectionInfo = WifiDirectConnectionInfo(
        isConnected = false,
        isGroupOwner = false,
        groupOwnerAddress = null,
        groupFormed = false,
        connectedPeersCount = 0
    )

    // TCP Sockets
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false
    private val clientSockets = ConcurrentHashMap<String, Socket>() // remote IP -> Socket
    private val socketWriters = ConcurrentHashMap<String, BufferedWriter>()
    private var outgoingClientSocket: Socket? = null
    private var outgoingWriter: BufferedWriter? = null

    // Handshake mapping: IP/Address -> DeviceId
    private val peerDeviceIds = ConcurrentHashMap<String, String>()

    var onHandshakeReceived: ((peerId: String, publicKey: String, remoteIp: String) -> Unit)? = null

    init {
        initializeChannel()
    }

    private fun initializeChannel() {
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi P2P Channel disconnected! Reinitializing...")
                initializeChannel()
            }
        } else {
            Log.w(TAG, "WifiP2pManager is not available on this device.")
        }
    }

    fun isSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && wifiP2pManager != null
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver for Wi-Fi Direct Events
    // -------------------------------------------------------------------------

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    isWifiP2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    Log.i(TAG, "Wi-Fi Direct state changed: isEnabled=$isWifiP2pEnabled")
                    mainHandler.post { onStateChanged(isWifiP2pEnabled) }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Wi-Fi Direct peers changed. Requesting peer list...")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        Log.i(TAG, "Wi-Fi P2P connected. Requesting connection info...")
                        requestConnectionInfo()
                    } else {
                        Log.i(TAG, "Wi-Fi P2P disconnected.")
                        handleDisconnection()
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d(TAG, "This device changed: name=${device?.deviceName}, status=${device?.status}")
                }
            }
        }
    }

    fun register() {
        if (isReceiverRegistered) return
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(p2pReceiver, intentFilter)
        }
        isReceiverRegistered = true
        Log.i(TAG, "WifiDirectManager receiver registered.")
    }

    fun unregister() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(p2pReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
        isReceiverRegistered = false
        stopSockets()
    }

    // -------------------------------------------------------------------------
    // Peer Discovery
    // -------------------------------------------------------------------------

    fun startDiscovery(onComplete: (Boolean, String?) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onComplete(false, "Wi-Fi Direct not initialized or unsupported.")
            return
        }

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                Log.i(TAG, "Wi-Fi Direct peer discovery started successfully.")
                onComplete(true, null)
            }

            override fun onFailure(reasonCode: Int) {
                isDiscovering = false
                val reason = getReasonString(reasonCode)
                Log.e(TAG, "Wi-Fi Direct peer discovery failed: $reason (code $reasonCode)")
                onComplete(false, reason)
            }
        })
    }

    fun stopDiscovery(onComplete: (Boolean, String?) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onComplete(false, "Wi-Fi Direct not initialized.")
            return
        }

        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = false
                Log.i(TAG, "Wi-Fi Direct peer discovery stopped.")
                onComplete(true, null)
            }

            override fun onFailure(reasonCode: Int) {
                val reason = getReasonString(reasonCode)
                Log.w(TAG, "Failed to stop Wi-Fi Direct peer discovery: $reason")
                onComplete(false, reason)
            }
        })
    }

    fun requestPeers() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.requestPeers(ch) { peers ->
            discoveredPeersList.clear()
            val list = mutableListOf<WifiDirectPeer>()
            for (device in peers.deviceList) {
                discoveredPeersList.add(device)
                list.add(
                    WifiDirectPeer(
                        deviceAddress = device.deviceAddress,
                        deviceName = device.deviceName ?: "Unknown Wi-Fi Peer",
                        status = device.status,
                        statusText = getDeviceStatusString(device.status),
                        isGroupOwner = device.isGroupOwner,
                        primaryDeviceType = device.primaryDeviceType ?: ""
                    )
                )
            }
            Log.d(TAG, "Discovered ${list.size} Wi-Fi Direct peers.")
            mainHandler.post { onPeersChanged(list) }
        }
    }

    fun getDiscoveredPeers(): List<WifiDirectPeer> {
        return discoveredPeersList.map { device ->
            WifiDirectPeer(
                deviceAddress = device.deviceAddress,
                deviceName = device.deviceName ?: "Unknown Wi-Fi Peer",
                status = device.status,
                statusText = getDeviceStatusString(device.status),
                isGroupOwner = device.isGroupOwner,
                primaryDeviceType = device.primaryDeviceType ?: ""
            )
        }
    }

    // -------------------------------------------------------------------------
    // Connect & Disconnect
    // -------------------------------------------------------------------------

    fun connect(deviceAddress: String, onComplete: (Boolean, String?) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onComplete(false, "Wi-Fi Direct not initialized.")
            return
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct connect initiated to $deviceAddress")
                onComplete(true, null)
            }

            override fun onFailure(reasonCode: Int) {
                val reason = getReasonString(reasonCode)
                Log.e(TAG, "Wi-Fi Direct connect failed: $reason (code $reasonCode)")
                onComplete(false, reason)
            }
        })
    }

    fun disconnect(onComplete: (Boolean, String?) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            stopSockets()
            handleDisconnection()
            onComplete(true, null)
            return
        }

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct group removed successfully.")
                stopSockets()
                handleDisconnection()
                onComplete(true, null)
            }

            override fun onFailure(reasonCode: Int) {
                val reason = getReasonString(reasonCode)
                Log.w(TAG, "Wi-Fi Direct removeGroup failed: $reason (attempting socket cleanup)")
                stopSockets()
                handleDisconnection()
                onComplete(false, reason)
            }
        })
    }

    // -------------------------------------------------------------------------
    // Connection Info & TCP Socket Management
    // -------------------------------------------------------------------------

    private fun requestConnectionInfo() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.requestConnectionInfo(ch) { info ->
            Log.i(TAG, "Connection info: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, GO=${info.groupOwnerAddress?.hostAddress}")
            if (info.groupFormed) {
                val goAddress = info.groupOwnerAddress?.hostAddress
                currentConnectionInfo = WifiDirectConnectionInfo(
                    isConnected = true,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = goAddress,
                    groupFormed = true,
                    connectedPeersCount = clientSockets.size + (if (outgoingClientSocket?.isConnected == true) 1 else 0)
                )
                mainHandler.post { onConnectionChanged(currentConnectionInfo) }

                if (info.isGroupOwner) {
                    startServerSocket()
                } else if (goAddress != null) {
                    startClientSocket(goAddress)
                }
            } else {
                handleDisconnection()
            }
        }
    }

    private fun handleDisconnection() {
        stopSockets()
        peerDeviceIds.clear()
        currentConnectionInfo = WifiDirectConnectionInfo(
            isConnected = false,
            isGroupOwner = false,
            groupOwnerAddress = null,
            groupFormed = false,
            connectedPeersCount = 0
        )
        mainHandler.post { onConnectionChanged(currentConnectionInfo) }
    }

    private fun startServerSocket() {
        if (isServerRunning) return
        isServerRunning = true
        executor.execute {
            try {
                serverSocket?.close()
                val server = ServerSocket(PORT)
                serverSocket = server
                Log.i(TAG, "Wi-Fi Direct Group Owner ServerSocket listening on port $PORT")

                while (isServerRunning && !server.isClosed) {
                    val clientSocket = server.accept()
                    val remoteIp = clientSocket.inetAddress.hostAddress ?: "unknown"
                    Log.i(TAG, "New incoming Wi-Fi Direct client connected from $remoteIp")
                    clientSockets[remoteIp] = clientSocket
                    val writer = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))
                    socketWriters[remoteIp] = writer

                    // Update connected count
                    notifyConnectionState()

                    // Send handshake immediately
                    sendHandshake(writer)

                    // Start reading from client in separate thread
                    executor.execute {
                        handleSocketInput(clientSocket, remoteIp)
                    }
                }
            } catch (e: Exception) {
                if (isServerRunning) {
                    Log.e(TAG, "ServerSocket error: ${e.message}", e)
                }
            } finally {
                isServerRunning = false
            }
        }
    }

    private fun startClientSocket(hostAddress: String) {
        executor.execute {
            var attempts = 0
            val maxAttempts = 5
            var connected = false

            while (attempts < maxAttempts && !connected) {
                attempts++
                try {
                    Log.i(TAG, "Connecting to Group Owner at $hostAddress:$PORT (Attempt $attempts/$maxAttempts)...")
                    val socket = Socket()
                    socket.connect(InetSocketAddress(hostAddress, PORT), SOCKET_TIMEOUT_MS)
                    outgoingClientSocket = socket
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                    outgoingWriter = writer
                    connected = true
                    Log.i(TAG, "Connected to Wi-Fi Direct Group Owner at $hostAddress:$PORT")

                    notifyConnectionState()

                    // Send handshake
                    sendHandshake(writer)

                    // Read from Group Owner
                    executor.execute {
                        handleSocketInput(socket, hostAddress)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Client socket connect attempt $attempts failed: ${e.message}")
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                }
            }

            if (!connected) {
                Log.e(TAG, "Failed to connect to Group Owner at $hostAddress after $maxAttempts attempts.")
            }
        }
    }

    private fun sendHandshake(writer: BufferedWriter) {
        executor.execute {
            try {
                val handshakeObj = JSONObject().apply {
                    put("type", "wifi_p2p_handshake")
                    put("senderId", localDeviceIdProvider())
                    put("senderPublicKey", localPublicKeyProvider())
                    put("timestamp", System.currentTimeMillis())
                }
                writer.write(handshakeObj.toString() + "\n")
                writer.flush()
                Log.i(TAG, "Sent Wi-Fi P2P handshake from ${localDeviceIdProvider()}")
            } catch (e: Exception) {
                Log.w(TAG, "Error sending handshake: ${e.message}")
            }
        }
    }

    private fun handleSocketInput(socket: Socket, remoteIp: String) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotEmpty()) {
                    processIncomingMessage(line, remoteIp)
                }
                line = reader.readLine()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Socket read terminated for $remoteIp: ${e.message}")
        } finally {
            closeSocket(socket, remoteIp)
        }
    }

    private fun processIncomingMessage(line: String, remoteIp: String) {
        try {
            val json = JSONObject(line)
            val type = json.optString("type")
            if (type == "wifi_p2p_handshake") {
                val peerId = json.optString("senderId")
                val pubKey = json.optString("senderPublicKey")
                if (peerId.isNotEmpty()) {
                    peerDeviceIds[remoteIp] = peerId
                    Log.i(TAG, "Received Wi-Fi P2P handshake from $peerId at $remoteIp")
                    notifyConnectionState()
                    mainHandler.post {
                        onHandshakeReceived?.invoke(peerId, pubKey, remoteIp)
                    }
                }
            }

            // Always forward incoming packet to the central engine
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            onPacketReceived(bytes, remoteIp, "wifi_direct")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming Wi-Fi P2P message: ${e.message}", e)
        }
    }

    private fun closeSocket(socket: Socket, remoteIp: String) {
        try {
            socket.close()
        } catch (_: Exception) {}
        clientSockets.remove(remoteIp)
        socketWriters.remove(remoteIp)
        if (outgoingClientSocket == socket) {
            outgoingClientSocket = null
            outgoingWriter = null
        }
        peerDeviceIds.remove(remoteIp)
        Log.i(TAG, "Closed socket for $remoteIp")
        notifyConnectionState()
    }

    private fun stopSockets() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for ((_, sock) in clientSockets) {
            try { sock.close() } catch (_: Exception) {}
        }
        clientSockets.clear()
        socketWriters.clear()

        try {
            outgoingClientSocket?.close()
        } catch (_: Exception) {}
        outgoingClientSocket = null
        outgoingWriter = null
    }

    private fun notifyConnectionState() {
        val totalCount = clientSockets.size + (if (outgoingClientSocket?.isConnected == true) 1 else 0)
        val remotePeerId = peerDeviceIds.values.firstOrNull()
        currentConnectionInfo = currentConnectionInfo.copy(
            isConnected = totalCount > 0 || currentConnectionInfo.groupFormed,
            connectedPeersCount = totalCount,
            remotePeerId = remotePeerId
        )
        mainHandler.post { onConnectionChanged(currentConnectionInfo) }
    }

    // -------------------------------------------------------------------------
    // Packet Transmission over Wi-Fi Direct
    // -------------------------------------------------------------------------

    fun sendPacket(bytes: ByteArray, targetAddress: String? = null, exceptAddress: String? = null): Boolean {
        if (!currentConnectionInfo.isConnected) {
            return false
        }

        val messageStr = try {
            String(bytes, StandardCharsets.UTF_8) + "\n"
        } catch (e: Exception) {
            return false
        }

        var sentSuccess = false

        // 1. If we are client with an outgoing connection to Group Owner
        val out = outgoingWriter
        val goAddress = currentConnectionInfo.groupOwnerAddress
        val goPeerId = if (goAddress != null) peerDeviceIds[goAddress] else null
        val isGoExcluded = exceptAddress != null && (exceptAddress == goAddress || (goPeerId != null && exceptAddress == goPeerId))
        val matchesGoTarget = targetAddress == null || targetAddress == goAddress || (goPeerId != null && targetAddress == goPeerId)

        if (out != null && outgoingClientSocket?.isConnected == true && !isGoExcluded && matchesGoTarget) {
            try {
                out.write(messageStr)
                out.flush()
                sentSuccess = true
                Log.d(TAG, "Sent message over Wi-Fi Direct to Group Owner ($goAddress)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to Group Owner: ${e.message}")
            }
        }

        // 2. If we are Group Owner with connected clients
        for ((ip, writer) in socketWriters) {
            val clientPeerId = peerDeviceIds[ip]
            val isClientExcluded = exceptAddress != null && (exceptAddress == ip || (clientPeerId != null && exceptAddress == clientPeerId))
            val matchesTarget = targetAddress == null || targetAddress == ip || (clientPeerId != null && targetAddress == clientPeerId)

            if (!isClientExcluded && matchesTarget) {
                try {
                    writer.write(messageStr)
                    writer.flush()
                    sentSuccess = true
                    Log.d(TAG, "Sent message over Wi-Fi Direct to client at $ip (peer: $clientPeerId)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to client $ip: ${e.message}")
                }
            }
        }

        return sentSuccess
    }

    fun getConnectedPeerDeviceIds(): Map<String, String> = HashMap(peerDeviceIds)
    fun getPeerIdForIp(ip: String): String? = peerDeviceIds[ip]
    fun getIpForPeerId(peerId: String): String? {
        for ((ip, id) in peerDeviceIds) {
            if (id == peerId) return ip
        }
        return null
    }

    fun hasConnectedPeers(): Boolean {
        return (outgoingClientSocket?.isConnected == true) || (clientSockets.isNotEmpty())
    }

    fun isPeerConnected(peerAddressOrId: String): Boolean {
        if (peerDeviceIds.containsValue(peerAddressOrId) || peerDeviceIds.containsKey(peerAddressOrId)) {
            return true
        }
        if (currentConnectionInfo.groupOwnerAddress == peerAddressOrId && outgoingClientSocket?.isConnected == true) {
            return true
        }
        return false
    }

    fun getConnectionInfo(): WifiDirectConnectionInfo {
        return currentConnectionInfo
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getReasonString(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported on this device"
            WifiP2pManager.ERROR -> "Internal P2P Error"
            WifiP2pManager.BUSY -> "P2P Framework is Busy"
            else -> "Unknown error ($reason)"
        }
    }

    private fun getDeviceStatusString(status: Int): String {
        return when (status) {
            WifiP2pDevice.CONNECTED -> "connected"
            WifiP2pDevice.INVITED -> "invited"
            WifiP2pDevice.FAILED -> "failed"
            WifiP2pDevice.AVAILABLE -> "available"
            WifiP2pDevice.UNAVAILABLE -> "unavailable"
            else -> "unknown"
        }
    }
}
