import { API_BASE_URL } from './client';

export interface LocationRequest {
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  altitude_m: number | null;
  captured_at: string;
}

export interface GeoJSONPoint {
  type: 'Point';
  coordinates: [number, number];
}

export interface LocationData {
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  altitude_m: number | null;
  captured_at: string;
  geometry: GeoJSONPoint;
}

export interface LocationValidationResponse {
  success: boolean;
  location: LocationData;
}

export interface ApiValidationError {
  type: string;
  loc: (string | number)[];
  msg: string;
  url?: string;
}

export type ValidateLocationResult =
  | { ok: true; data: LocationValidationResponse }
  | { ok: false; kind: 'validation'; errors: ApiValidationError[] }
  | { ok: false; kind: 'network'; message: string }
  | { ok: false; kind: 'server'; status: number; message: string };

export async function validateLocation(
  request: LocationRequest,
): Promise<ValidateLocationResult> {
  let response: Response;

  try {
    response = await fetch(`${API_BASE_URL}/api/v1/location/validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  } catch (error) {
    const message =
      error instanceof Error ? error.message : 'Unknown network error';
    return { ok: false, kind: 'network', message };
  }

  if (response.status === 422) {
    try {
      const body = await response.json();
      return {
        ok: false,
        kind: 'validation',
        errors: body.detail ?? [],
      };
    } catch {
      return {
        ok: false,
        kind: 'server',
        status: 422,
        message: 'Unprocessable response from server.',
      };
    }
  }

  if (!response.ok) {
    return {
      ok: false,
      kind: 'server',
      status: response.status,
      message: `Server error ${response.status}: ${response.statusText}`,
    };
  }

  try {
    const data: LocationValidationResponse = await response.json();
    return { ok: true, data };
  } catch {
    return {
      ok: false,
      kind: 'server',
      status: response.status,
      message: 'Could not parse server response.',
    };
  }
}
