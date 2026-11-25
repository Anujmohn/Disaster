import React from 'react';
import { DisasterEventSummary, DisasterType } from '../../api/disasterApi';
import { formatFreshnessLabel, formatSeverityLabel } from '../../utils/formatters';
import { getBasinName, getDisasterCoords, getFlowDirectionText } from '../../utils/floodFlow';

interface DisasterDetailsPanelProps {
  disaster: DisasterEventSummary;
  onClose: () => void;
  onFocusFloodPath: (disaster: DisasterEventSummary) => void;
  onAnalyzeEvacuation: (disasterType: DisasterType) => void;
}

export function DisasterDetailsPanel({
  disaster,
  onClose,
  onFocusFloodPath,
  onAnalyzeEvacuation,
}: DisasterDetailsPanelProps): React.JSX.Element {
  const coords = getDisasterCoords(disaster);

  return (
    <div className="side-panel">
      <div className="side-panel-header">
        <span className="side-panel-title">
          {disaster.disaster_type === 'EARTHQUAKE'
            ? '⚡ Earthquake Alert'
            : disaster.disaster_type === 'FLOOD'
              ? '🌊 Flood Alert'
              : '🌀 Cyclone Alert'}
        </span>
        <button className="close-btn" onClick={onClose} aria-label="Close panel">✕</button>
      </div>
      <div className="side-panel-body">
        <h3 style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '8px' }}>
          {disaster.title}
        </h3>
        <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginBottom: '14px', lineHeight: 1.45 }}>
          {disaster.description || 'Active hazard alert reported by official monitoring agencies.'}
        </p>

        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '16px' }}>
          <span className={`badge ${disaster.freshness === 'FRESH' ? 'badge-fresh' : disaster.freshness === 'STALE' ? 'badge-stale' : 'badge-expired'}`}>
            {formatFreshnessLabel(disaster.freshness)}
          </span>
          <span className="badge" style={{ background: '#E0E7FF', color: '#3730A3' }}>
            Severity: {formatSeverityLabel(disaster.severity)}
          </span>
          <span className="badge" style={{ background: '#F1F5F9', color: '#475569' }}>
            {disaster.status === 'ACTIVE' ? 'Active' : formatSeverityLabel(disaster.status)}
          </span>
        </div>

        {disaster.sources && disaster.sources.length > 0 && (
          <div style={{ marginBottom: '16px', borderTop: '1px solid #E2E8F0', paddingTop: '12px' }}>
            <span style={{ fontSize: '0.78rem', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Reported By
            </span>
            {disaster.sources.map((s, idx) => (
              <div key={idx} style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                🏛️ <strong>{s.source_name}</strong> ({s.source_type})
              </div>
            ))}
          </div>
        )}

        {disaster.disaster_type === 'FLOOD' && (
          <div style={{ marginBottom: '16px', background: '#F0F9FF', border: '1.5px solid #BAE6FD', borderRadius: '10px', padding: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
              <span style={{ fontSize: '1.25rem' }}>🌊</span>
              <div>
                <strong style={{ fontSize: '0.88rem', color: '#0369A1', display: 'block' }}>
                  Likely Flood Water Path
                </strong>
                <span style={{ fontSize: '0.72rem', color: '#0288D1' }}>
                  Shown in bright blue along the river valley on the map
                </span>
              </div>
            </div>
            <p style={{ fontSize: '0.78rem', color: '#0C4A6E', margin: '0 0 10px 0', lineHeight: 1.45 }}>
              Estimated downstream water movement based on local river slope and drainage.
            </p>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', fontSize: '0.75rem', marginBottom: '10px' }}>
              <div style={{ background: '#FFFFFF', padding: '6px 8px', borderRadius: '6px', border: '1px solid #E0F2FE' }}>
                <span style={{ color: '#64748B' }}>River Basin:</span><br />
                <strong style={{ color: '#0F172A' }}>{getBasinName(coords)}</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '6px 8px', borderRadius: '6px', border: '1px solid #E0F2FE' }}>
                <span style={{ color: '#64748B' }}>Flow Direction:</span><br />
                <strong style={{ color: '#0F172A' }}>{getFlowDirectionText(coords)}</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '6px 8px', borderRadius: '6px', border: '1px solid #E0F2FE' }}>
                <span style={{ color: '#64748B' }}>Estimated Speed:</span><br />
                <strong style={{ color: '#0288D1' }}>~1.2 – 1.6 m/s</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '6px 8px', borderRadius: '6px', border: '1px solid #E0F2FE' }}>
                <span style={{ color: '#64748B' }}>Watch Zone:</span><br />
                <strong style={{ color: '#D97706' }}>500 m buffer</strong>
              </div>
            </div>
            <button
              className="action-btn-pill"
              style={{ width: '100%', justifyContent: 'center', fontSize: '0.78rem', background: '#0288D1', color: '#FFFFFF', border: 'none', padding: '6px 10px' }}
              onClick={() => onFocusFloodPath(disaster)}
            >
              🔍 Zoom to Flood Path
            </button>
          </div>
        )}

        <button
          className="action-btn-pill action-btn-primary"
          style={{ width: '100%', justifyContent: 'center', marginTop: '8px' }}
          onClick={() => onAnalyzeEvacuation(disaster.disaster_type)}
        >
          Find Safe Places Near Me
        </button>
      </div>
    </div>
  );
}
