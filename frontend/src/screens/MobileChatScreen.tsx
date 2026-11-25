import React, { useEffect, useRef, useState } from 'react';
import { BleMessage, bleChatService, MeshParticipant } from '../services/bleChatService';
import { useMeshChat } from '../hooks/useMeshChat';

const MAX_MESSAGE_LENGTH = 256;

interface MobileChatScreenProps {
  onBack?: () => void;
}

function formatHopLabel(p: MeshParticipant): string {
  if (p.status === 'unreachable') return 'Out of range right now';
  if (p.status === 'direct' || p.hopCount <= 1) return 'Direct connection';
  return `Relayed via ${p.hopCount} phones`;
}

function formatDeliveryStatus(status?: BleMessage['deliveryStatus']): string {
  switch (status) {
    case 'sending':
      return 'Sending…';
    case 'forwarding':
    case 'relaying':
      return 'Sent nearby';
    case 'delivered':
      return 'Delivered';
    case 'queued':
      return 'Waiting for phone';
    case 'failed':
    case 'expired':
    case 'unconfirmed':
      return 'Not confirmed';
    default:
      return 'Sent nearby';
  }
}

export default function MobileChatScreen({ onBack }: MobileChatScreenProps): React.JSX.Element {
  const [chatMode, setChatMode] = useState<'broadcast' | 'direct'>('broadcast');
  const [selectedPeerId, setSelectedPeerId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [inputText, setInputText] = useState('');
  const [isDevicesSheetOpen, setIsDevicesSheetOpen] = useState(false);
  const [isSosModalOpen, setIsSosModalOpen] = useState(false);
  const [sosEmergencyType, setSosEmergencyType] = useState('MEDICAL');
  const [sosNote, setSosNote] = useState('');
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const {
    availability,
    isDiscovering,
    isConnecting,
    isSending,
    discoveredDevices,
    connectedPeers,
    meshParticipants,
    isBackgroundMeshActive,
    wifiDirectConnInfo,
    privateMessages,
    broadcastMessages,
    securityAlert,
    setSecurityAlert,
    handleRequestPermissions,
    handleEnableBluetooth,
    handleToggleDiscovery,
    handleConnectDevice,
    handleDisconnectDevice,
    handleToggleBackgroundMesh,
    sendChatMessage,
    sendSosBroadcast,
  } = useMeshChat({
    defaultIsNative: true,
    persistDirectMessages: true,
    ackTimeoutMs: 20000,
    ackTimeoutStatus: 'failed',
    pollDiagnostics: false,
    onErrorNotice: setStatusMessage,
  });

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [privateMessages, broadcastMessages, chatMode, selectedPeerId]);

  const handleSend = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const text = inputText.trim();
    if (!text || isSending) return;

    const isBroadcast = chatMode === 'broadcast';
    if (!isBroadcast && !selectedPeerId) return;

    const accepted = await sendChatMessage(text, isBroadcast ? 'broadcast' : selectedPeerId!);
    if (accepted) {
      setInputText('');
    }
    setTimeout(() => inputRef.current?.focus(), 50);
  };

  const handleSendSos = async () => {
    const sent = await sendSosBroadcast(sosEmergencyType, sosNote);
    if (sent) {
      setIsSosModalOpen(false);
      setSosNote('');
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
        ? 'Connecting…'
        : totalConnected > 0
          ? `${totalConnected} connected`
          : isDiscovering
            ? 'Looking nearby…'
            : 'No phones connected';

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
                : 'Nearby Chat'}
            </h1>
            <div className="apk-chat-status">
              <span className={`modern-status-dot ${statusDotClass}`} />
              <span>
                {chatMode === 'direct' && selectedPeerId
                  ? `${selectedParticipant ? formatHopLabel(selectedParticipant) : 'Nearby Mesh'} · Private`
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

      {!availability.hasPermissions && (
        <div className="apk-banner warning">
          <span className="apk-banner-text">
            {availability.blePermissionsPermanentlyDenied
              ? 'Nearby devices permission is off. Open Settings to chat offline.'
              : 'Allow nearby devices access to find and message phones offline.'}
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
            {availability.blePermissionsPermanentlyDenied ? 'Open Settings' : 'Allow'}
          </button>
        </div>
      )}

      {availability.hasPermissions && !availability.isBluetoothEnabled && (
        <div className="apk-banner warning">
          <span className="apk-banner-text">Bluetooth is off. Turn it on to reach nearby phones.</span>
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

      <div className="apk-channel-strip">
        <button
          type="button"
          className={`apk-channel-pill ${chatMode === 'broadcast' ? 'active' : ''}`}
          onClick={() => {
            setChatMode('broadcast');
            setSelectedPeerId(null);
          }}
        >
          Everyone Nearby
        </button>
        <button
          type="button"
          className={`apk-channel-pill ${chatMode === 'direct' ? 'active' : ''}`}
          onClick={() => setChatMode('direct')}
        >
          Private ({meshParticipants.filter((p) => p.status !== 'unreachable').length})
        </button>
      </div>

      {isDirectListView ? (
        <div className="apk-messages-scroll">
          <div className="apk-direct-header">
            <h2 className="apk-direct-title">People Nearby</h2>
            {meshParticipants.length > 4 && (
              <input
                type="search"
                className="apk-direct-search"
                placeholder="Search by name or ID…"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            )}
          </div>

          {filteredParticipants.length === 0 ? (
            <div className="apk-empty-state">
              <div className="apk-empty-title">No One Found Yet</div>
              <div className="apk-empty-subtitle">
                Connect to a nearby phone in Nearby. Other people reachable through the mesh will show up here automatically.
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
                    {p.status === 'direct' ? 'Direct' : p.status === 'mesh' ? 'Mesh' : 'Offline'}
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
                    ? 'Message People Around You'
                    : `Private Chat with ${selectedParticipant?.displayName || selectedPeerId}`}
                </div>
                <div className="apk-empty-subtitle">
                  {chatMode === 'broadcast'
                    ? 'Messages reach all phones connected nearby, even without mobile signal.'
                    : 'Private messages are encrypted and relayed across nearby phones.'}
                </div>
              </div>
            ) : (
              displayMessages.map((msg) => {
                const isSelf = msg.isSelf;
                const isEmergency = msg.type === 'sos' || msg.priority === 'emergency';
                const timeStr = new Date(msg.timestamp).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                });

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
                    ? 'Message everyone nearby…'
                    : `Message ${selectedParticipant?.displayName || selectedPeerId}…`
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
                {isSending ? '…' : 'Send'}
              </button>
            </form>
          </footer>
        </>
      )}

      {isDevicesSheetOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsDevicesSheetOpen(false)}>
          <div className="modern-sheet-content" onClick={(e) => e.stopPropagation()}>
            <div className="modern-sheet-header">
              <h2 className="modern-sheet-title">Nearby Phones</h2>
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
                  {isDiscovering ? 'Stop Scan' : 'Scan Nearby'}
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
                  Bluetooth Phones ({discoveredDevices.length})
                </div>
                {discoveredDevices.length === 0 ? (
                  <div style={{ padding: '16px 0', textAlign: 'center', color: '#64748B', fontSize: '0.875rem' }}>
                    {isDiscovering ? 'Looking for nearby phones…' : 'No phones found yet.'}
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
                              <span>Signal {dev.rssi} dBm</span>
                              {isDevConnected && <span style={{ color: '#16A34A', fontWeight: 600 }}>· Connected</span>}
                            </span>
                          </div>
                          <button
                            type="button"
                            className={`modern-device-action-btn ${isDevConnected ? 'disconnect' : 'connect'}`}
                            onClick={() =>
                              isDevConnected
                                ? handleDisconnectDevice(dev.address)
                                : handleConnectDevice(dev.address, true)
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
                    ? `Connected (${wifiDirectConnInfo.connectedPeersCount} phone${wifiDirectConnInfo.connectedPeersCount === 1 ? '' : 's'})`
                    : 'Not connected'}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {isSosModalOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsSosModalOpen(false)}>
          <div className="modern-sos-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="modern-sos-title">Send Emergency SOS</h2>
            <select
              className="modern-sos-select"
              value={sosEmergencyType}
              onChange={(e) => setSosEmergencyType(e.target.value)}
            >
              <option value="MEDICAL">Medical Emergency</option>
              <option value="TRAPPED">Trapped / Need Rescue</option>
              <option value="FIRE">Fire Hazard</option>
              <option value="FLOOD">Flash Flood</option>
              <option value="EVACUATION">Need Evacuation</option>
            </select>
            <textarea
              className="modern-sos-textarea"
              placeholder="Optional note (location, people with you, danger)…"
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
              {isSending ? 'Sending SOS…' : 'Send Emergency SOS'}
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
