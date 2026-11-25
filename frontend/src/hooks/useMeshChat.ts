import { useCallback, useEffect, useRef, useState } from 'react';
import {
  BackgroundMeshState,
  BleAckEvent,
  BleAvailability,
  BleConnectionState,
  BleDevice,
  BleMessage,
  BleMessageStatusEvent,
  BleRelayEvent,
  BleSecurityAlert,
  bleChatService,
  ConnectedPeer,
  MeshDiagnostics,
  MeshParticipant,
  WifiDirectConnectionInfo,
} from '../services/bleChatService';

const DM_STORAGE_KEY = 'disaster_mesh_dm_history_v1';

export interface UseMeshChatOptions {
  defaultIsNative?: boolean;
  persistDirectMessages?: boolean;
  ackTimeoutMs?: number;
  ackTimeoutStatus?: 'unconfirmed' | 'failed';
  pollDiagnostics?: boolean;
  onNotice?: (message: string | null) => void;
  onErrorNotice?: (message: string | null) => void;
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

function getBackgroundStatusLabel(bg: BackgroundMeshState, fallbackStopped: string): string {
  if (bg.isRunning) {
    return bg.connectedCount > 0 ? 'Mesh Active' : 'Looking for nearby phones…';
  }
  return bg.status || fallbackStopped;
}

function getErrorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export function useMeshChat(options: UseMeshChatOptions = {}) {
  const {
    defaultIsNative = false,
    persistDirectMessages = false,
    ackTimeoutMs = 15000,
    ackTimeoutStatus = 'unconfirmed',
    pollDiagnostics = false,
    onNotice,
    onErrorNotice,
  } = options;

  const onNoticeRef = useRef(onNotice);
  const onErrorNoticeRef = useRef(onErrorNotice);
  onNoticeRef.current = onNotice;
  onErrorNoticeRef.current = onErrorNotice;

  const [availability, setAvailability] = useState<BleAvailability>({
    isNative: defaultIsNative,
    hasBleHardware: defaultIsNative,
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

  const [isDiscovering, setIsDiscovering] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [discoveredDevices, setDiscoveredDevices] = useState<BleDevice[]>([]);
  const [connectedPeers, setConnectedPeers] = useState<ConnectedPeer[]>([]);
  const [meshParticipants, setMeshParticipants] = useState<MeshParticipant[]>([]);
  const [isBackgroundMeshActive, setIsBackgroundMeshActive] = useState(false);
  const [backgroundMeshStatus, setBackgroundMeshStatus] = useState('Background mesh off');
  const [wifiDirectConnInfo, setWifiDirectConnInfo] = useState<WifiDirectConnectionInfo>({
    isConnected: false,
    isGroupOwner: false,
    groupOwnerAddress: '',
    connectedPeersCount: 0,
  });
  const [meshDiagnostics, setMeshDiagnostics] = useState<MeshDiagnostics | null>(null);

  const [privateMessages, setPrivateMessages] = useState<BleMessage[]>(() =>
    persistDirectMessages ? loadSavedDirectMessages() : [],
  );
  const [broadcastMessages, setBroadcastMessages] = useState<BleMessage[]>([]);
  const [securityAlert, setSecurityAlert] = useState<string | null>(null);

  const ackTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    if (!persistDirectMessages) return;
    try {
      localStorage.setItem(DM_STORAGE_KEY, JSON.stringify(privateMessages.slice(-200)));
    } catch {
      // Ignore storage quota errors in restricted webviews.
    }
  }, [privateMessages, persistDirectMessages]);

  const tryStartDiscovery = useCallback(async (isMountedCheck?: () => boolean) => {
    try {
      const res = await bleChatService.startDiscovery();
      if (res.success && (!isMountedCheck || isMountedCheck())) {
        setIsDiscovering(true);
      }
    } catch {
      // Native side emits adapter/permission state if discovery cannot start yet.
    }
  }, []);

  useEffect(() => {
    let isMounted = true;
    const stillMounted = () => isMounted;

    async function syncMeshState() {
      const avail = await bleChatService.checkAvailability();
      if (!isMounted) return;
      setAvailability(avail);

      if (pollDiagnostics) {
        try {
          const diag = await bleChatService.getMeshDiagnostics();
          if (isMounted) setMeshDiagnostics(diag);
        } catch {
          // Diagnostics are best-effort.
        }
      }

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
        setBackgroundMeshStatus(getBackgroundStatusLabel(bgState, 'Background mesh off'));

        if (avail.hasPermissions && avail.isBluetoothEnabled && !bgState.isRunning) {
          await tryStartDiscovery(stillMounted);
        }
      }
    }

    syncMeshState();

    const subscriptions = [
      bleChatService.onDevicesUpdated((data) => {
        if (!isMounted) return;
        setDiscoveredDevices(data.devices);
        setAvailability((prev) => ({
          ...prev,
          connectedCount: data.connectedCount,
          maxConnections: data.maxConnections,
          relayedCount: data.relayedCount ?? prev.relayedCount,
        }));
      }),
      bleChatService.onMeshParticipantsUpdated((data) => {
        if (isMounted) setMeshParticipants(data.participants);
      }),
      bleChatService.onConnectionState(async (state: BleConnectionState) => {
        if (!isMounted) return;
        setIsConnecting(state.state === 'connecting');
        const [peers, participants] = await Promise.all([
          bleChatService.getConnectedPeers(),
          bleChatService.getMeshParticipants(),
        ]);
        if (!isMounted) return;
        setConnectedPeers(peers);
        setMeshParticipants(participants);

        if (state.state === 'connected') {
          onNoticeRef.current?.(`Connected to ${state.deviceId || state.address}.`);
        } else if (state.state === 'disconnected') {
          onNoticeRef.current?.(`${state.deviceId || state.address || 'Nearby phone'} disconnected.`);
        } else if (state.state === 'failed') {
          onErrorNoticeRef.current?.(state.message || 'Could not connect to that phone.');
        }
      }),
      bleChatService.onMessageReceived((msg: BleMessage) => {
        if (!isMounted) return;
        const isBroadcastMsg =
          msg.recipientId === 'BROADCAST' ||
          msg.recipientId === 'ALL' ||
          msg.type === 'broadcast' ||
          msg.type === 'sos';

        if (isBroadcastMsg) {
          setBroadcastMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
          if (msg.type === 'sos' || msg.priority === 'emergency') {
            onNoticeRef.current?.(`Emergency SOS received from ${msg.senderId}.`);
          }
        } else {
          setPrivateMessages((prev) => [...prev, { ...msg, isSelf: false, deliveryStatus: 'delivered' }]);
        }
      }),
      bleChatService.onAckReceived((ack: BleAckEvent) => {
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
      }),
      bleChatService.onMessageStatusChanged((evt: BleMessageStatusEvent) => {
        if (!isMounted) return;
        setPrivateMessages((prev) =>
          prev.map((m) =>
            m.id === evt.messageId
              ? {
                  ...m,
                  deliveryStatus: evt.status,
                  ackHopCount: evt.hopCount !== undefined ? evt.hopCount : m.ackHopCount,
                }
              : m,
          ),
        );
      }),
      bleChatService.onMessageRelayed((relay: BleRelayEvent) => {
        if (!isMounted) return;
        setAvailability((prev) => ({
          ...prev,
          relayedCount: relay.totalRelayedCount ?? prev.relayedCount + 1,
        }));
      }),
      bleChatService.onSecurityAlert((alert: BleSecurityAlert) => {
        if (isMounted) setSecurityAlert(alert.message);
      }),
      bleChatService.onBackgroundMeshStateChanged((bg: BackgroundMeshState) => {
        if (!isMounted) return;
        setIsBackgroundMeshActive(bg.isRunning);
        setBackgroundMeshStatus(getBackgroundStatusLabel(bg, 'Background mesh off'));
        if (bg.isRunning) setIsDiscovering(true);
      }),
      bleChatService.onWifiDirectConnectionChanged((info) => {
        if (isMounted) setWifiDirectConnInfo(info);
      }),
      bleChatService.onAdapterStateChanged((data) => {
        if (!isMounted) return;
        setAvailability((prev) => ({ ...prev, isBluetoothEnabled: data.isBluetoothEnabled }));
        if (!data.isBluetoothEnabled) setIsDiscovering(false);
      }),
      bleChatService.onPermissionsChanged(async (data) => {
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
          onNoticeRef.current?.(null);
          if (data.isBluetoothEnabled) {
            await tryStartDiscovery(stillMounted);
          }
        }
      }),
    ];

    const handleVisibilityChange = async () => {
      if (document.hidden || !isMounted) return;
      if (defaultIsNative) {
        await syncMeshState();
        return;
      }
      const updated = await bleChatService.checkAvailability();
      if (!isMounted) return;
      setAvailability(updated);
      if (updated.hasPermissions) {
        onNoticeRef.current?.(null);
        if (updated.isBluetoothEnabled) {
          await tryStartDiscovery(stillMounted);
        }
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    const diagInterval = pollDiagnostics
      ? setInterval(async () => {
          if (!isMounted) return;
          try {
            const diag = await bleChatService.getMeshDiagnostics();
            if (isMounted) setMeshDiagnostics(diag);
          } catch {
            // Ignore transient polling failures.
          }
        }, 5000)
      : null;

    return () => {
      isMounted = false;
      if (diagInterval) clearInterval(diagInterval);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      subscriptions.forEach((sub) => sub?.then((h) => h.remove()));
      for (const timer of ackTimers.current.values()) {
        clearTimeout(timer);
      }
      ackTimers.current.clear();
    };
  }, [defaultIsNative, pollDiagnostics, tryStartDiscovery]);

  const handleEnableBluetooth = useCallback(async () => {
    try {
      const enabled = await bleChatService.requestEnableBluetooth();
      const updated = await bleChatService.checkAvailability();
      setAvailability(updated);
      if (enabled) {
        onNoticeRef.current?.('Bluetooth is now on.');
        if (updated.hasPermissions) {
          await tryStartDiscovery();
        }
      }
    } catch (err) {
      console.warn('handleEnableBluetooth error:', err);
    }
  }, [tryStartDiscovery]);

  const handleRequestPermissions = useCallback(async () => {
    try {
      const result = await bleChatService.requestPermissionsDetailed();
      const updated = await bleChatService.checkAvailability();
      setAvailability({
        ...updated,
        blePermissionsPermanentlyDenied:
          result.permanentlyDenied || updated.blePermissionsPermanentlyDenied,
      });

      if (result.granted || updated.hasPermissions) {
        onNoticeRef.current?.('Nearby device permission granted.');
        onErrorNoticeRef.current?.(null);
        if (!updated.isBluetoothEnabled) {
          await handleEnableBluetooth();
        } else {
          await tryStartDiscovery();
        }
      } else if (result.permanentlyDenied) {
        onNoticeRef.current?.(
          'Nearby devices permission is off. Open App Settings to turn it on.',
        );
      } else {
        onNoticeRef.current?.('Permission needed to find nearby phones. Tap Allow to continue.');
      }
    } catch (err) {
      console.warn('handleRequestPermissions error:', err);
    }
  }, [handleEnableBluetooth, tryStartDiscovery]);

  const handleToggleDiscovery = useCallback(async () => {
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
      onNoticeRef.current?.('Stopped scanning for nearby phones.');
      return;
    }

    try {
      const res = await bleChatService.startDiscovery();
      if (res.success) {
        setIsDiscovering(true);
        onNoticeRef.current?.('Looking for nearby phones…');
      }
    } catch (err) {
      const msg = getErrorMessage(err);
      onNoticeRef.current?.(`Could not start scanning: ${msg}`);
      onErrorNoticeRef.current?.(msg);
    }
  }, [
    availability.hasPermissions,
    availability.isBluetoothEnabled,
    isDiscovering,
    handleRequestPermissions,
    handleEnableBluetooth,
  ]);

  const handleConnectDevice = useCallback(async (address: string, trackConnecting = false) => {
    if (trackConnecting) setIsConnecting(true);
    onNoticeRef.current?.('Connecting to nearby phone…');
    try {
      await bleChatService.connectToDevice(address);
    } catch (err) {
      if (trackConnecting) setIsConnecting(false);
      const msg = getErrorMessage(err);
      onNoticeRef.current?.(`Could not connect: ${msg}`);
      onErrorNoticeRef.current?.(msg);
    }
  }, []);

  const handleDisconnectDevice = useCallback(async (address: string) => {
    try {
      await bleChatService.disconnectDevice(address);
      onNoticeRef.current?.('Disconnected from device.');
    } catch (err) {
      console.warn('handleDisconnectDevice error:', err);
    }
  }, []);

  const handleToggleBackgroundMesh = useCallback(async () => {
    if (!availability.hasPermissions) {
      await handleRequestPermissions();
      return;
    }

    if (isBackgroundMeshActive) {
      try {
        const res = await bleChatService.stopBackgroundMesh();
        setIsBackgroundMeshActive(false);
        setBackgroundMeshStatus(res.status);
        onNoticeRef.current?.('Background mesh paused.');
      } catch (err) {
        console.warn('stopBackgroundMesh error:', err);
      }
      return;
    }

    try {
      const res = await bleChatService.startBackgroundMesh();
      setIsBackgroundMeshActive(res.isRunning);
      setBackgroundMeshStatus(res.status);
      if (res.isRunning) setIsDiscovering(true);
      onNoticeRef.current?.('Background mesh is now running.');
    } catch (err) {
      const msg = getErrorMessage(err);
      onNoticeRef.current?.(`Could not turn on background mesh: ${msg}`);
      onErrorNoticeRef.current?.(msg);
    }
  }, [availability.hasPermissions, isBackgroundMeshActive, handleRequestPermissions]);

  const sendChatMessage = useCallback(
    async (text: string, targetChannel: string): Promise<boolean> => {
      const isBroadcast = targetChannel === 'broadcast';
      const recipientId = isBroadcast ? 'BROADCAST' : targetChannel;
      const type = isBroadcast ? 'broadcast' : 'encrypted_chat';

      setIsSending(true);
      onErrorNoticeRef.current?.(null);

      try {
        const res = await bleChatService.sendMessage({
          text,
          recipientId,
          type,
          priority: 'normal',
          hopLimit: 5,
        });

        if (!res.success) return false;

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
          deliveryStatus: (res.status === 'queued'
            ? 'queued'
            : isBroadcast
              ? 'relaying'
              : 'forwarding') as BleMessage['deliveryStatus'],
        };

        if (isBroadcast) {
          setBroadcastMessages((prev) => [...prev, sentMsg]);
        } else {
          setPrivateMessages((prev) => [...prev, sentMsg]);
          const msgId = res.messageId;
          const timer = setTimeout(() => {
            setPrivateMessages((prev) =>
              prev.map((m) =>
                m.id === msgId &&
                (m.deliveryStatus === 'forwarding' || m.deliveryStatus === 'relaying')
                  ? { ...m, deliveryStatus: ackTimeoutStatus }
                  : m,
              ),
            );
          }, ackTimeoutMs);
          ackTimers.current.set(msgId, timer);
        }

        if (res.status === 'queued') {
          onNoticeRef.current?.('Saved offline — will send automatically when a phone connects.');
        } else {
          onNoticeRef.current?.(
            isBroadcast ? 'Message sent to nearby phones.' : `Message sent privately to ${recipientId}.`,
          );
        }
        return true;
      } catch (err) {
        const msg = getErrorMessage(err);
        onNoticeRef.current?.(`Could not send message: ${msg}`);
        onErrorNoticeRef.current?.(msg);
        return false;
      } finally {
        setIsSending(false);
      }
    },
    [ackTimeoutMs, ackTimeoutStatus, availability.localDeviceId],
  );

  const sendSosBroadcast = useCallback(
    async (emergencyType: string, note: string): Promise<boolean> => {
      if (isSending) return false;
      const text = note.trim() || `EMERGENCY SOS: ${emergencyType}`;
      setIsSending(true);
      try {
        const res = await bleChatService.sendMessage({
          text,
          recipientId: 'BROADCAST',
          type: 'sos',
          priority: 'emergency',
          emergencyType,
          hopLimit: 5,
        });

        if (!res.success) return false;

        const sentMsg: BleMessage = {
          id: res.messageId,
          text,
          senderId: availability.localDeviceId,
          senderAddress: 'self',
          recipientId: 'BROADCAST',
          type: 'sos',
          priority: 'emergency',
          emergencyType,
          hopCount: 0,
          hopLimit: 5,
          timestamp: Date.now(),
          isSelf: true,
          isEncrypted: false,
          deliveryStatus: (res.status === 'queued'
            ? 'queued'
            : 'relaying') as BleMessage['deliveryStatus'],
        };

        setBroadcastMessages((prev) => [...prev, sentMsg]);
        onNoticeRef.current?.('Emergency SOS alert sent to nearby phones.');
        return true;
      } catch (err) {
        const msg = getErrorMessage(err);
        onNoticeRef.current?.(`Could not send SOS: ${msg}`);
        onErrorNoticeRef.current?.(msg);
        return false;
      } finally {
        setIsSending(false);
      }
    },
    [availability.localDeviceId, isSending],
  );

  return {
    availability,
    isDiscovering,
    isConnecting,
    isSending,
    discoveredDevices,
    connectedPeers,
    meshParticipants,
    isBackgroundMeshActive,
    backgroundMeshStatus,
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
  };
}
