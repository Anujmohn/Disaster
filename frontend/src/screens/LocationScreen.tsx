import React, { useEffect, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import {
  BrowserGeolocationError,
  checkGeolocationAvailability,
  getCurrentBrowserLocation,
} from '../services/browserLocationService';
import { bleChatService } from '../services/bleChatService';
import { ApiValidationError, LocationData, validateLocation } from '../api/locationApi';

type ScreenState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; location: LocationData }
  | { status: 'permission_denied'; permanentlyDenied?: boolean }
  | { status: 'insecure_context' }
  | { status: 'gps_error'; message: string }
  | { status: 'network_error'; message: string }
  | { status: 'server_error'; message: string }
  | { status: 'validation_error'; errors: ApiValidationError[] };

function formatCoord(value: number, decimals = 5): string {
  return value.toFixed(decimals);
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  } catch {
    return iso;
  }
}

export default function LocationScreen(): React.JSX.Element {
  const [state, setState] = useState<ScreenState>({ status: 'idle' });
  const isNative = Capacitor.isNativePlatform();

  useEffect(() => {
    if (!isNative) return;
    let isMounted = true;

    const refreshPermState = async () => {
      const perm = await bleChatService.checkLocationPermission();
      if (!isMounted) return;
      if (perm.granted) {
        setState((prev) => (prev.status === 'permission_denied' ? { status: 'idle' } : prev));
      }
    };

    refreshPermState();
    const permSub = bleChatService.onPermissionsChanged((data) => {
      if (!isMounted) return;
      if (data.hasLocationPermissions) {
        setState((prev) => (prev.status === 'permission_denied' ? { status: 'idle' } : prev));
      }
    });

    const onVisibility = () => {
      if (!document.hidden) {
        refreshPermState();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      isMounted = false;
      document.removeEventListener('visibilitychange', onVisibility);
      permSub?.then((h) => h.remove());
    };
  }, [isNative]);

  async function handleGetLocation(): Promise<void> {
    setState({ status: 'loading' });

    const check = checkGeolocationAvailability();
    if (!check.available) {
      if (typeof window !== 'undefined' && window.isSecureContext === false) {
        setState({ status: 'insecure_context' });
      } else {
        setState({ status: 'gps_error', message: check.reason ?? 'Location is unavailable on this device.' });
      }
      return;
    }

    try {
      const fix = await getCurrentBrowserLocation();
      const res = await validateLocation({
        latitude: fix.latitude,
        longitude: fix.longitude,
        accuracy_m: fix.accuracy_m,
        altitude_m: fix.altitude_m,
        captured_at: fix.captured_at,
      });

      if (res.ok) {
        setState({ status: 'success', location: res.data.location });
      } else if (res.kind === 'validation') {
        setState({ status: 'validation_error', errors: res.errors });
      } else if (res.kind === 'network') {
        setState({ status: 'network_error', message: res.message });
      } else {
        setState({ status: 'server_error', message: res.message });
      }
    } catch (err) {
      if (err && typeof err === 'object' && 'type' in err) {
        const geoErr = err as BrowserGeolocationError;
        if (geoErr.type === 'PERMISSION_DENIED') {
          setState({ status: 'permission_denied', permanentlyDenied: geoErr.permanentlyDenied });
          return;
        }
        if (geoErr.type === 'INSECURE_CONTEXT') {
          setState({ status: 'insecure_context' });
          return;
        }
        setState({ status: 'gps_error', message: geoErr.message });
        return;
      }

      const msg = err instanceof Error ? err.message : String(err);
      setState({ status: 'server_error', message: msg });
    }
  }

  const isLoading = state.status === 'loading';

  return (
    <div className="location-page-container">
      <div className="location-card">
        <div className="location-hero-icon" aria-hidden="true">📍</div>
        <h1 className="location-title">Your Current Location</h1>
        <p className="location-subtitle">Used to find nearby shelters, alerts, and safe evacuation routes</p>

        {state.status === 'idle' && (
          <p style={{ color: 'var(--text-secondary)', marginBottom: '24px', fontSize: '0.95rem', lineHeight: 1.5 }}>
            {isNative
              ? 'Tap below to confirm your phone’s GPS location so we can guide you to the closest safe places.'
              : 'Share your current location so we can show nearby alerts and safe shelters around you.'}
          </p>
        )}

        {state.status === 'loading' && (
          <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
            <div
              style={{
                width: '32px',
                height: '32px',
                border: '3px solid #E2E8F0',
                borderTopColor: 'var(--accent)',
                borderRadius: '50%',
                animation: 'spin 1s linear infinite',
              }}
            />
            <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', fontWeight: 500 }}>
              Finding your exact position…
            </p>
          </div>
        )}

        {state.status === 'success' && (
          <div className="data-table-card" style={{ borderColor: '#BBF7D0', background: '#F0FDF4' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
              <span style={{ fontWeight: 700, color: '#166534', fontSize: '0.95rem' }}>
                ✓ Location Confirmed
              </span>
              <span className="badge badge-verified">Ready</span>
            </div>

            <div className="data-row">
              <span className="data-label">Latitude</span>
              <span className="data-value">{formatCoord(state.location.latitude)}°</span>
            </div>
            <div className="data-row">
              <span className="data-label">Longitude</span>
              <span className="data-value">{formatCoord(state.location.longitude)}°</span>
            </div>
            <div className="data-row">
              <span className="data-label">Signal Accuracy</span>
              <span className="data-value">
                {state.location.accuracy_m != null ? `Within ${Math.round(state.location.accuracy_m)} m` : 'Unavailable'}
              </span>
            </div>
            <div className="data-row">
              <span className="data-label">Elevation</span>
              <span className="data-value">
                {state.location.altitude_m != null ? `${state.location.altitude_m.toFixed(1)} m` : 'Unavailable'}
              </span>
            </div>
            <div className="data-row">
              <span className="data-label">Updated</span>
              <span className="data-value">{formatDate(state.location.captured_at)}</span>
            </div>

            <div style={{ marginTop: '12px', fontSize: '0.8rem', color: '#166534', textAlign: 'center' }}>
              {isNative ? '📍 Using Phone GPS' : '🌐 Using Browser Location'}
            </div>
          </div>
        )}

        {state.status === 'permission_denied' && (
          <div style={{ background: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '12px', padding: '16px', marginBottom: '24px' }}>
            <div style={{ fontSize: '2rem', marginBottom: '8px' }}>🔒</div>
            <h3 style={{ fontSize: '1rem', fontWeight: 700, color: '#991B1B', marginBottom: '6px' }}>Location Access Needed</h3>
            <p style={{ fontSize: '0.875rem', color: '#7F1D1D', lineHeight: 1.45, marginBottom: isNative && state.permanentlyDenied ? '12px' : 0 }}>
              {isNative
                ? state.permanentlyDenied
                  ? 'Location permission is turned off. Open App Settings, allow Location for this app, and come back here.'
                  : 'We need location permission to find shelters near you. Tap below to allow it.'
                : 'Location access was blocked. Click the lock or location icon in your browser address bar, allow location, and try again.'}
            </p>
            {isNative && state.permanentlyDenied && (
              <button
                type="button"
                className="action-btn-pill"
                style={{ width: '100%', justifyContent: 'center', background: '#991B1B', color: '#FFFFFF', border: 'none' }}
                onClick={() => bleChatService.openAppSettings()}
              >
                Open App Settings
              </button>
            )}
          </div>
        )}

        {state.status === 'insecure_context' && (
          <div style={{ background: '#FFFBEB', border: '1px solid #FDE68A', borderRadius: '12px', padding: '16px', marginBottom: '24px' }}>
            <div style={{ fontSize: '2rem', marginBottom: '8px' }}>⚠️</div>
            <h3 style={{ fontSize: '1rem', fontWeight: 700, color: '#92400E', marginBottom: '6px' }}>Secure Connection Needed</h3>
            <p style={{ fontSize: '0.875rem', color: '#B45309', lineHeight: 1.45 }}>
              Browsers only share GPS location over HTTPS or localhost. Open this page with HTTPS (or localhost) to detect your location automatically.
            </p>
          </div>
        )}

        {(state.status === 'gps_error' || state.status === 'network_error' || state.status === 'server_error') && (
          <div style={{ background: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '12px', padding: '16px', marginBottom: '24px' }}>
            <div style={{ fontSize: '2rem', marginBottom: '8px' }}>⚠️</div>
            <h3 style={{ fontSize: '1rem', fontWeight: 700, color: '#991B1B', marginBottom: '6px' }}>
              {state.status === 'network_error' ? 'Connection Issue' : 'Could Not Read Location'}
            </h3>
            <p style={{ fontSize: '0.875rem', color: '#7F1D1D', lineHeight: 1.45 }}>
              {state.message}
            </p>
          </div>
        )}

        {state.status === 'validation_error' && (
          <div style={{ background: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '12px', padding: '16px', marginBottom: '24px' }}>
            <div style={{ fontSize: '2rem', marginBottom: '8px' }}>📍</div>
            <h3 style={{ fontSize: '1rem', fontWeight: 700, color: '#991B1B', marginBottom: '6px' }}>Location Outside Coverage</h3>
            <p style={{ fontSize: '0.875rem', color: '#7F1D1D', lineHeight: 1.45 }}>
              {state.errors.map((e) => e.msg).join('; ')}
            </p>
          </div>
        )}

        <button
          className="action-btn-pill action-btn-primary"
          style={{ width: '100%', justifyContent: 'center', padding: '12px 24px', fontSize: '1rem' }}
          onClick={handleGetLocation}
          disabled={isLoading}
        >
          {isLoading ? 'Checking Location…' : state.status === 'success' ? '↻ Update My Location' : '📍 Share My Location'}
        </button>
      </div>

      <style>{`
        @keyframes spin {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
