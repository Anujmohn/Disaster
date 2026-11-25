import { requestJson } from './client';
import { GeoJSONLineString } from './disasterApi';

export type RouteClassification =
  | 'ROUTE_AVAILABLE'
  | 'ROUTE_WITH_WARNINGS'
  | 'ROUTE_EXCLUDED'
  | 'NO_SAFE_ROUTE_FOUND'
  | 'INSUFFICIENT_DATA'
  | 'ROUTING_SERVICE_UNAVAILABLE';

export interface HazardIntersectionItem {
  hazard_id: string | null;
  hazard_type: string;
  severity: string | null;
  distance_to_route_m: number;
  is_direct_intersection: boolean;
  is_hard_exclusion: boolean;
  description: string;
  source_name: string | null;
  reported_at: string | null;
  approximate_segment_coords: [number, number] | null;
}

export interface RouteDetailItem {
  route_id: string;
  route_geometry: GeoJSONLineString;
  distance_m: number;
  distance_km: number;
  estimated_time_seconds: number;
  estimated_time_minutes: number;
  route_status: RouteClassification;
  hazard_exposure_level: 'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL';
  hazard_intersections_count: number;
  hazard_intersections: HazardIntersectionItem[];
  warnings: string[];
  is_hard_excluded: boolean;
  hard_exclusion_reasons: string[];
  comparison_notes: string | null;
  routing_provider: string;
  calculated_at: string;
}

export interface CalculateRouteRequest {
  origin_latitude: number;
  origin_longitude: number;
  location_assessment_id: string;
  allow_alternatives?: boolean;
}

export interface CalculateRouteResponse {
  success: boolean;
  route_status: RouteClassification;
  origin: { latitude: number; longitude: number };
  destination_id: string;
  destination_name: string | null;
  destination_category: string | null;
  disaster_type: string;
  primary_route: RouteDetailItem | null;
  alternative_routes: RouteDetailItem[];
  total_routes_analyzed: number;
  comparison_summary: string | null;
  routing_provider: string;
  routing_limitations: string[];
  safety_disclaimer: string;
}

export function calculateEvacuationRoute(
  request: CalculateRouteRequest,
): Promise<CalculateRouteResponse> {
  return requestJson<CalculateRouteResponse>(
    '/api/v1/routes/calculate',
    {
      method: 'POST',
      body: JSON.stringify({
        origin_latitude: request.origin_latitude,
        origin_longitude: request.origin_longitude,
        location_assessment_id: request.location_assessment_id,
        allow_alternatives: request.allow_alternatives ?? true,
      }),
    },
    'Failed to calculate evacuation route',
  );
}
