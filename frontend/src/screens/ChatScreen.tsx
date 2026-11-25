import React, { useState, useEffect, useRef } from 'react';
import {
  bleChatService,
  BleDevice,
  BleConnectionState,
  BleMessage,
  BleAvailability,
  ConnectedPeer,
  PeerIdentity,
  BleAckEvent,
  BleRelayEvent,
  BleSecurityAlert,
  BackgroundMeshState,
  WifiDirectDevice,
  WifiDirectConnectionInfo,
  WifiDirectAvailability,
  UnifiedNeighbor,
  BleMessageStatusEvent,
  MeshDiagnostics,
  MeshParticipant,
} from '../services/bleChatService';

const MAX_MESSAGE_LENGTH = 256;
const ACK_TIMEOUT_MS = 15000;

export default function ChatScreen(): React.JSX.Element {
  // Navigation & View State
  const [activeChannel, setActiveChannel] = useState<string>('broadcast');
  const [directSearch, setDirectSearch] = useState<string>('');
  const [meshParticipants, setMeshParticipants] = useState<MeshParticipant[]>([]);
  const [inputText, setInputText] = useState<string>('');
  const [isSending, setIsSending] = useState<boolean>(false);
  const [isDevicesSheetOpen, setIsDevicesSheetOpen] = useState<boolean>(false);
  const [isSosModalOpen, setIsSosModalOpen] = useState<boolean>(false);

  // Hardware & Network State
  const [availability, setAvailability] = useState<BleAvailability>({
    isNative: false,
    hasBleHardware: false,
    isBluetoothEnabled: false,
    hasPermissions: false,
    localDeviceId: 'INITIALIZING',
    fingerprint: '',
    publicKey: '',
    maxConnections: 4,
    connectedCount: 0,
    relayedCount: 0,
  });

  const [isDiscovering, setIsDiscovering] = useState<boolean>(false);
  const [discoveredDevices, setDiscoveredDevices] = useState<BleDevice[]>([]);
  const [connectedPeers, setConnectedPeers] = useState<ConnectedPeer[]>([]);
  const [unifiedNeighbors, setUnifiedNeighbors] = useState<UnifiedNeighbor[]>([]);
  const [knownPeers, setKnownPeers] = useState<PeerIdentity[]>([]);

  // Background Mesh Service State
  const [isBackgroundMeshActive, setIsBackgroundMeshActive] = useState<boolean>(false);
  const [backgroundMeshStatus, setBackgroundMeshStatus] = useState<string>('Background mesh disabled');

  // Wi-Fi Direct State
  const [wifiDirectAvail, setWifiDirectAvail] = useState<WifiDirectAvailability>({
    isSupported: false,
    isEnabled: false,
    isDiscovering: false,
    isConnected: false,
    isGroupOwner: false,
    groupOwnerAddress: '',
    connectedPeersCount: 0,
    hasPermissions: false,
  });
  const [wifiDirectPeers, setWifiDirectPeers] = useState<WifiDirectDevice[]>([]);
  const [wifiDirectConnInfo, setWifiDirectConnInfo] = useState<WifiDirectConnectionInfo>({
    isConnected: false,
    isGroupOwner: false,
    groupOwnerAddress: '',
    connectedPeersCount: 0,
  });

  // Diagnostics State
  const [meshDiagnostics, setMeshDiagnostics] = useState<MeshDiagnostics | null>(null);

  // Message Lists
  const [privateMessages, setPrivateMessages] = useState<BleMessage[]>([]);
  const [broadcastMessages, setBroadcastMessages] = useState<BleMessage[]>([]);

  // Emergency SOS State
  const [sosEmergencyType, setSosEmergencyType] = useState<string>('MEDICAL');
  const [sosNote, setSosNote] = useState<string>('');
  const [sosIncludeLocation, setSosIncludeLocation] = useState<boolean>(true);
  const [sosCurrentCoords, setSosCurrentCoords] = useState<{ latitude: number; longitude: number } | null>(null);

  // Notices & Alerts
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [securityAlert, setSecurityAlert] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const ackTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const lastBroadcastTimeRef = useRef<number>(0);

  // Auto-scroll messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [privateMessages, broadcastMessages, activeChannel]);

  // Initial Hardware & BLE setup
  useEffect(() => {
    let isMounted = true;

    async function initBle() {
      const avail = await bleChatService.checkAvailability();
      if (!isMounted) return;
      setAvailability(avail);

      const uNeighbors = await bleChatService.getUnifiedNeighbors();
      if (isMounted) setUnifiedNeighbors(uNeighbors);

      try {
        const diag = await bleChatService.getMeshDiagnostics();
        if (isMounted) setMeshDiagnostics(diag);
      } catch (_) {}

      if (avail.isNative) {
        const peers = await bleChatService.getConnectedPeers();
        const participants = await bleChatService.getMeshParticipants();
        const storedPeers = await bleChatService.getKnownPeers();
        const bgState = await bleChatService.getBackgroundMeshState();
        const wAvail = await bleChatService.checkWifiDirectAvailability();
        const wInfo = await bleChatService.getWifiDirectConnectionInfo();
        const wPeers = await bleChatService.getWifiDirectPeers();
        if (isMounted) {
          setConnectedPeers(peers);
          setMeshParticipants(participants);
          setKnownPeers(storedPeers);
          setWifiDirectAvail(wAvail);
          setWifiDirectConnInfo(wInfo);
          setWifiDirectPeers(wPeers);
          setIsBackgroundMeshActive(bgState.isRunning);
          setBackgroundMeshStatus(
            bgState.isRunning
              ? (bgState.connectedCount > 0 ? 'Mesh Active' : 'Searching for nearby devices...')
              : bgState.status || 'Background mesh disabled'
          );

          // Auto-start discovery if permissions and Bluetooth are already active
          if (avail.hasPermissions && avail.isBluetoothEnabled && !bgState.isRunning) {
            try {
              const discRes = await bleChatService.startDiscovery();
              if (discRes.success) setIsDiscovering(true);
            } catch (_) {}
          }
        }
      }
    }
    initBle();

    // Event: Discovered devices updated
    const devSub = bleChatService.onDevicesUpdated(async (data) => {
      if (!isMounted) return;
      setDiscoveredDevices(data.devices);
      setAvailability((prev) => ({
        ...prev,
        connectedCount: data.connectedCount,
        maxConnections: data.maxConnections,
        relayedCount: data.relayedCount ?? prev.relayedCount,
      }));
      const storedPeers = await bleChatService.getKnownPeers();
      if (isMounted) setKnownPeers(storedPeers);
    });

    // Event: Mesh Participants updated (multi-hop discovery)
    const participantsSub = bleChatService.onMeshParticipantsUpdated((data) => {
      if (!isMounted) return;
      setMeshParticipants(data.participants);
    });

    // Event: Connection state changed for a device
    const connSub = bleChatService.onConnectionState(async (state: BleConnectionState) => {
      if (!isMounted) return;
      const peers = await bleChatService.getConnectedPeers();
      const storedPeers = await bleChatService.getKnownPeers();
      const uNeighbors = await bleChatService.getUnifiedNeighbors();
      setConnectedPeers(peers);
      setKnownPeers(storedPeers);
      setUnifiedNeighbors(uNeighbors);

      if (state.state === 'connected') {
        setActionNotice(`Connected to peer ${state.deviceId || state.address}.`);
      } else if (state.state === 'disconnected') {
        setActionNotice(`Peer ${state.deviceId || state.address || ''} disconnected.`);
      }
    });

    // Event: Message received
    const msgSub = bleChatService.onMessageReceived((msg: BleMessage) => {
      if (!isMounted) return;
      if (msg.recipientId === 'BROADCAST' || msg.recipientId === 'ALL' || msg.type === 'broadcast' || msg.type === 'sos') {
        setBroadcastMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
        if (msg.type === 'sos' || msg.priority === 'emergency') {
          setActionNotice(`Emergency SOS received from ${msg.senderId}!`);
        }
      } else {
        setPrivateMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
      }
    });

    // Event: Delivery Acknowledgment (ACK)
    const ackSub = bleChatService.onAckReceived((ack: BleAckEvent) => {
      if (!isMounted) return;
      const timer = ackTimers.current.get(ack.originalMessageId);
      if (timer) {
        clearTimeout(timer);
        ackTimers.current.delete(ack.originalMessageId);
      }
      setPrivateMessages((prev) =>
        prev.map((m) =>
          m.id === ack.originalMessageId
            ? {
                ...m,
                deliveryStatus: 'delivered',
                ackHopCount: ack.hopCount,
                ackSenderId: ack.ackSenderId,
                authVerified: ack.authVerified ?? true,
              }
            : m,
        ),
      );
    });

    // Event: Message Status Changed (Store-and-forward)
    const statusSub = bleChatService.onMessageStatusChanged((evt: BleMessageStatusEvent) => {
      if (!isMounted) return;
      setPrivateMessages((prev) =>
        prev.map((m) => {
          if (m.id === evt.messageId) {
            return {
              ...m,
              deliveryStatus: evt.status as any,
              ackHopCount: evt.hopCount !== undefined ? evt.hopCount : m.ackHopCount,
            };
          }
          return m;
        }),
      );
    });

    // Event: Relay event
    const relaySub = bleChatService.onMessageRelayed((relay: BleRelayEvent) => {
      if (!isMounted) return;
      setAvailability((prev) => ({
        ...prev,
        relayedCount: relay.totalRelayedCount ?? prev.relayedCount + 1,
      }));
    });

    // Event: Security Alert
    const secSub = bleChatService.onSecurityAlert((alert: BleSecurityAlert) => {
      if (!isMounted) return;
      setSecurityAlert(alert.message);
    });

    // Event: Native Background Mesh Service state changed
    const bgSub = bleChatService.onBackgroundMeshStateChanged((bg: BackgroundMeshState) => {
      if (!isMounted) return;
      setIsBackgroundMeshActive(bg.isRunning);
      setBackgroundMeshStatus(
        bg.isRunning
          ? (bg.connectedCount > 0 ? 'Mesh Active' : 'Searching for nearby devices...')
          : bg.status || 'Service stopped'
      );
      if (bg.isRunning) setIsDiscovering(true);
    });

    // Event: Wi-Fi Direct connection changed
    const wConnSub = bleChatService.onWifiDirectConnectionChanged((info) => {
      if (!isMounted) return;
      setWifiDirectConnInfo(info);
      setWifiDirectAvail((prev) => ({
        ...prev,
        isConnected: info.isConnected,
        isGroupOwner: info.isGroupOwner,
        groupOwnerAddress: info.groupOwnerAddress,
        connectedPeersCount: info.connectedPeersCount,
      }));
    });

    // Event: Unified Neighbors
    const uNeighborsSub = bleChatService.onUnifiedNeighborsChanged(async (data) => {
      if (!isMounted) return;
      setUnifiedNeighbors(data.neighbors);
    });

    // Event: Bluetooth Adapter state changed in real-time
    const adapterSub = bleChatService.onAdapterStateChanged((data) => {
      if (!isMounted) return;
      setAvailability((prev) => ({
        ...prev,
        isBluetoothEnabled: data.isBluetoothEnabled,
      }));
      if (!data.isBluetoothEnabled) {
        setIsDiscovering(false);
      }
    });

    // Event: Native Android permissions / hardware state changed (e.g. returning from Settings)
    const permSub = bleChatService.onPermissionsChanged(async (data) => {
      if (!isMounted) return;
      setAvailability((prev) => ({
        ...prev,
        hasPermissions: data.hasPermissions,
        blePermissionsPermanentlyDenied: data.blePermissionsPermanentlyDenied,
        hasLocationPermissions: data.hasLocationPermissions,
        locationPermissionsPermanentlyDenied: data.locationPermissionsPermanentlyDenied,
        isBluetoothEnabled: data.isBluetoothEnabled,
        isLocationServicesEnabled: data.isLocationServicesEnabled,
      }));
      if (data.hasPermissions) {
        setActionNotice((prev) =>
          prev && prev.toLowerCase().includes('permission') ? null : prev
        );
        if (data.isBluetoothEnabled) {
          try {
            const discRes = await bleChatService.startDiscovery();
            if (discRes.success && isMounted) setIsDiscovering(true);
          } catch (_) {}
        }
      }
    });

    const handleVisibilityChange = async () => {
      if (!document.hidden && isMounted) {
        const updated = await bleChatService.checkAvailability();
        if (!isMounted) return;
        setAvailability(updated);
        if (updated.hasPermissions) {
          setActionNotice((prev) =>
            prev && prev.toLowerCase().includes('permission') ? null : prev
          );
          if (updated.isBluetoothEnabled) {
            try {
              const discRes = await bleChatService.startDiscovery();
              if (discRes.success && isMounted) setIsDiscovering(true);
            } catch (_) {}
          }
        }
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    // Periodic diagnostics refresh
    const diagInterval = setInterval(async () => {
      if (!isMounted) return;
      try {
        const diag = await bleChatService.getMeshDiagnostics();
        if (isMounted) setMeshDiagnostics(diag);
      } catch (_) {}
    }, 5000);

    return () => {
      isMounted = false;
      clearInterval(diagInterval);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      devSub?.then((h) => h.remove());
      connSub?.then((h) => h.remove());
      msgSub?.then((h) => h.remove());
      ackSub?.then((h) => h.remove());
      statusSub?.then((h) => h?.remove());
      relaySub?.then((h) => h.remove());
      secSub?.then((h) => h.remove());
      bgSub?.then((h) => h.remove());
      wConnSub?.then((h) => h?.remove());
      uNeighborsSub?.then((h) => h?.remove());
      adapterSub?.then((h) => h?.remove());
      permSub?.then((h) => h?.remove());
      participantsSub?.then((h) => h?.remove());
      for (const timer of ackTimers.current.values()) {
        clearTimeout(timer);
      }
      ackTimers.current.clear();
    };
  }, []);

  // Request Bluetooth runtime permissions
  const handleRequestPermissions = async () => {
    try {
      const result = await bleChatService.requestPermissionsDetailed();
      const updated = await bleChatService.checkAvailability();
      setAvailability({
        ...updated,
        blePermissionsPermanentlyDenied: result.permanentlyDenied || updated.blePermissionsPermanentlyDenied,
      });
      if (result.granted || updated.hasPermissions) {
        setActionNotice('Bluetooth permissions granted.');
        if (!updated.isBluetoothEnabled) {
          handleEnableBluetooth();
        } else {
          try {
            const discRes = await bleChatService.startDiscovery();
            if (discRes.success) setIsDiscovering(true);
          } catch (_) {}
        }
      } else if (result.permanentlyDenied) {
        setActionNotice('Nearby devices permission is disabled. Open App Settings to enable it.');
      } else {
        setActionNotice('Bluetooth permission denied. Tap Grant Permission to allow.');
      }
    } catch (err: unknown) {
      console.warn('handleRequestPermissions error:', err);
    }
  };

  // Request system Bluetooth enable dialog
  const handleEnableBluetooth = async () => {
    try {
      const enabled = await bleChatService.requestEnableBluetooth();
      const updated = await bleChatService.checkAvailability();
      setAvailability(updated);
      if (enabled) {
        setActionNotice('Bluetooth turned on.');
        if (updated.hasPermissions) {
          try {
            const discRes = await bleChatService.startDiscovery();
            if (discRes.success) setIsDiscovering(true);
          } catch (_) {}
        }
      }
    } catch (err: unknown) {
      console.warn('handleEnableBluetooth error:', err);
    }
  };

  // Toggle Discovery
  const handleToggleDiscovery = async () => {
    if (!availability.hasPermissions) {
      await handleRequestPermissions();
      return;
    }
    if (!availability.isBluetoothEnabled) {
      await handleEnableBluetooth();
      return;
    }

    if (isDiscovering) {
      await bleChatService.stopDiscovery();
      setIsDiscovering(false);
      setActionNotice('Discovery stopped.');
    } else {
      try {
        const res = await bleChatService.startDiscovery();
        if (res.success) {
          setIsDiscovering(true);
          setActionNotice('Searching for nearby devices...');
        }
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        setActionNotice(`Could not start discovery: ${msg}`);
      }
    }
  };

  // Connect to a peer
  const handleConnectDevice = async (address: string) => {
    setActionNotice(`Connecting to ${address}...`);
    try {
      await bleChatService.connectToDevice(address);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Connection failed: ${msg}`);
    }
  };

  // Disconnect from a peer
  const handleDisconnectDevice = async (address: string) => {
    try {
      await bleChatService.disconnectDevice(address);
      setActionNotice(`Disconnected from ${address}.`);
    } catch (err: unknown) {
      console.warn('handleDisconnectDevice error:', err);
    }
  };

  // Toggle Background Mesh Service
  const handleToggleBackgroundMesh = async () => {
    if (!availability.hasPermissions) {
      await handleRequestPermissions();
      return;
    }
    if (isBackgroundMeshActive) {
      try {
        const res = await bleChatService.stopBackgroundMesh();
        setIsBackgroundMeshActive(false);
        setBackgroundMeshStatus(res.status);
        setActionNotice('Background mesh service stopped.');
      } catch (err: unknown) {
        console.warn('stopBackgroundMesh error:', err);
      }
    } else {
      try {
        const res = await bleChatService.startBackgroundMesh();
        setIsBackgroundMeshActive(res.isRunning);
        setBackgroundMeshStatus(res.status);
        if (res.isRunning) setIsDiscovering(true);
        setActionNotice('Background mesh service active.');
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        setActionNotice(`Could not start background mesh: ${msg}`);
      }
    }
  };

  // Core Send Message Pipeline (Works Connected & Disconnected)
  const handleSend = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const text = inputText.trim();
    if (!text || isSending) return;
    if (text.length > MAX_MESSAGE_LENGTH) {
      setActionNotice(`Message exceeds maximum limit of ${MAX_MESSAGE_LENGTH} characters.`);
      return;
    }

    const isBroadcast = activeChannel === 'broadcast';
    const recipientId = isBroadcast ? 'BROADCAST' : activeChannel;
    const type = isBroadcast ? 'broadcast' : 'encrypted_chat';

    setIsSending(true);
    try {
      const res = await bleChatService.sendMessage({
        text,
        recipientId,
        type,
        priority: 'normal',
        hopLimit: 5,
      });

      if (res.success) {
        const sentMsg: BleMessage = {
          id: res.messageId,
          text,
          senderId: availability.localDeviceId,
          senderAddress: 'self',
          recipientId,
          type,
          hopCount: 0,
          hopLimit: 5,
          timestamp: Date.now(),
          isSelf: true,
          isEncrypted: res.isEncrypted ?? !isBroadcast,
          deliveryStatus: (res.status === 'queued' ? 'queued' : (isBroadcast ? 'relaying' : 'forwarding')) as any,
        };

        if (isBroadcast) {
          setBroadcastMessages((prev) => [...prev, sentMsg]);
        } else {
          setPrivateMessages((prev) => [...prev, sentMsg]);
        }

        // CLEAR INPUT ONLY AFTER MESSAGE IS ACCEPTED FOR SENDING OR QUEUED
        setInputText('');

        if (res.status === 'queued') {
          setActionNotice('Saved to offline queue (waiting for connection).');
        } else {
          setActionNotice(isBroadcast ? 'Broadcast transmitted across mesh.' : `Encrypted message sent to ${recipientId}.`);
        }

        // Set ACK timeout for targeted messages
        if (!isBroadcast) {
          const msgId = res.messageId;
          const timer = setTimeout(() => {
            setPrivateMessages((prev) =>
              prev.map((m) =>
                m.id === msgId && (m.deliveryStatus === 'forwarding' || m.deliveryStatus === 'relaying')
                  ? { ...m, deliveryStatus: 'unconfirmed' }
                  : m,
              ),
            );
          }, ACK_TIMEOUT_MS);
          ackTimers.current.set(msgId, timer);
        }
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Send failed: ${msg}`);
      // Preserve input on failure so user does not lose draft!
    } finally {
      setIsSending(false);
      // Keep input focused so mobile keyboard remains open
      setTimeout(() => {
        inputRef.current?.focus();
      }, 50);
    }
  };

  // Emergency SOS Broadcast
  const handleSendSos = async () => {
    if (isSending) return;
    const text = sosNote.trim() || `EMERGENCY SOS: ${sosEmergencyType}`;
    setIsSending(true);
    try {
      const res = await bleChatService.sendMessage({
        text,
        recipientId: 'BROADCAST',
        type: 'sos',
        priority: 'emergency',
        emergencyType: sosEmergencyType,
        latitude: sosIncludeLocation && sosCurrentCoords ? sosCurrentCoords.latitude : undefined,
        longitude: sosIncludeLocation && sosCurrentCoords ? sosCurrentCoords.longitude : undefined,
        hopLimit: 5,
      });

      if (res.success) {
        const sentMsg: BleMessage = {
          id: res.messageId,
          text,
          senderId: availability.localDeviceId,
          senderAddress: 'self',
          recipientId: 'BROADCAST',
          type: 'sos',
          priority: 'emergency',
          emergencyType: sosEmergencyType,
          latitude: sosIncludeLocation && sosCurrentCoords ? sosCurrentCoords.latitude : undefined,
          longitude: sosIncludeLocation && sosCurrentCoords ? sosCurrentCoords.longitude : undefined,
          hopCount: 0,
          hopLimit: 5,
          timestamp: Date.now(),
          isSelf: true,
          isEncrypted: false,
          deliveryStatus: (res.status === 'queued' ? 'queued' : 'relaying') as any,
        };
        setBroadcastMessages((prev) => [...prev, sentMsg]);
        setIsSosModalOpen(false);
        setSosNote('');
        setActionNotice('EMERGENCY SOS BROADCAST TRANSMITTED ACROSS MESH.');
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setActionNotice(`Failed to broadcast SOS: ${msg}`);
    } finally {
      setIsSending(false);
    }
  };

  // Connected peers calculation
  const totalConnected = connectedPeers.length + (wifiDirectConnInfo.isConnected ? 1 : 0);
  const isConnected = totalConnected > 0;

  // Active messages based on channel
  const displayMessages = activeChannel === 'broadcast'
    ? broadcastMessages
    : privateMessages.filter((m) => m.recipientId === activeChannel || m.senderId === activeChannel);

  return (
    <div className="modern-chat-container">
      {/* ------------------------------------------------------------- */}
      {/* 1. Header Bar                                                 */}
      {/* ------------------------------------------------------------- */}
      <header className="modern-chat-header">
        <div className="modern-chat-header-left">
          <div className="modern-chat-title-group">
            <h1 className="modern-chat-title">Offline Chat</h1>
            <div className="modern-chat-subtitle">
              <span className={`modern-status-dot ${isConnected ? 'connected' : (isDiscovering ? 'searching' : 'offline')}`} />
              <span>
                {isConnected
                  ? `${totalConnected} device${totalConnected > 1 ? 's' : ''} connected`
                  : (isDiscovering ? 'Searching for devices...' : 'Offline mesh')}
              </span>
              <span>·</span>
              <span>{availability.isBluetoothEnabled ? 'Bluetooth On' : 'Bluetooth Off'}</span>
            </div>
          </div>
        </div>

        <div className="modern-header-actions">
          <button
            type="button"
            className="modern-pill-btn"
            onClick={() => setIsDevicesSheetOpen(true)}
            aria-label="View nearby devices"
          >
            Nearby ({discoveredDevices.length})
          </button>
          <button
            type="button"
            className="modern-pill-btn sos-btn"
            onClick={() => setIsSosModalOpen(true)}
            aria-label="Open emergency SOS"
          >
            SOS
          </button>
        </div>
      </header>

      {/* ------------------------------------------------------------- */}
      {/* 2. Contextual Permission / Bluetooth Onboarding Banner        */}
      {/* ------------------------------------------------------------- */}
      {availability.isNative && !availability.hasPermissions && (
        <div className="modern-guide-banner warning">
          <span>
            {availability.blePermissionsPermanentlyDenied
              ? 'Nearby devices permission is disabled. Enable it in App Settings to discover nearby phones.'
              : 'Bluetooth permissions are required for offline mesh messaging.'}
          </span>
          {availability.blePermissionsPermanentlyDenied ? (
            <button
              type="button"
              className="modern-guide-btn"
              onClick={() => bleChatService.openAppSettings()}
            >
              Open App Settings
            </button>
          ) : (
            <button
              type="button"
              className="modern-guide-btn"
              onClick={handleRequestPermissions}
            >
              Grant Permission
            </button>
          )}
        </div>
      )}

      {availability.isNative && availability.hasPermissions && !availability.isBluetoothEnabled && (
        <div className="modern-guide-banner warning">
          <span>Bluetooth is turned off. Enable Bluetooth to discover nearby devices.</span>
          <button
            type="button"
            className="modern-guide-btn"
            onClick={handleEnableBluetooth}
          >
            Turn On
          </button>
        </div>
      )}

      {/* Informational Action Notice */}
      {actionNotice && (
        <div className="modern-guide-banner info">
          <span>{actionNotice}</span>
          <button
            type="button"
            className="modern-sheet-close-btn"
            onClick={() => setActionNotice(null)}
            aria-label="Dismiss notice"
          >
            ✕
          </button>
        </div>
      )}

      {/* Security Alert */}
      {securityAlert && (
        <div className="modern-guide-banner error">
          <span>{securityAlert}</span>
          <button
            type="button"
            className="modern-sheet-close-btn"
            onClick={() => setSecurityAlert(null)}
            aria-label="Dismiss alert"
          >
            ✕
          </button>
        </div>
      )}

      {/* ------------------------------------------------------------- */}
      {/* 3. Recipient Channel Selector Strip                           */}
      {/* ------------------------------------------------------------- */}
      <div className="modern-channel-strip">
        <button
          type="button"
          className={`modern-channel-pill ${activeChannel === 'broadcast' ? 'active' : ''}`}
          onClick={() => setActiveChannel('broadcast')}
        >
          <span>📢 Broadcast All</span>
        </button>

        <button
          type="button"
          className={`modern-channel-pill ${activeChannel === 'direct_list' ? 'active' : ''}`}
          onClick={() => setActiveChannel('direct_list')}
        >
          <span>🔒 Direct ({meshParticipants.filter((p) => p.status !== 'unreachable').length})</span>
        </button>

        {activeChannel !== 'broadcast' && activeChannel !== 'direct_list' && (
          <button
            type="button"
            className="modern-channel-pill active"
            onClick={() => setActiveChannel(activeChannel)}
          >
            <span>🔒 {activeChannel}</span>
            <span className="channel-pill-meta">(E2EE)</span>
          </button>
        )}
      </div>

      {/* ------------------------------------------------------------- */}
      {/* 4. Chat Messages Scroll Area / Direct Participants List       */}
      {/* ------------------------------------------------------------- */}
      {activeChannel === 'direct_list' ? (
        <div className="modern-messages-scroll">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px', gap: '12px' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: '#0F172A' }}>Mesh Participants</h2>
            {meshParticipants.length > 4 && (
              <input
                type="search"
                className="apk-direct-search"
                style={{ maxWidth: '240px' }}
                placeholder="Search participants..."
                value={directSearch}
                onChange={(e) => setDirectSearch(e.target.value)}
              />
            )}
          </div>

          {meshParticipants.length === 0 ? (
            <div className="modern-empty-state">
              <div className="modern-empty-icon">🔒</div>
              <div className="modern-empty-title">No Mesh Participants Discovered</div>
              <div className="modern-empty-subtitle">
                Connect to a nearby device to discover direct and multi-hop mesh participants.
              </div>
            </div>
          ) : (
            <div className="apk-participant-list">
              {meshParticipants
                .filter((p) => !directSearch.trim() || p.displayName.toLowerCase().includes(directSearch.toLowerCase()) || p.deviceId.toLowerCase().includes(directSearch.toLowerCase()))
                .map((p) => (
                  <button
                    key={p.deviceId}
                    type="button"
                    className="apk-participant-row"
                    onClick={() => setActiveChannel(p.deviceId)}
                  >
                    <div className="apk-participant-main">
                      <div className="apk-participant-name">
                        {p.displayName && p.displayName !== p.deviceId ? `${p.displayName} (${p.deviceId})` : p.deviceId}
                      </div>
                      <div className="apk-participant-sub">
                        {p.status === 'unreachable'
                          ? 'Currently unreachable'
                          : p.status === 'direct' || p.hopCount <= 1
                            ? 'Direct connection'
                            : `Via ${p.hopCount} devices`}
                      </div>
                    </div>
                    <span className={`apk-participant-badge ${p.status}`}>
                      {p.status === 'direct' ? 'Direct' : p.status === 'mesh' ? 'Mesh' : 'Unreachable'}
                    </span>
                  </button>
                ))}
            </div>
          )}
        </div>
      ) : (
      <div className="modern-messages-scroll">
        {displayMessages.length === 0 ? (
          <div className="modern-empty-state">
            <div className="modern-empty-icon">💬</div>
            <div className="modern-empty-title">
              {activeChannel === 'broadcast' ? 'No Broadcast Messages Yet' : `Private Chat with ${activeChannel}`}
            </div>
            <div className="modern-empty-subtitle">
              Type a message below. Messages are sent directly or stored in the offline queue if devices are not yet in range.
            </div>
          </div>
        ) : (
          displayMessages.map((msg) => {
            const isSelf = msg.isSelf;
            const isEmergency = msg.type === 'sos' || msg.priority === 'emergency';
            const timeStr = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

            return (
              <div
                key={msg.id}
                className={`modern-message-row ${isSelf ? 'sent' : 'received'}`}
              >
                {!isSelf && (
                  <span className="modern-sender-tag">
                    {msg.senderId}
                  </span>
                )}

                <div className={`modern-bubble ${isEmergency ? 'emergency' : ''}`}>
                  {isEmergency && (
                    <div className="modern-emergency-header">
                      <span>🚨 EMERGENCY SOS ({msg.emergencyType || 'URGENT'})</span>
                    </div>
                  )}

                  <div className="modern-message-body">{msg.text}</div>

                  <div className="modern-message-footer">
                    <span>{timeStr}</span>
                    {isSelf ? (
                      <span className="status-indicator">
                        {msg.deliveryStatus === 'delivered' ? '✓ Delivered' : (msg.deliveryStatus === 'queued' ? '⏳ Queued' : '↗ Sent')}
                      </span>
                    ) : (
                      <span>{msg.hopCount !== undefined ? (msg.hopCount === 0 ? 'Direct link' : `${msg.hopCount} hop(s)`) : ''}</span>
                    )}
                  </div>
                </div>
              </div>
            );
          })
        )}
        <div ref={messagesEndRef} />
      </div>
      )}

      {/* ------------------------------------------------------------- */}
      {/* 5. Fixed Bottom Message Composer (Always Active)              */}
      {/* ------------------------------------------------------------- */}
      {activeChannel !== 'direct_list' && (
      <footer className="modern-composer-bar">
        <form className="modern-composer-form" onSubmit={handleSend}>
          <div className="modern-input-wrapper">
            <input
              ref={inputRef}
              type="text"
              className="modern-composer-input"
              placeholder={
                activeChannel === 'broadcast'
                  ? 'Message everyone nearby... (offline mesh)'
                  : `Private encrypted message to ${activeChannel}...`
              }
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              disabled={isSending}
              maxLength={MAX_MESSAGE_LENGTH}
              autoComplete="off"
            />
            {inputText.length > 200 && (
              <span className="modern-char-counter">
                {MAX_MESSAGE_LENGTH - inputText.length}
              </span>
            )}
          </div>

          <button
            type="submit"
            className="modern-send-btn"
            disabled={!inputText.trim() || isSending}
            aria-label="Send message"
          >
            {isSending ? '...' : 'Send'}
          </button>
        </form>
      </footer>
      )}

      {/* ------------------------------------------------------------- */}
      {/* 6. Nearby Devices & Settings Slide-Over Sheet                 */}
      {/* ------------------------------------------------------------- */}
      {isDevicesSheetOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsDevicesSheetOpen(false)}>
          <div className="modern-sheet-content" onClick={(e) => e.stopPropagation()}>
            <div className="modern-sheet-header">
              <h2 className="modern-sheet-title">Nearby Devices & Mesh Status</h2>
              <button
                type="button"
                className="modern-sheet-close-btn"
                onClick={() => setIsDevicesSheetOpen(false)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            <div className="modern-sheet-body">
              {/* Discovery Controls */}
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <button
                  type="button"
                  className="modern-pill-btn active"
                  style={{ flex: 1, padding: '8px 14px' }}
                  onClick={handleToggleDiscovery}
                >
                  {isDiscovering ? 'Stop Discovery' : 'Scan for Devices'}
                </button>
                <button
                  type="button"
                  className={`modern-pill-btn ${isBackgroundMeshActive ? 'active' : ''}`}
                  style={{ flex: 1, padding: '8px 14px' }}
                  onClick={handleToggleBackgroundMesh}
                >
                  {isBackgroundMeshActive ? 'Background Mesh Active' : 'Enable Background'}
                </button>
              </div>

              {/* Discovered & Connected Devices */}
              <div>
                <div className="modern-sheet-section-title">
                  Discovered Devices ({discoveredDevices.length})
                </div>

                {discoveredDevices.length === 0 ? (
                  <div style={{ padding: '16px', textAlign: 'center', color: '#94A3B8', fontSize: '0.85rem' }}>
                    {isDiscovering ? 'Searching for nearby phones via Bluetooth...' : 'Discovery is idle. Tap Scan for Devices above.'}
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {discoveredDevices.map((dev) => {
                      const isDevConnected = connectedPeers.some((p) => p.address === dev.address);
                      return (
                        <div key={dev.address} className="modern-device-card">
                          <div className="modern-device-info">
                            <span className="modern-device-name">
                              {dev.deviceName || dev.deviceId || 'Nearby Phone'}
                            </span>
                            <span className="modern-device-meta">
                              <span>Bluetooth</span>
                              <span>·</span>
                              <span>RSSI: {dev.rssi} dBm</span>
                              {isDevConnected && (
                                <span style={{ color: '#16A34A', fontWeight: 600 }}>· Connected</span>
                              )}
                            </span>
                          </div>

                          <button
                            type="button"
                            className={`modern-device-action-btn ${isDevConnected ? 'disconnect' : 'connect'}`}
                            onClick={() => (isDevConnected ? handleDisconnectDevice(dev.address) : handleConnectDevice(dev.address))}
                          >
                            {isDevConnected ? 'Disconnect' : 'Connect'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Wi-Fi Direct Status */}
              <div>
                <div className="modern-sheet-section-title">
                  Wi-Fi Direct Status
                </div>
                <div style={{ fontSize: '0.82rem', color: '#475569', background: '#F8FAFC', padding: '10px 14px', borderRadius: '8px', border: '1px solid #E2E8F0' }}>
                  <div>Status: {wifiDirectConnInfo.isConnected ? (wifiDirectConnInfo.isGroupOwner ? 'Connected (Group Owner)' : 'Connected (Client)') : 'Disconnected'}</div>
                  <div>Peers: {wifiDirectConnInfo.connectedPeersCount} connected</div>
                </div>
              </div>

              {/* Collapsible Diagnostics */}
              <details style={{ marginTop: '8px', background: '#F8FAFC', padding: '8px 12px', borderRadius: '8px', border: '1px solid #E2E8F0', fontSize: '0.82rem' }}>
                <summary style={{ cursor: 'pointer', fontWeight: 600 }}>
                  Mesh Diagnostics
                </summary>
                <div style={{ marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '0.78rem', color: '#475569' }}>
                  <div><strong>Device ID:</strong> <code>{availability.localDeviceId}</code></div>
                  <div><strong>Fingerprint:</strong> <code>{availability.fingerprint || 'N/A'}</code></div>
                  <div><strong>Total Relayed:</strong> {availability.relayedCount} packets</div>
                  <div><strong>Queue:</strong> {meshDiagnostics?.queuedMessageCount ?? 0} messages</div>
                  <div><strong>Cipher:</strong> AES-256-GCM + ECDSA</div>
                </div>
              </details>
            </div>
          </div>
        </div>
      )}

      {/* ------------------------------------------------------------- */}
      {/* 7. Emergency SOS Modal                                        */}
      {/* ------------------------------------------------------------- */}
      {isSosModalOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsSosModalOpen(false)}>
          <div className="modern-sos-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="modern-sos-title">
              <span>🚨</span>
              <span>Broadcast Emergency SOS</span>
            </h2>

            <p style={{ fontSize: '0.84rem', color: '#64748B', lineHeight: 1.4, margin: 0 }}>
              This will immediately flood an unencrypted, digitally signed emergency announcement to all devices within 5 hops.
            </p>

            <label style={{ fontSize: '0.82rem', fontWeight: 600, color: '#334155' }}>
              Emergency Type:
              <select
                className="modern-sos-select"
                style={{ width: '100%', marginTop: '4px' }}
                value={sosEmergencyType}
                onChange={(e) => setSosEmergencyType(e.target.value)}
              >
                <option value="MEDICAL">Medical Emergency</option>
                <option value="TRAPPED">Trapped / Search & Rescue</option>
                <option value="FIRE">Fire Hazard</option>
                <option value="FLOOD">Flash Flood</option>
                <option value="EVACUATION">Evacuation Needed</option>
              </select>
            </label>

            <label style={{ fontSize: '0.82rem', fontWeight: 600, color: '#334155' }}>
              Details (Optional):
              <textarea
                className="modern-sos-textarea"
                style={{ width: '100%', marginTop: '4px' }}
                placeholder="Describe your situation, trapped count, or hazards..."
                value={sosNote}
                onChange={(e) => setSosNote(e.target.value)}
                maxLength={MAX_MESSAGE_LENGTH}
              />
            </label>

            <button
              type="button"
              className="modern-sos-confirm-btn"
              onClick={handleSendSos}
              disabled={isSending}
            >
              {isSending ? 'Transmitting...' : 'Broadcast SOS Now'}
            </button>

            <button
              type="button"
              className="modern-sos-cancel-btn"
              onClick={() => setIsSosModalOpen(false)}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
