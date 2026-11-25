import React, { useState, useEffect, useRef } from 'react';
import {
  bleChatService,
  BleDevice,
  BleConnectionState,
  BleMessage,
  BleAvailability,
  ConnectedPeer,
  BleAckEvent,
  BleSecurityAlert,
  BackgroundMeshState,
  WifiDirectConnectionInfo,
  MeshParticipant,
} from '../services/bleChatService';

const MAX_MESSAGE_LENGTH = 256;
const ACK_TIMEOUT_MS = 20000;
const DM_STORAGE_KEY = 'disaster_mesh_dm_history_v1';

interface MobileChatScreenProps {
  onBack?: () => void;
}

function loadSavedDirectMessages(): BleMessage[] {
  try {
    const raw = localStorage.getItem(DM_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function formatHopLabel(p: MeshParticipant): string {
  if (p.status === 'unreachable') return 'Currently unreachable';
  if (p.status === 'direct' || p.hopCount <= 1) return 'Direct connection';
  return `Via ${p.hopCount} devices`;
}

function formatDeliveryStatus(status?: BleMessage['deliveryStatus']): string {
  switch (status) {
    case 'sending':
      return 'Sending';
    case 'forwarding':
    case 'relaying':
      return 'Sent to next hop';
    case 'delivered':
      return 'Delivered';
    case 'queued':
      return 'Waiting for route';
    case 'failed':
    case 'expired':
    case 'unconfirmed':
      return 'Failed';
    default:
      return 'Sent to next hop';
  }
}

export default function MobileChatScreen({ onBack }: MobileChatScreenProps): React.JSX.Element {
  const [chatMode, setChatMode] = useState<'broadcast' | 'direct'>('broadcast');
  const [selectedPeerId, setSelectedPeerId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [inputText, setInputText] = useState<string>('');
  const [isSending, setIsSending] = useState<boolean>(false);
  const [isConnecting, setIsConnecting] = useState<boolean>(false);
  const [isDevicesSheetOpen, setIsDevicesSheetOpen] = useState<boolean>(false);
  const [isSosModalOpen, setIsSosModalOpen] = useState<boolean>(false);

  const [availability, setAvailability] = useState<BleAvailability>({
    isNative: true,
    hasBleHardware: true,
    isBluetoothEnabled: false,
    hasPermissions: false,
    blePermissionsPermanentlyDenied: false,
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
  const [meshParticipants, setMeshParticipants] = useState<MeshParticipant[]>([]);
  const [isBackgroundMeshActive, setIsBackgroundMeshActive] = useState<boolean>(false);
  const [wifiDirectConnInfo, setWifiDirectConnInfo] = useState<WifiDirectConnectionInfo>({
    isConnected: false,
    isGroupOwner: false,
    groupOwnerAddress: '',
    connectedPeersCount: 0,
  });

  const [privateMessages, setPrivateMessages] = useState<BleMessage[]>(loadSavedDirectMessages);
  const [broadcastMessages, setBroadcastMessages] = useState<BleMessage[]>([]);
  const [sosEmergencyType, setSosEmergencyType] = useState<string>('MEDICAL');
  const [sosNote, setSosNote] = useState<string>('');
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [securityAlert, setSecurityAlert] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const ackTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    try {
      localStorage.setItem(DM_STORAGE_KEY, JSON.stringify(privateMessages.slice(-200)));
    } catch (_) {}
  }, [privateMessages]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [privateMessages, broadcastMessages, chatMode, selectedPeerId]);

  useEffect(() => {
    let isMounted = true;

    async function refreshAll() {
      const avail = await bleChatService.checkAvailability();
      if (!isMounted) return;
      setAvailability(avail);

      if (avail.isNative) {
        const [peers, participants, bgState, wInfo] = await Promise.all([
          bleChatService.getConnectedPeers(),
          bleChatService.getMeshParticipants(),
          bleChatService.getBackgroundMeshState(),
          bleChatService.getWifiDirectConnectionInfo(),
        ]);
        if (!isMounted) return;
        setConnectedPeers(peers);
        setMeshParticipants(participants);
        setWifiDirectConnInfo(wInfo);
        setIsBackgroundMeshActive(bgState.isRunning);

        if (avail.hasPermissions && avail.isBluetoothEnabled && !bgState.isRunning) {
          try {
            const discRes = await bleChatService.startDiscovery();
            if (discRes.success && isMounted) setIsDiscovering(true);
          } catch (_) {}
        }
      }
    }

    refreshAll();

    const devSub = bleChatService.onDevicesUpdated((data) => {
      if (!isMounted) return;
      setDiscoveredDevices(data.devices);
      setAvailability((prev) => ({
        ...prev,
        connectedCount: data.connectedCount,
        maxConnections: data.maxConnections,
        relayedCount: data.relayedCount ?? prev.relayedCount,
      }));
    });

    const participantsSub = bleChatService.onMeshParticipantsUpdated((data) => {
      if (!isMounted) return;
      setMeshParticipants(data.participants);
    });

    const connSub = bleChatService.onConnectionState(async (state: BleConnectionState) => {
      if (!isMounted) return;
      setIsConnecting(state.state === 'connecting');
      const [peers, participants] = await Promise.all([
        bleChatService.getConnectedPeers(),
        bleChatService.getMeshParticipants(),
      ]);
      if (!isMounted) return;
      setConnectedPeers(peers);
      setMeshParticipants(participants);
      if (state.state === 'failed') {
        setStatusMessage(state.message || 'Connection failed');
      }
    });

    const msgSub = bleChatService.onMessageReceived((msg: BleMessage) => {
      if (!isMounted) return;
      if (msg.recipientId === 'BROADCAST' || msg.recipientId === 'ALL' || msg.type === 'broadcast' || msg.type === 'sos') {
        setBroadcastMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
      } else {
        setPrivateMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
      }
    });

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
            ? { ...m, deliveryStatus: 'delivered', ackHopCount: ack.hopCount }
            : m,
        ),
      );
    });

    const statusSub = bleChatService.onMessageStatusChanged((evt) => {
      if (!isMounted) return;
      setPrivateMessages((prev) =>
        prev.map((m) => (m.id === evt.messageId ? { ...m, deliveryStatus: evt.status } : m)),
      );
    });

    const secSub = bleChatService.onSecurityAlert((alert: BleSecurityAlert) => {
      if (!isMounted) return;
      setSecurityAlert(alert.message);
    });

    const bgSub = bleChatService.onBackgroundMeshStateChanged((bg: BackgroundMeshState) => {
      if (!isMounted) return;
      setIsBackgroundMeshActive(bg.isRunning);
      if (bg.isRunning) setIsDiscovering(true);
    });

    const wConnSub = bleChatService.onWifiDirectConnectionChanged((info) => {
      if (!isMounted) return;
      setWifiDirectConnInfo(info);
    });

    const adapterSub = bleChatService.onAdapterStateChanged((data) => {
      if (!isMounted) return;
      setAvailability((prev) => ({ ...prev, isBluetoothEnabled: data.isBluetoothEnabled }));
      if (!data.isBluetoothEnabled) setIsDiscovering(false);
    });

    const permSub = bleChatService.onPermissionsChanged(async (data) => {
      if (!isMounted) return;
      setAvailability((prev) => ({
        ...prev,
        hasPermissions: data.hasPermissions,
        blePermissionsPermanentlyDenied: data.blePermissionsPermanentlyDenied,
        isBluetoothEnabled: data.isBluetoothEnabled,
      }));
      if (data.hasPermissions) {
        setStatusMessage(null);
        if (data.isBluetoothEnabled) {
          try {
            const discRes = await bleChatService.startDiscovery();
            if (discRes.success && isMounted) setIsDiscovering(true);
          } catch (_) {}
        }
      }
    });

    const onVisibility = () => {
      if (!document.hidden && isMounted) {
        refreshAll();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      isMounted = false;
      document.removeEventListener('visibilitychange', onVisibility);
      devSub?.then((h) => h.remove());
      participantsSub?.then((h) => h.remove());
      connSub?.then((h) => h.remove());
      msgSub?.then((h) => h.remove());
      ackSub?.then((h) => h.remove());
      statusSub?.then((h) => h?.remove());
      secSub?.then((h) => h.remove());
      bgSub?.then((h) => h.remove());
      wConnSub?.then((h) => h?.remove());
      adapterSub?.then((h) => h?.remove());
      permSub?.then((h) => h?.remove());
      for (const timer of ackTimers.current.values()) clearTimeout(timer);
      ackTimers.current.clear();
    };
  }, []);

  const handleRequestPermissions = async () => {
    const res = await bleChatService.requestPermissionsDetailed();
    const updated = await bleChatService.checkAvailability();
    setAvailability({
      ...updated,
      blePermissionsPermanentlyDenied: res.permanentlyDenied || updated.blePermissionsPermanentlyDenied,
    });
    if (res.granted || updated.hasPermissions) {
      setStatusMessage(null);
      if (!updated.isBluetoothEnabled) {
        await handleEnableBluetooth();
      } else {
        try {
          const discRes = await bleChatService.startDiscovery();
          if (discRes.success) setIsDiscovering(true);
        } catch (_) {}
      }
    }
  };

  const handleEnableBluetooth = async () => {
    const enabled = await bleChatService.requestEnableBluetooth();
    const updated = await bleChatService.checkAvailability();
    setAvailability(updated);
    if (enabled && updated.hasPermissions) {
      try {
        const discRes = await bleChatService.startDiscovery();
        if (discRes.success) setIsDiscovering(true);
      } catch (_) {}
    }
  };

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
    } else {
      try {
        const res = await bleChatService.startDiscovery();
        if (res.success) setIsDiscovering(true);
      } catch (err: unknown) {
        setStatusMessage(err instanceof Error ? err.message : String(err));
      }
    }
  };

  const handleConnectDevice = async (address: string) => {
    setIsConnecting(true);
    try {
      await bleChatService.connectToDevice(address);
    } catch (err: unknown) {
      setIsConnecting(false);
      setStatusMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleDisconnectDevice = async (address: string) => {
    try {
      await bleChatService.disconnectDevice(address);
    } catch (_) {}
  };

  const handleToggleBackgroundMesh = async () => {
    if (!availability.hasPermissions) {
      await handleRequestPermissions();
      return;
    }
    if (isBackgroundMeshActive) {
      await bleChatService.stopBackgroundMesh();
      setIsBackgroundMeshActive(false);
    } else {
      try {
        const res = await bleChatService.startBackgroundMesh();
        setIsBackgroundMeshActive(res.isRunning);
        if (res.isRunning) setIsDiscovering(true);
      } catch (err: unknown) {
        setStatusMessage(err instanceof Error ? err.message : String(err));
      }
    }
  };

  const handleSend = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const text = inputText.trim();
    if (!text || isSending) return;

    const isBroadcast = chatMode === 'broadcast';
    if (!isBroadcast && !selectedPeerId) return;

    const recipientId = isBroadcast ? 'BROADCAST' : selectedPeerId!;
    const type = isBroadcast ? 'broadcast' : 'encrypted_chat';

    setIsSending(true);
    setStatusMessage(null);
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
          deliveryStatus: (res.status === 'queued' ? 'queued' : (isBroadcast ? 'relaying' : 'forwarding')) as BleMessage['deliveryStatus'],
        };

        if (isBroadcast) {
          setBroadcastMessages((prev) => [...prev, sentMsg]);
        } else {
          setPrivateMessages((prev) => [...prev, sentMsg]);
          const msgId = res.messageId;
          const timer = setTimeout(() => {
            setPrivateMessages((prev) =>
              prev.map((m) =>
                m.id === msgId && (m.deliveryStatus === 'forwarding' || m.deliveryStatus === 'relaying')
                  ? { ...m, deliveryStatus: 'failed' }
                  : m,
              ),
            );
          }, ACK_TIMEOUT_MS);
          ackTimers.current.set(msgId, timer);
        }
        setInputText('');
      }
    } catch (err: unknown) {
      setStatusMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setIsSending(false);
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  };

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
        hopLimit: 5,
      });
      if (res.success) {
        setBroadcastMessages((prev) => [
          ...prev,
          {
            id: res.messageId,
            text,
            senderId: availability.localDeviceId,
            recipientId: 'BROADCAST',
            type: 'sos',
            priority: 'emergency',
            emergencyType: sosEmergencyType,
            timestamp: Date.now(),
            isSelf: true,
            deliveryStatus: res.status === 'queued' ? 'queued' : 'relaying',
          },
        ]);
        setIsSosModalOpen(false);
        setSosNote('');
      }
    } catch (err: unknown) {
      setStatusMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setIsSending(false);
    }
  };

  const totalConnected = connectedPeers.length + (wifiDirectConnInfo.isConnected ? 1 : 0);
  const selectedParticipant = selectedPeerId
    ? meshParticipants.find((p) => p.deviceId === selectedPeerId)
    : undefined;

  const connectionLabel = !availability.hasPermissions
    ? 'Permission needed'
    : !availability.isBluetoothEnabled
      ? 'Bluetooth off'
      : isConnecting
        ? 'Connecting...'
        : totalConnected > 0
          ? `${totalConnected} connected`
          : isDiscovering
            ? 'Searching nearby'
            : 'Disconnected';

  const statusDotClass =
    totalConnected > 0 ? 'connected' : isDiscovering || isConnecting ? 'searching' : 'offline';

  const filteredParticipants = meshParticipants.filter((p) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return p.displayName.toLowerCase().includes(q) || p.deviceId.toLowerCase().includes(q);
  });

  const isDirectListView = chatMode === 'direct' && !selectedPeerId;
  const displayMessages =
    chatMode === 'broadcast'
      ? broadcastMessages
      : privateMessages.filter((m) => m.recipientId === selectedPeerId || m.senderId === selectedPeerId);

  const handleHeaderBack = () => {
    if (chatMode === 'direct' && selectedPeerId) {
      setSelectedPeerId(null);
      return;
    }
    onBack?.();
  };

  return (
    <div className="apk-chat-container">
      {/* Header */}
      <header className="apk-chat-header">
        <div className="apk-chat-header-left">
          {(onBack || (chatMode === 'direct' && selectedPeerId)) && (
            <button
              type="button"
              className="apk-back-btn"
              onClick={handleHeaderBack}
              aria-label="Back"
            >
              ←
            </button>
          )}
          <div className="apk-chat-title-wrap">
            <h1 className="apk-chat-title">
              {chatMode === 'direct' && selectedPeerId
                ? selectedParticipant?.displayName || selectedPeerId
                : 'Offline Chat'}
            </h1>
            <div className="apk-chat-status">
              <span className={`modern-status-dot ${statusDotClass}`} />
              <span>
                {chatMode === 'direct' && selectedPeerId
                  ? `${selectedParticipant ? formatHopLabel(selectedParticipant) : 'Mesh'} · Encrypted`
                  : connectionLabel}
              </span>
            </div>
          </div>
        </div>

        <div className="apk-chat-header-actions">
          <button
            type="button"
            className="apk-header-btn"
            onClick={() => setIsDevicesSheetOpen(true)}
          >
            Nearby ({discoveredDevices.length})
          </button>
          <button
            type="button"
            className="apk-header-btn sos"
            onClick={() => setIsSosModalOpen(true)}
          >
            SOS
          </button>
        </div>
      </header>

      {/* Permission / Bluetooth Banner */}
      {!availability.hasPermissions && (
        <div className="apk-banner warning">
          <span className="apk-banner-text">
            {availability.blePermissionsPermanentlyDenied
              ? 'Nearby devices permission is disabled. Open Settings to allow Bluetooth mesh.'
              : 'Nearby devices permission is required to find and message phones offline.'}
          </span>
          <button
            type="button"
            className="apk-banner-btn"
            onClick={
              availability.blePermissionsPermanentlyDenied
                ? () => bleChatService.openAppSettings()
                : handleRequestPermissions
            }
          >
            {availability.blePermissionsPermanentlyDenied ? 'Open Settings' : 'Grant Permission'}
          </button>
        </div>
      )}

      {availability.hasPermissions && !availability.isBluetoothEnabled && (
        <div className="apk-banner warning">
          <span className="apk-banner-text">Bluetooth is off. Turn on Bluetooth to connect nearby.</span>
          <button type="button" className="apk-banner-btn" onClick={handleEnableBluetooth}>
            Turn On
          </button>
        </div>
      )}

      {(statusMessage || securityAlert) && (
        <div className="apk-banner error">
          <span className="apk-banner-text">{securityAlert || statusMessage}</span>
          <button
            type="button"
            className="apk-banner-dismiss"
            onClick={() => {
              setStatusMessage(null);
              setSecurityAlert(null);
            }}
            aria-label="Dismiss"
          >
            ✕
          </button>
        </div>
      )}

      {/* Mode Switcher: [Broadcast All] [Direct] */}
      <div className="apk-channel-strip">
        <button
          type="button"
          className={`apk-channel-pill ${chatMode === 'broadcast' ? 'active' : ''}`}
          onClick={() => {
            setChatMode('broadcast');
            setSelectedPeerId(null);
          }}
        >
          Broadcast All
        </button>
        <button
          type="button"
          className={`apk-channel-pill ${chatMode === 'direct' ? 'active' : ''}`}
          onClick={() => setChatMode('direct')}
        >
          Direct ({meshParticipants.filter((p) => p.status !== 'unreachable').length})
        </button>
      </div>

      {/* Main Content Area: Direct Participant Directory OR Conversation */}
      {isDirectListView ? (
        <div className="apk-messages-scroll">
          <div className="apk-direct-header">
            <h2 className="apk-direct-title">Mesh Participants</h2>
            {meshParticipants.length > 4 && (
              <input
                type="search"
                className="apk-direct-search"
                placeholder="Search participants..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            )}
          </div>

          {filteredParticipants.length === 0 ? (
            <div className="apk-empty-state">
              <div className="apk-empty-title">No Mesh Participants Discovered</div>
              <div className="apk-empty-subtitle">
                Connect to a nearby phone in Nearby. Remote participants reachable through the mesh will appear here automatically.
              </div>
            </div>
          ) : (
            <div className="apk-participant-list">
              {filteredParticipants.map((p) => (
                <button
                  key={p.deviceId}
                  type="button"
                  className="apk-participant-row"
                  onClick={() => setSelectedPeerId(p.deviceId)}
                >
                  <div className="apk-participant-main">
                    <div className="apk-participant-name">
                      {p.displayName && p.displayName !== p.deviceId
                        ? `${p.displayName} (${p.deviceId})`
                        : p.deviceId}
                    </div>
                    <div className="apk-participant-sub">{formatHopLabel(p)}</div>
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
        <>
          <div className="apk-messages-scroll">
            {displayMessages.length === 0 ? (
              <div className="apk-empty-state">
                <div className="apk-empty-title">
                  {chatMode === 'broadcast'
                    ? 'Offline Mesh Broadcast'
                    : `Encrypted Chat with ${selectedParticipant?.displayName || selectedPeerId}`}
                </div>
                <div className="apk-empty-subtitle">
                  {chatMode === 'broadcast'
                    ? 'Messages send to all reachable phones across the mesh.'
                    : 'Messages are encrypted end-to-end and forwarded across intermediate devices.'}
                </div>
              </div>
            ) : (
              displayMessages.map((msg) => {
                const isSelf = msg.isSelf;
                const isEmergency = msg.type === 'sos' || msg.priority === 'emergency';
                const timeStr = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

                return (
                  <div key={msg.id} className={`apk-msg-row ${isSelf ? 'sent' : 'received'}`}>
                    {!isSelf && <span className="apk-msg-sender">{msg.senderId}</span>}
                    <div className={`apk-msg-bubble ${isEmergency ? 'emergency' : ''}`}>
                      {isEmergency && (
                        <div className="apk-msg-sos-tag">SOS · {msg.emergencyType || 'EMERGENCY'}</div>
                      )}
                      <div className="apk-msg-text">{msg.text}</div>
                      <div className="apk-msg-meta">
                        <span>{timeStr}</span>
                        {isSelf && <span>{formatDeliveryStatus(msg.deliveryStatus)}</span>}
                      </div>
                    </div>
                  </div>
                );
              })
            )}
            <div ref={messagesEndRef} />
          </div>

          <footer className="apk-composer-bar">
            <form className="apk-composer-form" onSubmit={handleSend}>
              <input
                ref={inputRef}
                type="text"
                className="apk-composer-input"
                placeholder={
                  chatMode === 'broadcast'
                    ? 'Broadcast to mesh...'
                    : `Message ${selectedParticipant?.displayName || selectedPeerId}...`
                }
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                disabled={isSending}
                maxLength={MAX_MESSAGE_LENGTH}
                autoComplete="off"
              />
              <button
                type="submit"
                className="apk-send-btn"
                disabled={!inputText.trim() || isSending}
              >
                {isSending ? '...' : 'Send'}
              </button>
            </form>
          </footer>
        </>
      )}

      {/* Nearby Devices Sheet */}
      {isDevicesSheetOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsDevicesSheetOpen(false)}>
          <div className="modern-sheet-content" onClick={(e) => e.stopPropagation()}>
            <div className="modern-sheet-header">
              <h2 className="modern-sheet-title">Nearby Devices</h2>
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
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className="apk-sheet-btn primary"
                  onClick={handleToggleDiscovery}
                >
                  {isDiscovering ? 'Stop Scan' : 'Scan Devices'}
                </button>
                <button
                  type="button"
                  className={`apk-sheet-btn ${isBackgroundMeshActive ? 'primary' : ''}`}
                  onClick={handleToggleBackgroundMesh}
                >
                  {isBackgroundMeshActive ? 'Background On' : 'Background Off'}
                </button>
              </div>

              <div>
                <div className="modern-sheet-section-title">
                  Bluetooth Devices ({discoveredDevices.length})
                </div>
                {discoveredDevices.length === 0 ? (
                  <div style={{ padding: '16px 0', textAlign: 'center', color: '#64748B', fontSize: '0.875rem' }}>
                    {isDiscovering ? 'Scanning for nearby devices...' : 'No devices found yet.'}
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {discoveredDevices.map((dev) => {
                      const isDevConnected = connectedPeers.some((p) => p.address === dev.address);
                      return (
                        <div key={dev.address} className="modern-device-card">
                          <div className="modern-device-info">
                            <span className="modern-device-name">
                              {dev.deviceName || dev.deviceId || 'Nearby Device'}
                            </span>
                            <span className="modern-device-meta">
                              <span>{dev.rssi} dBm</span>
                              {isDevConnected && <span style={{ color: '#16A34A', fontWeight: 600 }}>· Connected</span>}
                            </span>
                          </div>
                          <button
                            type="button"
                            className={`modern-device-action-btn ${isDevConnected ? 'disconnect' : 'connect'}`}
                            onClick={() =>
                              isDevConnected
                                ? handleDisconnectDevice(dev.address)
                                : handleConnectDevice(dev.address)
                            }
                          >
                            {isDevConnected ? 'Disconnect' : 'Connect'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              <div>
                <div className="modern-sheet-section-title">Wi-Fi Direct</div>
                <div className="apk-wifi-box">
                  {wifiDirectConnInfo.isConnected
                    ? `Connected (${wifiDirectConnInfo.connectedPeersCount} peer${wifiDirectConnInfo.connectedPeersCount === 1 ? '' : 's'})`
                    : 'Not connected'}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* SOS Modal */}
      {isSosModalOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsSosModalOpen(false)}>
          <div className="modern-sos-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="modern-sos-title">Broadcast Emergency SOS</h2>
            <select
              className="modern-sos-select"
              value={sosEmergencyType}
              onChange={(e) => setSosEmergencyType(e.target.value)}
            >
              <option value="MEDICAL">Medical Emergency</option>
              <option value="TRAPPED">Trapped / Rescue</option>
              <option value="FIRE">Fire Hazard</option>
              <option value="FLOOD">Flash Flood</option>
              <option value="EVACUATION">Evacuation Needed</option>
            </select>
            <textarea
              className="modern-sos-textarea"
              placeholder="Optional emergency details..."
              value={sosNote}
              onChange={(e) => setSosNote(e.target.value)}
              maxLength={MAX_MESSAGE_LENGTH}
            />
            <button
              type="button"
              className="modern-sos-confirm-btn"
              onClick={handleSendSos}
              disabled={isSending}
            >
              {isSending ? 'Sending...' : 'Send SOS Broadcast'}
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
