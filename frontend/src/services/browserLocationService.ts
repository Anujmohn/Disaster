import { Capacitor } from '@capacitor/core';
import { bleChatService } from './bleChatService';

export interface BrowserLocationResult {
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  altitude_m: number | null;
  captured_at: string;
}

export type GeolocationErrorType =
  | 'UNSUPPORTED'
  | 'INSECURE_CONTEXT'
  | 'PERMISSION_DENIED'
  | 'POSITION_UNAVAILABLE'
  | 'TIMEOUT'
  | 'UNKNOWN';

export interface BrowserGeolocationError {
  type: GeolocationErrorType;
  message: string;
  permanentlyDenied?: boolean;
}

export function checkGeolocationAvailability(): { available: boolean; reason?: string } {
  if (typeof window === 'undefined' || !('geolocation' in navigator)) {
    return {
      available: false,
      reason: 'Geolocation API is not supported by this browser.',
    };
  }

  if (window.isSecureContext === false) {
    return {
      available: false,
      reason: 'Geolocation requires a secure context (HTTPS or localhost).',
    };
  }

  return { available: true };
}

export async function getCurrentBrowserLocation(): Promise<BrowserLocationResult> {
  const isNative = Capacitor.isNativePlatform();

  if (isNative) {
    const permStatus = await bleChatService.requestLocationPermission();
    if (!permStatus.granted) {
      throw {
        type: 'PERMISSION_DENIED',
        permanentlyDenied: permStatus.permanentlyDenied,
        message: permStatus.permanentlyDenied
          ? 'Location permission was denied. Please enable Location in Android App Settings.'
          : 'Location permission is required to retrieve your GPS coordinates.',
      } satisfies BrowserGeolocationError;
    }

    if (!permStatus.isLocationServicesEnabled) {
      throw {
        type: 'POSITION_UNAVAILABLE',
        message: 'Device Location (GPS) is turned off. Please enable Location in Android Settings.',
      } satisfies BrowserGeolocationError;
    }
  }

  const check = checkGeolocationAvailability();
  if (!check.available) {
    throw {
      type: typeof window !== 'undefined' && window.isSecureContext === false ? 'INSECURE_CONTEXT' : 'UNSUPPORTED',
      message: check.reason ?? 'Geolocation unavailable.',
    } satisfies BrowserGeolocationError;
  }

  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy_m: position.coords.accuracy ?? null,
          altitude_m: position.coords.altitude ?? null,
          captured_at: new Date(position.timestamp).toISOString(),
        });
      },
      (geoError) => {
        let type: GeolocationErrorType = 'UNKNOWN';
        let message = geoError.message || 'An unknown geolocation error occurred.';

        switch (geoError.code) {
          case geoError.PERMISSION_DENIED:
            type = 'PERMISSION_DENIED';
            message = isNative
              ? 'Location access was denied. Please allow Location permission in Android App Settings.'
              : 'Location access was denied. Please allow location permissions in your browser address bar.';
            break;
          case geoError.POSITION_UNAVAILABLE:
            type = 'POSITION_UNAVAILABLE';
            message = 'Location information is unavailable. Ensure your device GPS or system location services are enabled.';
            break;
          case geoError.TIMEOUT:
            type = 'TIMEOUT';
            message = 'The request to obtain your location timed out. Please try again.';
            break;
        }

        reject({ type, message } satisfies BrowserGeolocationError);
      },
      {
        enableHighAccuracy: true,
        timeout: 15000,
        maximumAge: 10000,
      },
    );
  });
}
