import React from 'react';
import { AlertItem, NotificationHistoryItem } from '../../api/alertApi';
import { formatSeverityLabel } from '../../utils/formatters';

interface AlertCenterModalProps {
  alerts: AlertItem[];
  notificationHistory: NotificationHistoryItem[];
  activeTab: 'alerts' | 'history' | 'preferences';
  onChangeTab: (tab: 'alerts' | 'history' | 'preferences') => void;
  onClose: () => void;
}

export function AlertCenterModal({
  alerts,
  notificationHistory,
  activeTab,
  onChangeTab,
  onClose,
}: AlertCenterModalProps): React.JSX.Element {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
          <h2 style={{ fontSize: '1.15rem', fontWeight: 700, margin: 0, color: '#0F172A' }}>
            🔔 Alerts & Updates
          </h2>
          <button
            style={{ background: 'transparent', border: 'none', fontSize: '1.2rem', cursor: 'pointer', color: '#6B7280' }}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div style={{ display: 'flex', gap: '8px', marginBottom: '14px' }}>
          <button
            className={`filter-pill ${activeTab === 'alerts' ? 'active' : ''}`}
            onClick={() => onChangeTab('alerts')}
          >
            Active Alerts ({alerts.length})
          </button>
          <button
            className={`filter-pill ${activeTab === 'history' ? 'active' : ''}`}
            onClick={() => onChangeTab('history')}
          >
            Past Updates ({notificationHistory.length})
          </button>
        </div>

        {activeTab === 'alerts' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', maxHeight: '350px', overflowY: 'auto' }}>
            {alerts.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                No active emergency alerts near you right now.
              </div>
            ) : (
              alerts.map((al) => (
                <div key={al.alert_id} style={{ border: '1px solid #FECACA', background: '#FEF2F2', borderRadius: '8px', padding: '10px' }}>
                  <div style={{ fontWeight: 700, color: '#991B1B', fontSize: '0.85rem' }}>{al.title}</div>
                  <div style={{ fontSize: '0.8rem', color: '#7F1D1D', marginTop: '3px', lineHeight: 1.4 }}>{al.body}</div>
                  <div style={{ fontSize: '0.72rem', color: '#B91C1C', marginTop: '5px' }}>
                    {al.issuing_authority || 'NDMA / IMD / CWC'} • {formatSeverityLabel(al.disaster_type)}
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {activeTab === 'history' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', maxHeight: '350px', overflowY: 'auto' }}>
            {notificationHistory.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                No past notifications on this device yet.
              </div>
            ) : (
              notificationHistory.map((nh) => (
                <div key={nh.notification_id} style={{ border: '1px solid #E2E8F0', borderRadius: '8px', padding: '8px 10px', fontSize: '0.8rem' }}>
                  <div style={{ fontWeight: 600 }}>{nh.title}</div>
                  <div style={{ color: 'var(--text-secondary)', marginTop: '2px' }}>{nh.body}</div>
                </div>
              ))
            )}
          </div>
        )}
      </div>
    </div>
  );
}
