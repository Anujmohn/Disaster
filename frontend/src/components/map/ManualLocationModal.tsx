import React, { useState } from 'react';

const CITY_PRESETS = [
  { name: 'Patna (Flood Zone)', lat: 25.5941, lng: 85.1376 },
  { name: 'Puri Coast (Cyclone)', lat: 19.8135, lng: 85.8312 },
  { name: 'Dehradun (Hills)', lat: 30.3165, lng: 78.0322 },
  { name: 'Delhi NCR', lat: 28.6139, lng: 77.2090 },
  { name: 'Kolkata', lat: 22.5726, lng: 88.3639 },
  { name: 'Mumbai Coast', lat: 19.0760, lng: 72.8777 },
];

interface ManualLocationModalProps {
  manualLat: string;
  manualLng: string;
  onChangeLat: (value: string) => void;
  onChangeLng: (value: string) => void;
  onClose: () => void;
  onStartMapTap: () => void;
  onApplyLocation: (lat: number, lng: number, cityName?: string) => void;
}

export function ManualLocationModal({
  manualLat,
  manualLng,
  onChangeLat,
  onChangeLng,
  onClose,
  onStartMapTap,
  onApplyLocation,
}: ManualLocationModalProps): React.JSX.Element {
  const [inputError, setInputError] = useState<string | null>(null);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
          <h2 style={{ fontSize: '1.1rem', fontWeight: 700, margin: 0, color: '#0F172A' }}>
            📍 Choose Your Location
          </h2>
          <button
            style={{ background: 'transparent', border: 'none', fontSize: '1.2rem', cursor: 'pointer', color: '#6B7280' }}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </div>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '16px', lineHeight: 1.45 }}>
          Pick a city below, type coordinates, or tap directly on the map to find safe shelters within 5 km.
        </p>

        <div style={{ marginBottom: '16px', background: '#F0FDF4', border: '1px solid #86EFAC', borderRadius: '10px', padding: '12px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '8px' }}>
            <div>
              <strong style={{ fontSize: '0.85rem', color: '#166534', display: 'block' }}>👆 Tap Anywhere on Map</strong>
              <span style={{ fontSize: '0.75rem', color: '#15803D' }}>
                Click your street or neighborhood on the map to place a pin.
              </span>
            </div>
            <button
              className="action-btn-pill action-btn-primary"
              style={{ fontSize: '0.75rem', padding: '6px 12px', whiteSpace: 'nowrap' }}
              onClick={onStartMapTap}
            >
              Tap on Map
            </button>
          </div>
        </div>

        <div style={{ marginBottom: '16px' }}>
          <span style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', display: 'block', marginBottom: '8px' }}>
            Quick Cities in India
          </span>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
            {CITY_PRESETS.map((c) => (
              <button
                key={c.name}
                className="action-btn-pill"
                style={{ fontSize: '0.75rem', padding: '6px 8px', justifyContent: 'center' }}
                onClick={() => onApplyLocation(c.lat, c.lng, c.name)}
              >
                {c.name}
              </button>
            ))}
          </div>
        </div>

        <div style={{ display: 'flex', gap: '10px', marginBottom: '12px' }}>
          <div style={{ flex: 1 }}>
            <label style={{ fontSize: '0.75rem', fontWeight: 600, color: '#374151', display: 'block', marginBottom: '4px' }}>
              Latitude
            </label>
            <input
              type="number"
              step="0.0001"
              className="input-field"
              value={manualLat}
              onChange={(e) => {
                setInputError(null);
                onChangeLat(e.target.value);
              }}
              style={{ width: '100%', padding: '6px 10px', border: '1px solid #D1D5DB', borderRadius: '6px' }}
            />
          </div>
          <div style={{ flex: 1 }}>
            <label style={{ fontSize: '0.75rem', fontWeight: 600, color: '#374151', display: 'block', marginBottom: '4px' }}>
              Longitude
            </label>
            <input
              type="number"
              step="0.0001"
              className="input-field"
              value={manualLng}
              onChange={(e) => {
                setInputError(null);
                onChangeLng(e.target.value);
              }}
              style={{ width: '100%', padding: '6px 10px', border: '1px solid #D1D5DB', borderRadius: '6px' }}
            />
          </div>
        </div>

        {inputError && <div className="modal-inline-error">{inputError}</div>}

        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            className="action-btn-pill action-btn-primary"
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => {
              const lat = parseFloat(manualLat);
              const lng = parseFloat(manualLng);
              if (isNaN(lat) || isNaN(lng)) {
                setInputError('Please enter valid numbers for latitude and longitude.');
                return;
              }
              onApplyLocation(lat, lng);
            }}
          >
            Set Location & Find Safe Places
          </button>
        </div>
      </div>
    </div>
  );
}
