"""
Stage 6: Hybrid Safe-Location Discovery Pipeline.

Priorities:
1. OpenStreetMap geographical feature discovery (primary reliable source)
2. Satellite imagery + zero-shot segmentation (supplementary discovery source)
3. Shapely metric geometry + Disaster-specific assessment (Earthquake vs Flood)

Progressive search radii: 100 m -> 250 m -> 500 m -> 1000 m -> 2000 m
using a single bounded Overpass query and local boundary-distance filtering.
"""

from __future__ import annotations

import asyncio
from typing import Any, Callable, Optional

from shapely.geometry import Point

from app.schemas.hybrid_safe_location import (
    DiscoveryDiagnostics,
    HybridDiscoveryResponse,
    HybridSafeCandidate,
    HybridSafeLocationRequest,
)
from app.services.dem_elevation import fetch_dem_elevations_from_api
from app.services.hybrid_safe_location.assessment import (
    assess_earthquake_candidate,
    assess_flood_candidate,
    assess_road_candidate,
)
from app.services.hybrid_safe_location.geometry import (
    analyze_polygon_metrics,
    deduplicate_metric_candidates,
    mask_to_wgs84_polygon,
)
from app.services.hybrid_safe_location.imagery import (
    AcquiredImagery,
    acquire_centered_satellite_imagery,
)
from app.services.hybrid_safe_location.osm_cross_validation import (
    build_osm_spatial_index,
    cross_validate_geometry_with_osm,
    fetch_osm_elements_once,
)
from app.services.hybrid_safe_location.segmentation import (
    ALL_ZERO_SHOT_PROMPTS,
    SegmentationDetection,
    get_zero_shot_segmenter,
)

PROGRESSIVE_RADII_M = [100, 250, 500, 1000, 2000]


async def discover_hybrid_safe_locations(
    req: HybridSafeLocationRequest,
    tile_fetcher: Optional[Callable[[int, int, int], bytes]] = None,
    custom_segmenter: Optional[Callable[[Any, list[str]], list[SegmentationDetection]]] = None,
    osm_elements_override: Optional[tuple[list[dict[str, Any]], str]] = None,
    dem_fetcher_override: Optional[Callable[[list[tuple[float, float]]], dict[tuple[float, float], float]]] = None,
) -> HybridDiscoveryResponse:
    warnings: list[str] = []
    max_r = min(req.max_radius_m, 2000)

    async def _get_imagery() -> AcquiredImagery:
        return await asyncio.to_thread(
            acquire_centered_satellite_imagery,
            req.latitude,
            req.longitude,
            18,
            512,
            tile_fetcher,
        )

    async def _get_osm() -> tuple[list[dict[str, Any]], str]:
        if osm_elements_override is not None:
            return osm_elements_override
        return await asyncio.to_thread(fetch_osm_elements_once, req.latitude, req.longitude, max_r)

    acquired_img, (osm_elements, osm_status) = await asyncio.gather(_get_imagery(), _get_osm())

    if acquired_img.metadata.status != "AVAILABLE":
        warnings.append(
            f"Satellite imagery unavailable ({acquired_img.metadata.unavailable_reason}); continuing with OSM discovery."
        )
    if osm_status != "AVAILABLE":
        warnings.append("OpenStreetMap Overpass data unavailable.")

    segmenter = get_zero_shot_segmenter()
    if acquired_img.rgb is not None:
        detections, seg_status = await asyncio.to_thread(
            segmenter.segment,
            acquired_img.rgb,
            ALL_ZERO_SHOT_PROMPTS,
            15.0,
            custom_segmenter,
        )
    else:
        detections = []
        seg_status = segmenter.get_status()

    if seg_status.status != "AVAILABLE":
        warnings.append(f"Zero-shot segmentation {seg_status.status}; continuing with OSM discovery.")

    osm_index = build_osm_spatial_index(osm_elements, osm_status, req.latitude, req.longitude)

    open_categories = {"open_field", "playground", "sports_ground", "park", "open_vacant_land"}
    cv_candidates: list[dict[str, Any]] = []
    osm_candidates: list[dict[str, Any]] = []

    for idx, det in enumerate(detections):
        if det.category not in open_categories:
            continue
        wgs84_poly = mask_to_wgs84_polygon(det.mask, acquired_img.metadata)
        if wgs84_poly is None:
            continue
        metrics = analyze_polygon_metrics(wgs84_poly, req.latitude, req.longitude, max_r)
        if metrics is None:
            continue

        osm_cv = cross_validate_geometry_with_osm(metrics["clipped_metric"], osm_index)
        matched = osm_cv.get("matched_osm_park")
        origin = "SATELLITE_AND_OSM_MATCHED" if matched else "SATELLITE_DETECTED"
        name = matched["name"] if matched and matched.get("name") else None
        sources = [acquired_img.metadata.provider, seg_status.model_name] + (["OpenStreetMap"] if matched else [])

        cv_candidates.append({
            "id": f"sat/{idx}/{metrics['centroid']['latitude']},{metrics['centroid']['longitude']}",
            "name": name,
            "category": det.category,
            "latitude": metrics["centroid"]["latitude"],
            "longitude": metrics["centroid"]["longitude"],
            "nearest_point": metrics["nearest_point"],
            "polygon_geojson": metrics["polygon_geojson"],
            "distance_m": metrics["distance_m"],
            "area_m2": metrics["area_m2"],
            "origin_classification": origin,
            "confidence": det.confidence,
            "evidence_sources": sources,
            "osm_cross_validation": osm_cv,
            "_metric_poly": metrics["clipped_metric"],
            "_kind": "open_area",
        })

    for oa in osm_index.open_areas:
        if oa.get("wgs84_poly") is not None:
            metrics = analyze_polygon_metrics(oa["wgs84_poly"], req.latitude, req.longitude, max_r)
            if metrics is None:
                continue
            osm_cv = cross_validate_geometry_with_osm(metrics["clipped_metric"], osm_index)
            osm_candidates.append({
                "id": oa["id"],
                "name": oa["name"],
                "category": oa["category"],
                "latitude": metrics["centroid"]["latitude"],
                "longitude": metrics["centroid"]["longitude"],
                "nearest_point": metrics["nearest_point"],
                "polygon_geojson": metrics["polygon_geojson"],
                "distance_m": metrics["distance_m"],
                "area_m2": metrics["area_m2"],
                "origin_classification": "OSM_VERIFIED",
                "confidence": None,
                "evidence_sources": ["OpenStreetMap"],
                "osm_cross_validation": osm_cv,
                "_metric_poly": metrics["clipped_metric"],
                "_kind": "open_area",
            })
        elif oa.get("pt_m") is not None:
            dist_m = round(float(Point(0.0, 0.0).distance(oa["pt_m"])), 1)
            if dist_m > max_r:
                continue
            osm_cv = cross_validate_geometry_with_osm(oa["pt_m"], osm_index)
            osm_candidates.append({
                "id": oa["id"],
                "name": oa["name"],
                "category": oa["category"],
                "latitude": round(oa["lat"], 6),
                "longitude": round(oa["lon"], 6),
                "nearest_point": {"latitude": round(oa["lat"], 6), "longitude": round(oa["lon"], 6)},
                "polygon_geojson": None,
                "distance_m": dist_m,
                "area_m2": None,
                "origin_classification": "OSM_VERIFIED",
                "confidence": None,
                "evidence_sources": ["OpenStreetMap"],
                "osm_cross_validation": osm_cv,
                "_metric_poly": None,
                "_kind": "open_area",
            })

    if req.disaster_type == "EARTHQUAKE":
        seen_roads: set[str] = set()
        sorted_roads = sorted(osm_index.roads, key=lambda r: r["distance_m"])
        for r in sorted_roads:
            if r["distance_m"] > max_r:
                continue
            r_label = r["name"] or r["highway"]
            r_key = f"{r_label}:{round(r['nearest_point']['latitude'], 3)},{round(r['nearest_point']['longitude'], 3)}"
            if r_key in seen_roads:
                continue
            seen_roads.add(r_key)
            osm_cv = cross_validate_geometry_with_osm(r["line_m"], osm_index)
            osm_candidates.append({
                "id": r["id"],
                "name": r["name"],
                "category": "road",
                "latitude": r["nearest_point"]["latitude"],
                "longitude": r["nearest_point"]["longitude"],
                "nearest_point": r["nearest_point"],
                "polygon_geojson": None,
                "distance_m": r["distance_m"],
                "area_m2": None,
                "origin_classification": "OSM_VERIFIED",
                "confidence": None,
                "evidence_sources": ["OpenStreetMap"],
                "osm_cross_validation": osm_cv,
                "_metric_poly": None,
                "_kind": "road",
                "_road": r,
            })

    merged_raw = cv_candidates + osm_candidates
    merged_raw.sort(
        key=lambda c: (
            0 if c["origin_classification"] == "SATELLITE_AND_OSM_MATCHED"
            else 1 if c["_kind"] == "open_area" and c["origin_classification"] == "SATELLITE_DETECTED"
            else 2 if c["_kind"] == "open_area"
            else 3,
            c["distance_m"],
        )
    )
    deduped = deduplicate_metric_candidates(merged_raw)
    duplicates_removed = len(merged_raw) - len(deduped)

    radii = [r for r in PROGRESSIVE_RADII_M if r <= max_r] or [max_r]
    scanned_radii: list[int] = []
    effective_r = radii[0]
    in_radius: list[dict[str, Any]] = []

    for r in radii:
        scanned_radii.append(r)
        effective_r = r
        in_radius = [c for c in deduped if c["distance_m"] <= r]
        open_count = sum(1 for c in in_radius if c["_kind"] == "open_area")
        if open_count >= req.min_candidates:
            break

    dem_status = "NOT_APPLICABLE"
    elev_map: dict[tuple[float, float], float] = {}
    user_elev: Optional[float] = None

    if req.disaster_type == "FLOOD":
        pts = [(req.latitude, req.longitude)] + [(c["latitude"], c["longitude"]) for c in in_radius]
        if dem_fetcher_override is not None:
            elev_map = dem_fetcher_override(pts)
        else:
            elev_map = await asyncio.to_thread(fetch_dem_elevations_from_api, pts, 3.0)
        user_elev = elev_map.get((req.latitude, req.longitude))
        dem_status = "AVAILABLE" if elev_map else "DEM_UNAVAILABLE"
        if dem_status == "DEM_UNAVAILABLE":
            warnings.append("DEM elevation data unavailable; flood terrain assessment marked UNKNOWN.")

    assessed: list[tuple[float, HybridSafeCandidate]] = []
    rejected_count = osm_index.rejected_count
    rejection_reasons = list(osm_index.rejection_reasons)

    for c in in_radius:
        osm_cv = c["osm_cross_validation"]
        if c["_kind"] == "road":
            r_obj = c["_road"]
            status, reasons, concerns, score = assess_road_candidate(r_obj, osm_cv)
            c_elev = None
            elev_stat = "NOT_APPLICABLE"
            road_w: str | float | None = r_obj["width_m"] if r_obj["width_m"] is not None else "UNKNOWN"
            road_attrs = {
                "highway": r_obj["highway"],
                "width_m": road_w,
                "lanes": r_obj["lanes"],
                "surface": r_obj["surface"],
                "access": r_obj["access"],
            }
        elif req.disaster_type == "EARTHQUAKE":
            status, reasons, concerns, score = assess_earthquake_candidate(
                c["area_m2"], osm_cv, c["origin_classification"], c["confidence"]
            )
            c_elev = None
            elev_stat = "NOT_APPLICABLE"
            road_w = osm_cv.get("nearest_road_width_m", "UNKNOWN")
            road_attrs = None
        else:
            c_elev = elev_map.get((c["latitude"], c["longitude"]))
            status, reasons, concerns, elev_stat, score = assess_flood_candidate(
                c_elev, user_elev, osm_cv
            )
            road_w = osm_cv.get("nearest_road_width_m", "UNKNOWN")
            road_attrs = None

        if status == "NOT_RECOMMENDED":
            rejected_count += 1
            if concerns and concerns[0] not in rejection_reasons:
                rejection_reasons.append(concerns[0])

        cand_obj = HybridSafeCandidate(
            id=c["id"],
            name=c["name"],
            category=c["category"],
            latitude=c["latitude"],
            longitude=c["longitude"],
            nearest_point=c["nearest_point"],
            polygon_geojson=c["polygon_geojson"],
            distance_m=c["distance_m"],
            area_m2=c["area_m2"],
            origin_classification=c["origin_classification"],
            assessment_status=status,
            applicable_disaster=req.disaster_type,
            confidence=c["confidence"],
            evidence_sources=c["evidence_sources"] + (["Copernicus DEM"] if c_elev is not None else []),
            reasons=reasons,
            rejection_or_conditional_reasons=concerns,
            osm_cross_validation=osm_cv,
            elevation_m=c_elev,
            elevation_status=elev_stat,
            road_width_m=road_w,
            road_attributes=road_attrs,
        )
        assessed.append((score, cand_obj))

    status_priority = {
        "SUITABLE_WITH_EVIDENCE": 0,
        "CONDITIONALLY_SUITABLE": 1,
        "INSUFFICIENT_DATA": 2,
        "UNKNOWN": 3,
        "NOT_RECOMMENDED": 4,
    }
    assessed.sort(
        key=lambda x: (
            status_priority[x[1].assessment_status],
            0 if x[1].category != "road" else 1,
            -x[0],
            x[1].distance_m,
        )
    )
    final_candidates = [x[1] for x in assessed[: req.max_results]]

    if not final_candidates:
        overall_status = "INSUFFICIENT_DATA" if (osm_status != "AVAILABLE" and acquired_img.metadata.status != "AVAILABLE") else "NO_CANDIDATES_FOUND"
    else:
        usable = [
            c for c in final_candidates
            if c.assessment_status in ("SUITABLE_WITH_EVIDENCE", "CONDITIONALLY_SUITABLE", "INSUFFICIENT_DATA")
        ]
        overall_status = "OK" if usable else "NO_SUITABLE_LOCATION_FOUND"

    diagnostics = DiscoveryDiagnostics(
        osm_features_found=osm_index.raw_elements_count,
        cv_detections_found=len(detections),
        merged_candidates_count=len(deduped),
        duplicates_removed=duplicates_removed,
        candidates_rejected=rejected_count,
        rejection_reasons=rejection_reasons[:10],
        candidates_returned=len(final_candidates),
    )

    return HybridDiscoveryResponse(
        success=True,
        status=overall_status,
        user_location={"latitude": req.latitude, "longitude": req.longitude},
        disaster_type=req.disaster_type,
        scanned_radii_m=scanned_radii,
        effective_radius_m=effective_r,
        osm_candidates_count=len(osm_candidates),
        satellite_candidates_count=len(cv_candidates),
        merged_candidates_count=len(deduped),
        diagnostics=diagnostics,
        imagery_metadata=acquired_img.metadata,
        segmentation_status=seg_status,
        osm_status=osm_status,
        dem_status=dem_status,
        total_candidates=len(final_candidates),
        candidates=final_candidates,
        warnings=warnings,
    )
