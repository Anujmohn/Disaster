import React, { useEffect, useMemo, useState } from 'react';
import { getCurrentBrowserLocation } from '../../services/browserLocationService';
import searchStatus from '../../data/mainpuri-disaster-search-status.json';
import {
  distanceBetweenCoordinatesKm,
  MainpuriSafeLocation,
  SafeLocationCategory,
  SafeLocationVerificationStatus,
} from '../../services/mainpuriSafeLocations';

interface MainpuriSafeLocationsPanelProps {
  locations: MainpuriSafeLocation[];
  selectedLocationId: string | null;
  userCoordinates: { latitude: number; longitude: number } | null;
  onClose: () => void;
  onSelectLocation: (location: MainpuriSafeLocation) => void;
  onUserCoordinatesChange: (coordinates: { latitude: number; longitude: number }) => void;
  onVisibleLocationsChange: (locations: MainpuriSafeLocation[]) => void;
}

const FAVORITES_STORAGE_KEY = 'mainpuri-safe-location-favorites';

const categoryLabels: Record<SafeLocationCategory, string> = {
  hospital: 'Government hospital',
  healthcare_centre: 'Healthcare centre',
  potential_emergency_facility: 'Potential emergency facility',
  disaster_shelter: 'Official disaster shelter',
  potential_safe_area: 'Potential higher-ground / safe area',
  disaster_risk_area: 'Verified hazard / affected area',
};

function getCategorySearchStatus(category: SafeLocationCategory) {
  if (
    category === 'disaster_shelter' ||
    category === 'potential_safe_area' ||
    category === 'disaster_risk_area'
  ) {
    return searchStatus.categories[category];
  }
  return null;
}

function getLocationCategoryLabel(category: SafeLocationCategory): string {
  return categoryLabels[category];
}

function getDirectionsUrl(location: MainpuriSafeLocation): string | null {
  if (location.latitude == null || location.longitude == null) return null;
  return `https://www.google.com/maps/dir/?api=1&destination=${location.latitude},${location.longitude}`;
}

export function MainpuriSafeLocationsPanel({
  locations,
  selectedLocationId,
  userCoordinates,
  onClose,
  onSelectLocation,
  onUserCoordinatesChange,
  onVisibleLocationsChange,
}: MainpuriSafeLocationsPanelProps): React.JSX.Element {
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<'all' | SafeLocationCategory>('all');
  const [verification, setVerification] = useState<'all' | SafeLocationVerificationStatus>('all');
  const [savedOnly, setSavedOnly] = useState(false);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [favoritesLoaded, setFavoritesLoaded] = useState(false);
  const [gpsLoading, setGpsLoading] = useState(false);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [storageError, setStorageError] = useState(false);

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(FAVORITES_STORAGE_KEY);
      if (saved) {
        const parsed: unknown = JSON.parse(saved);
        if (Array.isArray(parsed) && parsed.every((id) => typeof id === 'string')) {
          setFavorites(parsed);
        } else {
          setStorageError(true);
        }
      }
    } catch {
      setStorageError(true);
    } finally {
      setFavoritesLoaded(true);
    }
  }, []);

  useEffect(() => {
    if (!favoritesLoaded) return;
    try {
      window.localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify(favorites));
      setStorageError(false);
    } catch {
      setStorageError(true);
    }
  }, [favorites, favoritesLoaded]);

  const visibleLocations = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    return locations
      .filter((location) => {
        const matchesQuery =
          !query ||
          location.name.toLocaleLowerCase().includes(query) ||
          location.address.toLocaleLowerCase().includes(query);
        const matchesCategory = category === 'all' || location.category === category;
        const matchesVerification =
          verification === 'all' || location.verification_status === verification;
        const matchesSaved = !savedOnly || favorites.includes(location.id);
        return matchesQuery && matchesCategory && matchesVerification && matchesSaved;
      })
      .sort((left, right) => {
        const leftHasCoordinates = left.latitude != null && left.longitude != null;
        const rightHasCoordinates = right.latitude != null && right.longitude != null;
        if (userCoordinates && leftHasCoordinates !== rightHasCoordinates) {
          return leftHasCoordinates ? -1 : 1;
        }
        if (
          userCoordinates &&
          left.latitude != null &&
          left.longitude != null &&
          right.latitude != null &&
          right.longitude != null
        ) {
          const leftDistance = distanceBetweenCoordinatesKm(userCoordinates, {
            latitude: left.latitude,
            longitude: left.longitude,
          });
          const rightDistance = distanceBetweenCoordinatesKm(userCoordinates, {
            latitude: right.latitude,
            longitude: right.longitude,
          });
          return leftDistance - rightDistance;
        }
        return left.name.localeCompare(right.name);
      });
  }, [category, favorites, locations, savedOnly, search, userCoordinates, verification]);

  useEffect(() => {
    onVisibleLocationsChange(visibleLocations);
  }, [onVisibleLocationsChange, visibleLocations]);

  const selectedLocation = locations.find((location) => location.id === selectedLocationId) ?? null;

  const handleGetGps = async () => {
    setGpsLoading(true);
    setGpsError(null);
    try {
      const position = await getCurrentBrowserLocation();
      onUserCoordinatesChange({
        latitude: position.latitude,
        longitude: position.longitude,
      });
    } catch (error) {
      const message =
        error && typeof error === 'object' && 'message' in error
          ? String(error.message)
          : 'Could not read your GPS location.';
      setGpsError(message);
    } finally {
      setGpsLoading(false);
    }
  };

  const toggleFavorite = (locationId: string) => {
    setFavorites((current) =>
      current.includes(locationId)
        ? current.filter((id) => id !== locationId)
        : [...current, locationId],
    );
  };

  return (
    <aside className="side-panel mainpuri-safe-locations-panel" aria-label="Mainpuri safe locations">
      <div className="side-panel-header">
        <span className="side-panel-title">📍 Mainpuri Safe Locations</span>
        <button className="close-btn" onClick={onClose} aria-label="Close safe locations panel">
          ✕
        </button>
      </div>
      <div className="side-panel-body">
        <p className="mainpuri-shelter-notice">
          No officially designated shelter is verified for this map. Hospitals are medical facilities,
          not confirmed shelters. See the category search results below; missing results do not mean
          the district is free of hazards.
        </p>

        <section className="mainpuri-search-coverage" aria-label="Mainpuri disaster data search results">
          <strong>Search coverage · Mainpuri district</strong>
          {Object.values(searchStatus.categories).map((result) => (
            <div className="mainpuri-search-coverage-item" key={result.title}>
              <span>{result.title}</span>
              <p>{result.summary}</p>
              <div>
                {result.sources.map((source) => (
                  <a key={source.name} href={source.url} target="_blank" rel="noopener noreferrer">
                    {source.name}: {source.outcome}
                  </a>
                ))}
              </div>
            </div>
          ))}
          <a href={searchStatus.boundary_source_url} target="_blank" rel="noopener noreferrer">
            Search boundary source: Mainpuri district (OpenStreetMap)
          </a>
        </section>

        <label className="mainpuri-search-label" htmlFor="mainpuri-location-search">
          Search name or address
        </label>
        <input
          id="mainpuri-location-search"
          className="mainpuri-search-input"
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="Search Mainpuri locations"
        />

        <div className="mainpuri-filter-row">
          <label>
            <span>Category</span>
            <select
              value={category}
              onChange={(event) => setCategory(event.target.value as 'all' | SafeLocationCategory)}
            >
              <option value="all">All categories</option>
              <option value="hospital">Hospitals</option>
              <option value="healthcare_centre">Healthcare centres</option>
              <option value="potential_emergency_facility">Potential emergency facilities</option>
              <option value="disaster_shelter">Official disaster shelters</option>
              <option value="potential_safe_area">Potential higher-ground / safe areas</option>
              <option value="disaster_risk_area">Verified hazard / affected areas</option>
            </select>
          </label>
          <label>
            <span>Facility record</span>
            <select
              value={verification}
              onChange={(event) =>
                setVerification(event.target.value as 'all' | SafeLocationVerificationStatus)
              }
            >
              <option value="all">All statuses</option>
              <option value="verified">Verified record</option>
              <option value="unverified">Unverified</option>
              <option value="pending">Pending verification</option>
            </select>
          </label>
        </div>

        <div className="mainpuri-location-tools">
          <button
            type="button"
            className={`mainpuri-tool-button ${savedOnly ? 'active' : ''}`}
            onClick={() => setSavedOnly((current) => !current)}
            aria-pressed={savedOnly}
          >
            ★ {savedOnly ? 'Showing saved' : 'Saved'} ({favorites.length})
          </button>
          <button
            type="button"
            className="mainpuri-tool-button"
            onClick={handleGetGps}
            disabled={gpsLoading}
          >
            {gpsLoading ? 'Locating…' : userCoordinates ? '↻ Update GPS' : '📍 Use my location'}
          </button>
        </div>

        {gpsError && <p className="mainpuri-inline-error" role="status">{gpsError}</p>}
        {!userCoordinates && (
          <p className="mainpuri-helper-text">Allow GPS to calculate distance and sort nearest first.</p>
        )}
        {storageError && (
          <p className="mainpuri-inline-error" role="status">
            Saved locations could not be stored on this device.
          </p>
        )}

        <div className="mainpuri-location-results" aria-live="polite">
          <div className="mainpuri-result-count">
            {visibleLocations.length} location{visibleLocations.length === 1 ? '' : 's'}
            {userCoordinates ? ' · nearest first' : ' · alphabetical'}
          </div>
          {visibleLocations.map((location) => {
            const distance =
              userCoordinates && location.latitude != null && location.longitude != null
                ? distanceBetweenCoordinatesKm(userCoordinates, {
                    latitude: location.latitude,
                    longitude: location.longitude,
                  })
                : null;
            return (
              <div
                className={`mainpuri-location-card ${selectedLocationId === location.id ? 'selected' : ''}`}
                key={location.id}
              >
                <button
                  type="button"
                  className="mainpuri-location-select"
                  onClick={() => onSelectLocation(location)}
                >
                  <strong>{location.name}</strong>
                  <span>{location.address}</span>
                  <span className="mainpuri-location-meta">
                    {getLocationCategoryLabel(location.category)}
                    {distance == null ? '' : ` · ${distance.toFixed(1)} km`}
                  </span>
                  <span className="mainpuri-location-source">
                    {location.verification_status === 'verified'
                      ? 'Verified'
                      : location.verification_status === 'pending'
                        ? 'Pending verification'
                        : 'Unverified'}{' '}
                    · {location.source_name}
                  </span>
                </button>
                <button
                  type="button"
                  className="mainpuri-favorite-button"
                  onClick={() => toggleFavorite(location.id)}
                  aria-label={`${favorites.includes(location.id) ? 'Remove' : 'Save'} ${location.name}`}
                  aria-pressed={favorites.includes(location.id)}
                >
                  {favorites.includes(location.id) ? '★' : '☆'}
                </button>
              </div>
            );
          })}
          {visibleLocations.length === 0 && (
            <p className="mainpuri-helper-text">
              {category === 'all'
                ? 'No locations match the selected search and filters.'
                : getCategorySearchStatus(category)?.summary ??
                  'No records in this category have been verified for the local dataset.'}
            </p>
          )}
        </div>

        {selectedLocation && (
          <section className="mainpuri-location-details" aria-label="Selected location details">
            <h3>{selectedLocation.name}</h3>
            <p>{selectedLocation.address}</p>
            <div className="mainpuri-detail-badges">
              <span className="mainpuri-status-badge">
                {selectedLocation.verification_status === 'verified'
                  ? 'Facility record verified'
                  : selectedLocation.verification_status === 'pending'
                    ? 'Pending verification'
                    : 'Facility not independently verified'}
              </span>
              <span className="mainpuri-status-badge not-shelter">Not a designated shelter</span>
            </div>
            <p className="mainpuri-helper-text">{selectedLocation.verification_detail}</p>
            <p><strong>Contact:</strong> {selectedLocation.contact || 'Not listed in source data'}</p>
            {selectedLocation.latitude != null && selectedLocation.longitude != null && (
              <p>
                <strong>Coordinates:</strong> {selectedLocation.latitude}, {selectedLocation.longitude}
                {userCoordinates
                  ? ` · ${distanceBetweenCoordinatesKm(userCoordinates, {
                      latitude: selectedLocation.latitude,
                      longitude: selectedLocation.longitude!,
                    }).toFixed(1)} km away`
                  : ''}
              </p>
            )}
            <div className="mainpuri-detail-actions">
              {getDirectionsUrl(selectedLocation) && (
                <a
                  className="mainpuri-directions-button"
                  href={getDirectionsUrl(selectedLocation) ?? undefined}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Get Directions
                </a>
              )}
              <a href={selectedLocation.source_url} target="_blank" rel="noopener noreferrer">
                View source
              </a>
            </div>
          </section>
        )}
      </div>
    </aside>
  );
}
