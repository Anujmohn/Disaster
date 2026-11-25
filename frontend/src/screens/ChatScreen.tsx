import React, { useEffect, useRef, useState } from 'react';
import { bleChatService } from '../services/bleChatService';
import { useMeshChat } from '../hooks/useMeshChat';

const MAX_MESSAGE_LENGTH = 256;

export default function ChatScreen(): React.JSX.Element {
  const [activeChannel, setActiveChannel] = useState('broadcast');
  const [directSearch, setDirectSearch] = useState('');
  const [inputText, setInputText] = useState('');
  const [isDevicesSheetOpen, setIsDevicesSheetOpen] = useState(false);
  const [isSosModalOpen, setIsSosModalOpen] = useState(false);
  const [sosEmergencyType, setSosEmergencyType] = useState('MEDICAL');
  const [sosNote, setSosNote] = useState('');
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const {
    availability,
    isDiscovering,
    isSending,
    discoveredDevices,
    connectedPeers,
    meshParticipants,
    isBackgroundMeshActive,
    wifiDirectConnInfo,
    meshDiagnostics,
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
    defaultIsNative: false,
    persistDirectMessages: false,
    ackTimeoutMs: 15000,
    ackTimeoutStatus: 'unconfirmed',
    pollDiagnostics: true,
    onNotice: setActionNotice,
  });

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [privateMessages, broadcastMessages, activeChannel]);

  const handleSend = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const text = inputText.trim();
    if (!text || isSending) return;
    if (text.length > MAX_MESSAGE_LENGTH) {
      setActionNotice(`Keep messages under ${MAX_MESSAGE_LENGTH} characters.`);
      return;
    }

    const accepted = await sendChatMessage(text, activeChannel);
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
  const isConnected = totalConnected > 0;
  const displayMessages =
    activeChannel === 'broadcast'
      ? broadcastMessages
      : privateMessages.filter((m) => m.recipientId === activeChannel || m.senderId === activeChannel);

  return (
    <div className="modern-chat-container">
      <header className="modern-chat-header">
        <div className="modern-chat-header-left">
          <div className="modern-chat-title-group">
            <h1 className="modern-chat-title">Nearby Offline Chat</h1>
            <div className="modern-chat-subtitle">
              <span className={`modern-status-dot ${isConnected ? 'connected' : isDiscovering ? 'searching' : 'offline'}`} />
              <span>
                {isConnected
                  ? `${totalConnected} phone${totalConnected > 1 ? 's' : ''} connected`
                  : isDiscovering
                    ? 'Looking for nearby phones…'
                    : 'Works without internet'}
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

      {availability.isNative && !availability.hasPermissions && (
        <div className="modern-guide-banner warning">
          <span>
            {availability.blePermissionsPermanentlyDenied
              ? 'Nearby devices permission is off. Turn it on in App Settings to chat with people around you.'
              : 'Allow nearby device access so you can message people offline.'}
          </span>
          {availability.blePermissionsPermanentlyDenied ? (
            <button
              type="button"
              className="modern-guide-btn"
              onClick={() => bleChatService.openAppSettings()}
            >
              Open Settings
            </button>
          ) : (
            <button
              type="button"
              className="modern-guide-btn"
              onClick={handleRequestPermissions}
            >
              Allow Access
            </button>
          )}
        </div>
      )}

      {availability.isNative && availability.hasPermissions && !availability.isBluetoothEnabled && (
        <div className="modern-guide-banner warning">
          <span>Bluetooth is off. Turn it on to connect with nearby phones.</span>
          <button
            type="button"
            className="modern-guide-btn"
            onClick={handleEnableBluetooth}
          >
            Turn On
          </button>
        </div>
      )}

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

      <div className="modern-channel-strip">
        <button
          type="button"
          className={`modern-channel-pill ${activeChannel === 'broadcast' ? 'active' : ''}`}
          onClick={() => setActiveChannel('broadcast')}
        >
          <span>📢 Everyone Nearby</span>
        </button>

        <button
          type="button"
          className={`modern-channel-pill ${activeChannel === 'direct_list' ? 'active' : ''}`}
          onClick={() => setActiveChannel('direct_list')}
        >
          <span>🔒 Private ({meshParticipants.filter((p) => p.status !== 'unreachable').length})</span>
        </button>

        {activeChannel !== 'broadcast' && activeChannel !== 'direct_list' && (
          <button
            type="button"
            className="modern-channel-pill active"
            onClick={() => setActiveChannel(activeChannel)}
          >
            <span>🔒 {activeChannel}</span>
            <span className="channel-pill-meta">(Private)</span>
          </button>
        )}
      </div>

      {activeChannel === 'direct_list' ? (
        <div className="modern-messages-scroll">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px', gap: '12px' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: '#0F172A' }}>People Nearby</h2>
            {meshParticipants.length > 4 && (
              <input
                type="search"
                className="apk-direct-search"
                style={{ maxWidth: '240px' }}
                placeholder="Search by name or ID…"
                value={directSearch}
                onChange={(e) => setDirectSearch(e.target.value)}
              />
            )}
          </div>

          {meshParticipants.length === 0 ? (
            <div className="modern-empty-state">
              <div className="modern-empty-icon">🔒</div>
              <div className="modern-empty-title">No One Found Yet</div>
              <div className="modern-empty-subtitle">
                Connect to a nearby phone in Nearby to start a private conversation, even across multiple hops.
              </div>
            </div>
          ) : (
            <div className="apk-participant-list">
              {meshParticipants
                .filter(
                  (p) =>
                    !directSearch.trim() ||
                    p.displayName.toLowerCase().includes(directSearch.toLowerCase()) ||
                    p.deviceId.toLowerCase().includes(directSearch.toLowerCase()),
                )
                .map((p) => (
                  <button
                    key={p.deviceId}
                    type="button"
                    className="apk-participant-row"
                    onClick={() => setActiveChannel(p.deviceId)}
                  >
                    <div className="apk-participant-main">
                      <div className="apk-participant-name">
                        {p.displayName && p.displayName !== p.deviceId
                          ? `${p.displayName} (${p.deviceId})`
                          : p.deviceId}
                      </div>
                      <div className="apk-participant-sub">
                        {p.status === 'unreachable'
                          ? 'Out of range right now'
                          : p.status === 'direct' || p.hopCount <= 1
                            ? 'Direct connection'
                            : `Relayed via ${p.hopCount} phones`}
                      </div>
                    </div>
                    <span className={`apk-participant-badge ${p.status}`}>
                      {p.status === 'direct' ? 'Direct' : p.status === 'mesh' ? 'Nearby Mesh' : 'Offline'}
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
                {activeChannel === 'broadcast'
                  ? 'Message People Around You'
                  : `Private Chat with ${activeChannel}`}
              </div>
              <div className="modern-empty-subtitle">
                No mobile signal or Wi-Fi needed. Messages send right away or wait safely until someone is in range.
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
                <div
                  key={msg.id}
                  className={`modern-message-row ${isSelf ? 'sent' : 'received'}`}
                >
                  {!isSelf && <span className="modern-sender-tag">{msg.senderId}</span>}

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
                          {msg.deliveryStatus === 'delivered'
                            ? '✓ Delivered'
                            : msg.deliveryStatus === 'queued'
                              ? '⏳ Waiting'
                              : '↗ Sent'}
                        </span>
                      ) : (
                        <span>
                          {msg.hopCount !== undefined
                            ? msg.hopCount === 0
                              ? 'Direct'
                              : `${msg.hopCount} hop${msg.hopCount === 1 ? '' : 's'}`
                            : ''}
                        </span>
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
                    ? 'Message everyone nearby…'
                    : `Send a private message to ${activeChannel}…`
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
              {isSending ? '…' : 'Send'}
            </button>
          </form>
        </footer>
      )}

      {isDevicesSheetOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsDevicesSheetOpen(false)}>
          <div className="modern-sheet-content" onClick={(e) => e.stopPropagation()}>
            <div className="modern-sheet-header">
              <h2 className="modern-sheet-title">Nearby Phones & Connection</h2>
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
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <button
                  type="button"
                  className="modern-pill-btn active"
                  style={{ flex: 1, padding: '8px 14px', justifyContent: 'center' }}
                  onClick={handleToggleDiscovery}
                >
                  {isDiscovering ? 'Stop Scanning' : 'Scan Nearby'}
                </button>
                <button
                  type="button"
                  className={`modern-pill-btn ${isBackgroundMeshActive ? 'active' : ''}`}
                  style={{ flex: 1, padding: '8px 14px', justifyContent: 'center' }}
                  onClick={handleToggleBackgroundMesh}
                >
                  {isBackgroundMeshActive ? 'Background On' : 'Run in Background'}
                </button>
              </div>

              <div>
                <div className="modern-sheet-section-title">
                  Phones Found ({discoveredDevices.length})
                </div>

                {discoveredDevices.length === 0 ? (
                  <div style={{ padding: '16px', textAlign: 'center', color: '#64748B', fontSize: '0.85rem' }}>
                    {isDiscovering
                      ? 'Searching for nearby phones over Bluetooth…'
                      : 'Tap Scan Nearby to look for phones around you.'}
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
                              <span>Signal {dev.rssi} dBm</span>
                              {isDevConnected && (
                                <span style={{ color: '#16A34A', fontWeight: 600 }}>· Connected</span>
                              )}
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
                <div style={{ fontSize: '0.82rem', color: '#475569', background: '#F8FAFC', padding: '10px 14px', borderRadius: '8px', border: '1px solid #E2E8F0' }}>
                  <div>
                    Status:{' '}
                    {wifiDirectConnInfo.isConnected
                      ? wifiDirectConnInfo.isGroupOwner
                        ? 'Connected (Host)'
                        : 'Connected'
                      : 'Not connected'}
                  </div>
                  <div>Connected peers: {wifiDirectConnInfo.connectedPeersCount}</div>
                </div>
              </div>

              <details style={{ marginTop: '8px', background: '#F8FAFC', padding: '8px 12px', borderRadius: '8px', border: '1px solid #E2E8F0', fontSize: '0.82rem' }}>
                <summary style={{ cursor: 'pointer', fontWeight: 600 }}>Device Details</summary>
                <div style={{ marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '0.78rem', color: '#475569' }}>
                  <div><strong>Your ID:</strong> <code>{availability.localDeviceId}</code></div>
                  <div><strong>Key Fingerprint:</strong> <code>{availability.fingerprint || 'N/A'}</code></div>
                  <div><strong>Messages Relayed:</strong> {availability.relayedCount}</div>
                  <div><strong>Waiting to Send:</strong> {meshDiagnostics?.queuedMessageCount ?? 0}</div>
                  <div><strong>Encryption:</strong> AES-256-GCM + ECDSA</div>
                </div>
              </details>
            </div>
          </div>
        </div>
      )}

      {isSosModalOpen && (
        <div className="modern-sheet-backdrop" onClick={() => setIsSosModalOpen(false)}>
          <div className="modern-sos-dialog" onClick={(e) => e.stopPropagation()}>
            <h2 className="modern-sos-title">
              <span>🚨</span>
              <span>Send Emergency SOS</span>
            </h2>

            <p style={{ fontSize: '0.84rem', color: '#64748B', lineHeight: 1.45, margin: 0 }}>
              Sends a priority emergency alert to all nearby phones and relays it up to 5 hops away.
            </p>

            <label style={{ fontSize: '0.82rem', fontWeight: 600, color: '#334155' }}>
              What kind of help do you need?
              <select
                className="modern-sos-select"
                style={{ width: '100%', marginTop: '6px' }}
                value={sosEmergencyType}
                onChange={(e) => setSosEmergencyType(e.target.value)}
              >
                <option value="MEDICAL">Medical Emergency</option>
                <option value="TRAPPED">Trapped / Need Rescue</option>
                <option value="FIRE">Fire Hazard</option>
                <option value="FLOOD">Flash Flood</option>
                <option value="EVACUATION">Need Evacuation</option>
              </select>
            </label>

            <label style={{ fontSize: '0.82rem', fontWeight: 600, color: '#334155' }}>
              Short note (optional)
              <textarea
                className="modern-sos-textarea"
                style={{ width: '100%', marginTop: '6px' }}
                placeholder="Share where you are, number of people, or immediate danger…"
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
