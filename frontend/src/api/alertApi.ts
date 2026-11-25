import { API_BASE_URL, requestJson } from './client';
import { DisasterType } from './disasterApi';

export type AlertClassification =
  | 'OFFICIAL_WARNING'
  | 'OFFICIAL_ADVISORY'
  | 'NEW_DISASTER_EVENT'
  | 'WARNING_ESCALATION'
  | 'WARNING_UPDATE'
  | 'WARNING_CANCELLED'
  | 'WARNING_EXPIRED'
  | 'SIGNIFICANT_HAZARD_CHANGE'
  | 'DATA_SOURCE_FAILURE';

export type AlertSourceKind =
  | 'OFFICIAL_GOVERNMENT_WARNING'
  | 'OFFICIAL_OBSERVATION'
  | 'SYSTEM_GENERATED_ASSESSMENT'
  | 'TECHNICAL_NOTIFICATION';

export interface AlertItem {
  alert_id: string;
  crisis_event_id: string | null;
  crisis_source_report_id: string | null;
  disaster_type: DisasterType;
  alert_classification: AlertClassification;
  alert_source_kind: AlertSourceKind;
  title: string;
  body: string;
  original_severity: string | null;
  issuing_authority: string | null;
  source_url: string | null;
  issued_at: string | null;
  valid_until: string | null;
  distance_km: number | null;
  location: unknown | null;
  affected_area: unknown | null;
  freshness: string;
  is_official_warning: boolean;
  deep_link_url: string | null;
}

export interface NearbyAlertsRequest {
  latitude: number;
  longitude: number;
  radius_km?: number;
  disaster_type?: DisasterType;
  classification?: AlertClassification;
  limit?: number;
}

export interface NearbyAlertsResponse {
  success: boolean;
  total_alerts: number;
  alerts: AlertItem[];
  search_center: { latitude: number; longitude: number };
  search_radius_km: number;
  safety_disclaimer: string;
}

export interface DevicePreferencesRead {
  device_id: string;
  push_token: string | null;
  platform: string;
  alert_earthquake: boolean;
  alert_flood: boolean;
  alert_cyclone: boolean;
  alert_critical: boolean;
  alert_advisory: boolean;
  notification_radius_km: number;
  sound_enabled: boolean;
  is_active: boolean;
  updated_at: string;
}

export interface DevicePreferencesUpdate {
  device_id: string;
  alert_earthquake?: boolean;
  alert_flood?: boolean;
  alert_cyclone?: boolean;
  alert_critical?: boolean;
  alert_advisory?: boolean;
  notification_radius_km?: number;
  sound_enabled?: boolean;
}

export interface DeviceRegisterRequest {
  device_id: string;
  push_token?: string | null;
  platform?: string;
  alert_earthquake?: boolean;
  alert_flood?: boolean;
  alert_cyclone?: boolean;
  alert_critical?: boolean;
  alert_advisory?: boolean;
  notification_radius_km?: number;
  sound_enabled?: boolean;
  latitude?: number | null;
  longitude?: number | null;
}

export interface DeviceRegisterResponse {
  success: boolean;
  device_id: string;
  message: string;
  preferences: DevicePreferencesRead;
}

export interface NotificationHistoryItem {
  notification_id: string;
  device_id: string;
  crisis_event_id: string | null;
  disaster_type: DisasterType;
  alert_classification: AlertClassification;
  alert_source_kind: AlertSourceKind;
  title: string;
  body: string;
  original_severity: string | null;
  issuing_authority: string | null;
  source_url: string | null;
  deep_link_url: string | null;
  delivery_status: string;
  is_read: boolean;
  issued_at: string | null;
  expires_at: string | null;
  created_at: string;
}

export interface NotificationHistoryResponse {
  success: boolean;
  device_id: string;
  total_notifications: number;
  unread_count: number;
  notifications: NotificationHistoryItem[];
  safety_disclaimer: string;
}

export interface AlertSyncResponse {
  success: boolean;
  sync_mode: string;
  total_events_detected: number;
  total_alerts_generated: number;
  sources_synced: string[];
  monitoring_health: string;
  message: string;
  disclaimer: string;
}

export function fetchNearbyAlerts(
  request: NearbyAlertsRequest,
): Promise<NearbyAlertsResponse> {
  const params = new URLSearchParams({
    latitude: request.latitude.toString(),
    longitude: request.longitude.toString(),
    radius_km: (request.radius_km ?? 50.0).toString(),
    limit: (request.limit ?? 50).toString(),
  });
  if (request.disaster_type) params.append('disaster_type', request.disaster_type);
  if (request.classification) params.append('classification', request.classification);

  return requestJson<NearbyAlertsResponse>(
    `/api/v1/alerts/nearby?${params.toString()}`,
    {},
    'Failed to fetch alerts',
  );
}

export function fetchDevicePreferences(
  deviceId: string,
): Promise<DevicePreferencesRead> {
  return requestJson<DevicePreferencesRead>(
    `/api/v1/notifications/preferences?device_id=${encodeURIComponent(deviceId)}`,
    {},
    'Failed to fetch preferences',
  );
}

export function updateDevicePreferences(
  update: DevicePreferencesUpdate,
): Promise<DevicePreferencesRead> {
  return requestJson<DevicePreferencesRead>(
    '/api/v1/notifications/preferences',
    { method: 'PUT', body: JSON.stringify(update) },
    'Failed to update preferences',
  );
}

export function registerDevice(
  request: DeviceRegisterRequest,
): Promise<DeviceRegisterResponse> {
  return requestJson<DeviceRegisterResponse>(
    '/api/v1/notifications/devices/register',
    { method: 'POST', body: JSON.stringify(request) },
    'Failed to register device',
  );
}

export async function unregisterDevice(deviceId: string): Promise<boolean> {
  const res = await fetch(
    `${API_BASE_URL}/api/v1/notifications/devices/${encodeURIComponent(deviceId)}`,
    { method: 'DELETE', headers: { Accept: 'application/json' } },
  );
  return res.ok;
}

export function fetchNotificationHistory(
  deviceId: string,
  unreadOnly = false,
): Promise<NotificationHistoryResponse> {
  const params = new URLSearchParams({
    device_id: deviceId,
    unread_only: unreadOnly ? 'true' : 'false',
  });
  return requestJson<NotificationHistoryResponse>(
    `/api/v1/notifications/history?${params.toString()}`,
    {},
    'Failed to fetch notification history',
  );
}

export async function markNotificationRead(
  notificationId: string,
  deviceId?: string,
): Promise<boolean> {
  const params = deviceId ? `?device_id=${encodeURIComponent(deviceId)}` : '';
  const res = await fetch(
    `${API_BASE_URL}/api/v1/notifications/history/${notificationId}/read${params}`,
    { method: 'PATCH', headers: { Accept: 'application/json' } },
  );
  return res.ok;
}

export function triggerManualAlertSync(
  disasterType?: DisasterType,
  sourceCode?: string,
): Promise<AlertSyncResponse> {
  return requestJson<AlertSyncResponse>(
    '/api/v1/alerts/sync',
    {
      method: 'POST',
      body: JSON.stringify({
        disaster_type: disasterType || null,
        source_code: sourceCode || null,
      }),
    },
    'Failed to trigger alert sync',
  );
}
