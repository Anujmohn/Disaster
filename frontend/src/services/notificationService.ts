import { DeviceRegisterResponse, registerDevice } from '../api/alertApi';

const STORAGE_KEY = 'disaster_safety_device_id';

function getOrGenerateDeviceId(): string {
  if (typeof window === 'undefined') {
    return 'web-client-ssr';
  }
  let id = localStorage.getItem(STORAGE_KEY);
  if (!id) {
    id = `web-${Math.random().toString(36).substring(2, 10)}-${Date.now().toString(36)}`;
    localStorage.setItem(STORAGE_KEY, id);
  }
  return id;
}

let currentDeviceId: string = getOrGenerateDeviceId();
let currentWebToken: string | null = null;

export function getDeviceId(): string {
  return currentDeviceId;
}

export function setDeviceId(id: string): void {
  currentDeviceId = id;
  if (typeof window !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, id);
  }
}

export function isBrowserNotificationSupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function getNotificationPermission(): NotificationPermission {
  if (!isBrowserNotificationSupported()) {
    return 'denied';
  }
  return Notification.permission;
}

export async function initializeWebNotifications(
  userLocation?: { latitude: number; longitude: number },
): Promise<{
  permission: NotificationPermission;
  registration: DeviceRegisterResponse | null;
}> {
  let permission: NotificationPermission = 'denied';

  if (isBrowserNotificationSupported()) {
    try {
      permission =
        Notification.permission === 'default'
          ? await Notification.requestPermission()
          : Notification.permission;
    } catch (err) {
      console.warn('Error requesting browser notification permission:', err);
      permission = Notification.permission;
    }
  }

  if (!currentWebToken) {
    currentWebToken = `web-token-${currentDeviceId}-${Date.now().toString(36)}`;
  }

  try {
    const registration = await registerDevice({
      device_id: currentDeviceId,
      push_token: currentWebToken,
      platform: 'web',
      alert_earthquake: true,
      alert_flood: true,
      alert_cyclone: true,
      alert_critical: true,
      alert_advisory: true,
      notification_radius_km: 50.0,
      sound_enabled: true,
      latitude: userLocation?.latitude ?? null,
      longitude: userLocation?.longitude ?? null,
    });

    return { permission, registration };
  } catch (err) {
    console.warn('Failed to register web device with backend:', err);
    return { permission, registration: null };
  }
}

export function showBrowserNotification(title: string, options?: NotificationOptions): boolean {
  if (!isBrowserNotificationSupported() || Notification.permission !== 'granted') {
    return false;
  }
  try {
    new Notification(title, {
      badge: '/favicon.ico',
      ...options,
    });
    return true;
  } catch (err) {
    console.warn('Failed to display browser notification:', err);
    return false;
  }
}
