import React from 'react';
import {
  RecommendedDestinationItem,
  UnifiedRecommendationResponse,
} from '../../api/recommendationApi';
import {
  formatCategoryLabel,
  formatDistance,
  formatRecommendationStatus,
  formatSeverityLabel,
  formatSuitabilityLabel,
} from '../../utils/formatters';

interface UnifiedRecommendationsModalProps {
  recommendations: UnifiedRecommendationResponse;
  onClose: () => void;
  onSelectRecommendation: (rec: RecommendedDestinationItem) => void;
}

export function UnifiedRecommendationsModal({
  recommendations,
  onClose,
  onSelectRecommendation,
}: UnifiedRecommendationsModalProps): React.JSX.Element {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
          <h2 style={{ fontSize: '1.15rem', fontWeight: 700, margin: 0, color: '#0F172A' }}>
            🛡️ Safest Places Nearby ({formatSeverityLabel(recommendations.disaster_type)})
          </h2>
          <button
            style={{ background: 'transparent', border: 'none', fontSize: '1.2rem', cursor: 'pointer', color: '#6B7280' }}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '10px' }}>
          <span className="badge badge-verified">{formatRecommendationStatus(recommendations.status)}</span>
          <span className="badge" style={{ background: '#F1F5F9', color: '#475569' }}>
            Checked {recommendations.total_candidates_found || recommendations.recommendations.length} places
          </span>
          {recommendations.diagnostics && (
            <span className="badge" style={{ background: '#E0F2FE', color: '#0369A1' }}>
              Search radius:{' '}
              {recommendations.diagnostics.effective_radius_m >= 1000
                ? `${(recommendations.diagnostics.effective_radius_m / 1000).toFixed(1)} km`
                : `${recommendations.diagnostics.effective_radius_m.toFixed(0)} m`}
            </span>
          )}
        </div>

        {recommendations.diagnostics && (
          <div style={{ fontSize: '0.76rem', background: '#F8FAFC', border: '1px solid #E2E8F0', borderRadius: '8px', padding: '8px 10px', marginBottom: '12px', color: '#475569', lineHeight: 1.45 }}>
            <div>
              <strong>Sources:</strong>{' '}
              {recommendations.diagnostics.data_sources_succeeded.join(', ') || 'OpenStreetMap, Regional Elevation'}
            </div>
            <div>
              <strong>Summary:</strong> {recommendations.diagnostics.candidates_suitable_with_evidence} Recommended •{' '}
              {recommendations.diagnostics.candidates_conditionally_suitable} Use With Caution •{' '}
              {recommendations.diagnostics.candidates_excluded + recommendations.diagnostics.candidates_not_recommended}{' '}
              Skipped for Safety
            </div>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', maxHeight: '420px', overflowY: 'auto' }}>
          {recommendations.recommendations.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '24px 16px', background: '#F9FAFB', border: '1px dashed #D1D5DB', borderRadius: '10px' }}>
              <div style={{ fontSize: '1.4rem', marginBottom: '8px' }}>🛡️</div>
              <div style={{ fontWeight: 600, color: '#374151', fontSize: '0.9rem', marginBottom: '4px' }}>
                {recommendations.status === 'NO_CANDIDATES_FOUND'
                  ? 'No Safe Places Found Nearby'
                  : recommendations.status === 'NO_SUITABLE_DESTINATION_FOUND'
                    ? 'No Options Passed Safety Checks'
                    : 'Search Complete'}
              </div>
              <div style={{ fontSize: '0.8rem', color: '#6B7280', maxWidth: '380px', margin: '0 auto', lineHeight: 1.45 }}>
                {recommendations.overall_uncertainties[0] ||
                  'We searched outward up to 5 km, but could not find a place that meets safety criteria here.'}
              </div>
              {recommendations.diagnostics?.rejections && recommendations.diagnostics.rejections.length > 0 && (
                <div style={{ marginTop: '12px', textAlign: 'left', background: '#FFFBEB', border: '1px solid #FDE68A', borderRadius: '6px', padding: '8px 10px', fontSize: '0.75rem' }}>
                  <div style={{ fontWeight: 700, color: '#92400E', marginBottom: '4px' }}>Why nearby places were skipped:</div>
                  {recommendations.diagnostics.rejections.slice(0, 3).map((rej, rIdx) => (
                    <div key={rIdx} style={{ color: '#B45309', marginBottom: '2px' }}>
                      • <strong>{rej.name || formatCategoryLabel(rej.category)}:</strong> {rej.primary_reason}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            recommendations.recommendations.map((rec, idx) => (
              <div
                key={rec.destination_id}
                style={{
                  border: '1px solid #E2E8F0',
                  borderRadius: '10px',
                  padding: '10px 12px',
                  background: idx === 0 ? '#F0FDF4' : '#FFFFFF',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                  <span style={{ fontWeight: 700, fontSize: '0.92rem', color: '#0F172A' }}>
                    #{idx + 1} {rec.name || 'Safe Shelter'}
                  </span>
                  <span className="badge badge-verified">
                    {rec.total_points != null ? `${rec.total_points.toFixed(0)}/100` : formatDistance(rec.distance_km)}
                  </span>
                </div>

                <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '6px' }}>
                  <span
                    className={`badge ${rec.suitability_classification === 'SUITABILITY_SUPPORTED_BY_EVIDENCE' ? 'badge-verified' : 'badge-unverified'}`}
                    style={{ fontSize: '0.7rem' }}
                  >
                    {formatSuitabilityLabel(rec.suitability_classification)}
                  </span>
                  <span className="badge" style={{ background: '#F1F5F9', color: '#475569', fontSize: '0.7rem' }}>
                    {formatCategoryLabel(rec.category)} • {formatDistance(rec.distance_km)}
                  </span>
                </div>

                {rec.suitability_reasons && rec.suitability_reasons.length > 0 && (
                  <div style={{ fontSize: '0.75rem', color: '#166534', marginBottom: '4px' }}>
                    ✓ {rec.suitability_reasons[0]}
                  </div>
                )}

                {rec.suitability_warnings && rec.suitability_warnings.length > 0 && (
                  <div style={{ fontSize: '0.72rem', color: '#B45309', marginBottom: '6px', background: '#FEF3C7', padding: '4px 6px', borderRadius: '4px' }}>
                    ⚠️ {rec.suitability_warnings[0]}
                  </div>
                )}

                <button
                  className="action-btn-pill action-btn-primary"
                  style={{ fontSize: '0.75rem', padding: '5px 12px', marginTop: '4px' }}
                  onClick={() => onSelectRecommendation(rec)}
                >
                  Choose & Show Route
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
