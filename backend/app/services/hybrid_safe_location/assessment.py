"""
Stage 5: Disaster-Specific Geographical Assessment.

Strictly separates Earthquake assessment from Flood assessment:
- Earthquake: open-space area, distance from nearby buildings, surrounding building
  density, road access, obstacle/building overlap. Wide roads assessed from actual
  width/lanes/surroundings; bridges/flyovers/tunnels/motorways never recommended.
- Flood: Copernicus DEM elevation, relative elevation, terrain slope/flow accumulation,
  nearby water bodies. Missing DEM elevation -> UNKNOWN / INSUFFICIENT_DATA.
"""

from __future__ import annotations

from typing import Any, Optional

from app.schemas.hybrid_safe_location import AssessmentStatus

MIN_EARTHQUAKE_AREA_M2 = 500.0
CONDITIONAL_EARTHQUAKE_AREA_M2 = 250.0


def assess_earthquake_candidate(
    area_m2: Optional[float],
    osm_cv: dict[str, Any],
    origin: str,
    confidence: Optional[float],
) -> tuple[AssessmentStatus, list[str], list[str], float]:
    """
    Evaluates open-space suitability for EARTHQUAKE only.
    Does not silently drop candidates when area or OSM exposure is missing.
    """
    reasons: list[str] = []
    concerns: list[str] = []

    overlap = osm_cv.get("building_overlap_ratio")
    nearest_bldg = osm_cv.get("nearest_building_m")
    bldg_density = osm_cv.get("nearby_buildings_50m")
    nearest_road = osm_cv.get("nearest_road_m")
    osm_ok = osm_cv.get("osm_status") == "AVAILABLE"

    if overlap is not None and overlap > 0.20:
        concerns.append(f"Overlaps mapped building footprints ({overlap * 100:.0f}% overlap).")
        return "NOT_RECOMMENDED", reasons, concerns, 5.0

    if area_m2 is None:
        concerns.append("Polygon boundary/area unavailable (node-only OSM feature); clearance unverified.")
        if nearest_bldg is not None:
            reasons.append(f"Nearest mapped building is {nearest_bldg:.1f} m away.")
        return "INSUFFICIENT_DATA", reasons, concerns, 25.0

    if area_m2 < CONDITIONAL_EARTHQUAKE_AREA_M2:
        concerns.append(f"Open area ({area_m2:.0f} m²) is below minimum 250 m² clearance.")
        return "NOT_RECOMMENDED", reasons, concerns, 10.0

    if area_m2 >= MIN_EARTHQUAKE_AREA_M2:
        reasons.append(f"Open area of {area_m2:.0f} m² meets ≥500 m² threshold for earthquake assembly.")
    else:
        concerns.append(f"Moderate open area ({area_m2:.0f} m²); limited capacity (<500 m²).")

    if nearest_bldg is not None:
        if nearest_bldg >= 15.0:
            reasons.append(f"Nearest mapped building is {nearest_bldg:.1f} m away (lower collapse-debris exposure).")
        else:
            concerns.append(f"Adjacent building only {nearest_bldg:.1f} m from boundary (<15 m).")

    if bldg_density is not None:
        reasons.append(f"{bldg_density} mapped buildings within 50 m buffer.")

    if nearest_road is not None and nearest_road <= 60.0:
        reasons.append(f"Accessible via nearby road ({nearest_road:.1f} m away; width: {osm_cv.get('nearest_road_width_m', 'UNKNOWN')}).")

    if not osm_ok:
        concerns.append("OSM cross-validation unavailable; building proximity unverified.")

    score = min(
        100.0,
        40.0
        + min(area_m2 / 50.0, 35.0)
        + (15.0 if (nearest_bldg or 0) >= 15.0 else 0.0)
        + ((confidence or 0.7) * 10.0),
    )

    if area_m2 >= MIN_EARTHQUAKE_AREA_M2 and (nearest_bldg is None or nearest_bldg >= 15.0) and (osm_ok or origin == "SATELLITE_AND_OSM_MATCHED"):
        return "SUITABLE_WITH_EVIDENCE", reasons, concerns, round(score, 1)

    return "CONDITIONALLY_SUITABLE", reasons, concerns, round(score * 0.88, 1)


def assess_road_candidate(
    road: dict[str, Any],
    osm_cv: dict[str, Any],
) -> tuple[AssessmentStatus, list[str], list[str], float]:
    """
    Assesses an OSM road segment for earthquake evacuation clearance.
    Never invents width; marks missing width as UNKNOWN / INSUFFICIENT_DATA.
    Never recommends bridges, flyovers, tunnels, or motorways.
    """
    reasons: list[str] = []
    concerns: list[str] = []

    hw = road["highway"]
    width_m = road.get("width_m")
    lanes = road.get("lanes")
    surface = road.get("surface")
    access = str(road.get("access") or "").lower()
    nearest_bldg = osm_cv.get("nearest_building_m")

    if road.get("is_bridge_or_highway"):
        concerns.append(f"Excluded infrastructure ({hw} / bridge / tunnel): not recommended for earthquake refuge.")
        return "NOT_RECOMMENDED", reasons, concerns, 5.0

    if access in ("private", "no"):
        concerns.append(f"Restricted access ({access}).")
        return "NOT_RECOMMENDED", reasons, concerns, 5.0

    if surface:
        reasons.append(f"Road surface: {surface}.")
    if lanes is not None:
        reasons.append(f"Mapped lanes: {lanes}.")

    if width_m is not None:
        reasons.append(f"Mapped road width: {width_m:.1f} m.")
        if width_m >= 10.0 and (nearest_bldg is None or nearest_bldg >= 8.0):
            if nearest_bldg is not None:
                reasons.append(f"Nearest mapped building is {nearest_bldg:.1f} m from roadway.")
            return "CONDITIONALLY_SUITABLE", reasons, concerns, 62.0
        concerns.append(f"Road width ({width_m:.1f} m) or adjacent building clearance is narrow.")
        return "NOT_RECOMMENDED", reasons, concerns, 18.0

    if lanes is not None and lanes >= 3:
        concerns.append("Explicit width tag absent (UNKNOWN); multi-lane carriageway inferred from lanes >= 3.")
        if nearest_bldg is not None:
            reasons.append(f"Nearest mapped building is {nearest_bldg:.1f} m from roadway.")
        return "CONDITIONALLY_SUITABLE", reasons, concerns, 52.0

    concerns.append("Road width unavailable in OSM (UNKNOWN); cannot verify safe width clearance.")
    return "INSUFFICIENT_DATA", reasons, concerns, 20.0


def assess_flood_candidate(
    cand_elev_m: Optional[float],
    user_elev_m: Optional[float],
    osm_cv: dict[str, Any],
    terrain_metrics: Optional[dict[str, Any]] = None,
) -> tuple[AssessmentStatus, list[str], list[str], str, float]:
    """
    Evaluates terrain & water exposure for FLOOD only.
    Never uses hardcoded city elevation. Missing DEM -> UNKNOWN / INSUFFICIENT_DATA.
    Higher elevation alone never guarantees flood safety.
    """
    reasons: list[str] = []
    concerns: list[str] = []

    if cand_elev_m is None:
        concerns.append("DEM ground elevation is unavailable (UNKNOWN); cannot assess flood safety.")
        return "INSUFFICIENT_DATA", reasons, concerns, "UNKNOWN", 0.0

    elev_status = "VERIFIED_DEM"
    reasons.append(f"DEM ground elevation: {cand_elev_m:.1f} m MSL.")

    rel_elev = None if user_elev_m is None else round(cand_elev_m - user_elev_m, 1)
    if rel_elev is not None:
        if rel_elev >= 2.0:
            reasons.append(f"Candidate is +{rel_elev:.1f} m higher than user location.")
        elif rel_elev >= 0.0:
            concerns.append(f"Minimal elevation gain over user (+{rel_elev:.1f} m); higher elevation alone does not ensure flood safety.")
        else:
            concerns.append(f"Lower than user location ({rel_elev:.1f} m); elevated flood exposure.")
            return "NOT_RECOMMENDED", reasons, concerns, elev_status, 10.0

    nearest_water = osm_cv.get("nearest_water_m")
    if nearest_water is not None:
        if nearest_water < 50.0:
            concerns.append(f"Close to mapped water body/channel ({nearest_water:.1f} m away).")
            return "NOT_RECOMMENDED", reasons, concerns, elev_status, 12.0
        reasons.append(f"Nearest mapped water body is {nearest_water:.1f} m away.")

    slope_deg = (terrain_metrics or {}).get("slope_deg")
    flow_accum = (terrain_metrics or {}).get("flow_accumulation")
    if slope_deg is not None and slope_deg > 20.0:
        concerns.append(f"Steep slope ({slope_deg:.1f}°); potential flash-flood runoff or landslide exposure.")

    if flow_accum is not None and flow_accum > 15.0:
        concerns.append("High DEM flow accumulation index (drainage corridor; not hydraulic simulation).")

    score = 50.0 + (min(rel_elev * 5.0, 25.0) if rel_elev else 0.0) + (15.0 if (nearest_water or 200.0) >= 150.0 else 0.0)
    concerns.append("Terrain indices do not replace official flood-inundation forecasts or shelter designation.")
    return "CONDITIONALLY_SUITABLE", reasons, concerns, elev_status, round(min(95.0, score), 1)
