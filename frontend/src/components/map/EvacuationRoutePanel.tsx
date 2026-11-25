import React from 'react';
import { RouteDetailItem } from '../../api/routeApi';
import { formatRouteStatusLabel } from '../../utils/formatters';

interface EvacuationRoutePanelProps {
  route: RouteDetailItem;
  hasSelectedDestination: boolean;
  onClose: () => void;
}

export function EvacuationRoutePanel({
  route,
  hasSelectedDestination,
  onClose,
}: EvacuationRoutePanelProps): React.JSX.Element {
  return (
    <div
      className="side-panel"
      style={{ left: 'auto', right: '12px', width: '360px', top: hasSelectedDestination ? '220px' : '60px' }}
    >
      <div className="side-panel-header">
        <span className="side-panel-title">🗺️ Safe Route</span>
        <button className="close-btn" onClick={onClose} aria-label="Close route panel">✕</button>
      </div>
      <div className="side-panel-body">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
          <span className={`badge ${route.route_status === 'ROUTE_AVAILABLE' ? 'badge-verified' : 'badge-unverified'}`}>
            {formatRouteStatusLabel(route.route_status)}
          </span>
          <span style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-muted)' }}>
            Estimated Road Path
          </span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', marginBottom: '14px' }}>
          <div style={{ background: '#F8FAFC', padding: '8px 12px', borderRadius: '8px', border: '1px solid #E2E8F0' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Distance</span>
            <span style={{ fontSize: '1.1rem', fontWeight: 700, color: '#0F172A' }}>
              {route.distance_km.toFixed(1)} km
            </span>
          </div>
          <div style={{ background: '#F8FAFC', padding: '8px 12px', borderRadius: '8px', border: '1px solid #E2E8F0' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', display: 'block' }}>Est. Time</span>
            <span style={{ fontSize: '1.1rem', fontWeight: 700, color: '#0F172A' }}>
              {route.estimated_time_minutes.toFixed(0)} min
            </span>
          </div>
        </div>

        {route.hazard_intersections && route.hazard_intersections.length > 0 && (
          <div style={{ marginBottom: '14px' }}>
            <span style={{ fontSize: '0.78rem', fontWeight: 700, color: '#B91C1C', textTransform: 'uppercase' }}>
              Hazards Along Way ({route.hazard_intersections.length})
            </span>
            {route.hazard_intersections.map((h, idx) => (
              <div
                key={idx}
                style={{ background: '#FEF2F2', border: '1px solid #FECACA', borderRadius: '6px', padding: '6px 8px', marginTop: '6px', fontSize: '0.78rem', color: '#991B1B' }}
              >
                ⚠️ {h.hazard_type} zone ({h.distance_to_route_m.toFixed(0)} m from route)
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
