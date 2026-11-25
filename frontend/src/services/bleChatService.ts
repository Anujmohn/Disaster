import { Capacitor, registerPlugin, PluginListenerHandle } from '@capacitor/core';

export interface BleDevice {
  deviceId: string;
  deviceName: string;
  address: string;
  rssi: number;
  lastSeen: number;
  status?: 'discovered' | 'connecting' | 'connected' | 'failed' | 'disconnected';
  isConnected?: boolean;
  fingerprint?: string;
  isVerified?: boolean;
  keyChanged?: boolean;
}

export interface ConnectedPeer {
  address: string;
  deviceId: string;
  status: string;
  role: 'client' | 'server';
  fingerprint?: string;
  isVerified?: boolean;
  keyChanged?: boolean;
}

export interface PeerIdentity {
  deviceId: string;
  publicKey: string;
  fingerprint: string;
  isVerified: boolean;
  keyChanged: boolean;
  lastSeen: number;
}

export interface MeshParticipant {
  deviceId: string;
  displayName: string;
  fingerprint: string;
  nextHopDeviceId: string;
  hopCount: number;
  transport: 'ble' | 'wifi_direct' | string;
  lastSeen: number;
  status: 'direct' | 'mesh' | 'unreachable';
}

export interface BleConnectionState {
  state: 'disconnected' | 'connecting' | 'connected' | 'failed';
  address?: string;
  deviceId?: string;
  message?: string;
  totalConnected?: number;
  maxConnections?: number;
  relayedCount?: number;
}

export interface UnifiedNeighbor {
  deviceId: string;
  deviceName: string;
  bleAddress?: string;
  wifiAddress?: string;
  transport: 'ble' | 'wifi_direct' | 'hybrid' | 'none';
  isBleConnected: boolean;
  isWifiDirectConnected: boolean;
  isConnected: boolean;
  rssi: number;
  lastSeen: number;
  fingerprint: string;
  isVerified: boolean;
  keyChanged: boolean;
  status: string;
}

export interface BleMessage {
  id: string;
  text: string;
  senderId: string;
  senderAddress?: string;
  recipientId?: string;
  type?: string;
  priority?: 'normal' | 'emergency';
  emergencyType?: string;
  latitude?: number;
  longitude?: number;
  isSignatureValid?: boolean;
  hopCount?: number;
  hopLimit?: number;
  timestamp: number;
  isSelf?: boolean;
  isEncrypted?: boolean;
  transport?: 'ble' | 'wifi_direct' | 'hybrid';
  isVerified?: boolean;
  fingerprint?: string;
  deliveryStatus?: 'sending' | 'queued' | 'forwarding' | 'delivered' | 'expired' | 'failed' | 'relaying' | 'unconfirmed';
  ackHopCount?: number;
  ackSenderId?: string;
  authVerified?: boolean;
}

export interface BleMessageStatusEvent {
  messageId: string;
  status: 'queued' | 'forwarding' | 'delivered' | 'expired' | 'failed';
  hopCount?: number;
  timestamp: number;
}

export interface QueuedMessageInfo {
  id: string;
  senderId: string;
  recipientId: string;
  type: string;
  status: string;
  priority: string;
  hopCount: number;
  createdAt: number;
  expiresAt: number;
  retryCount: number;
  isOutgoing: boolean;
}

export interface BleAckEvent {
  originalMessageId: string;
  ackSenderId: string;
  hopCount: number;
  authVerified?: boolean;
  transport?: string;
  timestamp: number;
}

export interface BleRelayEvent {
  id: string;
  senderId: string;
  recipientId: string;
  hopCount: number;
  hopLimit: number;
  type: string;
  transport?: string;
  totalRelayedCount?: number;
}

export interface BleSecurityAlert {
  type: 'key_changed' | 'message_blocked_unverified' | 'tamper_detected';
  deviceId: string;
  fingerprint?: string;
  message: string;
}

export interface WifiDirectDevice {
  deviceAddress: string;
  deviceName: string;
  status: string;
  isGroupOwner: boolean;
  primaryDeviceType?: string;
}

export interface WifiDirectConnectionInfo {
  isConnected: boolean;
  isGroupOwner: boolean;
  groupOwnerAddress: string;
  groupFormed?: boolean;
  connectedPeersCount: number;
  remotePeerId?: string;
}

export interface WifiDirectAvailability {
  isSupported: boolean;
  isEnabled: boolean;
  isDiscovering: boolean;
  isConnected: boolean;
  isGroupOwner: boolean;
  groupOwnerAddress: string;
  connectedPeersCount: number;
  hasPermissions: boolean;
}

export interface BackgroundMeshState {
  isRunning: boolean;
  status: string;
  connectedCount: number;
  isBluetoothEnabled: boolean;
  hasPermissions: boolean;
  hasNotificationPermission?: boolean;
}

export interface MeshDiagnostics {
  deviceId: string;
  fingerprint: string;
  bleConnectedCount: number;
  wifiDirectConnected: boolean;
  wifiDirectGroupOwner: boolean;
  wifiDirectPeersCount: number;
  totalConnected: number;
  queuedMessageCount: number;
  queueSizeBytes: number;
  maxCapacity: number;
  totalRelayedCount: number;
  lastCommunicationTime: number;
  lastForwardingError?: string;
  foregroundServiceRunning: boolean;
  e2eeCipher: string;
}

export interface BleAvailability {
  isNative: boolean;
  hasBleHardware: boolean;
  isBluetoothEnabled: boolean;
  hasPermissions: boolean;
  blePermissionsPermanentlyDenied?: boolean;
  hasLocationPermissions?: boolean;
  locationPermissionsPermanentlyDenied?: boolean;
  isLocationServicesEnabled?: boolean;
  localDeviceId: string;
  fingerprint: string;
  publicKey: string;
  maxConnections: number;
  connectedCount: number;
  relayedCount: number;
}

export interface LocationPermissionStatus {
  granted: boolean;
  preciseGranted: boolean;
  permanentlyDenied: boolean;
  isLocationServicesEnabled: boolean;
}

export interface PermissionsChangedEvent {
  hasPermissions: boolean;
  blePermissionsPermanentlyDenied: boolean;
  hasLocationPermissions: boolean;
  locationPermissionsPermanentlyDenied: boolean;
  isBluetoothEnabled: boolean;
  isLocationServicesEnabled: boolean;
}

interface BleChatPluginInterface {
  getLocalDeviceId(): Promise<{ deviceId: string; fingerprint?: string; publicKey?: string }>;
  getDeviceIdentity(): Promise<{ deviceId: string; publicKey: string; fingerprint: string }>;
  getKnownPeers(): Promise<{ peers: PeerIdentity[] }>;
  getMeshParticipants(): Promise<{ participants: MeshParticipant[] }>;
  verifyPeerKey(options: { deviceId: string; verified?: boolean }): Promise<{ success: boolean; deviceId: string; isVerified: boolean }>;
  requestKeyExchange(options: { targetDeviceId: string }): Promise<{ success: boolean; targetDeviceId: string }>;
  checkAvailability(): Promise<{
    hasBleHardware: boolean;
    isBluetoothEnabled: boolean;
    hasPermissions: boolean;
    blePermissionsPermanentlyDenied?: boolean;
    hasLocationPermissions?: boolean;
    locationPermissionsPermanentlyDenied?: boolean;
    isLocationServicesEnabled?: boolean;
    localDeviceId: string;
    fingerprint?: string;
    publicKey?: string;
    maxConnections: number;
    connectedCount: number;
    relayedCount?: number;
  }>;
  requestBlePermissions(): Promise<{ granted: boolean; permanentlyDenied?: boolean }>;
  checkLocationPermission(): Promise<LocationPermissionStatus>;
  requestLocationPermission(): Promise<LocationPermissionStatus>;
  requestEnableBluetooth(): Promise<{ enabled: boolean }>;
  openAppSettings(): Promise<{ success: boolean }>;
  openBluetoothSettings(): Promise<{ success: boolean }>;
  startDiscovery(): Promise<{
    success: boolean;
    localDeviceId: string;
    fingerprint?: string;
    isScanning: boolean;
    isAdvertising: boolean;
    maxConnections: number;
    connectedCount: number;
    relayedCount?: number;
  }>;
  stopDiscovery(): Promise<{ success: boolean }>;
  getDiscoveredDevices(): Promise<{
    devices: BleDevice[];
    connectedCount: number;
    maxConnections: number;
    relayedCount?: number;
  }>;
  getConnectedPeers(): Promise<{
    connectedPeers: ConnectedPeer[];
    count: number;
    maxConnections: number;
    relayedCount?: number;
  }>;
  getUnifiedNeighbors(): Promise<{
    neighbors: UnifiedNeighbor[];
    connectedCount: number;
    maxConnections: number;
    relayedCount?: number;
  }>;
  connectToDevice(options: { address: string }): Promise<{ status: string; address: string }>;
  disconnectDevice(options: { address: string }): Promise<{ success: boolean; address: string }>;
  disconnectAll(): Promise<{ success: boolean }>;
  startBackgroundMesh(): Promise<{ success: boolean; isRunning: boolean; status: string; connectedCount: number }>;
  stopBackgroundMesh(): Promise<{ success: boolean; isRunning: boolean; status: string; connectedCount: number }>;
  getBackgroundMeshState(): Promise<BackgroundMeshState>;
  requestNotificationPermission(): Promise<{ granted: boolean }>;
  sendMessage(options: {
    text: string;
    recipientAddress?: string;
    recipientId?: string;
    type?: string;
    priority?: 'normal' | 'emergency';
    emergencyType?: string;
    latitude?: number;
    longitude?: number;
    hopLimit?: number;
    messageId?: string;
  }): Promise<{
    success: boolean;
    messageId: string;
    deliveredCount: number;
    text: string;
    isEncrypted?: boolean;
    type?: string;
    priority?: string;
    emergencyType?: string;
    latitude?: number;
    longitude?: number;
    transport?: 'ble' | 'wifi_direct';
    status?: string;
    wifiDirectActive?: boolean;
    hopLimit?: number;
    createdAt?: number;
  }>;
  getQueuedMessages(): Promise<{
    messages: QueuedMessageInfo[];
    count: number;
    pendingCount: number;
    deliveredCount: number;
    expiredCount: number;
    failedCount: number;
  }>;
  getMeshDiagnostics(): Promise<MeshDiagnostics>;
  checkWifiDirectAvailability(): Promise<WifiDirectAvailability>;
  startWifiDirectDiscovery(): Promise<{ success: boolean; isDiscovering: boolean }>;
  stopWifiDirectDiscovery(): Promise<{ success: boolean; error?: string }>;
  getWifiDirectPeers(): Promise<{ peers: WifiDirectDevice[]; count: number }>;
  connectWifiDirect(options: { deviceAddress: string }): Promise<{ success: boolean; deviceAddress: string }>;
  disconnectWifiDirect(): Promise<{ success: boolean; error?: string }>;
  getWifiDirectConnectionInfo(): Promise<WifiDirectConnectionInfo>;
  requestWifiDirectPermissions(): Promise<{ granted: boolean }>;
  addListener(
    eventName: 'bleDevicesUpdated',
    listenerFunc: (data: { devices: BleDevice[]; connectedCount: number; maxConnections: number; relayedCount?: number }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleConnectionState',
    listenerFunc: (data: BleConnectionState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleMessageReceived',
    listenerFunc: (data: BleMessage) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleAckReceived',
    listenerFunc: (data: BleAckEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleMessageRelayed',
    listenerFunc: (data: BleRelayEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleSecurityAlert',
    listenerFunc: (data: BleSecurityAlert) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'blePeerKeyExchanged',
    listenerFunc: (data: { peerId: string; fingerprint: string; isVerified: boolean; keyChanged?: boolean }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleBackgroundMeshStateChanged',
    listenerFunc: (data: BackgroundMeshState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'wifiDirectPeersChanged',
    listenerFunc: (data: { peers: WifiDirectDevice[] }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'wifiDirectConnectionChanged',
    listenerFunc: (data: WifiDirectConnectionInfo) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'wifiDirectStateChanged',
    listenerFunc: (data: { isEnabled: boolean }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'unifiedNeighborsChanged',
    listenerFunc: (data: { neighbors: UnifiedNeighbor[]; connectedCount: number; relayedCount?: number }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleMessageStatusChanged',
    listenerFunc: (data: BleMessageStatusEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bleAdapterStateChanged',
    listenerFunc: (data: { isBluetoothEnabled: boolean }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'permissionsChanged',
    listenerFunc: (data: PermissionsChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'meshParticipantsUpdated',
    listenerFunc: (data: { participants: MeshParticipant[] }) => void,
  ): Promise<PluginListenerHandle>;
}

const BleChat = registerPlugin<BleChatPluginInterface>('BleChat');

export const bleChatService = {
  isNative(): boolean {
    return Capacitor.isNativePlatform();
  },

  async checkAvailability(): Promise<BleAvailability> {
    if (!Capacitor.isNativePlatform()) {
      return {
        isNative: false,
        hasBleHardware: false,
        isBluetoothEnabled: false,
        hasPermissions: false,
        localDeviceId: 'WEB-CLIENT',
        fingerprint: '0000:1111:2222:3333:4444:5555:6666:7777',
        publicKey: '',
        maxConnections: 4,
        connectedCount: 0,
        relayedCount: 0,
      };
    }

    try {
      const res = await BleChat.checkAvailability();
      return {
        isNative: true,
        hasBleHardware: res.hasBleHardware,
        isBluetoothEnabled: res.isBluetoothEnabled,
        hasPermissions: res.hasPermissions,
        blePermissionsPermanentlyDenied: res.blePermissionsPermanentlyDenied ?? false,
        hasLocationPermissions: res.hasLocationPermissions ?? false,
        locationPermissionsPermanentlyDenied: res.locationPermissionsPermanentlyDenied ?? false,
        isLocationServicesEnabled: res.isLocationServicesEnabled ?? true,
        localDeviceId: res.localDeviceId,
        fingerprint: res.fingerprint || '',
        publicKey: res.publicKey || '',
        maxConnections: res.maxConnections ?? 4,
        connectedCount: res.connectedCount ?? 0,
        relayedCount: res.relayedCount ?? 0,
      };
    } catch (err) {
      console.warn('BleChat checkAvailability error:', err);
      return {
        isNative: true,
        hasBleHardware: false,
        isBluetoothEnabled: false,
        hasPermissions: false,
        localDeviceId: 'UNKNOWN',
        fingerprint: '',
        publicKey: '',
        maxConnections: 4,
        connectedCount: 0,
        relayedCount: 0,
      };
    }
  },

  async getDeviceIdentity(): Promise<{ deviceId: string; publicKey: string; fingerprint: string }> {
    if (!Capacitor.isNativePlatform()) {
      return {
        deviceId: 'WEB-CLIENT',
        publicKey: '',
        fingerprint: 'WEB-SIMULATED-FINGERPRINT',
      };
    }
    return await BleChat.getDeviceIdentity();
  },

  async getKnownPeers(): Promise<PeerIdentity[]> {
    if (!Capacitor.isNativePlatform()) return [];
    const res = await BleChat.getKnownPeers();
    return res.peers;
  },

  async getMeshParticipants(): Promise<MeshParticipant[]> {
    if (!Capacitor.isNativePlatform()) return [];
    try {
      const res = await BleChat.getMeshParticipants();
      return res.participants;
    } catch (err) {
      console.warn('BleChat getMeshParticipants error:', err);
      return [];
    }
  },

  onMeshParticipantsUpdated(
    callback: (data: { participants: MeshParticipant[] }) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('meshParticipantsUpdated', callback);
  },

  async verifyPeerKey(deviceId: string, verified = true): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    const res = await BleChat.verifyPeerKey({ deviceId, verified });
    return res.success;
  },

  async requestKeyExchange(targetDeviceId: string): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    const res = await BleChat.requestKeyExchange({ targetDeviceId });
    return res.success;
  },

  async requestPermissions(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return false;
    try {
      const res = await BleChat.requestBlePermissions();
      return res.granted;
    } catch (err) {
      console.warn('BleChat requestBlePermissions error:', err);
      return false;
    }
  },

  async requestPermissionsDetailed(): Promise<{ granted: boolean; permanentlyDenied: boolean }> {
    if (!Capacitor.isNativePlatform()) return { granted: false, permanentlyDenied: false };
    try {
      const res = await BleChat.requestBlePermissions();
      return {
        granted: res.granted,
        permanentlyDenied: res.permanentlyDenied ?? false,
      };
    } catch (err) {
      console.warn('BleChat requestBlePermissions error:', err);
      return { granted: false, permanentlyDenied: false };
    }
  },

  async checkLocationPermission(): Promise<LocationPermissionStatus> {
    if (!Capacitor.isNativePlatform()) {
      return { granted: true, preciseGranted: true, permanentlyDenied: false, isLocationServicesEnabled: true };
    }
    try {
      return await BleChat.checkLocationPermission();
    } catch (err) {
      console.warn('BleChat checkLocationPermission error:', err);
      return { granted: false, preciseGranted: false, permanentlyDenied: false, isLocationServicesEnabled: true };
    }
  },

  async requestLocationPermission(): Promise<LocationPermissionStatus> {
    if (!Capacitor.isNativePlatform()) {
      return { granted: true, preciseGranted: true, permanentlyDenied: false, isLocationServicesEnabled: true };
    }
    try {
      return await BleChat.requestLocationPermission();
    } catch (err) {
      console.warn('BleChat requestLocationPermission error:', err);
      return { granted: false, preciseGranted: false, permanentlyDenied: false, isLocationServicesEnabled: true };
    }
  },

  onPermissionsChanged(
    callback: (data: PermissionsChangedEvent) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('permissionsChanged', callback);
  },

  async requestEnableBluetooth(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    try {
      const res = await BleChat.requestEnableBluetooth();
      return res.enabled;
    } catch (err) {
      console.warn('BleChat requestEnableBluetooth error:', err);
      return false;
    }
  },

  async openAppSettings(): Promise<void> {
    if (!Capacitor.isNativePlatform()) return;
    try {
      await BleChat.openAppSettings();
    } catch (err) {
      console.warn('BleChat openAppSettings error:', err);
    }
  },

  async openBluetoothSettings(): Promise<void> {
    if (!Capacitor.isNativePlatform()) return;
    try {
      await BleChat.openBluetoothSettings();
    } catch (err) {
      console.warn('BleChat openBluetoothSettings error:', err);
    }
  },

  onAdapterStateChanged(
    callback: (data: { isBluetoothEnabled: boolean }) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleAdapterStateChanged', callback);
  },

  async startDiscovery(): Promise<{
    success: boolean;
    localDeviceId: string;
    maxConnections: number;
    connectedCount: number;
    relayedCount: number;
  }> {
    if (!Capacitor.isNativePlatform()) {
      throw new Error('Native Bluetooth LE is only supported on Android devices.');
    }
    const res = await BleChat.startDiscovery();
    return {
      success: res.success,
      localDeviceId: res.localDeviceId,
      maxConnections: res.maxConnections,
      connectedCount: res.connectedCount,
      relayedCount: res.relayedCount ?? 0,
    };
  },

  async stopDiscovery(): Promise<void> {
    if (!Capacitor.isNativePlatform()) return;
    await BleChat.stopDiscovery();
  },

  async getDiscoveredDevices(): Promise<BleDevice[]> {
    if (!Capacitor.isNativePlatform()) return [];
    const res = await BleChat.getDiscoveredDevices();
    return res.devices;
  },

  async getConnectedPeers(): Promise<ConnectedPeer[]> {
    if (!Capacitor.isNativePlatform()) return [];
    const res = await BleChat.getConnectedPeers();
    return res.connectedPeers;
  },

  async connectToDevice(address: string): Promise<void> {
    if (!Capacitor.isNativePlatform()) {
      throw new Error('Native Bluetooth connection requires Android application runtime.');
    }
    await BleChat.connectToDevice({ address });
  },

  async disconnectDevice(address: string): Promise<void> {
    if (!Capacitor.isNativePlatform()) return;
    await BleChat.disconnectDevice({ address });
  },

  async disconnectAll(): Promise<void> {
    if (!Capacitor.isNativePlatform()) return;
    await BleChat.disconnectAll();
  },

  async sendMessage(options: {
    text: string;
    recipientAddress?: string;
    recipientId?: string;
    type?: string;
    priority?: 'normal' | 'emergency';
    emergencyType?: string;
    latitude?: number;
    longitude?: number;
    hopLimit?: number;
    messageId?: string;
  }): Promise<{
    success: boolean;
    messageId: string;
    deliveredCount: number;
    text: string;
    isEncrypted?: boolean;
    type?: string;
    priority?: string;
    emergencyType?: string;
    latitude?: number;
    longitude?: number;
    transport?: 'ble' | 'wifi_direct';
    status?: string;
    wifiDirectActive?: boolean;
    hopLimit?: number;
    createdAt?: number;
  }> {
    if (!Capacitor.isNativePlatform()) {
      throw new Error('Native message transmission requires active BLE connection on Android.');
    }
    return await BleChat.sendMessage(options);
  },

  onDevicesUpdated(
    callback: (data: { devices: BleDevice[]; connectedCount: number; maxConnections: number; relayedCount?: number }) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleDevicesUpdated', callback);
  },

  onConnectionState(callback: (state: BleConnectionState) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleConnectionState', callback);
  },

  onMessageReceived(callback: (message: BleMessage) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleMessageReceived', callback);
  },

  onAckReceived(callback: (ack: BleAckEvent) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleAckReceived', callback);
  },

  onMessageRelayed(callback: (relay: BleRelayEvent) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleMessageRelayed', callback);
  },

  onSecurityAlert(callback: (alert: BleSecurityAlert) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleSecurityAlert', callback);
  },

  onPeerKeyExchanged(callback: (data: { peerId: string; fingerprint: string; isVerified: boolean; keyChanged?: boolean }) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('blePeerKeyExchanged', callback);
  },

  async startBackgroundMesh(): Promise<{ success: boolean; isRunning: boolean; status: string; connectedCount: number }> {
    if (!Capacitor.isNativePlatform()) {
      return { success: true, isRunning: true, status: 'Simulated Background Mesh', connectedCount: 0 };
    }
    return await BleChat.startBackgroundMesh();
  },

  async stopBackgroundMesh(): Promise<{ success: boolean; isRunning: boolean; status: string; connectedCount: number }> {
    if (!Capacitor.isNativePlatform()) {
      return { success: true, isRunning: false, status: 'Stopped', connectedCount: 0 };
    }
    return await BleChat.stopBackgroundMesh();
  },

  async getBackgroundMeshState(): Promise<BackgroundMeshState> {
    if (!Capacitor.isNativePlatform()) {
      return {
        isRunning: false,
        status: 'Web Mode (Foreground only)',
        connectedCount: 0,
        isBluetoothEnabled: false,
        hasPermissions: false,
        hasNotificationPermission: false,
      };
    }
    return await BleChat.getBackgroundMeshState();
  },

  async requestNotificationPermission(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    try {
      const res = await BleChat.requestNotificationPermission();
      return res.granted;
    } catch (err) {
      console.warn('BleChat requestNotificationPermission error:', err);
      return false;
    }
  },

  onBackgroundMeshStateChanged(callback: (state: BackgroundMeshState) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleBackgroundMeshStateChanged', callback);
  },

  // ---------------------------------------------------------------------------
  // Wi-Fi Direct (Wi-Fi P2P) High-Throughput Transport API
  // ---------------------------------------------------------------------------

  async checkWifiDirectAvailability(): Promise<WifiDirectAvailability> {
    if (!Capacitor.isNativePlatform()) {
      return {
        isSupported: false,
        isEnabled: false,
        isDiscovering: false,
        isConnected: false,
        isGroupOwner: false,
        groupOwnerAddress: '',
        connectedPeersCount: 0,
        hasPermissions: false,
      };
    }
    try {
      return await BleChat.checkWifiDirectAvailability();
    } catch (err) {
      console.warn('BleChat checkWifiDirectAvailability error:', err);
      return {
        isSupported: false,
        isEnabled: false,
        isDiscovering: false,
        isConnected: false,
        isGroupOwner: false,
        groupOwnerAddress: '',
        connectedPeersCount: 0,
        hasPermissions: false,
      };
    }
  },

  async requestWifiDirectPermissions(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return false;
    try {
      const res = await BleChat.requestWifiDirectPermissions();
      return res.granted;
    } catch (err) {
      console.warn('BleChat requestWifiDirectPermissions error:', err);
      return false;
    }
  },

  async startWifiDirectDiscovery(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return false;
    try {
      const res = await BleChat.startWifiDirectDiscovery();
      return res.success;
    } catch (err) {
      console.warn('BleChat startWifiDirectDiscovery error:', err);
      return false;
    }
  },

  async stopWifiDirectDiscovery(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    try {
      const res = await BleChat.stopWifiDirectDiscovery();
      return res.success;
    } catch (err) {
      console.warn('BleChat stopWifiDirectDiscovery error:', err);
      return false;
    }
  },

  async getWifiDirectPeers(): Promise<WifiDirectDevice[]> {
    if (!Capacitor.isNativePlatform()) return [];
    try {
      const res = await BleChat.getWifiDirectPeers();
      return res.peers;
    } catch (err) {
      console.warn('BleChat getWifiDirectPeers error:', err);
      return [];
    }
  },

  async connectWifiDirect(deviceAddress: string): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return false;
    try {
      const res = await BleChat.connectWifiDirect({ deviceAddress });
      return res.success;
    } catch (err) {
      console.warn('BleChat connectWifiDirect error:', err);
      return false;
    }
  },

  async disconnectWifiDirect(): Promise<boolean> {
    if (!Capacitor.isNativePlatform()) return true;
    try {
      const res = await BleChat.disconnectWifiDirect();
      return res.success;
    } catch (err) {
      console.warn('BleChat disconnectWifiDirect error:', err);
      return false;
    }
  },

  async getWifiDirectConnectionInfo(): Promise<WifiDirectConnectionInfo> {
    if (!Capacitor.isNativePlatform()) {
      return {
        isConnected: false,
        isGroupOwner: false,
        groupOwnerAddress: '',
        groupFormed: false,
        connectedPeersCount: 0,
      };
    }
    try {
      return await BleChat.getWifiDirectConnectionInfo();
    } catch (err) {
      console.warn('BleChat getWifiDirectConnectionInfo error:', err);
      return {
        isConnected: false,
        isGroupOwner: false,
        groupOwnerAddress: '',
        groupFormed: false,
        connectedPeersCount: 0,
      };
    }
  },

  onWifiDirectPeersChanged(callback: (data: { peers: WifiDirectDevice[] }) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('wifiDirectPeersChanged', callback);
  },

  onWifiDirectConnectionChanged(callback: (info: WifiDirectConnectionInfo) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('wifiDirectConnectionChanged', callback);
  },

  onWifiDirectStateChanged(callback: (data: { isEnabled: boolean }) => void): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('wifiDirectStateChanged', callback);
  },

  async getUnifiedNeighbors(): Promise<UnifiedNeighbor[]> {
    if (!Capacitor.isNativePlatform()) {
      return [
        {
          deviceId: 'MOCK-NODE-B1',
          deviceName: 'Phone B (Mesh Relay)',
          bleAddress: 'AA:BB:CC:DD:EE:01',
          wifiAddress: '192.168.49.2',
          transport: 'hybrid',
          isBleConnected: true,
          isWifiDirectConnected: true,
          isConnected: true,
          rssi: -58,
          lastSeen: Date.now(),
          fingerprint: '1111:2222:3333:4444:5555:6666:7777:8888',
          isVerified: true,
          keyChanged: false,
          status: 'connected',
        },
        {
          deviceId: 'MOCK-NODE-C2',
          deviceName: 'Phone C (Direct Wi-Fi)',
          wifiAddress: '192.168.49.3',
          transport: 'wifi_direct',
          isBleConnected: false,
          isWifiDirectConnected: true,
          isConnected: true,
          rssi: 0,
          lastSeen: Date.now(),
          fingerprint: '2222:3333:4444:5555:6666:7777:8888:9999',
          isVerified: false,
          keyChanged: false,
          status: 'connected',
        },
        {
          deviceId: 'MOCK-NODE-D3',
          deviceName: 'Phone D (BLE Only)',
          bleAddress: 'AA:BB:CC:DD:EE:03',
          transport: 'ble',
          isBleConnected: true,
          isWifiDirectConnected: false,
          isConnected: true,
          rssi: -72,
          lastSeen: Date.now(),
          fingerprint: '3333:4444:5555:6666:7777:8888:9999:0000',
          isVerified: true,
          keyChanged: false,
          status: 'connected',
        },
      ];
    }
    try {
      const res = await BleChat.getUnifiedNeighbors();
      return res.neighbors;
    } catch (err) {
      console.warn('BleChat getUnifiedNeighbors error:', err);
      return [];
    }
  },

  onUnifiedNeighborsChanged(
    callback: (data: { neighbors: UnifiedNeighbor[]; connectedCount: number; relayedCount?: number }) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('unifiedNeighborsChanged', callback);
  },

  async getQueuedMessages(): Promise<{
    messages: QueuedMessageInfo[];
    count: number;
    pendingCount: number;
    deliveredCount: number;
    expiredCount: number;
    failedCount: number;
  }> {
    if (!Capacitor.isNativePlatform()) {
      return { messages: [], count: 0, pendingCount: 0, deliveredCount: 0, expiredCount: 0, failedCount: 0 };
    }
    try {
      return await BleChat.getQueuedMessages();
    } catch (err) {
      console.warn('BleChat getQueuedMessages error:', err);
      return { messages: [], count: 0, pendingCount: 0, deliveredCount: 0, expiredCount: 0, failedCount: 0 };
    }
  },

  onMessageStatusChanged(
    callback: (data: BleMessageStatusEvent) => void,
  ): Promise<PluginListenerHandle> | null {
    if (!Capacitor.isNativePlatform()) return null;
    return BleChat.addListener('bleMessageStatusChanged', callback);
  },

  async getMeshDiagnostics(): Promise<MeshDiagnostics> {
    if (!Capacitor.isNativePlatform()) {
      return {
        deviceId: 'WEB-SIM-DIAG',
        fingerprint: '1111:2222:3333:4444:5555:6666:7777:8888',
        bleConnectedCount: 2,
        wifiDirectConnected: false,
        wifiDirectGroupOwner: false,
        wifiDirectPeersCount: 0,
        totalConnected: 2,
        queuedMessageCount: 0,
        queueSizeBytes: 0,
        maxCapacity: 1000,
        totalRelayedCount: 0,
        lastCommunicationTime: Date.now(),
        lastForwardingError: '',
        foregroundServiceRunning: false,
        e2eeCipher: 'AES-256-GCM+AAD',
      };
    }
    try {
      return await BleChat.getMeshDiagnostics();
    } catch (err) {
      console.warn('BleChat getMeshDiagnostics error:', err);
      return {
        deviceId: '',
        fingerprint: '',
        bleConnectedCount: 0,
        wifiDirectConnected: false,
        wifiDirectGroupOwner: false,
        wifiDirectPeersCount: 0,
        totalConnected: 0,
        queuedMessageCount: 0,
        queueSizeBytes: 0,
        maxCapacity: 1000,
        totalRelayedCount: 0,
        lastCommunicationTime: 0,
        lastForwardingError: String(err),
        foregroundServiceRunning: false,
        e2eeCipher: 'AES-256-GCM+AAD',
      };
    }
  },
};
