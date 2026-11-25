import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  MAINPURI_CENTER,
  MAINPURI_ZOOM,
  MAP_ATTRIBUTION,
  MAP_STYLE_URL,
  USER_LOCATION_ZOOM,
} from '../config/mapConfig';
import { BrowserGeolocationError, getCurrentBrowserLocation } from '../services/browserLocationService';
import { LocationData, validateLocation } from '../api/locationApi';
import { DisasterEventSummary, DisasterType, fetchAllActiveDisasters } from '../api/disasterApi';
import {
  discoverHybridSafeLocations,
  fetchNearbySafeLocations,
  HybridDiscoveryResponse,
  SafeLocationItem,
  SafePlaceItem,
} from '../api/safeLocationApi';
import {
  assessSafeLocationsSuitability,
  fetchUnifiedRecommendations,
  LocationSuitabilityAssessmentItem,
  SuitabilityClassification,
  UnifiedRecommendationResponse,
} from '../api/recommendationApi';
import { calculateEvacuationRoute, RouteDetailItem } from '../api/routeApi';
import {
  AlertItem,
  fetchNearbyAlerts,
  fetchNotificationHistory,
  NotificationHistoryItem,
} from '../api/alertApi';
import { getDeviceId, initializeWebNotifications } from '../services/notificationService';
import {
  formatDistance,
  formatSafeLocationCategoryLabel,
  formatSafeLocationDesignationLabel,
  formatVerificationLabel,
  isOfficiallyVerifiedShelter,
} from '../utils/formatters';
import { computePredictedFloodPath, getBasinName, getDisasterCoords, getFlowDirectionText } from '../utils/floodFlow';
import { DisasterDetailsPanel } from '../components/map/DisasterDetailsPanel';
import { SafeLocationDetailsPanel } from '../components/map/SafeLocationDetailsPanel';
import { MainpuriSafeLocationsPanel } from '../components/map/MainpuriSafeLocationsPanel';
import { EvacuationRoutePanel } from '../components/map/EvacuationRoutePanel';
import { ManualLocationModal } from '../components/map/ManualLocationModal';
import { UnifiedRecommendationsModal } from '../components/map/UnifiedRecommendationsModal';
import { AlertCenterModal } from '../components/map/AlertCenterModal';
import {
  getSafeLocationMarkerColor,
  isValidMainpuriSafeLocation,
  MainpuriSafeLocation,
  mainpuriSafeLocations,
} from '../services/mainpuriSafeLocations';

type FilterCategory = 'ALL' | 'EARTHQUAKE' | 'FLOOD' | 'CYCLONE';

interface UserLocationState {
  status: 'idle' | 'loading' | 'success' | 'error';
  location: LocationData | null;
  errorMessage?: string;
}

function upsertUserMarker(
  map: maplibregl.Map,
  markerRef: React.MutableRefObject<maplibregl.Marker | null>,
  lng: number,
  lat: number,
  popupHtml?: string,
): void {
  if (!markerRef.current) {
    const el = document.createElement('div');
    el.className = 'user-location-marker';
    const pulse = document.createElement('div');
    pulse.className = 'user-location-pulse';
    el.appendChild(pulse);

    markerRef.current = new maplibregl.Marker({ element: el })
      .setLngLat([lng, lat])
      .addTo(map);
  } else {
    markerRef.current.setLngLat([lng, lat]);
  }

  if (popupHtml) {
    const popup = new maplibregl.Popup({ offset: 25, closeButton: false }).setHTML(popupHtml);
    markerRef.current.setPopup(popup);
  }
}

function getDisasterColor(type: DisasterType): string {
  if (type === 'EARTHQUAKE') return '#E65100';
  if (type === 'FLOOD') return '#0288D1';
  return '#7B1FA2';
}

export default function MapScreen(): React.JSX.Element {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const userMarkerRef = useRef<maplibregl.Marker | null>(null);
  const disasterMarkersRef = useRef<maplibregl.Marker[]>([]);
  const safeLocationMarkersRef = useRef<maplibregl.Marker[]>([]);
  const mainpuriLocationMarkersRef = useRef<maplibregl.Marker[]>([]);
  const cvMarkersRef = useRef<maplibregl.Marker[]>([]);

  const [cvResult, setCvResult] = useState<HybridDiscoveryResponse | null>(null);
  const [loadingCv, setLoadingCv] = useState(false);
  const [showCvLayer, setShowCvLayer] = useState(true);

  const [userLoc, setUserLoc] = useState<UserLocationState>({ status: 'idle', location: null });
  const [activeFilter, setActiveFilter] = useState<FilterCategory>('ALL');

  const [disasters, setDisasters] = useState<DisasterEventSummary[]>([]);
  const [selectedDisaster, setSelectedDisaster] = useState<DisasterEventSummary | null>(null);

  const [safeLocations, setSafeLocations] = useState<SafeLocationItem[]>([]);
  const validMainpuriLocations = useMemo(
    () => mainpuriSafeLocations.filter(isValidMainpuriSafeLocation),
    [],
  );
  const [visibleMainpuriLocations, setVisibleMainpuriLocations] =
    useState<MainpuriSafeLocation[]>(validMainpuriLocations);
  const [showMainpuriSafeLocations, setShowMainpuriSafeLocations] = useState(false);
  const [selectedMainpuriLocationId, setSelectedMainpuriLocationId] = useState<string | null>(null);
  const [mainpuriUserCoordinates, setMainpuriUserCoordinates] = useState<{
    latitude: number;
    longitude: number;
  } | null>(null);
  const [mapTilesUnavailable, setMapTilesUnavailable] = useState(false);
  const [safePlacesMap, setSafePlacesMap] = useState<Record<string, SafePlaceItem>>({});
  const [effectiveRadiusKm, setEffectiveRadiusKm] = useState(5.0);
  const [loadingSafePlaces, setLoadingSafePlaces] = useState(false);
  const [selectedSafeLocation, setSelectedSafeLocation] = useState<SafeLocationItem | null>(null);
  const [userSelectedSafeLocation, setUserSelectedSafeLocation] = useState<SafeLocationItem | null>(null);
  const [suitabilityMap, setSuitabilityMap] = useState<Record<string, LocationSuitabilityAssessmentItem>>({});
  const [evaluatingSuitability, setEvaluatingSuitability] = useState(false);

  const [showManualLocationModal, setShowManualLocationModal] = useState(false);
  const [manualLat, setManualLat] = useState('28.6139');
  const [manualLng, setManualLng] = useState('77.2090');
  const [locationSourceLabel, setLocationSourceLabel] = useState('GPS');
  const [feedbackToast, setFeedbackToast] = useState<string | null>(null);
  const handleApplyManualLocationRef = useRef<(latVal: number, lngVal: number, cityName?: string) => Promise<void>>(
    () => Promise.resolve(),
  );

  const [activeRoute, setActiveRoute] = useState<RouteDetailItem | null>(null);
  const [calculatingRoute, setCalculatingRoute] = useState(false);

  const [unifiedRecs, setUnifiedRecs] = useState<UnifiedRecommendationResponse | null>(null);
  const [loadingUnifiedRecs, setLoadingUnifiedRecs] = useState(false);
  const [showUnifiedModal, setShowUnifiedModal] = useState(false);

  const [alerts, setAlerts] = useState<AlertItem[]>([]);
  const [notificationHistory, setNotificationHistory] = useState<NotificationHistoryItem[]>([]);
  const [showAlertCenter, setShowAlertCenter] = useState(false);
  const [activeAlertTab, setActiveAlertTab] = useState<'alerts' | 'history' | 'preferences'>('alerts');

  const showToast = useCallback((message: string) => {
    setFeedbackToast(message);
    setTimeout(() => setFeedbackToast(null), 3500);
  }, []);

  const focusMainpuriLocations = useCallback((locations: MainpuriSafeLocation[]) => {
    const map = mapRef.current;
    const positioned = locations.filter(
      (location) =>
        location.latitude != null &&
        location.longitude != null &&
        Number.isFinite(location.latitude) &&
        Number.isFinite(location.longitude),
    );
    if (!map || positioned.length === 0) return;

    if (positioned.length === 1) {
      const location = positioned[0];
      map.easeTo({
        center: [location.longitude!, location.latitude!],
        zoom: 14,
        duration: 500,
      });
      return;
    }

    const bounds = new maplibregl.LngLatBounds();
    positioned.forEach((location) => bounds.extend([location.longitude!, location.latitude!]));
    map.fitBounds(bounds, { padding: 80, maxZoom: 12, duration: 500 });
  }, []);

  const handleSelectMainpuriLocation = useCallback(
    (location: MainpuriSafeLocation) => {
      setSelectedMainpuriLocationId(location.id);
      setSelectedSafeLocation(null);
      setSelectedDisaster(null);
      setShowMainpuriSafeLocations(true);
      if (location.latitude != null && location.longitude != null) {
        mapRef.current?.easeTo({
          center: [location.longitude, location.latitude],
          zoom: 14,
          duration: 500,
        });
      }
    },
    [],
  );

  const handleMainpuriUserCoordinatesChange = useCallback(
    (coordinates: { latitude: number; longitude: number }) => {
      setMainpuriUserCoordinates(coordinates);
      if (mapRef.current) {
        mapRef.current.flyTo({
          center: [coordinates.longitude, coordinates.latitude],
          zoom: USER_LOCATION_ZOOM,
          essential: true,
        });
        upsertUserMarker(mapRef.current, userMarkerRef, coordinates.longitude, coordinates.latitude);
      }
    },
    [],
  );

  const handleVisibleMainpuriLocationsChange = useCallback(
    (locations: MainpuriSafeLocation[]) => setVisibleMainpuriLocations(locations),
    [],
  );

  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    const map = new maplibregl.Map({
      container: mapContainerRef.current,
      style: MAP_STYLE_URL,
      center: MAINPURI_CENTER,
      zoom: MAINPURI_ZOOM,
      attributionControl: false,
    });

    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'bottom-right');
    map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left');
    map.addControl(
      new maplibregl.AttributionControl({ compact: false, customAttribution: MAP_ATTRIBUTION }),
      'bottom-right',
    );

    map.getCanvas().style.cursor = 'crosshair';

    map.on('click', (e) => {
      const roundedLat = parseFloat(e.lngLat.lat.toFixed(5));
      const roundedLng = parseFloat(e.lngLat.lng.toFixed(5));
      handleApplyManualLocationRef.current(roundedLat, roundedLng, 'Map Pin');
    });

    map.on('load', () => {
      map.addSource('affected-areas', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });

      map.addLayer({
        id: 'affected-areas-fill',
        type: 'fill',
        source: 'affected-areas',
        paint: { 'fill-color': ['get', 'fillColor'], 'fill-opacity': 0.12 },
      });

      map.addLayer({
        id: 'affected-areas-outline',
        type: 'line',
        source: 'affected-areas',
        paint: { 'line-color': ['get', 'strokeColor'], 'line-width': 2, 'line-opacity': 0.8 },
      });

      map.addSource('flood-flow-paths', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });

      map.addLayer({
        id: 'flood-flow-glow',
        type: 'line',
        source: 'flood-flow-paths',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: {
          'line-color': '#00E5FF',
          'line-width': ['case', ['boolean', ['get', 'isSelected'], false], 10, 6],
          'line-opacity': 0.5,
          'line-blur': 3,
        },
      });

      map.addLayer({
        id: 'flood-flow-lines',
        type: 'line',
        source: 'flood-flow-paths',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: {
          'line-color': ['get', 'color'],
          'line-width': ['case', ['boolean', ['get', 'isSelected'], false], 5, 3.5],
          'line-opacity': 0.95,
          'line-dasharray': [3, 2],
        },
      });

      map.addSource('flood-flow-nodes', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });

      map.addLayer({
        id: 'flood-flow-nodes-circle',
        type: 'circle',
        source: 'flood-flow-nodes',
        paint: {
          'circle-radius': ['case', ['boolean', ['get', 'isSelected'], false], 6, 4],
          'circle-color': '#00E5FF',
          'circle-stroke-width': 2,
          'circle-stroke-color': '#01579B',
        },
      });

      map.addSource('evacuation-route', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });

      map.addLayer({
        id: 'evacuation-route-line',
        type: 'line',
        source: 'evacuation-route',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 5, 'line-opacity': 0.85 },
      });
    });

    map.on('error', (event) => {
      if (/tile|network|fetch|request failed|failed to load|connection/i.test(event.error.message)) {
        setMapTilesUnavailable(true);
      }
    });

    mapRef.current = map;

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const handleOffline = () => setMapTilesUnavailable(true);
    const handleOnline = () => setMapTilesUnavailable(false);
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', handleOnline);
    return () => {
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', handleOnline);
    };
  }, []);

  useEffect(() => {
    if (!userLoc.location) return;
    setMainpuriUserCoordinates({
      latitude: userLoc.location.latitude,
      longitude: userLoc.location.longitude,
    });
  }, [userLoc.location]);

  const handleAcquireLocation = useCallback(async () => {
    setUserLoc({ status: 'loading', location: null });
    try {
      const fix = await getCurrentBrowserLocation();
      const res = await validateLocation({
        latitude: fix.latitude,
        longitude: fix.longitude,
        accuracy_m: fix.accuracy_m,
        altitude_m: fix.altitude_m,
        captured_at: fix.captured_at,
      });

      if (res.ok) {
        setUserLoc({ status: 'success', location: res.data.location });
        setLocationSourceLabel('GPS');

        if (mapRef.current) {
          const { longitude, latitude } = res.data.location;
          mapRef.current.flyTo({ center: [longitude, latitude], zoom: USER_LOCATION_ZOOM, essential: true });
          upsertUserMarker(mapRef.current, userMarkerRef, longitude, latitude);
        }

        initializeWebNotifications({
          latitude: res.data.location.latitude,
          longitude: res.data.location.longitude,
        });
      } else {
        setUserLoc({ status: 'error', location: null, errorMessage: 'Could not verify your GPS location.' });
      }
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as BrowserGeolocationError).message)
          : 'Could not read your location.';
      setUserLoc({ status: 'error', location: null, errorMessage: msg });
    }
  }, []);

  const handleApplyManualLocation = useCallback(
    async (latVal: number, lngVal: number, cityName?: string) => {
      try {
        const res = await validateLocation({
          latitude: latVal,
          longitude: lngVal,
          accuracy_m: 10,
          altitude_m: null,
          captured_at: new Date().toISOString(),
        });

        if (res.ok) {
          setUserLoc({ status: 'success', location: res.data.location });
          setLocationSourceLabel(
            cityName ? cityName : `${latVal.toFixed(2)}°, ${lngVal.toFixed(2)}°`,
          );
          setManualLat(latVal.toFixed(4));
          setManualLng(lngVal.toFixed(4));

          if (mapRef.current) {
            mapRef.current.easeTo({
              center: [lngVal, latVal],
              zoom: Math.max(mapRef.current.getZoom(), USER_LOCATION_ZOOM),
              duration: 600,
            });

            const popupHtml = `
              <div style="font-family:system-ui,sans-serif;padding:4px 6px;font-size:12px;line-height:1.4;">
                <strong style="color:#0F172A;">📍 Your Pin (${cityName || 'Map Pin'})</strong><br/>
                <span style="color:#475569;">${latVal.toFixed(4)}°, ${lngVal.toFixed(4)}°</span><br/>
                <span style="color:#0288D1;font-size:11px;font-weight:600;">Tap anywhere on the map to move</span>
              </div>
            `;
            upsertUserMarker(mapRef.current, userMarkerRef, lngVal, latVal, popupHtml);
          }

          showToast(
            `📍 Location updated${cityName ? ` (${cityName})` : ''} — checking nearby safe places…`,
          );
          setShowManualLocationModal(false);
        } else {
          const errorMsg =
            res.kind === 'validation' ? res.errors.map((e) => e.msg).join(', ') : res.message;
          showToast(errorMsg || 'Please pick a location within India.');
        }
      } catch (err) {
        showToast('Could not set location: ' + (err instanceof Error ? err.message : String(err)));
      }
    },
    [showToast],
  );

  useEffect(() => {
    handleApplyManualLocationRef.current = handleApplyManualLocation;
  }, [handleApplyManualLocation]);

  useEffect(() => {
    handleAcquireLocation();
  }, [handleAcquireLocation]);

  const loadDisasters = useCallback(async () => {
    try {
      const disasterTypes = activeFilter === 'ALL' ? undefined : [activeFilter as DisasterType];
      const res = await fetchAllActiveDisasters({
        disaster_types: disasterTypes,
        status: ['ACTIVE'],
        limit: 200,
      });

      if (res.ok) {
        setDisasters(res.data.events);
        const source = mapRef.current?.getSource('affected-areas') as maplibregl.GeoJSONSource | undefined;
        if (source) {
          const features = res.data.events
            .filter((ev) => ev.affected_area != null)
            .map((ev) => {
              const color = getDisasterColor(ev.disaster_type);
              return {
                type: 'Feature' as const,
                properties: { id: ev.id, title: ev.title, fillColor: color, strokeColor: color },
                geometry: ev.affected_area!,
              };
            });
          source.setData({ type: 'FeatureCollection', features });
        }
      }
    } catch (err) {
      console.warn('Failed to fetch active disasters across India:', err);
    }
  }, [activeFilter]);

  const loadSafePlaces = useCallback(async () => {
    const lat = userLoc.location?.latitude ?? 28.6139;
    const lng = userLoc.location?.longitude ?? 77.209;
    setLoadingSafePlaces(true);
    try {
      const fb = await fetchNearbySafeLocations({
        latitude: lat,
        longitude: lng,
        radius_km: 15.0,
        limit: 30,
      });
      if (fb.success) {
        setSafeLocations(fb.locations);
        setSafePlacesMap({});
        setEffectiveRadiusKm(15.0);
      }
    } catch (fbErr) {
      console.warn('Nearby safe locations / hospitals fetch note:', fbErr);
    } finally {
      setLoadingSafePlaces(false);
    }
  }, [userLoc.location]);

  const loadAlerts = useCallback(async () => {
    try {
      const lat = userLoc.location?.latitude ?? 20.5937;
      const lng = userLoc.location?.longitude ?? 78.9629;
      const [alertRes, histResp] = await Promise.all([
        fetchNearbyAlerts({ latitude: lat, longitude: lng, radius_km: 1000 }),
        fetchNotificationHistory(getDeviceId()),
      ]);
      if (alertRes.success) setAlerts(alertRes.alerts);
      setNotificationHistory(histResp.notifications);
    } catch (err) {
      console.warn('Failed to fetch alerts:', err);
    }
  }, [userLoc.location]);

  const loadComputerVisionFeatures = useCallback(async () => {
    if (!userLoc.location) return;
    setLoadingCv(true);
    try {
      const mode = activeFilter === 'FLOOD' || selectedDisaster?.disaster_type === 'FLOOD' ? 'FLOOD' : 'EARTHQUAKE';
      const res = await discoverHybridSafeLocations({
        latitude: userLoc.location.latitude,
        longitude: userLoc.location.longitude,
        disaster_type: mode,
        max_radius_m: 2000,
        min_candidates: 3,
        max_results: 25,
      });
      if (res.success) {
        setCvResult(res);
      }
    } catch (err) {
      console.warn('Hybrid safe-location discovery note:', err);
    } finally {
      setLoadingCv(false);
    }
  }, [userLoc.location, activeFilter, selectedDisaster]);

  useEffect(() => {
    loadDisasters();
    loadSafePlaces();
    loadAlerts();
    loadComputerVisionFeatures();
  }, [loadDisasters, loadSafePlaces, loadAlerts, loadComputerVisionFeatures]);

  useEffect(() => {
    cvMarkersRef.current.forEach((m) => m.remove());
    cvMarkersRef.current = [];

    if (!mapRef.current || !showCvLayer || !cvResult?.candidates?.length) return;

    const statusColor: Record<string, string> = {
      SUITABLE_WITH_EVIDENCE: '#16A34A',
      CONDITIONALLY_SUITABLE: '#0D9488',
      INSUFFICIENT_DATA: '#D97706',
      UNKNOWN: '#64748B',
      NOT_RECOMMENDED: '#DC2626',
    };

    for (const cand of cvResult.candidates) {
      const bg = statusColor[cand.assessment_status] ?? '#0D9488';
      const el = document.createElement('div');
      el.className = 'custom-maplibre-marker';
      el.title = `${cand.name || cand.category} (${cand.assessment_status})`;
      el.innerHTML = `
        <div class="marker-inner" style="background-color: ${bg}; width: 24px; height: 24px; font-size: 12px; border: 2px solid #FFFFFF; box-shadow: 0 2px 6px rgba(0,0,0,0.28); display: flex; align-items: center; justify-content: center;">
          ${cand.applicable_disaster === 'FLOOD' ? '⛰️' : '🟩'}
        </div>
      `;

      const areaLine = cand.area_m2
        ? `<div><strong>Area:</strong> ${Math.round(cand.area_m2).toLocaleString()} m²</div>`
        : '';
      const elevLine = cand.elevation_m != null
        ? `<div><strong>Elevation:</strong> ${cand.elevation_m} m MSL</div>`
        : '';
      const reasonText = [...cand.reasons, ...cand.rejection_or_conditional_reasons].slice(0, 2).join(' • ');

      const popup = new maplibregl.Popup({ offset: 18 }).setHTML(`
        <div style="font-family:system-ui,sans-serif;padding:4px 6px;font-size:12px;line-height:1.45;max-width:250px;">
          <div style="font-weight:700;color:${bg};font-size:11px;text-transform:uppercase;">
            ${cand.assessment_status.replace(/_/g, ' ')} (${cand.applicable_disaster})
          </div>
          <div style="font-weight:700;color:#0F172A;margin:2px 0 4px;">${cand.name || cand.category.replace(/_/g, ' ')}</div>
          <div style="color:#475569;">
            <div><strong>Distance (boundary):</strong> ${Math.round(cand.distance_m)} m</div>
            ${areaLine}${elevLine}
            <div><strong>Origin:</strong> ${cand.origin_classification.replace(/_/g, ' ')}</div>
            ${reasonText ? `<div style="font-size:11px;color:#334155;margin-top:4px;">${reasonText}</div>` : ''}
            <div style="font-size:10px;color:#64748B;margin-top:4px;">Sources: ${cand.evidence_sources.join(', ')}</div>
          </div>
        </div>
      `);

      const marker = new maplibregl.Marker({ element: el })
        .setLngLat([cand.nearest_point.longitude, cand.nearest_point.latitude])
        .setPopup(popup)
        .addTo(mapRef.current);
      cvMarkersRef.current.push(marker);
    }
  }, [cvResult, showCvLayer]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    const sourceId = 'cv-earthquake-polygons-source';
    const fillLayerId = 'cv-earthquake-polygons-fill';
    const outlineLayerId = 'cv-earthquake-polygons-outline';

    const polygonFeatures =
      showCvLayer && cvResult?.candidates
        ? cvResult.candidates
            .filter((c) => c.polygon_geojson && c.assessment_status !== 'NOT_RECOMMENDED')
            .map((c) => ({
              type: 'Feature' as const,
              geometry: c.polygon_geojson!,
              properties: {
                id: c.id,
                name: c.name || c.category.replace(/_/g, ' '),
                status: c.assessment_status,
                disaster: c.applicable_disaster,
                area_m2: c.area_m2 ?? 0,
                distance_m: Math.round(c.distance_m),
                origin: c.origin_classification,
              },
            }))
        : [];

    const featureCollection = {
      type: 'FeatureCollection' as const,
      features: polygonFeatures,
    };

    const existingSource = map.getSource(sourceId) as maplibregl.GeoJSONSource | undefined;
    if (existingSource) {
      existingSource.setData(featureCollection);
      return;
    }

    const addPolygonLayers = () => {
      if (map.getSource(sourceId)) return;
      map.addSource(sourceId, { type: 'geojson', data: featureCollection });
      map.addLayer({
        id: fillLayerId,
        type: 'fill',
        source: sourceId,
        paint: {
          'fill-color': '#16A34A',
          'fill-opacity': 0.25,
        },
      });
      map.addLayer({
        id: outlineLayerId,
        type: 'line',
        source: sourceId,
        paint: {
          'line-color': '#15803D',
          'line-width': 2.5,
        },
      });
      map.on('click', fillLayerId, (e) => {
        const feat = e.features?.[0];
        if (!feat || !feat.properties) return;
        const p = feat.properties as Record<string, string | number>;
        new maplibregl.Popup({ offset: 12 })
          .setLngLat(e.lngLat)
          .setHTML(`
            <div style="font-family:system-ui,sans-serif;padding:4px 6px;font-size:12px;line-height:1.45;max-width:240px;">
              <div style="font-weight:700;color:#15803D;font-size:11px;text-transform:uppercase;">
                ${String(p.status).replace(/_/g, ' ')} (${p.disaster})
              </div>
              <div style="font-weight:700;color:#0F172A;margin:2px 0 4px;">${p.name}</div>
              <div style="color:#475569;">
                <div><strong>Area:</strong> ${Number(p.area_m2).toLocaleString()} m²</div>
                <div><strong>Boundary Distance:</strong> ${p.distance_m} m</div>
                <div><strong>Origin:</strong> ${String(p.origin).replace(/_/g, ' ')}</div>
              </div>
            </div>
          `)
          .addTo(map);
      });
      map.on('mouseenter', fillLayerId, () => {
        map.getCanvas().style.cursor = 'pointer';
      });
      map.on('mouseleave', fillLayerId, () => {
        map.getCanvas().style.cursor = '';
      });
    };

    if (map.isStyleLoaded()) {
      addPolygonLayers();
    } else {
      map.once('styledata', addPolygonLayers);
    }
  }, [cvResult, showCvLayer]);

  useEffect(() => {
    if (!mapRef.current) return;

    disasterMarkersRef.current.forEach((m) => m.remove());
    disasterMarkersRef.current = [];

    for (const ev of disasters) {
      const coords = getDisasterCoords(ev);
      if (!coords) continue;

      const markerEl = document.createElement('div');
      markerEl.className = 'custom-maplibre-marker';
      const color = getDisasterColor(ev.disaster_type);
      const emoji = ev.disaster_type === 'EARTHQUAKE' ? '⚡' : ev.disaster_type === 'FLOOD' ? '🌊' : '🌀';

      markerEl.innerHTML = `
        <div class="marker-inner" style="background-color: ${color}; width: 32px; height: 32px; font-size: 16px;">
          ${emoji}
        </div>
      `;

      markerEl.addEventListener('click', (e) => {
        e.stopPropagation();
        setSelectedDisaster(ev);
        setSelectedSafeLocation(null);
      });

      const marker = new maplibregl.Marker({ element: markerEl })
        .setLngLat(coords)
        .addTo(mapRef.current);
      disasterMarkersRef.current.push(marker);
    }
  }, [disasters]);

  useEffect(() => {
    if (!mapRef.current) return;

    safeLocationMarkersRef.current.forEach((m) => m.remove());
    safeLocationMarkersRef.current = [];

    for (const loc of safeLocations) {
      if (!loc.location?.coordinates) continue;
      const [lng, lat] = loc.location.coordinates;

      const isSelected = userSelectedSafeLocation?.id === loc.id;
      const isOfficial = isOfficiallyVerifiedShelter(
        loc.designation_status,
        loc.verification_status,
      );
      const cat = (loc.category || '').toUpperCase();
      const candType = safePlacesMap[loc.id]?.candidate_type;
      const isNaturalTerrain = cat === 'NATURAL_ELEVATED_TERRAIN' || candType === 'NATURAL_ELEVATED_TERRAIN';
      const isShelter = isOfficial;
      const isFlyover = cat.includes('FLYOVER') || candType === 'ELEVATED_INFRASTRUCTURE';
      const isOpenArea = ['OPEN_GROUND', 'PARK', 'PLAYGROUND', 'GARDEN', 'FIELD', 'FARMLAND', 'ROAD'].includes(cat);
      const isHospital = cat.includes('HOSPITAL');
      const isMedicalFacility =
        isHospital || cat.includes('HEALTH') || cat.includes('MEDICAL');

      const suitability = suitabilityMap[loc.id];
      let markerIcon = isSelected
        ? '★'
        : isNaturalTerrain
          ? '⛰️'
          : isFlyover
            ? '🌉'
            : isShelter
              ? '🏠'
              : isOpenArea
                ? '🌳'
                : isMedicalFacility
                  ? '🏥'
                  : '🏛️';
      let bgColor = isSelected
        ? (isOfficial ? '#16A34A' : isMedicalFacility ? '#2563EB' : '#D97706')
        : isOfficial
          ? '#16A34A'
          : isMedicalFacility
            ? '#2563EB'
            : '#D97706';
      let badgeText = '';

      if (suitability) {
        if (suitability.suitability_classification === 'SUITABILITY_SUPPORTED_BY_EVIDENCE') {
          badgeText = isNaturalTerrain ? 'High Ground' : isFlyover ? 'Flyover' : 'Safe';
        } else if (suitability.suitability_classification === 'CONDITIONALLY_SUITABLE') {
          badgeText = 'Caution';
        } else if (suitability.suitability_classification === 'NOT_RECOMMENDED') {
          badgeText = 'Higher Risk';
        } else if (suitability.suitability_classification === 'EXCLUDED') {
          markerIcon = '🚫';
          badgeText = 'Unsafe';
        }
      }

      const el = document.createElement('div');
      el.className = `custom-maplibre-marker ${isSelected ? 'selected-safe-marker' : ''}`;
      const typeLabel = isNaturalTerrain
        ? 'High Ground'
        : isFlyover
          ? 'Elevated Flyover'
          : isOfficial
            ? 'Official Shelter'
            : formatSafeLocationCategoryLabel(
                loc.category,
                loc.designation_status,
                loc.verification_status,
              );
      el.title = `${loc.name || typeLabel} (${typeLabel})${badgeText ? ` — ${badgeText}` : ''}`;
      el.innerHTML = `
        <div class="marker-inner" style="background-color: ${bgColor}; width: ${isSelected ? '36px' : '28px'}; height: ${isSelected ? '36px' : '28px'}; font-size: ${isSelected ? '18px' : '14px'}; border-color: ${isSelected ? '#F59E0B' : '#FFFFFF'}; display: flex; align-items: center; justify-content: center;">
          ${markerIcon}
        </div>
      `;

      el.addEventListener('click', (e) => {
        e.stopPropagation();
        setSelectedSafeLocation(loc);
        setSelectedDisaster(null);
      });

      const marker = new maplibregl.Marker({ element: el })
        .setLngLat([lng, lat])
        .addTo(mapRef.current);
      safeLocationMarkersRef.current.push(marker);
    }
  }, [safeLocations, safePlacesMap, userSelectedSafeLocation, suitabilityMap]);

  useEffect(() => {
    mainpuriLocationMarkersRef.current.forEach((marker) => marker.remove());
    mainpuriLocationMarkersRef.current = [];

    const map = mapRef.current;
    if (!map) return;

    for (const location of visibleMainpuriLocations) {
      if (location.latitude == null || location.longitude == null) continue;

      const markerElement = document.createElement('button');
      markerElement.type = 'button';
      markerElement.className = 'custom-maplibre-marker mainpuri-location-marker';
      markerElement.setAttribute('aria-label', `Show ${location.name}`);
      markerElement.title = `${location.name} — not a designated disaster shelter`;

      const markerInner = document.createElement('span');
      markerInner.className = 'marker-inner';
      markerInner.textContent =
        location.category === 'hospital' || location.category === 'healthcare_centre' ? '✚' : '!';
      markerInner.style.backgroundColor = getSafeLocationMarkerColor(location);
      markerElement.appendChild(markerInner);

      const selectMarker = (event: Event) => {
        event.stopPropagation();
        handleSelectMainpuriLocation(location);
      };
      markerElement.addEventListener('click', selectMarker);
      markerElement.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          selectMarker(event);
        }
      });

      const marker = new maplibregl.Marker({ element: markerElement, anchor: 'bottom' })
        .setLngLat([location.longitude, location.latitude])
        .addTo(map);
      mainpuriLocationMarkersRef.current.push(marker);
    }

    focusMainpuriLocations(visibleMainpuriLocations);
  }, [focusMainpuriLocations, handleSelectMainpuriLocation, visibleMainpuriLocations]);

  useEffect(() => {
    const source = mapRef.current?.getSource('evacuation-route') as maplibregl.GeoJSONSource | undefined;
    if (!source) return;

    if (!activeRoute) {
      source.setData({ type: 'FeatureCollection', features: [] });
      return;
    }

    const routeColor =
      activeRoute.route_status === 'ROUTE_AVAILABLE'
        ? '#10B981'
        : activeRoute.route_status === 'ROUTE_WITH_WARNINGS'
          ? '#F59E0B'
          : '#EF4444';

    source.setData({
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: { color: routeColor },
          geometry: activeRoute.route_geometry,
        },
      ],
    });

    if (activeRoute.route_geometry?.coordinates?.length && mapRef.current) {
      const bounds = new maplibregl.LngLatBounds();
      activeRoute.route_geometry.coordinates.forEach((coord) => bounds.extend(coord as [number, number]));
      if (userLoc.location) {
        bounds.extend([userLoc.location.longitude, userLoc.location.latitude]);
      }
      mapRef.current.fitBounds(bounds, { padding: 80, duration: 800 });
    }
  }, [activeRoute, userLoc.location]);

  const zoomToFloodPath = useCallback((disaster: DisasterEventSummary) => {
    if (!mapRef.current) return;
    const flowCoords = computePredictedFloodPath(disaster);
    if (flowCoords.length >= 2) {
      const bounds = new maplibregl.LngLatBounds();
      flowCoords.forEach((pt) => bounds.extend(pt));
      const coords = getDisasterCoords(disaster);
      if (coords) bounds.extend(coords);
      mapRef.current.fitBounds(bounds, { padding: 90, maxZoom: 13, duration: 1000 });
    }
  }, []);

  useEffect(() => {
    const lineSource = mapRef.current?.getSource('flood-flow-paths') as maplibregl.GeoJSONSource | undefined;
    const nodeSource = mapRef.current?.getSource('flood-flow-nodes') as maplibregl.GeoJSONSource | undefined;
    if (!lineSource) return;

    const lineFeatures: Array<{
      type: 'Feature';
      properties: { id: string; title: string | null; isSelected: boolean; color: string };
      geometry: { type: 'LineString'; coordinates: [number, number][] };
    }> = [];
    const nodeFeatures: Array<{
      type: 'Feature';
      properties: { id: string; stage: string; isSelected: boolean };
      geometry: { type: 'Point'; coordinates: [number, number] };
    }> = [];

    if (selectedDisaster?.disaster_type === 'FLOOD') {
      const flowCoords = computePredictedFloodPath(selectedDisaster);
      if (flowCoords.length >= 2) {
        lineFeatures.push({
          type: 'Feature',
          properties: {
            id: selectedDisaster.id,
            title: selectedDisaster.title,
            isSelected: true,
            color: '#00E5FF',
          },
          geometry: { type: 'LineString', coordinates: flowCoords },
        });

        flowCoords.forEach((pt, idx) => {
          let stage = 'Flow Waypoint';
          if (idx === 0) stage = 'Flood Crest Origin';
          else if (idx === Math.floor(flowCoords.length / 2)) stage = 'Intermediate Inundation Surge';
          else if (idx === flowCoords.length - 1) stage = 'Downstream Basin Drainage';

          nodeFeatures.push({
            type: 'Feature',
            properties: { id: `${selectedDisaster.id}-node-${idx}`, stage, isSelected: true },
            geometry: { type: 'Point', coordinates: pt },
          });
        });
      }
    } else if (activeFilter === 'FLOOD' || activeFilter === 'ALL') {
      disasters
        .filter((d) => d.disaster_type === 'FLOOD')
        .forEach((floodEv) => {
          const flowCoords = computePredictedFloodPath(floodEv);
          if (flowCoords.length >= 2) {
            lineFeatures.push({
              type: 'Feature',
              properties: { id: floodEv.id, title: floodEv.title, isSelected: false, color: '#0288D1' },
              geometry: { type: 'LineString', coordinates: flowCoords },
            });
          }
        });
    }

    lineSource.setData({ type: 'FeatureCollection', features: lineFeatures });
    nodeSource?.setData({ type: 'FeatureCollection', features: nodeFeatures });
  }, [selectedDisaster, activeFilter, disasters]);

  useEffect(() => {
    if (selectedDisaster?.disaster_type === 'FLOOD') {
      zoomToFloodPath(selectedDisaster);
    }
  }, [selectedDisaster, zoomToFloodPath]);

  const activeDisasterType: DisasterType = activeFilter === 'ALL' ? 'FLOOD' : (activeFilter as DisasterType);

  const handleAssessSuitability = useCallback(
    async (_safeLocationId: string, disasterType: DisasterType) => {
      const lat = userLoc.location?.latitude ?? 28.6139;
      const lng = userLoc.location?.longitude ?? 77.209;
      setEvaluatingSuitability(true);
      try {
        const res = await assessSafeLocationsSuitability({
          latitude: lat,
          longitude: lng,
          disaster_type: disasterType,
          search_radius_km: 5.0,
          limit: 20,
        });

        if (res.success) {
          const mapping: Record<string, LocationSuitabilityAssessmentItem> = {};
          res.assessed_locations.forEach((item) => {
            mapping[item.location_id] = item;
          });
          setSuitabilityMap((prev) => ({ ...prev, ...mapping }));
        }
      } catch (err) {
        console.warn('Suitability assessment error:', err);
      } finally {
        setEvaluatingSuitability(false);
      }
    },
    [userLoc.location],
  );

  const handleCalculateRoute = useCallback(
    async (safeLocationId: string, disasterType: DisasterType) => {
      const lat = userLoc.location?.latitude ?? 28.6139;
      const lng = userLoc.location?.longitude ?? 77.209;
      setCalculatingRoute(true);
      try {
        let assessmentId = suitabilityMap[safeLocationId]?.assessment_id;
        if (!assessmentId) {
          const suitRes = await assessSafeLocationsSuitability({
            latitude: lat,
            longitude: lng,
            disaster_type: disasterType,
            search_radius_km: 5.0,
            limit: 20,
          });
          assessmentId = suitRes.assessed_locations.find((l) => l.location_id === safeLocationId)?.assessment_id;
        }

        if (!assessmentId) {
          showToast('Could not prepare a route for that location yet.');
          return;
        }

        const routeRes = await calculateEvacuationRoute({
          origin_latitude: lat,
          origin_longitude: lng,
          location_assessment_id: assessmentId,
          allow_alternatives: true,
        });

        if (routeRes.success && routeRes.primary_route) {
          setActiveRoute(routeRes.primary_route);
        } else {
          showToast('No safe driving route found right now.');
        }
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Could not calculate route.');
      } finally {
        setCalculatingRoute(false);
      }
    },
    [userLoc.location, suitabilityMap, showToast],
  );

  const handleSelectSafeLocationByUser = useCallback(
    (loc: SafeLocationItem) => {
      setUserSelectedSafeLocation(loc);
      setSelectedSafeLocation(loc);

      if (loc.location?.coordinates && mapRef.current) {
        const [lng, lat] = loc.location.coordinates;
        mapRef.current.flyTo({ center: [lng, lat], zoom: 15, essential: true });
      }

      handleCalculateRoute(loc.id, activeDisasterType);
    },
    [activeDisasterType, handleCalculateRoute],
  );

  const handleGetUnifiedRecommendations = useCallback(
    async (disasterType: DisasterType) => {
      const lat = userLoc.location?.latitude ?? 28.6139;
      const lng = userLoc.location?.longitude ?? 77.209;
      setLoadingUnifiedRecs(true);
      try {
        const res = await fetchUnifiedRecommendations({
          latitude: lat,
          longitude: lng,
          disaster_type: disasterType,
          search_radius_km: 5.0,
          max_destinations: 5,
        });

        setUnifiedRecs(res);
        setShowUnifiedModal(true);

        if (res.recommendations.length > 0 && res.recommendations[0].route) {
          setActiveRoute(res.recommendations[0].route);
        }
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Could not load safe place recommendations.');
      } finally {
        setLoadingUnifiedRecs(false);
      }
    },
    [userLoc.location, showToast],
  );

  const disasterCounts = useMemo(() => {
    let eq = 0;
    let fl = 0;
    let cy = 0;
    for (const d of disasters) {
      if (d.disaster_type === 'EARTHQUAKE') eq++;
      else if (d.disaster_type === 'FLOOD') fl++;
      else if (d.disaster_type === 'CYCLONE') cy++;
    }
    return { eq, fl, cy, total: disasters.length };
  }, [disasters]);

  return (
    <div className="map-screen">
      <div className="map-controls-bar">
        <div className="controls-group-left">
          <button
            className="action-btn-pill"
            style={{ background: '#EFF6FF', borderColor: '#BFDBFE', color: '#1E40AF', fontWeight: 700 }}
            onClick={() => {
              mapRef.current?.flyTo({ center: DEFAULT_CENTER, zoom: DEFAULT_ZOOM, essential: true });
            }}
            title="Zoom out to see all of India"
          >
            🇮🇳 India ({disasterCounts.total})
          </button>

          <button
            className={`filter-pill ${showMainpuriSafeLocations ? 'active' : ''}`}
            onClick={() => {
              const open = !showMainpuriSafeLocations;
              setShowMainpuriSafeLocations(open);
              if (open) focusMainpuriLocations(visibleMainpuriLocations);
            }}
            aria-pressed={showMainpuriSafeLocations}
            title="Search and filter offline Mainpuri safe-location records"
          >
            🛟 Mainpuri Locations ({validMainpuriLocations.length})
          </button>

          {(['ALL', 'EARTHQUAKE', 'FLOOD', 'CYCLONE'] as FilterCategory[]).map((cat) => (
            <button
              key={cat}
              className={`filter-pill ${activeFilter === cat ? 'active' : ''}`}
              onClick={() => setActiveFilter(cat)}
            >
              {cat === 'ALL'
                ? `All (${disasterCounts.total})`
                : cat === 'EARTHQUAKE'
                  ? `⚡ Earthquake (${disasterCounts.eq})`
                  : cat === 'FLOOD'
                    ? `🌊 Flood (${disasterCounts.fl})`
                    : `🌀 Cyclone (${disasterCounts.cy})`}
            </button>
          ))}
        </div>

        <div className="controls-group-right">
          <select
            className="action-btn-pill"
            style={{
              padding: '6px 12px',
              outline: 'none',
              maxWidth: '290px',
              border: userSelectedSafeLocation ? '2px solid #16A34A' : '1px solid var(--border-subtle)',
              fontWeight: userSelectedSafeLocation ? 700 : 500,
              backgroundColor: userSelectedSafeLocation ? '#F0FDF4' : '#FFFFFF',
            }}
            value={userSelectedSafeLocation?.id || ''}
            onChange={(e) => {
              const found = safeLocations.find((s) => s.id === e.target.value);
              if (found) {
                handleSelectSafeLocationByUser(found);
              } else {
                setUserSelectedSafeLocation(null);
                setActiveRoute(null);
              }
            }}
          >
            <option value="">
              🛡️ Nearby Safe Places ({loadingSafePlaces ? 'Searching…' : `within ${effectiveRadiusKm} km`})
            </option>
            {safeLocations.map((loc) => {
              const distLabel = loc.distance_km != null ? `${formatDistance(loc.distance_km)} · ` : '';
              const ptsLabel = loc.total_points != null ? ` (${Math.round(loc.total_points)}/100)` : '';
              return (
                <option key={loc.id} value={loc.id}>
                  {distLabel}
                  {loc.name || 'Shelter'} —{' '}
                  {formatSafeLocationCategoryLabel(
                    loc.category,
                    loc.designation_status,
                    loc.verification_status,
                  )}
                  {ptsLabel}
                </option>
              );
            })}
          </select>

          <button
            className="action-btn-pill action-btn-primary"
            onClick={() => handleGetUnifiedRecommendations(activeDisasterType)}
            disabled={loadingUnifiedRecs}
          >
            {loadingUnifiedRecs ? 'Finding Safest…' : '🛡️ Safest Options'}
          </button>

          <button
            className="action-btn-pill"
            onClick={() => setShowAlertCenter(true)}
            style={{ position: 'relative' }}
          >
            🔔 Alerts
            {alerts.length > 0 && (
              <span
                style={{
                  background: '#EF4444',
                  color: '#FFFFFF',
                  borderRadius: '9999px',
                  padding: '1px 6px',
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  marginLeft: '4px',
                }}
              >
                {alerts.length}
              </span>
            )}
          </button>

          <button
            className="action-btn-pill"
            style={{
              background: userLoc.status === 'success' ? '#F0FDF4' : '#FFFFFF',
              borderColor: userLoc.status === 'success' ? '#86EFAC' : 'var(--border-subtle)',
              color: userLoc.status === 'success' ? '#166534' : 'var(--text-primary)',
              fontWeight: 600,
            }}
            onClick={() => setShowManualLocationModal(true)}
            title="Pick a city or type coordinates"
          >
            ✏️ Change Location
          </button>

          <button
            className="action-btn-pill"
            style={{
              backgroundColor: showCvLayer ? '#CCFBF1' : 'var(--bg-surface)',
              borderColor: showCvLayer ? '#14B8A6' : 'var(--border-subtle)',
              color: showCvLayer ? '#0F766E' : 'var(--text-primary)',
              fontWeight: 600,
            }}
            onClick={() => setShowCvLayer((prev) => !prev)}
            title={
              cvResult
                ? `${cvResult.imagery_metadata.provider} (${cvResult.imagery_metadata.status}) • Seg: ${cvResult.segmentation_status.status} • OSM: ${cvResult.osm_status}`
                : 'Toggle Hybrid Safe Spaces layer'
            }
          >
            {loadingCv
              ? '🛰️ Scanning…'
              : `🛰️ Hybrid Safe Spaces (${cvResult?.total_candidates ?? 0} • ${cvResult?.effective_radius_m ?? 100}m)`}
          </button>

          <button
            className="action-btn-pill"
            onClick={handleAcquireLocation}
            title="Center map on my current GPS location"
          >
            📍 {locationSourceLabel}
          </button>
        </div>
      </div>

      <div className="map-container" ref={mapContainerRef} />

      {mapTilesUnavailable && (
        <div className="map-tile-notice" role="status">
          Online map tiles are unavailable. Location records and markers remain available offline.
        </div>
      )}

      <div className="mainpuri-map-legend" aria-label="Safe location map legend">
        <strong>Mainpuri map legend</strong>
        <span><i className="legend-dot green" /> Officially designated shelter</span>
        <span><i className="legend-dot blue" /> Hospital / medical facility</span>
        <span><i className="legend-dot orange" /> Potential facility / safe area, unverified</span>
        <span><i className="legend-dot red" /> Verified hazard / affected area (none verified)</span>
      </div>

      {showMainpuriSafeLocations && (
        <MainpuriSafeLocationsPanel
          locations={validMainpuriLocations}
          selectedLocationId={selectedMainpuriLocationId}
          userCoordinates={mainpuriUserCoordinates}
          onClose={() => setShowMainpuriSafeLocations(false)}
          onSelectLocation={handleSelectMainpuriLocation}
          onUserCoordinatesChange={handleMainpuriUserCoordinatesChange}
          onVisibleLocationsChange={handleVisibleMainpuriLocationsChange}
        />
      )}

      {feedbackToast && (
        <div className="location-feedback-toast">
          <span>{feedbackToast}</span>
        </div>
      )}

      {selectedDisaster && selectedDisaster.disaster_type === 'FLOOD' && (
        <div className="flood-path-indicator-banner">
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '1.3rem' }}>🌊</span>
            <div>
              <strong style={{ color: '#0369A1', fontSize: '0.85rem', display: 'block' }}>
                Estimated Flood Flow Path
              </strong>
              <div style={{ fontSize: '0.74rem', color: '#0C4A6E' }}>
                <strong>{getBasinName(getDisasterCoords(selectedDisaster))}</strong> ({getFlowDirectionText(getDisasterCoords(selectedDisaster))})
              </div>
            </div>
          </div>
          <button
            className="action-btn-pill"
            style={{
              fontSize: '0.72rem',
              padding: '4px 8px',
              background: '#0288D1',
              color: '#FFFFFF',
              border: 'none',
              whiteSpace: 'nowrap',
            }}
            onClick={() => zoomToFloodPath(selectedDisaster)}
            title="Zoom to the downstream flood path"
          >
            🔍 Focus Path
          </button>
        </div>
      )}

      {userSelectedSafeLocation && (
        <div className="selected-safe-location-banner">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '0.85rem', fontWeight: 800, color: '#166534', display: 'flex', alignItems: 'center', gap: '6px' }}>
              ★ Chosen Safe Place
            </span>
            <button
              style={{ background: 'transparent', border: 'none', color: '#6B7280', cursor: 'pointer', fontSize: '1rem' }}
              onClick={() => {
                setUserSelectedSafeLocation(null);
                setActiveRoute(null);
              }}
              title="Clear selected shelter"
            >
              ✕
            </button>
          </div>

          <div style={{ fontWeight: 700, fontSize: '0.95rem', color: '#0F172A' }}>
            {userSelectedSafeLocation.name || 'Nearby Shelter'}
          </div>

          <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap', fontSize: '0.75rem' }}>
            <span
              className={`badge ${
                isOfficiallyVerifiedShelter(
                  userSelectedSafeLocation.designation_status,
                  userSelectedSafeLocation.verification_status,
                )
                  ? 'badge-official'
                  : 'badge-unverified'
              }`}
            >
              {formatSafeLocationDesignationLabel(
                userSelectedSafeLocation.designation_status,
                userSelectedSafeLocation.verification_status,
              )}
            </span>
            <span className={`badge ${userSelectedSafeLocation.verification_status === 'VERIFIED' ? 'badge-verified' : 'badge-unverified'}`}>
              {formatVerificationLabel(userSelectedSafeLocation.verification_status)}
            </span>
            {userSelectedSafeLocation.capacity != null && (
              <span className="badge" style={{ background: '#F1F5F9', color: '#475569' }}>
                Cap: {userSelectedSafeLocation.capacity}
              </span>
            )}
            {userSelectedSafeLocation.distance_km != null && (
              <span className="badge" style={{ background: '#DCFCE7', color: '#166534', fontWeight: 700 }}>
                {formatDistance(userSelectedSafeLocation.distance_km)} away
              </span>
            )}
          </div>

          {activeRoute && (
            <div style={{ background: '#ECFDF5', padding: '6px 8px', borderRadius: '6px', fontSize: '0.8rem', color: '#065F46', display: 'flex', justifyContent: 'space-between' }}>
              <span>🚗 Distance: <strong>{activeRoute.distance_km.toFixed(1)} km</strong></span>
              <span>⏱️ About <strong>{activeRoute.estimated_time_minutes.toFixed(0)} min</strong></span>
            </div>
          )}

          <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
            <button
              className="action-btn-pill action-btn-primary"
              style={{ flex: 1, justifyContent: 'center', fontSize: '0.8rem', padding: '5px 8px' }}
              onClick={() => handleCalculateRoute(userSelectedSafeLocation.id, activeDisasterType)}
              disabled={calculatingRoute}
            >
              {calculatingRoute ? 'Updating Route…' : '🗺️ Refresh Route'}
            </button>
            <button
              className="action-btn-pill"
              style={{ fontSize: '0.8rem', padding: '5px 8px' }}
              onClick={() => handleAssessSuitability(userSelectedSafeLocation.id, activeDisasterType)}
              disabled={evaluatingSuitability}
            >
              {evaluatingSuitability ? 'Checking…' : 'Safety Check'}
            </button>
          </div>
        </div>
      )}

      {selectedDisaster && (
        <DisasterDetailsPanel
          disaster={selectedDisaster}
          onClose={() => setSelectedDisaster(null)}
          onFocusFloodPath={zoomToFloodPath}
          onAnalyzeEvacuation={handleGetUnifiedRecommendations}
        />
      )}

      {selectedSafeLocation && (
        <SafeLocationDetailsPanel
          location={selectedSafeLocation}
          safePlace={safePlacesMap[selectedSafeLocation.id]}
          suitability={suitabilityMap[selectedSafeLocation.id]}
          effectiveRadiusKm={effectiveRadiusKm}
          isSelectedDestination={userSelectedSafeLocation?.id === selectedSafeLocation.id}
          calculatingRoute={calculatingRoute}
          evaluatingSuitability={evaluatingSuitability}
          onClose={() => setSelectedSafeLocation(null)}
          onSelectDestination={handleSelectSafeLocationByUser}
          onCalculateRoute={(id) => handleCalculateRoute(id, activeDisasterType)}
          onAssessSuitability={(id) => handleAssessSuitability(id, activeDisasterType)}
        />
      )}

      {activeRoute && (
        <EvacuationRoutePanel
          route={activeRoute}
          hasSelectedDestination={Boolean(userSelectedSafeLocation)}
          onClose={() => setActiveRoute(null)}
        />
      )}

      {showManualLocationModal && (
        <ManualLocationModal
          manualLat={manualLat}
          manualLng={manualLng}
          onChangeLat={setManualLat}
          onChangeLng={setManualLng}
          onClose={() => setShowManualLocationModal(false)}
          onStartMapTap={() => {
            setShowManualLocationModal(false);
            showToast('👆 Tap anywhere on the map to place your pin');
          }}
          onApplyLocation={handleApplyManualLocation}
        />
      )}

      {showUnifiedModal && unifiedRecs && (
        <UnifiedRecommendationsModal
          recommendations={unifiedRecs}
          onClose={() => setShowUnifiedModal(false)}
          onSelectRecommendation={(rec) => {
            const found = safeLocations.find((s) => s.id === rec.destination_id);
            if (found) handleSelectSafeLocationByUser(found);
            if (rec.route) setActiveRoute(rec.route);
            setShowUnifiedModal(false);
          }}
        />
      )}

      {showAlertCenter && (
        <AlertCenterModal
          alerts={alerts}
          notificationHistory={notificationHistory}
          activeTab={activeAlertTab}
          onChangeTab={setActiveAlertTab}
          onClose={() => setShowAlertCenter(false)}
        />
      )}

      <div className="safety-disclaimer-pill">
        ⚠️ <strong>Safety Note:</strong> Shelter suggestions are advisory only — always follow local authorities (NDMA, IMD, CWC) and emergency responders.
      </div>
    </div>
  );
}
