"""
Stage 3: Pixel Mask to Geographical Polygon & Metric CRS Spatial Analysis.

Converts binary segmentation masks into geographically aligned WGS84 polygons,
projects into a local metric CRS to compute exact area (m^2), minimum boundary
distance to user (NEVER centroid-only), nearest accessible boundary point,
search-radius clipping, and IoU-based deduplication.
"""

from __future__ import annotations

import math
from typing import Any, Optional

import numpy as np
from shapely.geometry import MultiPoint, Point, Polygon
from shapely.ops import nearest_points, transform

from app.schemas.hybrid_safe_location import ImageryMetadata
from app.services.hybrid_safe_location.imagery import web_mercator_pixel_to_latlon


def to_metric_xy(ref_lat: float, ref_lon: float, lon: float, lat: float) -> tuple[float, float]:
    """Local metric CRS projection (meters relative to user coordinates)."""
    x = (lon - ref_lon) * 111320.0 * max(0.1, math.cos(math.radians(ref_lat)))
    y = (lat - ref_lat) * 110574.0
    return x, y


def to_wgs84_lonlat(ref_lat: float, ref_lon: float, x: float, y: float) -> tuple[float, float]:
    """Inverse local metric CRS projection back to WGS84 (lon, lat)."""
    lon = ref_lon + x / (111320.0 * max(0.1, math.cos(math.radians(ref_lat))))
    lat = ref_lat + y / 110574.0
    return lon, lat


def wgs84_to_metric_geom(geom: Any, ref_lat: float, ref_lon: float) -> Any:
    return transform(lambda x, y, z=None: to_metric_xy(ref_lat, ref_lon, x, y), geom)


def metric_to_wgs84_geom(geom: Any, ref_lat: float, ref_lon: float) -> Any:
    return transform(lambda x, y, z=None: to_wgs84_lonlat(ref_lat, ref_lon, x, y), geom)


def polygon_to_geojson(poly: Polygon) -> dict[str, Any]:
    ring = [[round(float(c[0]), 7), round(float(c[1]), 7)] for c in poly.exterior.coords]
    return {"type": "Polygon", "coordinates": [ring]}


def mask_to_wgs84_polygon(
    mask: np.ndarray, metadata: ImageryMetadata
) -> Optional[Polygon]:
    """
    Converts a 2D boolean segmentation mask into a geographically aligned WGS84 Polygon
    using the exact pixel-to-Web-Mercator-to-WGS84 transformation from ImageryMetadata.
    Returns None if geographical alignment is missing or mask has < 3 boundary pixels.
    """
    if metadata.status != "AVAILABLE" or not metadata.pixel_to_geo_transform:
        return None

    tfm = metadata.pixel_to_geo_transform
    origin_x = tfm.get("origin_px_x")
    origin_y = tfm.get("origin_px_y")
    zoom = int(tfm.get("zoom", metadata.zoom))
    if origin_x is None or origin_y is None:
        return None

    mask_bool = np.asarray(mask, dtype=bool)
    if mask_bool.ndim != 2 or int(mask_bool.sum()) < 9:
        return None

    # Extract boundary pixels (pixels in mask that have at least one 4-neighbor outside)
    padded = np.pad(mask_bool, 1, mode="constant", constant_values=False)
    is_interior = (
        padded[1:-1, 1:-1]
        & padded[:-2, 1:-1]
        & padded[2:, 1:-1]
        & padded[1:-1, :-2]
        & padded[1:-1, 2:]
    )
    boundary = mask_bool & (~is_interior)
    ys, xs = np.where(boundary)
    if len(xs) < 3:
        return None

    lonlat_pts: list[tuple[float, float]] = []
    for py, px in zip(ys.tolist(), xs.tolist()):
        lat, lon = web_mercator_pixel_to_latlon(origin_x + px + 0.5, origin_y + py + 0.5, zoom)
        lonlat_pts.append((lon, lat))

    hull = MultiPoint(lonlat_pts).convex_hull
    if hull.geom_type != "Polygon" or not hull.is_valid or hull.is_empty:
        return None
    return hull


def analyze_polygon_metrics(
    wgs84_poly: Polygon,
    user_lat: float,
    user_lon: float,
    clip_radius_m: float,
) -> Optional[dict[str, Any]]:
    """
    Clips a WGS84 polygon to the search circle, computes metric area (m^2),
    minimum distance from user to polygon boundary, and nearest accessible point.
    """
    metric_poly = wgs84_to_metric_geom(wgs84_poly, user_lat, user_lon)
    if not metric_poly.is_valid:
        metric_poly = metric_poly.buffer(0)
    if metric_poly.is_empty:
        return None

    user_pt_m = Point(0.0, 0.0)
    search_circle_m = user_pt_m.buffer(clip_radius_m)
    clipped_m = metric_poly.intersection(search_circle_m)
    if clipped_m.is_empty or clipped_m.area < 1.0:
        return None

    if clipped_m.geom_type == "MultiPolygon":
        clipped_m = max(clipped_m.geoms, key=lambda g: g.area)
    if clipped_m.geom_type != "Polygon":
        return None

    # Minimum boundary distance (0.0 if user is inside polygon)
    dist_m = float(user_pt_m.distance(clipped_m))
    _, nearest_m = nearest_points(user_pt_m, clipped_m)
    n_lon, n_lat = to_wgs84_lonlat(user_lat, user_lon, float(nearest_m.x), float(nearest_m.y))
    centroid_m = clipped_m.centroid
    c_lon, c_lat = to_wgs84_lonlat(user_lat, user_lon, float(centroid_m.x), float(centroid_m.y))
    clipped_wgs84 = metric_to_wgs84_geom(clipped_m, user_lat, user_lon)

    return {
        "clipped_wgs84": clipped_wgs84,
        "clipped_metric": clipped_m,
        "polygon_geojson": polygon_to_geojson(clipped_wgs84),
        "area_m2": round(float(metric_poly.area), 1),
        "clipped_area_m2": round(float(clipped_m.area), 1),
        "distance_m": round(dist_m, 1),
        "nearest_point": {"latitude": round(n_lat, 6), "longitude": round(n_lon, 6)},
        "centroid": {"latitude": round(c_lat, 6), "longitude": round(c_lon, 6)},
    }


def deduplicate_metric_candidates(
    candidates: list[dict[str, Any]], iou_threshold: float = 0.45
) -> list[dict[str, Any]]:
    """Removes overlapping duplicate detections based on Shapely polygon IoU."""
    kept: list[dict[str, Any]] = []
    for cand in candidates:
        poly_a = cand.get("_metric_poly")
        if poly_a is None:
            kept.append(cand)
            continue
        is_dup = False
        for existing in kept:
            poly_b = existing.get("_metric_poly")
            if poly_b is None:
                continue
            inter = poly_a.intersection(poly_b).area
            if inter <= 0.0:
                continue
            union = poly_a.union(poly_b).area
            if union > 0.0 and (inter / union) >= iou_threshold:
                is_dup = True
                break
        if not is_dup:
            kept.append(cand)
    return kept
