from __future__ import annotations

import json
import logging
import math
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any

from app.schemas.flood_assessment import GeographicalEnvironment
from app.services.city_elevation_data import (
    find_nearest_city_elevation,
    get_city_height_from_sea_level,
)
from app.services.terrain_analysis import (
    ElevationGrid,
    calculate_local_elevation,
    calculate_slope_and_aspect,
    classify_valley_morphometry,
    extract_drainage_network_and_hand,
)

logger = logging.getLogger(__name__)

# In-memory thread-safe cache for retrieved DEM elevations by rounded coordinate (lat_4dec, lon_4dec)
_DEM_ELEVATION_CACHE: dict[tuple[float, float], float] = {}

OPEN_METEO_ELEVATION_API_URL = "https://api.open-meteo.com/v1/elevation"


def get_cached_dem_elevation(lat: float, lon: float) -> float | None:
    """Returns cached elevation if already retrieved."""
    key = (round(lat, 4), round(lon, 4))
    return _DEM_ELEVATION_CACHE.get(key)


def set_cached_dem_elevation(lat: float, lon: float, elev: float) -> None:
    """Stores elevation in local memory cache."""
    key = (round(lat, 4), round(lon, 4))
    _DEM_ELEVATION_CACHE[key] = round(float(elev), 1)


def fetch_dem_elevations_from_api(
    coords: list[tuple[float, float]],
    timeout_seconds: float = 3.0,
) -> dict[tuple[float, float], float]:
    """
    Retrieves authentic ground elevations from Open-Meteo DEM Elevation API
    (powered by Copernicus DEM GLO-90 and SRTM).
    Supports multi-point batching in a single request.
    """
    results: dict[tuple[float, float], float] = {}
    missing_coords: list[tuple[float, float]] = []

    for lat, lon in coords:
        cached = get_cached_dem_elevation(lat, lon)
        if cached is not None:
            results[(lat, lon)] = cached
        else:
            missing_coords.append((lat, lon))

    if not missing_coords:
        return results

    try:
        lat_str = ",".join(str(round(c[0], 5)) for c in missing_coords)
        lon_str = ",".join(str(round(c[1], 5)) for c in missing_coords)
        url = f"{OPEN_METEO_ELEVATION_API_URL}?latitude={lat_str}&longitude={lon_str}"

        req = urllib.request.Request(
            url,
            headers={"User-Agent": "DisasterSafetyApp/2.0 (+https://github.com/disaster-safety-app)"},
        )
        with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            elev_list = data.get("elevation", [])
            for c, elev_val in zip(missing_coords, elev_list):
                if elev_val is not None:
                    elev_f = round(float(elev_val), 1)
                    results[c] = elev_f
                    set_cached_dem_elevation(c[0], c[1], elev_f)
    except Exception as exc:
        logger.debug(f"Open-Meteo DEM Elevation API fetch note: {exc}")

    return results


def retrieve_candidate_elevation_and_3d(
    lat: float,
    lon: float,
    user_lat: float,
    user_lon: float,
    dem_grid: ElevationGrid | None = None,
    is_flyover: bool = False,
    is_shelter: bool = False,
    osm_tags: dict[str, Any] | None = None,
    user_dem_grid: ElevationGrid | None = None,
) -> dict[str, Any]:
    """
    Mandatory Requirements 2, 3, 4, 7:
    Retrieves real elevation and performs 3D analysis for arbitrary coordinates.
    1. Distinguishes:
       - Ground elevation above sea level (from verified DEM).
       - Relative elevation compared with user location.
       - Local terrain slope and drainage feature.
       - Estimated structure height (flyover deck, building levels, or UNKNOWN).
    2. Works for arbitrary valid coordinates (independent of city names).
    3. Handles source unavailability with explicit statuses:
       - VERIFIED_DEM (Copernicus / SRTM)
       - REGIONAL_BENCHMARK_INTERPOLATED (Survey of India regional macro mesh fallback)
       - ELEVATION_UNAVAILABLE (missing data; never fabricated).
    """
    now_iso = datetime.now(timezone.utc).isoformat()
    tags = osm_tags or {}

    # --- 1. User Ground Elevation ---
    user_ground_msl: float | None = None
    if user_dem_grid is not None:
        user_ground_msl = calculate_local_elevation(user_dem_grid)
    else:
        # Check cache or DEM API
        batch_res = fetch_dem_elevations_from_api([(user_lat, user_lon)])
        if (user_lat, user_lon) in batch_res:
            user_ground_msl = batch_res[(user_lat, user_lon)]
        else:
            # Macro regional benchmark fallback within Indian bounds (lat 6-38, lon 68-98)
            if 6.0 <= user_lat <= 38.0 and 68.0 <= user_lon <= 98.0:
                try:
                    user_ground_msl = get_city_height_from_sea_level(user_lat, user_lon)
                except Exception:
                    user_ground_msl = None

    # --- 2. Candidate Ground Elevation ---
    cand_ground_msl: float | None = None
    elev_status = "UNKNOWN"
    elev_source: str | None = None
    elev_res_m: float | None = None
    elev_quality = "UNKNOWN"
    uncertainty_m: float | None = None

    if dem_grid is not None:
        cand_ground_msl = calculate_local_elevation(dem_grid)
        elev_status = "VERIFIED_DEM"
        elev_source = f"{dem_grid.source_name} ({dem_grid.resolution_m}m)"
        elev_res_m = dem_grid.resolution_m
        elev_quality = "HIGH"
        uncertainty_m = 2.0
    else:
        # Fetch from real DEM API
        batch_cand = fetch_dem_elevations_from_api([(lat, lon)])
        if (lat, lon) in batch_cand:
            cand_ground_msl = batch_cand[(lat, lon)]
            elev_status = "VERIFIED_DEM"
            elev_source = "Copernicus DEM GLO-90 / SRTM (Open-Meteo)"
            elev_res_m = 90.0
            elev_quality = "HIGH"
            uncertainty_m = 2.5
        elif 6.0 <= lat <= 38.0 and 68.0 <= lon <= 98.0:
            # Secondary supplementary macro regional reference within Indian bounds
            try:
                cand_ground_msl = get_city_height_from_sea_level(lat, lon)
                nearest_city, _, city_dist_km, _, _ = find_nearest_city_elevation(lat, lon)
                elev_status = "REGIONAL_BENCHMARK_INTERPOLATED"
                elev_source = f"Survey of India Regional MSL Benchmark ({nearest_city} Reference)"
                elev_res_m = 25000.0
                elev_quality = "INTERPOLATED" if city_dist_km > 25.0 else "BENCHMARK"
                uncertainty_m = 8.0
            except Exception:
                cand_ground_msl = None
        else:
            # Completely outside coverage or missing data
            cand_ground_msl = None
            elev_status = "ELEVATION_UNAVAILABLE"
            elev_quality = "UNKNOWN"

    # --- 3. Structure Height & 3D Geographical Analysis (Section 4) ---
    structure_height_m: float | None = None
    structure_height_status = "UNKNOWN"

    if is_flyover:
        structure_height_m = 6.0
        structure_height_status = "ESTIMATED_INFRASTRUCTURE_DECK"
    elif is_shelter or tags.get("amenity") == "shelter" or "shelter" in str(tags.get("name", "")).lower():
        structure_height_m = 3.5
        structure_height_status = "ESTIMATED_SHELTER_PLINTH"
    elif "height" in tags:
        try:
            h_str = str(tags["height"]).replace("m", "").strip()
            structure_height_m = round(float(h_str), 1)
            structure_height_status = "VERIFIED_3D_FOOTPRINT"
        except (ValueError, TypeError):
            structure_height_m = None
    elif "building:levels" in tags:
        try:
            levels = float(tags["building:levels"])
            structure_height_m = round(levels * 3.5, 1)
            structure_height_status = "ESTIMATED_FROM_STOREY_COUNT"
        except (ValueError, TypeError):
            structure_height_m = None

    # Total refuge elevation above sea level
    if cand_ground_msl is not None:
        total_refuge_elev = cand_ground_msl + (structure_height_m if structure_height_m is not None else 0.0)
    else:
        total_refuge_elev = None

    # Relative elevation compared with user
    if total_refuge_elev is not None and user_ground_msl is not None:
        rel_elev_m = round(total_refuge_elev - user_ground_msl, 1)
    else:
        rel_elev_m = None

    # --- 4. Local Terrain Slope & Drainage Morphometry ---
    if dem_grid is not None:
        slope_deg, aspect_deg, aspect_compass = calculate_slope_and_aspect(dem_grid)
        from app.services.terrain_analysis import calculate_d8_flow_directions, calculate_flow_accumulation
        flow_dirs, center_flow_deg = calculate_d8_flow_directions(dem_grid)
        accum_matrix, center_accum, flow_dir_deg = calculate_flow_accumulation(dem_grid, flow_dirs)
        drainage_prox_m, hand_m = extract_drainage_network_and_hand(dem_grid, flow_dirs, accum_matrix)
        valley_conf, _ = classify_valley_morphometry(dem_grid, flow_dir_deg)
        center_accum = float(center_accum)
        feature_map = {
            "RIDGE_OR_PEAK": "RIDGE_OR_HIGH_GROUND",
            "OPEN_VALLEY": "FLAT_PLAIN" if slope_deg < 5.0 else "TERRACE",
            "NARROW_CANYON": "VALLEY_BOTTOM",
            "V_SHAPED_VALLEY": "VALLEY_BOTTOM",
            "CONVERGENT_HOLLOW": "CONVERGENT_HOLLOW",
        }
        feat_type = feature_map.get(valley_conf, "TERRACE")
    else:
        # Approximate baseline morphology from relative relief
        is_hilly = cand_ground_msl is not None and cand_ground_msl > 600.0
        if is_hilly:
            slope_deg = 15.0
            feat_type = "RIDGE_OR_HIGH_GROUND" if (rel_elev_m is not None and rel_elev_m >= 3.0) else "TERRACE"
            hand_m = max(1.0, (rel_elev_m or 0.0) + 4.0)
            drainage_prox_m = 250.0
            center_accum = 6.0
        else:
            slope_deg = 1.0
            feat_type = "RIDGE_OR_HIGH_GROUND" if (rel_elev_m is not None and rel_elev_m >= 2.0) else "FLAT_PLAIN"
            hand_m = max(1.5, (rel_elev_m or 0.0) + 3.0)
            drainage_prox_m = 450.0
            center_accum = 2.0

    data_avail = "SOURCE_AVAILABLE"
    if elev_status == "ELEVATION_UNAVAILABLE":
        data_avail = "ELEVATION_UNAVAILABLE"
    elif elev_status == "REGIONAL_BENCHMARK_INTERPOLATED":
        data_avail = "PARTIAL_DATA_AVAILABLE"
    elif structure_height_status == "UNKNOWN":
        data_avail = "PARTIAL_DATA_AVAILABLE"

    return {
        "elevation_m": total_refuge_elev,
        "ground_elevation_m": cand_ground_msl,
        "structure_height_m": structure_height_m,
        "structure_height_status": structure_height_status,
        "relative_elevation_m": rel_elev_m,
        "user_ground_elevation_m": user_ground_msl,
        "elevation_status": elev_status,
        "elevation_source": elev_source,
        "elevation_resolution_m": elev_res_m,
        "elevation_retrieval_time": now_iso,
        "elevation_quality": elev_quality,
        "slope_deg": slope_deg,
        "terrain_feature": feat_type,
        "hand_m": hand_m,
        "drainage_distance_m": drainage_prox_m,
        "flow_accumulation": center_accum,
        "elevation_uncertainty_m": uncertainty_m,
        "data_availability_status": data_avail,
    }
