export function formatCategoryLabel(category: string): string {
  if (!category) return 'Safe Location';
  return category
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

export function formatDistance(distanceKm?: number | null): string {
  if (distanceKm == null) return 'Nearby';
  if (distanceKm < 1) return `${Math.round(distanceKm * 1000)} m`;
  return `${distanceKm.toFixed(1)} km`;
}

export function formatDesignationLabel(status: string): string {
  switch (status) {
    case 'OFFICIAL':
      return 'Official Shelter';
    case 'POTENTIAL':
      return 'Suggested Site';
    default:
      return 'Community Site';
  }
}

export function formatVerificationLabel(status: string): string {
  switch (status) {
    case 'VERIFIED':
      return 'Verified';
    case 'REJECTED':
      return 'Not Verified';
    default:
      return 'Pending Review';
  }
}

export function formatSuitabilityLabel(classification: string): string {
  switch (classification) {
    case 'SUITABILITY_SUPPORTED_BY_EVIDENCE':
      return 'Recommended';
    case 'CONDITIONALLY_SUITABLE':
      return 'Use With Caution';
    case 'NOT_RECOMMENDED':
      return 'Higher Risk';
    case 'EXCLUDED':
      return 'Unsafe';
    case 'INSUFFICIENT_DATA':
      return 'Limited Info';
    default:
      return 'Unrated';
  }
}

export function formatRouteStatusLabel(status: string): string {
  switch (status) {
    case 'ROUTE_AVAILABLE':
      return 'Clear Route';
    case 'ROUTE_WITH_WARNINGS':
      return 'Route With Caution';
    case 'ROUTE_EXCLUDED':
      return 'Unsafe Route';
    case 'NO_SAFE_ROUTE_FOUND':
      return 'No Safe Route';
    case 'ROUTING_SERVICE_UNAVAILABLE':
      return 'Routing Unavailable';
    default:
      return 'Route Check Needed';
  }
}

export function formatRecommendationStatus(status: string): string {
  switch (status) {
    case 'RECOMMENDATIONS_AVAILABLE':
      return 'Safe Options Found';
    case 'LIMITED_RECOMMENDATIONS':
      return 'Limited Options';
    case 'NO_SUITABLE_DESTINATION_FOUND':
      return 'No Safe Match';
    case 'NO_CANDIDATES_FOUND':
      return 'Nothing Found Nearby';
    case 'NO_ROUTE_AVAILABLE':
      return 'No Clear Route';
    default:
      return 'Scan Complete';
  }
}

export function formatFreshnessLabel(freshness: string): string {
  switch (freshness) {
    case 'FRESH':
      return 'Live';
    case 'STALE':
      return 'Older Update';
    case 'EXPIRED':
      return 'Expired';
    default:
      return 'Status Unknown';
  }
}

export function formatSeverityLabel(severity: string | null): string {
  if (!severity) return 'Moderate';
  return severity.charAt(0).toUpperCase() + severity.slice(1).toLowerCase();
}
