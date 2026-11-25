import locationsDatabase from '../data/mainpuri-safe-locations.json';

export type SafeLocationCategory =
  | 'hospital'
  | 'healthcare_centre'
  | 'potential_emergency_facility'
  | 'disaster_shelter'
  | 'potential_safe_area'
  | 'disaster_risk_area';
export type SafeLocationVerificationStatus = 'verified' | 'unverified' | 'pending';
export type SafeLocationDesignationStatus = 'officially_designated' | 'not_designated' | 'pending';

export interface MainpuriSafeLocation {
  id: string;
  name: string;
  category: SafeLocationCategory;
  address: string;
  latitude: number | null;
  longitude: number | null;
  contact: string | null;
  verification_status: SafeLocationVerificationStatus;
  designation_status: SafeLocationDesignationStatus;
  verification_detail: string;
  source_url: string;
  source_name: string;
}

const bounds = locationsDatabase.district_boundary.bounding_box;

function isCategory(value: string): value is SafeLocationCategory {
  return [
    'hospital',
    'healthcare_centre',
    'potential_emergency_facility',
    'disaster_shelter',
    'potential_safe_area',
    'disaster_risk_area',
  ].includes(value);
}

function isVerificationStatus(value: string): value is SafeLocationVerificationStatus {
  return ['verified', 'unverified', 'pending'].includes(value);
}

function isDesignationStatus(value: string): value is SafeLocationDesignationStatus {
  return ['officially_designated', 'not_designated', 'pending'].includes(value);
}

export const mainpuriSafeLocations: MainpuriSafeLocation[] = locationsDatabase.locations.flatMap(
  (location) => {
    if (
      !isCategory(location.category) ||
      !isVerificationStatus(location.verification_status) ||
      !isDesignationStatus(location.designation_status)
    ) {
      return [];
    }

    return [
      {
        ...location,
        category: location.category,
        verification_status: location.verification_status,
        designation_status: location.designation_status,
      },
    ];
  },
);

export function isValidMainpuriSafeLocation(location: MainpuriSafeLocation): boolean {
  if (
    !location.id ||
    !location.name.trim() ||
    !location.address.trim() ||
    !location.source_url.startsWith('https://') ||
    !location.source_name.trim()
  ) {
    return false;
  }

  if (location.latitude == null || location.longitude == null) {
    return location.latitude == null && location.longitude == null;
  }

  return (
    Number.isFinite(location.latitude) &&
    Number.isFinite(location.longitude) &&
    location.latitude >= bounds.south &&
    location.latitude <= bounds.north &&
    location.longitude >= bounds.west &&
    location.longitude <= bounds.east
  );
}

export function distanceBetweenCoordinatesKm(
  from: { latitude: number; longitude: number },
  to: { latitude: number; longitude: number },
): number {
  const radians = (degrees: number) => (degrees * Math.PI) / 180;
  const latitudeDelta = radians(to.latitude - from.latitude);
  const longitudeDelta = radians(to.longitude - from.longitude);
  const haversine =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(radians(from.latitude)) *
      Math.cos(radians(to.latitude)) *
      Math.sin(longitudeDelta / 2) ** 2;

  return 6371 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
}

export function getSafeLocationMarkerColor(location: MainpuriSafeLocation): string {
  if (
    location.category === 'disaster_risk_area' &&
    location.verification_status === 'verified'
  ) {
    return '#DC2626';
  }
  if (
    location.verification_status === 'verified' &&
    location.designation_status === 'officially_designated'
  ) {
    return '#16A34A';
  }
  if (location.category === 'hospital' || location.category === 'healthcare_centre') {
    return '#2563EB';
  }
  return '#F97316';
}
