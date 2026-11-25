import React from 'react';
import {
  LocationSuitabilityAssessmentItem,
  SafeLocationItem,
  SafePlaceItem,
} from '../../api/safeLocationApi';
import {
  formatDistance,
  formatSafeLocationCategoryLabel,
  formatSafeLocationDesignationLabel,
  formatSuitabilityLabel,
  formatVerificationLabel,
  isOfficiallyVerifiedShelter,
} from '../../utils/formatters';

interface SafeLocationDetailsPanelProps {
  location: SafeLocationItem;
  safePlace?: SafePlaceItem;
  suitability?: LocationSuitabilityAssessmentItem;
  effectiveRadiusKm: number;
  isSelectedDestination: boolean;
  calculatingRoute: boolean;
  evaluatingSuitability: boolean;
  onClose: () => void;
  onSelectDestination: (location: SafeLocationItem) => void;
  onCalculateRoute: (locationId: string) => void;
  onAssessSuitability: (locationId: string) => void;
}

export function SafeLocationDetailsPanel({
  location,
  safePlace,
  suitability,
  effectiveRadiusKm,
  isSelectedDestination,
  calculatingRoute,
  evaluatingSuitability,
  onClose,
  onSelectDestination,
  onCalculateRoute,
  onAssessSuitability,
}: SafeLocationDetailsPanelProps): React.JSX.Element {
  const floodFields = safePlace?.flood_fields;
  const totalPoints = location.total_points ?? suitability?.total_points;
  const isVerifiedOfficialShelter = isOfficiallyVerifiedShelter(
    location.designation_status,
    location.verification_status,
  );

  return (
    <div className="side-panel">
      <div className="side-panel-header">
        <span className="side-panel-title">🛡️ Safe Place Details</span>
        <button className="close-btn" onClick={onClose} aria-label="Close panel">✕</button>
      </div>
      <div className="side-panel-body">
        <h3 style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '4px' }}>
          {location.name || 'Nearby Shelter'}
        </h3>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '12px' }}>
          {formatSafeLocationCategoryLabel(
            location.category,
            location.designation_status,
            location.verification_status,
          )}{' '}
          • {formatDistance(location.distance_km)} away
        </p>

        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '14px' }}>
          <span className={`badge ${isVerifiedOfficialShelter ? 'badge-official' : 'badge-unverified'}`}>
            {formatSafeLocationDesignationLabel(
              location.designation_status,
              location.verification_status,
            )}
          </span>
          <span className={`badge ${location.verification_status === 'VERIFIED' ? 'badge-verified' : 'badge-unverified'}`}>
            {formatVerificationLabel(location.verification_status)}
          </span>
          {location.capacity != null && (
            <span className="badge" style={{ background: '#F1F5F9', color: '#475569' }}>
              Capacity: {location.capacity}
            </span>
          )}
        </div>

        {totalPoints != null && (
          <div
            style={{
              background: '#F0FDF4',
              border: '1px solid #BBF7D0',
              borderRadius: '8px',
              padding: '10px 12px',
              marginBottom: '12px',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
              <span style={{ fontSize: '0.8rem', fontWeight: 700, color: '#166534' }}>
                Overall Safety Score
              </span>
              <span
                className="badge"
                style={{ background: '#166534', color: '#FFFFFF', fontWeight: 800, fontSize: '0.8rem', padding: '2px 8px' }}
              >
                {totalPoints.toFixed(0)} / 100
              </span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px', fontSize: '0.75rem' }}>
              <div style={{ background: '#FFFFFF', padding: '4px 8px', borderRadius: '4px', border: '1px solid #DCFCE7', color: '#15803D' }}>
                📍 Distance: <strong>{(location.distance_points ?? suitability?.distance_points ?? 0).toFixed(0)} pts</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '4px 8px', borderRadius: '4px', border: '1px solid #DCFCE7', color: '#15803D' }}>
                🛡️ Safety: <strong>{(location.safety_points ?? suitability?.safety_points ?? 0).toFixed(0)} pts</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '4px 8px', borderRadius: '4px', border: '1px solid #DCFCE7', color: '#15803D' }}>
                👥 Capacity: <strong>{(location.availability_points ?? suitability?.availability_points ?? 0).toFixed(0)} pts</strong>
              </div>
              <div style={{ background: '#FFFFFF', padding: '4px 8px', borderRadius: '4px', border: '1px solid #DCFCE7', color: '#15803D' }}>
                ⚡ Facilities: <strong>{(location.features_points ?? suitability?.features_points ?? 0).toFixed(0)} pts</strong>
              </div>
            </div>
          </div>
        )}

        {safePlace && (
          <div
            style={{
              background: '#F8FAFC',
              border: '1px solid #E2E8F0',
              borderRadius: '8px',
              padding: '10px 12px',
              marginBottom: '14px',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <span style={{ fontSize: '0.8rem', fontWeight: 800, color: '#0F172A' }}>
                Safety Checks
              </span>
              <span className="badge" style={{ background: '#EEF2FF', color: '#4338CA', fontWeight: 700, fontSize: '0.72rem' }}>
                Within {effectiveRadiusKm} km
              </span>
            </div>

            {safePlace.earthquake_fields && (
              <div style={{ marginBottom: '8px', borderTop: '1px solid #E2E8F0', paddingTop: '6px' }}>
                <div style={{ fontWeight: 700, fontSize: '0.8rem', color: '#C2410C', marginBottom: '4px' }}>
                  ⚡ Earthquake Safety
                </div>
                <div style={{ fontSize: '0.75rem', color: '#334155', display: 'flex', flexDirection: 'column', gap: '3px' }}>
                  <div>
                    <strong>Distance:</strong> {safePlace.earthquake_fields.distance_from_user_km.toFixed(2)} km (
                    {safePlace.earthquake_fields.distance_from_user_m.toFixed(0)} m)
                  </div>
                  <div>
                    <strong>Open space:</strong>{' '}
                    {safePlace.earthquake_fields.safe_area_m2.toLocaleString()} m² area
                  </div>
                  <div>
                    <strong>Shelter type:</strong>{' '}
                    {safePlace.earthquake_fields.is_open_place
                      ? '✅ ' + (safePlace.earthquake_fields.open_place_type || 'Open Ground')
                      : '🏢 Indoor Structure'}{' '}
                    ({safePlace.earthquake_fields.building_collapse_protection})
                  </div>
                  <div>
                    <strong>Slope & rockfall risk:</strong>{' '}
                    {safePlace.earthquake_fields.stone_crushing_risk === 'ZERO'
                      ? '✅ Low rockfall risk'
                      : '⚠️ Watch for nearby slopes'}{' '}
                    — {safePlace.earthquake_fields.hilly_area_safety}
                  </div>
                </div>
              </div>
            )}

            {floodFields && (
              <div style={{ marginBottom: '8px', borderTop: '1px solid #E2E8F0', paddingTop: '6px' }}>
                <div style={{ fontWeight: 700, fontSize: '0.8rem', color: '#0369A1', marginBottom: '4px' }}>
                  🌊 Flood Safety
                </div>
                <div style={{ fontSize: '0.75rem', color: '#334155', display: 'flex', flexDirection: 'column', gap: '3px' }}>
                  <div>
                    <strong>Place type:</strong>{' '}
                    {floodFields.candidate_type === 'NATURAL_ELEVATED_TERRAIN'
                      ? '⛰️ High Ground'
                      : floodFields.candidate_type === 'ELEVATED_INFRASTRUCTURE'
                        ? '🌉 Elevated Bridge / Flyover'
                        : floodFields.candidate_type === 'OFFICIAL_FLOOD_SHELTER'
                          ? '🏠 Official Flood Shelter'
                          : formatSafeLocationCategoryLabel(
                              location.category,
                              location.designation_status,
                              location.verification_status,
                            )}
                  </div>
                  <div>
                    <strong>Distance:</strong> {floodFields.distance_from_user_km.toFixed(2)} km
                  </div>
                  <div>
                    <strong>Total height:</strong>{' '}
                    {floodFields.elevation_m != null ? `${floodFields.elevation_m.toFixed(1)} m above sea level` : 'Unavailable'}{' '}
                    {floodFields.relative_elevation_m != null
                      ? `(${floodFields.relative_elevation_m >= 0 ? '+' : ''}${floodFields.relative_elevation_m.toFixed(1)} m vs your spot)`
                      : ''}
                  </div>
                  <div>
                    <strong>Ground level:</strong>{' '}
                    {floodFields.ground_elevation_m != null
                      ? `${floodFields.ground_elevation_m.toFixed(1)} m`
                      : floodFields.elevation_m != null
                        ? `${floodFields.elevation_m.toFixed(1)} m`
                        : 'Unavailable'}
                  </div>
                  {floodFields.structure_height_m != null && (
                    <div>
                      <strong>Building height:</strong> +{floodFields.structure_height_m.toFixed(1)} m
                    </div>
                  )}
                  <div><strong>Safety rating:</strong> {floodFields.safety_factor}</div>
                  <div><strong>Shelter type:</strong> {floodFields.safe_building_type}</div>
                  <div>
                    <strong>Access:</strong> {floodFields.public_access_status || 'Check access on arrival'}
                  </div>
                  <div><strong>Water outlook:</strong> {floodFields.flood_flow_prediction}</div>
                  {floodFields.assessment_reasons && floodFields.assessment_reasons.length > 0 && (
                    <div style={{ marginTop: '3px', background: '#F0F9FF', padding: '5px 7px', borderRadius: '4px', border: '1px solid #BAE6FD' }}>
                      <strong style={{ color: '#0369A1' }}>Why it was picked:</strong>
                      {floodFields.assessment_reasons.slice(0, 3).map((r, i) => (
                        <div key={i} style={{ color: '#0C4A6E', marginTop: '2px' }}>✓ {r}</div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}

            {safePlace.cyclone_fields && (
              <div style={{ borderTop: '1px solid #E2E8F0', paddingTop: '6px' }}>
                <div style={{ fontWeight: 700, fontSize: '0.8rem', color: '#7E22CE', marginBottom: '4px' }}>
                  🌀 Cyclone Safety
                </div>
                <div style={{ fontSize: '0.75rem', color: '#334155', display: 'flex', flexDirection: 'column', gap: '3px' }}>
                  <div>
                    <strong>Distance:</strong> {safePlace.cyclone_fields.distance_from_user_km.toFixed(2)} km
                  </div>
                  <div>
                    <strong>Coastal exposure:</strong> {safePlace.cyclone_fields.affect_of_typhoon_on_area} (
                    {safePlace.cyclone_fields.distance_to_coastline_km.toFixed(1)} km from coast)
                  </div>
                  <div>
                    <strong>Shelter strength:</strong> {safePlace.cyclone_fields.safety_factor_of_area} (
                    {safePlace.cyclone_fields.cyclone_shelter_engineering})
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {suitability && (
          <div
            style={{
              background:
                suitability.suitability_classification === 'SUITABILITY_SUPPORTED_BY_EVIDENCE'
                  ? '#F0FDF4'
                  : suitability.suitability_classification === 'EXCLUDED'
                    ? '#FEF2F2'
                    : '#FFFBEB',
              border: `1px solid ${
                suitability.suitability_classification === 'SUITABILITY_SUPPORTED_BY_EVIDENCE'
                  ? '#BBF7D0'
                  : suitability.suitability_classification === 'EXCLUDED'
                    ? '#FECACA'
                    : '#FDE68A'
              }`,
              borderRadius: '8px',
              padding: '10px 12px',
              marginBottom: '16px',
            }}
          >
            <div style={{ fontWeight: 700, fontSize: '0.82rem', marginBottom: '4px', color: '#166534' }}>
              Status: {formatSuitabilityLabel(suitability.suitability_classification)}
            </div>
            {suitability.supporting_evidence.slice(0, 3).map((ev, idx) => (
              <div key={idx} style={{ fontSize: '0.75rem', color: '#166534', lineHeight: 1.35, marginTop: '2px' }}>
                ✓ {ev}
              </div>
            ))}
            {suitability.warnings.slice(0, 2).map((wn, idx) => (
              <div key={idx} style={{ fontSize: '0.75rem', color: '#B45309', lineHeight: 1.35, marginTop: '2px' }}>
                ℹ️ {wn}
              </div>
            ))}
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          <button
            className="action-btn-pill action-btn-primary"
            style={{
              justifyContent: 'center',
              background: isSelectedDestination ? '#15803D' : '#0284C7',
            }}
            onClick={() => onSelectDestination(location)}
          >
            {isSelectedDestination ? '★ Selected Destination' : '★ Choose This Safe Place'}
          </button>

          <button
            className="action-btn-pill"
            style={{ justifyContent: 'center' }}
            onClick={() => onCalculateRoute(location.id)}
            disabled={calculatingRoute}
          >
            {calculatingRoute ? 'Finding Route…' : '🗺️ Show Directions'}
          </button>

          <button
            className="action-btn-pill"
            style={{ justifyContent: 'center' }}
            onClick={() => onAssessSuitability(location.id)}
            disabled={evaluatingSuitability}
          >
            {evaluatingSuitability ? 'Checking Safety…' : '🔍 Refresh Safety Check'}
          </button>
        </div>
      </div>
    </div>
  );
}
