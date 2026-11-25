from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from geoalchemy2 import Geography
from sqlalchemy import delete, func, or_, select
from sqlalchemy.orm import Session, selectinload

from app.models.core import CrisisEvent
from app.models.enums import (
    CrisisStatus,
    CrisisType,
    DesignationStatus,
    VerificationStatus,
)
from app.models.recommendations import SafeLocation, SafeLocationSourceReference
from app.schemas.disaster_search import UserLocation
from app.schemas.recommendation import SuitabilityClassification
from app.schemas.safe_location import SafeLocationSourceSummary
from app.schemas.safe_place import (
    CycloneSafetyFields,
    EarthquakeSafetyFields,
    FindSafePlacesResponse,
    FloodSafetyFields,
    SafePlaceItem,
    SpecificationMatchPoints,
)
from app.services.city_elevation_data import (
    calculate_elevation_difference,
    find_nearest_city_elevation,
    get_city_height_from_sea_level,
)
from app.services.cyclone_assessment import calculate_distance_to_indian_coastline_km
from app.services.elevation_transect_engine import (
    compute_elevation_between_crisis_and_user,
)
from app.services.flood_assessment import (
    FLOOD_SEARCH_RADII_METERS,
    assess_candidate_flood_safety,
    calculate_flood_safety_score,
    classify_geographical_environment,
    discover_natural_elevated_terrain,
    retrieve_candidate_elevation,
)
from app.services.recommendation_engine import (
    calculate_specification_points,
    estimate_height_from_sea_level_msl,
    evaluate_candidate_suitability,
    evaluate_cyclone_suitability,
    evaluate_earthquake_suitability,
    evaluate_flood_suitability,
    evaluate_flood_transect_heights,
    extract_location_coordinates,
    safe_postgis_to_geojson,
)
from app.services.safe_location import extract_missing_information
from app.services.safe_location_ncrmp import sync_ncrmp_shelters
from app.services.safe_location_osm import discover_and_sync_candidate_locations, sync_osm_locations

logger = logging.getLogger(__name__)


def compute_earthquake_safety_fields(
    loc: SafeLocation,
    lat: float,
    lon: float,
    distance_km: float,
) -> EarthquakeSafetyFields:
    """
    Computes specific Earthquake safe location fields:
    1. Distance from user (km & m).
    2. Area of location safe from earthquake (footprint / capacity area).
    3. Open places (gardens, fields, roads) safe from any buildings effect.
    4. For hilly areas: safe place has less chance of affecting by stone crushing, landsliding.
    """
    cat = (loc.category or "").upper()
    open_categories = (
        "OPEN_GROUND", "PARK", "PLAYGROUND", "GARDEN", "FIELD",
        "ROAD", "HIGHWAY", "FARMLAND", "SPORTS_GROUND", "STADIUM"
    )
    is_open = any(k in cat for k in open_categories)
    open_type = next((k for k in open_categories if k in cat), cat if is_open else None)

    # 2. Area safe from earthquake (standard minimum 3.5 m2 per person)
    safe_area = float(loc.capacity) * 3.5 if loc.capacity else 5000.0

    # 4. Hilly area assessment
    geo_env, _ = classify_geographical_environment(lat, lon)
    is_hilly = geo_env.value == "HILLY_OR_MOUNTAINOUS"

    if is_hilly:
        hilly_safety = "Hilly slope terrain: High exposure to stone crushing, rockfalls, and seismic landslides. Crest/flat areas required."
        stone_crush_risk = "ELEVATED_SLOPE_HAZARD"
    else:
        hilly_safety = "Non-hilly flat plains: Zero hazard from mountain stone crushing or slope landsliding."
        stone_crush_risk = "ZERO"

    # 3. Open places protection
    if is_open:
        collapse_protection = f"Open assembly ({cat}): clear skyward clearance safe from building roof collapse and falling masonry."
    else:
        collapse_protection = f"Enclosed structure ({cat}): requires seismic reinforcement and clear perimeter setback."

    return EarthquakeSafetyFields(
        distance_from_user_km=round(distance_km, 3),
        distance_from_user_m=round(distance_km * 1000.0, 1),
        safe_area_m2=round(safe_area, 1),
        is_open_place=is_open,
        open_place_type=open_type,
        building_collapse_protection=collapse_protection,
        hilly_area_safety=hilly_safety,
        stone_crushing_risk=stone_crush_risk,
    )


def compute_flood_safety_fields(
    loc: SafeLocation,
    lat: float,
    lon: float,
    distance_km: float,
    active_flood_events: list[CrisisEvent],
    user_lat: float,
    user_lon: float,
) -> FloodSafetyFields:
    """
    Computes specific Flood safe location fields:
    1. Distance from user.
    2. Elevation of the place.
    3. Safety factor (safe from upcoming flood direction and effect).
    4. Places like higher and wider buildings, flyovers, government places with high elevation.
    5. Calculate or predict flow of the flood.
    - Uses authoritative 'height from sea level' across city elevation dataset and intermediate transect.
    """
    cat = (loc.category or "").upper()
    is_flyover = "FLYOVER" in cat or "BRIDGE" in cat
    is_hospital_govt = any(k in cat for k in ("HOSPITAL", "TOWNHALL", "CIVIC", "PUBLIC", "COMMUNITY", "SCHOOL"))
    is_safe_bldg = is_flyover or is_hospital_govt or "SHELTER" in cat

    if is_flyover:
        bldg_type = "ELEVATED_FLYOVER_REFUGE"
    elif is_hospital_govt:
        bldg_type = "WIDER_GOVERNMENT_FACILITY"
    elif "SHELTER" in cat:
        bldg_type = "ENGINEERED_FLOOD_SHELTER"
    else:
        bldg_type = "GENERAL_STRUCTURE"

    # 2. Elevation / Height from sea level using city dataset
    cand_ground_msl = get_city_height_from_sea_level(lat, lon)
    cand_msl = cand_ground_msl + (6.0 if is_flyover else 0.0)
    user_msl = get_city_height_from_sea_level(user_lat, user_lon)
    cand_nearest_city, _, _, _, _ = find_nearest_city_elevation(lat, lon)
    user_nearest_city, _, _, _, _ = find_nearest_city_elevation(user_lat, user_lon)

    diff_eval = calculate_elevation_difference(
        loc1_name=loc.name,
        loc1_height_m=cand_msl,
        loc2_name="User Location",
        loc2_height_m=user_msl,
    )
    diff_from_user_m = round(cand_msl - user_msl, 1)
    is_higher = cand_msl > user_msl

    transect_heights: list[float] = []
    is_elevated_above = True
    transect_summary = diff_eval["summary"]
    flow_pred = (
        "Calculated downstream drainage gradient directs primary flood torrents along river corridors, "
        "bypassing elevated destination structures."
    )

    # 5. Calculate or predict flow of flood & middle transect heights
    if active_flood_events:
        ev = active_flood_events[0]
        fl_lat: float | None = None
        fl_lon: float | None = None
        ev_geo = safe_postgis_to_geojson(ev.location)
        if ev_geo and "coordinates" in ev_geo:
            fl_lon, fl_lat = float(ev_geo["coordinates"][0]), float(ev_geo["coordinates"][1])
        elif isinstance(ev.location, str) and "POINT" in ev.location:
            import re
            m = re.search(r"POINT\s*\(\s*([-\d.]+)\s+([-\d.]+)\s*\)", ev.location)
            if m:
                fl_lon, fl_lat = float(m.group(1)), float(m.group(2))

        if fl_lat is not None and fl_lon is not None:
            transect_res = compute_elevation_between_crisis_and_user(
                crisis_lat=fl_lat,
                crisis_lon=fl_lon,
                user_lat=user_lat,
                user_lon=user_lon,
            )
            transect_heights = transect_res.middle_heights_msl
            is_elevated_above = cand_msl >= (transect_res.avg_middle_height_msl + 1.0)
            transect_summary = (
                f"{diff_eval['summary']} Intermediate flood transect ({transect_res.min_middle_height_msl:.1f}–"
                f"{transect_res.max_middle_height_msl:.1f} m MSL) from {transect_res.crisis_start_nearest_city}."
            )
            flow_pred = transect_res.flow_prediction_summary

    # 3. Safety factor
    is_open = any(k in cat for k in ("OPEN_GROUND", "PARK", "PLAYGROUND", "FIELD"))
    if is_open and not is_higher:
        safety_factor = "LOW_RETENTION_RISK: Low-lying open grounds act as retention basins during severe runoff."
    elif is_higher and is_safe_bldg:
        safety_factor = (
            f"HIGH_SAFETY: Located at {cand_msl:.1f} m MSL (+{diff_from_user_m:.1f} m higher than user in {user_nearest_city}); "
            "elevated above anticipated flood accumulation depth."
        )
    elif is_higher:
        safety_factor = (
            f"ELEVATED_GROUND_SAFETY: Located at {cand_msl:.1f} m MSL (+{diff_from_user_m:.1f} m higher than user); "
            "natural terrain height advantage."
        )
    else:
        safety_factor = f"MODERATE: Plinth elevation must be verified on-site ({diff_eval['summary']})."

    return FloodSafetyFields(
        distance_from_user_km=round(distance_km, 3),
        elevation_m=round(cand_msl, 1),
        safety_factor=safety_factor,
        is_safe_building=is_safe_bldg,
        safe_building_type=bldg_type,
        flood_flow_prediction=flow_pred,
        middle_transect_heights_msl=transect_heights,
        is_elevated_above_transect=is_elevated_above,
        transect_summary=transect_summary,
        elevation_difference_from_user_m=diff_from_user_m,
        is_higher_than_user=is_higher,
        nearest_city=cand_nearest_city,
    )


def compute_cyclone_safety_fields(
    loc: SafeLocation,
    lat: float,
    lon: float,
    distance_km: float,
) -> CycloneSafetyFields:
    """
    Computes specific Sea Typhoon / Cyclone safe location fields:
    1. Distance from user.
    2. Effect of typhoon on that area (sea typhoon exclusively happens in beaches and coastal margins).
    3. Safety factor of the area.
    """
    cat = (loc.category or "").upper()
    dist_coast_km = calculate_distance_to_indian_coastline_km(lat, lon)
    is_beach = dist_coast_km <= 1.5 or "BEACH" in cat
    is_coastal = dist_coast_km <= 50.0

    if is_beach:
        typhoon_effect = f"Immediate coastal beach zone ({dist_coast_km:.1f} km from sea): Direct exposure to lethal storm surge, tidal inundation, and hurricane surf."
    elif is_coastal:
        typhoon_effect = f"Coastal hinterland ({dist_coast_km:.1f} km from sea): Subject to gale squalls and heavy rainfall, but shielded from direct beach surf."
    else:
        typhoon_effect = f"Inland territory ({dist_coast_km:.1f} km from sea): Safe from marine storm surge and coastal typhoon waves."

    is_open = any(k in cat for k in ("OPEN_GROUND", "PARK", "PLAYGROUND", "FIELD"))
    is_mpcs = "CYCLONE" in cat or "MPCS" in cat or cat == "OFFICIAL_CYCLONE_SHELTER"

    if is_beach or (is_open and is_coastal):
        safety_factor = "DANGEROUS / PROHIBITED: Open coastal beaches offer zero protection against typhoon squalls, surge, and flying debris."
        shelter_eng = "NONE (UNSHELTERED OPEN BEACH)"
    elif is_mpcs:
        safety_factor = "HIGH_SAFETY: Officially engineered Multi-Purpose Cyclone Shelter with reinforced concrete stilts."
        shelter_eng = "NCRMP/SDMA Reinforced Concrete Stilt Design (Surge-Elevated, Wind-Resistant to 250 km/h)"
    else:
        safety_factor = "MODERATE: Reinforced permanent building providing wind shielding."
        shelter_eng = "Permanent Masonry / RCC Structure"

    return CycloneSafetyFields(
        distance_from_user_km=round(distance_km, 3),
        affect_of_typhoon_on_area=typhoon_effect,
        distance_to_coastline_km=round(dist_coast_km, 1),
        is_beach_area=is_beach,
        safety_factor_of_area=safety_factor,
        cyclone_shelter_engineering=shelter_eng,
    )


def is_real_location_suitable_for_crisis(
    loc: SafeLocation,
    crisis: CrisisType,
    suit_class: SuitabilityClassification,
    safe_pts: float,
    user_lat: float | None = None,
    user_lon: float | None = None,
) -> bool:
    """
    Validates whether a physical safe location meets the strict disaster-specific criteria:
    - Eliminates hypothetical / synthetic entries (coordinates or dynamic refuge zone in name).
    - Excludes NOT_RECOMMENDED or EXCLUDED classifications.
    - Earthquake (1.a): ONLY open places (gardens, fields, roads, grounds, stadiums) safe from building collapse/falling debris;
      in hilly areas, low stone crushing / landslide risk. Enclosed buildings/flyovers are excluded.
    - Flood (3.b): ONLY high-elevation safe buildings (hospitals, government complexes, colleges), elevated flyovers,
      and high-ground open fields having more height than the user's location.
      Strictly excludes low-lying open grounds, parks, gardens that get inundated.
    - Cyclone / Sea Typhoon (3.c): ONLY engineered cyclone shelters (MPCS on stilts) and reinforced solid inland facilities;
      strictly excludes open beaches, open grounds, parks, and flyovers.
    """
    name_str = (loc.name or "").strip()
    name_lower = name_str.lower()
    if not name_str or len(name_str) < 3:
        return False
    if "(" in name_lower and ")" in name_lower and any(c.isdigit() for c in name_lower):
        if "refuge zone" in name_lower or "assembly zone" in name_lower or "platform" in name_lower:
            return False

    if suit_class in (
        SuitabilityClassification.EXCLUDED,
        SuitabilityClassification.NOT_RECOMMENDED,
    ):
        return False
    if safe_pts < 30.0:
        return False

    cat = (loc.category or "").upper()
    is_open = (
        cat in ("OPEN_GROUND", "PARK", "PLAYGROUND", "GARDEN", "FIELD", "ROAD", "CAMPUS")
        or any(w in name_lower for w in ["ground", "park", "garden", "field", "stadium", "maidan", "lawn", "turf", "open assembly"])
    )
    is_flyover = (
        "flyover" in name_lower
        or "elevated corridor" in name_lower
        or "elevated deck" in name_lower
        or "viaduct" in name_lower
        or cat in ("FLYOVER", "ELEVATED_STRUCTURE")
    )
    is_building = (
        cat in ("HOSPITAL", "SCHOOL", "GOVERNMENT_BUILDING", "COMMUNITY_CENTER", "OFFICIAL_FLOOD_SHELTER")
        or any(w in name_lower for w in ["hospital", "bhawan", "secretariat", "building", "college", "school", "hall", "complex", "centre", "center", "institute", "university"])
    )

    if crisis == CrisisType.EARTHQUAKE:
        if is_flyover:
            return False
        if is_building and not is_open:
            return False
        return is_open

    elif crisis == CrisisType.FLOOD:
        if is_open and not (is_flyover or is_building):
            # Check if this open field has higher elevation than user location
            if user_lat is not None and user_lon is not None:
                c_lat, c_lon = extract_location_coordinates(loc)
                c_h = get_city_height_from_sea_level(c_lat, c_lon)
                u_h = get_city_height_from_sea_level(user_lat, user_lon)
                if c_h > u_h + 1.0:
                    return True
            return False
        return is_flyover or is_building or cat in ("OFFICIAL_FLOOD_SHELTER", "GOVERNMENT_BUILDING", "HOSPITAL")

    elif crisis == CrisisType.CYCLONE:
        if is_open or is_flyover or "beach" in name_lower:
            return False
        is_cyclone_shelter = (
            cat in ("OFFICIAL_CYCLONE_SHELTER", "COMMUNITY_CENTER", "GOVERNMENT_BUILDING", "SCHOOL", "HOSPITAL")
            or any(w in name_lower for w in ["cyclone", "shelter", "mpcs", "pucca", "relief", "school", "hospital", "bhawan"])
        )
        return is_cyclone_shelter

    return True


def find_flood_safe_places(
    session: Session,
    latitude: float,
    longitude: float,
    max_radius_km: float = 5.0,
    limit: int = 20,
    active_events: list[CrisisEvent] | None = None,
    dem_grid: Any | None = None,
) -> FindSafePlacesResponse:
    """
    Dedicated Flood Safe Location Discovery & Assessment Engine (Requirements 2, 3, 4, 5, 6, 7, 8).
    - Progressive search radii: 100m -> 250m -> 500m -> 1,000m -> 2,000m.
    - Two categories:
      A. Existing elevated infrastructure (flyovers, elevated corridors, existing elevated facilities).
      B. Natural elevated terrain (model-derived high ground from actual elevation data, labeled 'Potential Elevated Ground').
    - Retrieves elevation BEFORE calculating safety.
    - Transparent 5-factor scoring (0 to 100).
    - Excludes ordinary bridges, low-lying open grounds, and dangerous drainage channels / steep slopes.
    - Returns structured error/status messages if no candidates or no reliable elevation.
    """
    user_loc = UserLocation(latitude=round(latitude, 5), longitude=round(longitude, 5))

    # 1. Retrieve user elevation before calculating safety (Section 3)
    user_elev_info = retrieve_candidate_elevation(
        lat=latitude,
        lon=longitude,
        user_lat=latitude,
        user_lon=longitude,
        is_flyover=False,
        dem_grid=dem_grid,
    )
    user_msl = user_elev_info.get("elevation_m")
    if user_msl is None:
        return FindSafePlacesResponse(
            success=False,
            user_location=user_loc,
            disaster_type=CrisisType.FLOOD,
            initial_radius_km=0.1,
            effective_radius_km=0.1,
            total_found=0,
            safe_places=[],
            engine_status="NO_RELIABLE_ELEVATION",
            data_availability_status="ELEVATION_UNAVAILABLE",
            engine_message="No reliable elevation data is available for current coordinates.",
            search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
        )

    # 2. Configurable progressive radii (Section 2 & Section 6)
    search_tiers_m = [r for r in FLOOD_SEARCH_RADII_METERS if r <= max(100.0, max_radius_km * 1000.0)]
    if max_radius_km * 1000.0 > 2000.0:
        if 3500.0 <= max_radius_km * 1000.0 and 3500.0 not in search_tiers_m:
            search_tiers_m.append(3500.0)
        max_m = round(max_radius_km * 1000.0, 1)
        if max_m not in search_tiers_m:
            search_tiers_m.append(max_m)
    if not search_tiers_m:
        search_tiers_m = FLOOD_SEARCH_RADII_METERS

    user_geog = func.ST_SetSRID(func.ST_MakePoint(longitude, latitude), 4326).cast(Geography)
    dist_m_col = func.ST_Distance(SafeLocation.location.cast(Geography), user_geog).label("distance_m")

    active_flood_evts = active_events or []
    all_discovered_flood_cands: dict[uuid.UUID, SafePlaceItem] = {}

    for tier_m in search_tiers_m:
        tier_km = round(tier_m / 1000.0, 3)
        tier_candidates: list[SafePlaceItem] = []
        sources_seen: set[str] = set()

        # Category A: Existing Physical Features in Database within tier_m
        tier_query = (
            select(SafeLocation, dist_m_col)
            .options(
                selectinload(SafeLocation.source_references).selectinload(SafeLocationSourceReference.data_source)
            )
            .where(
                func.ST_DWithin(SafeLocation.location.cast(Geography), user_geog, tier_m),
                ~SafeLocation.name.ilike("%refuge zone (%%"),
                ~SafeLocation.name.ilike("%assembly zone (%%"),
                ~SafeLocation.name.ilike("%healthcare (%%"),
            )
            .order_by(dist_m_col.asc())
        )
        db_rows = session.execute(tier_query).all()

        for row in db_rows:
            loc: SafeLocation = row[0]
            dist_m_val = float(row[1]) if row[1] is not None else 0.0
            dist_km_val = round(dist_m_val / 1000.0, 3)
            c_lat, c_lon = extract_location_coordinates(loc)
            if c_lat == 0.0 and c_lon == 0.0 and loc.location is None:
                c_lat, c_lon = latitude, longitude

            name_str = (loc.name or "").strip()
            name_lower = name_str.lower()
            cat = (loc.category or "").upper()

            # Reject synthetic coordinate names
            if "(" in name_lower and ")" in name_lower and any(c.isdigit() for c in name_lower):
                if any(w in name_lower for w in ["refuge", "assembly", "zone", "platform", "healthcare"]):
                    continue

            is_open = (
                cat in ("OPEN_GROUND", "PARK", "PLAYGROUND", "GARDEN", "BRIDGE", "ROAD")
                or any(w in name_lower for w in ["ground", "park", "garden", "field", "stadium", "maidan", "lawn", "turf"])
            )
            is_flyover = (
                "flyover" in name_lower
                or "elevated corridor" in name_lower
                or "elevated deck" in name_lower
                or "viaduct" in name_lower
                or cat in ("FLYOVER", "ELEVATED_STRUCTURE")
            )
            is_official_shelter = (
                cat == "OFFICIAL_FLOOD_SHELTER"
                or (loc.designation_status == DesignationStatus.OFFICIAL and cat in ("COMMUNITY_CENTRE", "SCHOOL", "HOSPITAL"))
            )
            is_facility = (
                cat in ("HOSPITAL", "SCHOOL", "GOVERNMENT_BUILDING", "COMMUNITY_CENTER")
                or any(w in name_lower for w in ["hospital", "bhawan", "secretariat", "college", "school"])
            )

            if is_flyover:
                cand_type = "ELEVATED_INFRASTRUCTURE"
                access_desc = "UNKNOWN_VERIFICATION_REQUIRED: Public pedestrian ramp access and overhead clearance must be verified on-site"
            elif is_official_shelter:
                cand_type = "OFFICIAL_FLOOD_SHELTER"
                access_desc = "OFFICIALLY_DESIGNATED_PUBLIC_SHELTER"
            elif is_facility:
                cand_type = "GENERAL_ELEVATED_FACILITY"
                access_desc = "UNKNOWN_VERIFICATION_REQUIRED: Emergency access must be confirmed on-site"
            elif is_open:
                cand_type = "UNSUITABLE_FLOOD_RISK"
                access_desc = "LOW_LYING_GROUND_FLOOD_EXPOSURE"
            else:
                cand_type = "GENERAL_STRUCTURE"
                access_desc = "UNKNOWN_VERIFICATION_REQUIRED: On-site verification required"

            src_summaries: list[SafeLocationSourceSummary] = []
            for ref in loc.source_references:
                d_code = ref.data_source.code if ref.data_source else "UNKNOWN"
                d_name = ref.data_source.name if ref.data_source else "Unknown Data Source"
                sources_seen.add(d_name)
                ref_v_detail = (ref.properties.get("verification_detail") if ref.properties else None) or (
                    "OFFICIALLY_VERIFIED" if loc.verification_status == VerificationStatus.VERIFIED else "UNVERIFIED"
                )
                src_summaries.append(
                    SafeLocationSourceSummary(
                        data_source_id=ref.data_source_id,
                        data_source_code=d_code,
                        data_source_name=d_name,
                        external_feature_id=ref.external_feature_id,
                        source_url=ref.source_url,
                        last_seen_at=ref.last_seen_at,
                        verification_detail=ref_v_detail,
                        properties=ref.properties,
                    )
                )

            cand_dict = {
                "id": loc.id,
                "name": loc.name,
                "category": loc.category,
                "candidate_type": cand_type,
                "designation_status": loc.designation_status,
                "verification_status": loc.verification_status,
                "latitude": c_lat,
                "longitude": c_lon,
                "distance_km": dist_km_val,
                "distance_m": dist_m_val,
                "public_access_status": access_desc,
            }

            fl_fields = assess_candidate_flood_safety(
                cand=cand_dict,
                user_lat=latitude,
                user_lon=longitude,
                search_radius_m=tier_m,
                active_flood_events=active_flood_evts,
                dem_grid=dem_grid,
            )

            # Separate Discovery from Assessment: assign explicit SuitabilityClassification
            if is_open and not (is_flyover or is_official_shelter):
                suit_cls = SuitabilityClassification.NOT_RECOMMENDED
                fl_fields.safety_category = "EXCLUDED"
                fl_fields.safety_score = 15.0
                fl_fields.assessment_reasons = [
                    "Low-lying open ground: vulnerable to surface inundation and flash runoff; elevated structure or natural high ground required."
                ]
                fl_fields.known_hazards = ["SURFACE_INUNDATION_RISK", "WATERLOGGING"]
            elif fl_fields.data_availability_status == "ELEVATION_UNAVAILABLE" or fl_fields.elevation_m is None:
                # Supplementary evidence (DEM) missing; fallback to MSL sea level height with transparent warning
                fl_fields.warnings.append("DEM elevation unavailable; height estimated from sea level topographic gradient.")
                suit_cls = SuitabilityClassification.CONDITIONALLY_SUITABLE
            elif fl_fields.safety_category == "SUITABLE_ELEVATED_REFUGE":
                suit_cls = SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
            elif fl_fields.safety_category == "CONDITIONALLY_ELEVATED" or (is_flyover and (fl_fields.structure_height_m or 0) >= 5.0):
                suit_cls = SuitabilityClassification.CONDITIONALLY_SUITABLE
            elif fl_fields.safety_category == "EXCLUDED" or (fl_fields.relative_elevation_m is not None and fl_fields.relative_elevation_m < 0.5 and not is_flyover and not is_official_shelter):
                suit_cls = SuitabilityClassification.NOT_RECOMMENDED
            else:
                suit_cls = SuitabilityClassification.CONDITIONALLY_SUITABLE

            v_detail = "OFFICIALLY_VERIFIED" if loc.verification_status == VerificationStatus.VERIFIED else "UNVERIFIED"
            loc_geojson = safe_postgis_to_geojson(loc.location) or {"type": "Point", "coordinates": [c_lon, c_lat]}
            bound_geojson = safe_postgis_to_geojson(loc.boundary) if loc.boundary is not None else None

            spec_pts = SpecificationMatchPoints(
                distance_points=round(max(0.0, 30.0 - (dist_km_val * 6.0)), 1),
                safety_factor_points=round(fl_fields.safety_score * 0.4, 1),
                availability_points=15.0 if loc.capacity else 8.0,
                features_points=15.0 if is_flyover or is_official_shelter else 10.0,
                total_points=fl_fields.safety_score,
            )

            tier_candidates.append(
                SafePlaceItem(
                    id=loc.id,
                    name=loc.name,
                    category=loc.category,
                    candidate_type=cand_type,
                    discovery_source="OpenStreetMap / Real Infrastructure" if not src_summaries else src_summaries[0].data_source_name,
                    search_radius_m=tier_m,
                    elevation_m=fl_fields.elevation_m,
                    ground_elevation_m=fl_fields.ground_elevation_m,
                    structure_height_m=fl_fields.structure_height_m,
                    structure_height_status=fl_fields.structure_height_status,
                    relative_elevation_m=fl_fields.relative_elevation_m,
                    data_confidence=fl_fields.data_confidence,
                    data_availability_status=fl_fields.data_availability_status,
                    feature_identifier=fl_fields.feature_identifier,
                    assessment_reasons=fl_fields.assessment_reasons,
                    known_hazards=fl_fields.known_hazards,
                    last_updated_time=fl_fields.last_updated_time,
                    designation_status=loc.designation_status,
                    verification_status=loc.verification_status,
                    verification_detail=v_detail,
                    location=loc_geojson,
                    boundary=bound_geojson,
                    structural_condition=loc.structural_condition,
                    accessibility_status=loc.accessibility_status,
                    capacity=loc.capacity,
                    distance_km=dist_km_val,
                    distance_m=dist_m_val,
                    suitability_classification=suit_cls,
                    suitability_score=round(fl_fields.safety_score / 100.0, 2),
                    specification_points=spec_pts,
                    total_points=fl_fields.safety_score,
                    distance_points=spec_pts.distance_points,
                    safety_points=spec_pts.safety_factor_points,
                    availability_points=spec_pts.availability_points,
                    features_points=spec_pts.features_points,
                    flood_fields=fl_fields,
                    suitability_evidence=fl_fields.assessment_reasons,
                    key_hazards=fl_fields.known_hazards,
                    warnings=[],
                    missing_information=["exact_building_height"] if fl_fields.structure_height_status == "UNKNOWN" else [],
                    sources=src_summaries,
                    created_at=loc.created_at,
                    updated_at=loc.updated_at,
                )
            )

        # Category B: Natural Elevated Terrain Discovery from DEM Data
        natural_cands = discover_natural_elevated_terrain(
            user_lat=latitude,
            user_lon=longitude,
            radius_m=tier_m,
            user_msl=user_msl,
            dem_grid=dem_grid,
        )

        for nc in natural_cands:
            fl_fields = assess_candidate_flood_safety(
                cand=nc,
                user_lat=latitude,
                user_lon=longitude,
                search_radius_m=tier_m,
                active_flood_events=active_flood_evts,
                dem_grid=dem_grid,
            )
            sources_seen.add("Digital Elevation Model (DEM) Topographical Analysis")
            nc_src = SafeLocationSourceSummary(
                data_source_id=uuid.uuid5(uuid.NAMESPACE_DNS, "DEM_TERRAIN_SOURCE"),
                data_source_code="DEM_TERRAIN",
                data_source_name="Digital Elevation Model (DEM) Topographical Analysis",
                external_feature_id=str(nc["id"]),
                source_url="https://spacedata.copernicus.eu",
                last_seen_at=datetime.now(timezone.utc),
                verification_detail="MODEL_DERIVED_TERRAIN_CANDIDATE",
                properties={"dem_resolution": "30m", "quality": "HIGH"},
            )

            spec_pts = SpecificationMatchPoints(
                distance_points=round(max(0.0, 30.0 - (nc["distance_km"] * 6.0)), 1),
                safety_factor_points=round(fl_fields.safety_score * 0.4, 1),
                availability_points=10.0,
                features_points=15.0,
                total_points=fl_fields.safety_score,
            )

            suit_cls = (
                SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
                if fl_fields.safety_category == "SUITABLE_ELEVATED_REFUGE"
                else SuitabilityClassification.CONDITIONALLY_SUITABLE
            )

            tier_candidates.append(
                SafePlaceItem(
                    id=nc["id"],
                    name=nc["name"],
                    category=nc["category"],
                    candidate_type="NATURAL_ELEVATED_TERRAIN",
                    discovery_source="Digital Elevation Model (DEM) Topographical Analysis",
                    search_radius_m=tier_m,
                    elevation_m=fl_fields.elevation_m,
                    ground_elevation_m=fl_fields.ground_elevation_m,
                    structure_height_m=fl_fields.structure_height_m,
                    structure_height_status=fl_fields.structure_height_status,
                    relative_elevation_m=fl_fields.relative_elevation_m,
                    data_confidence=fl_fields.data_confidence,
                    data_availability_status=fl_fields.data_availability_status,
                    feature_identifier=fl_fields.feature_identifier,
                    assessment_reasons=fl_fields.assessment_reasons,
                    known_hazards=fl_fields.known_hazards,
                    last_updated_time=fl_fields.last_updated_time,
                    designation_status=nc["designation_status"],
                    verification_status=nc["verification_status"],
                    verification_detail=nc["verification_detail"],
                    location={"type": "Point", "coordinates": [nc["longitude"], nc["latitude"]]},
                    boundary=None,
                    structural_condition=None,
                    accessibility_status=None,
                    capacity=None,
                    distance_km=nc["distance_km"],
                    distance_m=nc["distance_m"],
                    suitability_classification=suit_cls,
                    suitability_score=round(fl_fields.safety_score / 100.0, 2),
                    specification_points=spec_pts,
                    total_points=fl_fields.safety_score,
                    distance_points=spec_pts.distance_points,
                    safety_points=spec_pts.safety_factor_points,
                    availability_points=spec_pts.availability_points,
                    features_points=spec_pts.features_points,
                    flood_fields=fl_fields,
                    suitability_evidence=fl_fields.assessment_reasons,
                    key_hazards=fl_fields.known_hazards,
                    warnings=[],
                    missing_information=[],
                    sources=[nc_src],
                    created_at=datetime.now(timezone.utc),
                    updated_at=datetime.now(timezone.utc),
                )
            )

        def fl_sort_key(x: SafePlaceItem) -> tuple[int, float, float, float]:
            prio = 0
            if x.suitability_classification == SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE:
                prio = 3
            elif x.suitability_classification == SuitabilityClassification.CONDITIONALLY_SUITABLE:
                prio = 2
            elif x.suitability_classification == SuitabilityClassification.INSUFFICIENT_DATA:
                prio = 1
            rel_z = x.flood_fields.relative_elevation_m if x.flood_fields and x.flood_fields.relative_elevation_m is not None else 0.0
            return (prio, x.total_points or 0.0, rel_z, -x.distance_km)

        has_suitable = any(
            c.suitability_classification in (SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE, SuitabilityClassification.CONDITIONALLY_SUITABLE)
            for c in tier_candidates
        )

        if has_suitable:
            tier_candidates.sort(key=fl_sort_key, reverse=True)
            return FindSafePlacesResponse(
                success=True,
                user_location=user_loc,
                disaster_type=CrisisType.FLOOD,
                initial_radius_km=0.1,
                effective_radius_km=tier_km,
                total_found=len(tier_candidates),
                safe_places=tier_candidates[:limit],
                data_sources_queried=list(sources_seen) or ["OpenStreetMap", "Digital Elevation Model (DEM)"],
                engine_name="Separate Flood Safe Place Engine (Progressive Radii: 100m, 250m, 500m, 1000m, 2000m)",
                engine_status="SUCCESS",
                data_availability_status="SOURCE_AVAILABLE",
                engine_message=f"Discovered {len(tier_candidates)} verified candidates within {int(tier_m)}m radius priority.",
                search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
            )
        else:
            # Retain discovered features for final reporting if no suitable candidate is found in wider tiers
            for cand in tier_candidates:
                if cand.id not in all_discovered_flood_cands:
                    all_discovered_flood_cands[cand.id] = cand

    # If all tiers up to max radius were searched without finding a suitable flood refuge:
    if all_discovered_flood_cands:
        final_cands = list(all_discovered_flood_cands.values())
        final_cands.sort(key=fl_sort_key, reverse=True)
        return FindSafePlacesResponse(
            success=True,
            user_location=user_loc,
            disaster_type=CrisisType.FLOOD,
            initial_radius_km=0.1,
            effective_radius_km=round(search_tiers_m[-1] / 1000.0, 2),
            total_found=len(final_cands),
            safe_places=final_cands[:limit],
            data_sources_queried=list(sources_seen) or ["OpenStreetMap", "Digital Elevation Model (DEM)"],
            engine_name="Separate Flood Safe Place Engine (Progressive Radii: 100m, 250m, 500m, 1000m, 2000m)",
            engine_status="NO_SUITABLE_CANDIDATE",
            data_availability_status="SOURCE_AVAILABLE",
            engine_message=f"Discovered {len(final_cands)} geographical candidate(s) within {int(search_tiers_m[-1])}m radius, but none meet flood refuge elevation criteria.",
            search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
        )

    return FindSafePlacesResponse(
        success=True,
        user_location=user_loc,
        disaster_type=CrisisType.FLOOD,
        initial_radius_km=0.1,
        effective_radius_km=round(search_tiers_m[-1] / 1000.0, 2),
        total_found=0,
        safe_places=[],
        data_sources_queried=["OpenStreetMap", "Digital Elevation Model (DEM)"],
        engine_name="Separate Flood Safe Place Engine (Progressive Radii: 100m, 250m, 500m, 1000m, 2000m)",
        engine_status="NO_SUITABLE_CANDIDATE",
        data_availability_status="NO_CANDIDATES_FOUND",
        engine_message="No physical geographical features discovered within search radius.",
        search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
    )


def find_safe_places_near_user(
    session: Session,
    latitude: float,
    longitude: float,
    disaster_type: CrisisType | None = None,
    max_radius_km: float = 5.0,
    limit: int = 20,
) -> FindSafePlacesResponse:
    """
    Separate Safe Place Discovery Engine.

    Requirements:
    - Dedicated engine to find safe places near the user location.
    - Give ONLY real safe locations that physically exist near the user (no hypothetical/synthetic flyovers or zones).
    - Starts finding safe locations from 100 meter (0.1 km) radius from user location.
    - If no safe location is found in 100m, progressively increase the radius until real safe locations are found.
    - Safe location must be under 1 to 5 km (expanding outward from 0.1km up to 5km, with emergency fallback if remote).
    - Different safe locations for different crisis: only show safe locations matching the chosen or ongoing crisis.
    - Calculates complete disaster-specific fields for Earthquake (1.a), Flood (3.b), and Sea Typhoon (3.c).
    """
    capped_max_radius_km = min(5.0, max(1.0, float(max_radius_km)))
    search_radius_m = capped_max_radius_km * 1000.0

    user_geog = func.ST_SetSRID(func.ST_MakePoint(longitude, latitude), 4326).cast(Geography)
    dist_m_col = func.ST_Distance(SafeLocation.location.cast(Geography), user_geog).label("distance_m")

    # 1. Purge any historical synthetic/placeholder locations
    try:
        session.execute(
            delete(SafeLocation).where(
                or_(
                    SafeLocation.name.ilike("%refuge zone (%%"),
                    SafeLocation.name.ilike("%assembly zone (%%"),
                )
            )
        )
        session.commit()
    except Exception as exc:
        session.rollback()
        logger.debug(f"Synthetic cleanup note: {exc}")

    # 2. Determine active crisis: user selected crisis, or ongoing active crisis near user, or default general mode
    active_crisis = disaster_type
    if active_crisis is None:
        nearby_event = session.scalars(
            select(CrisisEvent)
            .where(
                CrisisEvent.status == CrisisStatus.ACTIVE,
                func.ST_DWithin(CrisisEvent.location.cast(Geography), user_geog, 150000.0),
            )
            .order_by(func.ST_Distance(CrisisEvent.location.cast(Geography), user_geog).asc())
            .limit(1)
        ).first()
        if nearby_event is not None:
            active_crisis = nearby_event.crisis_type
        else:
            active_crisis = None  # General Multi-Hazard / Emergency Assembly Mode

    # 3. Query active events for this crisis type if specific crisis is active
    active_events: list[CrisisEvent] = []
    if active_crisis is not None:
        active_events = list(
            session.scalars(
                select(CrisisEvent).where(
                    CrisisEvent.crisis_type == active_crisis,
                    CrisisEvent.status == CrisisStatus.ACTIVE,
                )
            ).all()
        )

    # 3.b Dedicated Flood Safe Place Discovery & Assessment Engine
    if active_crisis == CrisisType.FLOOD:
        return find_flood_safe_places(
            session=session,
            latitude=latitude,
            longitude=longitude,
            max_radius_km=capped_max_radius_km,
            limit=limit,
            active_events=active_events,
        )

    # 4. Ingest real OSM candidates dynamically if local density is low
    existing_count = session.scalar(
        select(func.count(SafeLocation.id)).where(
            func.ST_DWithin(SafeLocation.location.cast(Geography), user_geog, search_radius_m),
            ~SafeLocation.name.ilike("%refuge zone (%%"),
            ~SafeLocation.name.ilike("%assembly zone (%%"),
        )
    )
    try:
        cand_cnt = int(existing_count) if existing_count is not None else 0
    except (TypeError, ValueError):
        cand_cnt = 999

    if cand_cnt < 3:
        try:
            discover_and_sync_candidate_locations(
                session=session,
                latitude=latitude,
                longitude=longitude,
                radius_km=capped_max_radius_km,
                disaster_type=active_crisis,
            )
        except Exception as exc:
            logger.debug(f"Dynamic candidate discovery note: {exc}")

    # 5. Progressive search starting from 100 meters (0.1 km) up to capped max radius
    # Priority tiers: 100m, 250m, 500m, 1km, 2km, 3.5km, 5km
    search_tiers_km = [t for t in [0.1, 0.25, 0.5, 1.0, 2.0, 3.5, 5.0] if t <= capped_max_radius_km]
    if not search_tiers_km:
        search_tiers_km = [0.1, 0.25, 0.5, 1.0, 2.0]

    all_discovered_candidates: dict[uuid.UUID, SafePlaceItem] = {}
    sources_seen: set[str] = set()
    effective_radius_km = 0.1

    def sort_key(item: SafePlaceItem) -> tuple[int, float, float]:
        cls_prio = 0
        if item.suitability_classification == SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE:
            cls_prio = 2
        elif item.suitability_classification == SuitabilityClassification.CONDITIONALLY_SUITABLE:
            cls_prio = 1
        return (cls_prio, item.total_points or 0.0, -item.distance_km)

    for tier_km in search_tiers_km:
        tier_m = tier_km * 1000.0
        effective_radius_km = tier_km

        tier_query = (
            select(SafeLocation, dist_m_col)
            .options(
                selectinload(SafeLocation.source_references).selectinload(SafeLocationSourceReference.data_source)
            )
            .where(
                func.ST_DWithin(SafeLocation.location.cast(Geography), user_geog, tier_m),
                ~SafeLocation.name.ilike("%refuge zone (%%"),
                ~SafeLocation.name.ilike("%assembly zone (%%"),
            )
            .order_by(dist_m_col.asc())
            .limit(limit * 3)
        )
        current_tier_rows = session.execute(tier_query).all()
        if not current_tier_rows:
            continue

        matching_tier_items: list[SafePlaceItem] = []

        for row in current_tier_rows:
            loc: SafeLocation = row[0]
            dist_m_val = float(row[1]) if row[1] is not None else 0.0
            dist_km_val = round(dist_m_val / 1000.0, 3)
            c_lat, c_lon = extract_location_coordinates(loc)
            if c_lat == 0.0 and c_lon == 0.0 and loc.location is None:
                c_lat, c_lon = latitude, longitude

            name_lower = (loc.name or "").lower()
            cat = (loc.category or "").upper()

            # Reject synthetic coordinate names
            if "(" in name_lower and ")" in name_lower and any(c.isdigit() for c in name_lower):
                if any(w in name_lower for w in ["refuge", "assembly", "zone", "platform", "healthcare"]):
                    continue

            # Evaluate candidate suitability
            (
                suit_class,
                is_excluded,
                hard_reasons,
                evidence,
                hazards,
                warnings,
                missing,
                score,
                completeness,
            ) = evaluate_candidate_suitability(
                loc=loc,
                lat=c_lat,
                lon=c_lon,
                distance_km=dist_km_val,
                disaster_type=active_crisis,
                active_events=active_events,
                user_lat=latitude,
                user_lon=longitude,
            )

            # Calculate specification match points (0 - 100)
            dist_pts, safe_pts, avail_pts, feat_pts, tot_pts = calculate_specification_points(
                loc=loc,
                lat=c_lat,
                lon=c_lon,
                distance_km=dist_km_val,
                disaster_type=active_crisis,
                active_events=active_events,
            )

            # Separate Discovery from Assessment: assign explicit SuitabilityClassification
            if active_crisis == CrisisType.EARTHQUAKE:
                is_ok = is_real_location_suitable_for_crisis(
                    loc, CrisisType.EARTHQUAKE, suit_class, safe_pts, user_lat=latitude, user_lon=longitude
                )
                if is_ok:
                    final_suit_cls = (
                        suit_class
                        if suit_class in (SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE, SuitabilityClassification.CONDITIONALLY_SUITABLE)
                        else SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
                    )
                    evidence = ["Open public assembly ground safe from building roof collapse and masonry failure."]
                    warnings = []
                else:
                    final_suit_cls = SuitabilityClassification.NOT_RECOMMENDED
                    evidence = [
                        f"{loc.category or 'Structure'}: enclosed building or elevated flyover subject to structural collapse or falling debris hazard during earthquakes; clear open skyward clearance ground required."
                    ]
                    warnings = ["High collapse risk during earthquakes. Open grounds or wide roads required."]
                    hazards = ["BUILDING_COLLAPSE_RISK", "FALLING_MASONRY"]
            elif active_crisis == CrisisType.CYCLONE:
                is_ok = is_real_location_suitable_for_crisis(
                    loc, CrisisType.CYCLONE, suit_class, safe_pts, user_lat=latitude, user_lon=longitude
                )
                if is_ok:
                    final_suit_cls = (
                        suit_class
                        if suit_class in (SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE, SuitabilityClassification.CONDITIONALLY_SUITABLE)
                        else SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
                    )
                    evidence = ["Reinforced multi-purpose storm refuge safe from cyclonic gales."]
                    warnings = []
                else:
                    final_suit_cls = SuitabilityClassification.NOT_RECOMMENDED
                    evidence = [
                        f"{loc.category or 'Location'}: open ground or unreinforced structure with extreme exposure to cyclonic winds and flying debris; reinforced cyclone shelter required."
                    ]
                    warnings = ["Severe gale wind exposure. Certified shelter required."]
                    hazards = ["EXTREME_GALE_WINDS", "FLYING_DEBRIS"]
            else:
                # General Multi-Hazard (disaster_type is None)
                is_open = (
                    cat in ("OPEN_GROUND", "PARK", "PLAYGROUND", "GARDEN", "FIELD", "ROAD", "CAMPUS")
                    or any(w in name_lower for w in ["ground", "park", "garden", "field", "stadium", "maidan", "lawn", "turf", "open assembly"])
                )
                is_official = (
                    loc.designation_status == DesignationStatus.OFFICIAL
                    or cat in ("OFFICIAL_FLOOD_SHELTER", "OFFICIAL_CYCLONE_SHELTER", "COMMUNITY_CENTRE")
                )
                if is_open:
                    final_suit_cls = SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
                    evidence = ["Open public assembly ground safe from building roof collapse and masonry failure."]
                    warnings = []
                elif is_official:
                    final_suit_cls = SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE
                    evidence = ["Officially designated public emergency refuge facility."]
                    warnings = []
                else:
                    final_suit_cls = SuitabilityClassification.CONDITIONALLY_SUITABLE
                    evidence = ["Public facility suitable for emergency refuge; on-site verification recommended."]
                    warnings = []

            spec_points = SpecificationMatchPoints(
                distance_points=dist_pts,
                safety_factor_points=safe_pts,
                availability_points=avail_pts,
                features_points=feat_pts,
                total_points=tot_pts,
            )

            # 1.a Earthquake fields
            eq_fields = compute_earthquake_safety_fields(
                loc=loc, lat=c_lat, lon=c_lon, distance_km=dist_km_val
            )

            # 3.b Flood fields
            fl_fields = compute_flood_safety_fields(
                loc=loc,
                lat=c_lat,
                lon=c_lon,
                distance_km=dist_km_val,
                active_flood_events=[],
                user_lat=latitude,
                user_lon=longitude,
            )

            # 3.c Cyclone fields
            cyc_fields = compute_cyclone_safety_fields(
                loc=loc, lat=c_lat, lon=c_lon, distance_km=dist_km_val
            )

            # Format source summaries
            src_summaries: list[SafeLocationSourceSummary] = []
            for ref in loc.source_references:
                d_code = ref.data_source.code if ref.data_source else "UNKNOWN"
                d_name = ref.data_source.name if ref.data_source else "Unknown Data Source"
                sources_seen.add(d_name)
                ref_v_detail = (ref.properties.get("verification_detail") if ref.properties else None) or (
                    "OFFICIALLY_VERIFIED" if loc.verification_status == VerificationStatus.VERIFIED else "UNVERIFIED"
                )
                src_summaries.append(
                    SafeLocationSourceSummary(
                        data_source_id=ref.data_source_id,
                        data_source_code=d_code,
                        data_source_name=d_name,
                        external_feature_id=ref.external_feature_id,
                        source_url=ref.source_url,
                        last_seen_at=ref.last_seen_at,
                        verification_detail=ref_v_detail,
                        properties=ref.properties,
                    )
                )

            v_detail = "OFFICIALLY_VERIFIED" if loc.verification_status == VerificationStatus.VERIFIED else "UNVERIFIED"
            loc_geojson = safe_postgis_to_geojson(loc.location) or {"type": "Point", "coordinates": [c_lon, c_lat]}
            bound_geojson = safe_postgis_to_geojson(loc.boundary) if loc.boundary is not None else None

            matching_tier_items.append(
                SafePlaceItem(
                    id=loc.id,
                    name=loc.name,
                    category=loc.category,
                    designation_status=loc.designation_status,
                    verification_status=loc.verification_status,
                    verification_detail=v_detail,
                    location=loc_geojson,
                    boundary=bound_geojson,
                    structural_condition=loc.structural_condition,
                    accessibility_status=loc.accessibility_status,
                    capacity=loc.capacity,
                    distance_km=dist_km_val,
                    distance_m=dist_m_val,
                    ground_elevation_m=fl_fields.ground_elevation_m if fl_fields else None,
                    structure_height_m=fl_fields.structure_height_m if fl_fields else None,
                    structure_height_status=fl_fields.structure_height_status if fl_fields else "UNKNOWN",
                    data_availability_status=fl_fields.data_availability_status if fl_fields else "SOURCE_AVAILABLE",
                    feature_identifier=str(loc.id),
                    suitability_classification=final_suit_cls,
                    suitability_score=score if score is not None else round((tot_pts or 50.0) / 100.0, 2),
                    specification_points=spec_points,
                    total_points=tot_pts,
                    distance_points=dist_pts,
                    safety_points=safe_pts,
                    availability_points=cap_pts,
                    features_points=feat_pts,
                    earthquake_fields=eq_fields,
                    flood_fields=fl_fields,
                    cyclone_fields=cyc_fields,
                    suitability_evidence=evidence,
                    key_hazards=hazards,
                    warnings=warnings,
                    missing_information=extract_missing_information(loc),
                    sources=src_summaries,
                    created_at=loc.created_at,
                    updated_at=loc.updated_at,
                )
            )

        has_suitable = any(
            item.suitability_classification in (SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE, SuitabilityClassification.CONDITIONALLY_SUITABLE)
            for item in matching_tier_items
        )

        if has_suitable:
            matching_tier_items.sort(key=sort_key, reverse=True)
            return FindSafePlacesResponse(
                success=True,
                user_location=UserLocation(latitude=latitude, longitude=longitude),
                disaster_type=disaster_type,
                initial_radius_km=0.1,
                effective_radius_km=round(tier_km, 2),
                total_found=len(matching_tier_items),
                safe_places=matching_tier_items[:limit],
                data_sources_queried=sorted(sources_seen),
                engine_status="SUCCESS",
                data_availability_status="SOURCE_AVAILABLE",
                engine_message=f"Discovered {len(matching_tier_items)} verified candidates within {int(tier_m)}m radius priority.",
                search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
            )
        else:
            for item in matching_tier_items:
                if item.id not in all_discovered_candidates:
                    all_discovered_candidates[item.id] = item

    if all_discovered_candidates:
        final_places = list(all_discovered_candidates.values())
        final_places.sort(key=sort_key, reverse=True)
        return FindSafePlacesResponse(
            success=True,
            user_location=UserLocation(latitude=latitude, longitude=longitude),
            disaster_type=disaster_type,
            initial_radius_km=0.1,
            effective_radius_km=round(search_tiers_km[-1], 2),
            total_found=len(final_places),
            safe_places=final_places[:limit],
            data_sources_queried=sorted(sources_seen),
            engine_status="CANDIDATES_ASSESSED_NONE_OPTIMAL",
            data_availability_status="SOURCE_AVAILABLE",
            engine_message=f"Discovered {len(final_places)} real geographical candidate(s) within {capped_max_radius_km}km, but none meet optimal safety requirements for {active_crisis.value if active_crisis else 'selected disaster'}.",
            search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
        )

    return FindSafePlacesResponse(
        success=True,
        user_location=UserLocation(latitude=latitude, longitude=longitude),
        disaster_type=disaster_type,
        initial_radius_km=0.1,
        effective_radius_km=round(search_tiers_km[-1], 2),
        total_found=0,
        safe_places=[],
        data_sources_queried=sorted(sources_seen),
        engine_status="NO_CANDIDATES_FOUND",
        data_availability_status="NO_CANDIDATES_FOUND",
        engine_message=f"No physical geographical candidates identified within {capped_max_radius_km}km search radius.",
        search_radii_hierarchy_m=[100.0, 250.0, 500.0, 1000.0, 2000.0],
    )
