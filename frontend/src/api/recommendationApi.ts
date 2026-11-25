import { requestJson } from './client';
import { DisasterEventSummary } from './disasterApi';
import { RouteClassification, RouteDetailItem } from './routeApi';

export type DisasterType = 'EARTHQUAKE' | 'FLOOD' | 'CYCLONE';

export type SuitabilityClassification =
  | 'SUITABILITY_SUPPORTED_BY_EVIDENCE'
  | 'CONDITIONALLY_SUITABLE'
  | 'NOT_RECOMMENDED'
  | 'EXCLUDED'
  | 'INSUFFICIENT_DATA'
  | 'UNKNOWN';

export type DesignationStatus = 'OFFICIAL' | 'POTENTIAL' | 'UNKNOWN';
export type VerificationStatus = 'VERIFIED' | 'UNVERIFIED' | 'REJECTED';

export type UnifiedRecommendationStatus =
  | 'RECOMMENDATIONS_AVAILABLE'
  | 'LIMITED_RECOMMENDATIONS'
  | 'NO_SUITABLE_DESTINATION_FOUND'
  | 'NO_CANDIDATES_FOUND'
  | 'NO_ROUTE_AVAILABLE'
  | 'INSUFFICIENT_DATA'
  | 'DISASTER_CONTEXT_UNAVAILABLE'
  | 'ASSESSMENT_FAILED';

export interface CandidateRejectionDetail {
  candidate_id?: string | null;
  name: string | null;
  category: string;
  distance_km: number;
  suitability_classification: SuitabilityClassification;
  primary_reason: string;
  detailed_reasons: string[];
}

export interface StageDiagnostics {
  user_coordinates: { latitude?: number; longitude?: number };
  disaster_type: DisasterType;
  search_radii_attempted_m: number[];
  effective_radius_m: number;
  data_sources_requested: string[];
  data_sources_succeeded: string[];
  data_sources_failed: string[];
  raw_geographical_features_found: number;
  valid_candidates_discovered: number;
  enrichment_status: Record<string, unknown>;
  candidates_assessed: number;
  candidates_suitable_with_evidence: number;
  candidates_conditionally_suitable: number;
  candidates_not_recommended: number;
  candidates_excluded: number;
  candidates_insufficient_data: number;
  rejections: CandidateRejectionDetail[];
}

export interface DisasterHazardAssessmentSummary {
  disaster_type: DisasterType;
  status: string;
  summary: string;
  data_freshness: string;
  key_hazards: string[];
  warnings: string[];
  limitations: string[];
}

export interface RecommendedDestinationItem {
  destination_id: string;
  assessment_id?: string | null;
  name: string | null;
  category: string;
  official_designation: DesignationStatus;
  verification_status: VerificationStatus;
  verification_detail: string;
  coordinates: { latitude: number; longitude: number };
  distance_km: number;
  suitability_classification: SuitabilityClassification;
  suitability_score: number | null;
  distance_points?: number | null;
  safety_points?: number | null;
  availability_points?: number | null;
  features_points?: number | null;
  total_points?: number | null;
  suitability_reasons: string[];
  suitability_warnings: string[];
  route: RouteDetailItem | null;
  route_status: RouteClassification | null;
  ranking_score: number | null;
  ranking_rationale: string;
}

export interface UnifiedRecommendationRequest {
  latitude: number;
  longitude: number;
  disaster_type: DisasterType;
  crisis_event_id?: string | null;
  search_radius_km?: number;
  max_destinations?: number;
}

export interface UnifiedRecommendationResponse {
  success: boolean;
  status: UnifiedRecommendationStatus;
  user_location: { latitude: number; longitude: number };
  disaster_type: DisasterType;
  crisis_event: DisasterEventSummary | null;
  hazard_assessment: DisasterHazardAssessmentSummary;
  total_candidates_found: number;
  total_suitable_destinations: number;
  total_routed_destinations: number;
  recommendations: RecommendedDestinationItem[];
  excluded_destinations_count: number;
  diagnostics?: StageDiagnostics | null;
  methodology_and_ranking_notes: string[];
  overall_uncertainties: string[];
  safety_disclaimer: string;
}

export interface LocationSuitabilityAssessmentItem {
  assessment_id?: string | null;
  location_id: string;
  name: string | null;
  category: string;
  official_designation: DesignationStatus;
  verification_status: VerificationStatus;
  verification_detail: string;
  distance_km: number;
  disaster_type: DisasterType;
  suitability_classification: SuitabilityClassification;
  suitability_score: number | null;
  distance_points?: number | null;
  safety_points?: number | null;
  availability_points?: number | null;
  features_points?: number | null;
  total_points?: number | null;
  data_completeness: number | null;
  hard_exclusion_status: boolean;
  hard_exclusion_reasons: string[];
  supporting_evidence: string[];
  identified_hazards: string[];
  warnings: string[];
  missing_information: string[];
  data_sources: string[];
  data_freshness: string;
  assessed_at: string;
}

export interface AssessRecommendationsRequest {
  latitude: number;
  longitude: number;
  disaster_type: DisasterType;
  crisis_event_id?: string | null;
  search_radius_km?: number;
  limit?: number;
}

export interface AssessRecommendationsResponse {
  success: boolean;
  recommendation_run_id: string;
  user_location: { latitude: number; longitude: number };
  disaster_type: DisasterType;
  crisis_event_id: string | null;
  total_candidates_assessed: number;
  total_potentially_suitable: number;
  total_conditionally_suitable: number;
  total_not_recommended: number;
  total_excluded: number;
  total_insufficient_data: number;
  assessed_locations: LocationSuitabilityAssessmentItem[];
  diagnostics?: StageDiagnostics | null;
  assessment_methodology: string;
  overall_data_limitations: string[];
  safety_disclaimer: string;
}

export function assessSafeLocationsSuitability(
  request: AssessRecommendationsRequest,
): Promise<AssessRecommendationsResponse> {
  return requestJson<AssessRecommendationsResponse>(
    '/api/v1/recommendations/assess',
    { method: 'POST', body: JSON.stringify(request) },
    'Failed to assess recommendations',
  );
}

export function fetchUnifiedRecommendations(
  request: UnifiedRecommendationRequest,
): Promise<UnifiedRecommendationResponse> {
  return requestJson<UnifiedRecommendationResponse>(
    '/api/v1/recommendations/unified',
    { method: 'POST', body: JSON.stringify(request) },
    'Failed to generate unified recommendations',
  );
}
