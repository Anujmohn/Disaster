"""
Stage 4: Single-Query OpenStreetMap Discovery & Cross-Validation.

Executes ONE bounded Overpass query at the maximum search radius (e.g. 2000m)
with separate output blocks so dense building footprints never crowd out
parks, gardens, playgrounds, sports grounds, open land, or roads.
Uses Shapely for exact polygon/segment distances and never invents road widths.
"""

from __future__ import annotations

import json
import logging
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

from shapely.geometry import LineString, Point, Polygon
from shapely.ops import nearest_points
from shapely.strtree import STRtree

from app.config import settings
from app.services.hybrid_safe_location.geometry import (
    metric_to_wgs84_geom,
    polygon_to_geojson,
    to_wgs84_lonlat,
    wgs84_to_metric_geom,
)

logger = logging.getLogger(__name__)


@dataclass
class OSMIndex:
    status: str  # "AVAILABLE" | "OSM_UNAVAILABLE"
    raw_elements_count: int = 0
    buildings_m: list[Polygon] = field(default_factory=list)
    water_m: list[Polygon] = field(default_factory=list)
    roads: list[dict[str, Any]] = field(default_factory=list)
    open_areas: list[dict[str, Any]] = field(default_factory=list)
    building_tree: Optional[STRtree] = None
    water_tree: Optional[STRtree] = None
    rejected_count: int = 0
    rejection_reasons: list[str] = field(default_factory=list)


def fetch_osm_elements_once(
    lat: float, lon: float, max_radius_m: int
) -> tuple[list[dict[str, Any]], str]:
    """
    Single bounded Overpass query returning:
    1) Open spaces, parks, gardens, playgrounds, sports grounds, water, and roads (up to 800)
    2) Building footprints within min(max_radius_m, 500)m (up to 400) for exposure checks
    """
    a = f"(around:{max_radius_m},{lat},{lon})"
    r_road = min(max_radius_m, 600)
    r_water = min(max_radius_m, 1000)
    r_bldg = min(max_radius_m, 250)
    query = (
        f"[out:json][timeout:15];("
        f'nwr["leisure"~"^(park|garden|nature_reserve|playground|pitch|sports_centre|recreation_ground|common|stadium|track)$"]{a};'
        f'nwr["landuse"~"^(grass|meadow|recreation_ground|village_green|greenfield)$"]{a};'
        f'nwr["natural"~"^(grassland|heath|water)$"]{a};'
        f'way["waterway"~"^(river|canal|stream|drain)$"](around:{r_water},{lat},{lon});'
        f'way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential)$"](around:{r_road},{lat},{lon});'
        f");out body geom 600;"
        f'(way["building"](around:{r_bldg},{lat},{lon}););out body geom 300;'
    )

    raw_urls = getattr(
        settings,
        "overpass_urls",
        "https://overpass.openstreetmap.fr/api/interpreter,https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter",
    )
    urls = (
        raw_urls
        if isinstance(raw_urls, list)
        else [u.strip() for u in raw_urls.split(",") if u.strip()]
    )
    data_bytes = urllib.parse.urlencode({"data": query}).encode("utf-8")

    for url in urls:
        try:
            req = urllib.request.Request(
                url,
                data=data_bytes,
                headers={
                    "User-Agent": "DisasterSafetyApp/2.0",
                    "Content-Type": "application/x-www-form-urlencoded",
                },
            )
            with urllib.request.urlopen(req, timeout=15.0) as resp:
                parsed = json.loads(resp.read().decode("utf-8"))
                return parsed.get("elements", []), "AVAILABLE"
        except Exception as exc:
            logger.warning("Overpass mirror %s failed: %s", url, exc)

    return [], "OSM_UNAVAILABLE"


def _parse_explicit_road_width(tags: dict[str, Any]) -> Optional[float]:
    """Only returns width when explicitly present in OSM tags; never invents width."""
    w_raw = tags.get("width")
    if w_raw is None:
        return None
    try:
        cleaned = "".join(ch for ch in str(w_raw) if ch.isdigit() or ch == ".")
        return float(cleaned) if cleaned else None
    except ValueError:
        return None


def _parse_lanes(tags: dict[str, Any]) -> Optional[int]:
    raw = tags.get("lanes")
    if raw is None:
        return None
    try:
        return int(float(str(raw).split(";")[0]))
    except ValueError:
        return None


def build_osm_spatial_index(
    elements: list[dict[str, Any]],
    osm_status: str,
    user_lat: float,
    user_lon: float,
) -> OSMIndex:
    """Converts raw Overpass elements into indexed Shapely metric geometries."""
    idx = OSMIndex(status=osm_status, raw_elements_count=len(elements))
    if osm_status != "AVAILABLE":
        return idx

    user_pt_m = Point(0.0, 0.0)

    for el in elements:
        tags = el.get("tags") or {}
        geom = el.get("geometry") or []
        coords_lonlat = [(p["lon"], p["lat"]) for p in geom] if geom else []

        if "building" in tags and len(coords_lonlat) >= 3:
            if coords_lonlat[0] != coords_lonlat[-1]:
                coords_lonlat.append(coords_lonlat[0])
            poly_m = wgs84_to_metric_geom(Polygon(coords_lonlat), user_lat, user_lon)
            if not poly_m.is_valid:
                poly_m = poly_m.buffer(0)
            if not poly_m.is_empty:
                idx.buildings_m.append(poly_m)
            continue

        if tags.get("natural") == "water" or "waterway" in tags:
            if len(coords_lonlat) >= 3 and coords_lonlat[0] == coords_lonlat[-1]:
                poly_m = wgs84_to_metric_geom(Polygon(coords_lonlat), user_lat, user_lon)
            elif len(coords_lonlat) >= 2:
                poly_m = wgs84_to_metric_geom(LineString(coords_lonlat), user_lat, user_lon).buffer(5.0)
            else:
                continue
            if not poly_m.is_valid:
                poly_m = poly_m.buffer(0)
            if not poly_m.is_empty:
                idx.water_m.append(poly_m)
            continue

        if "highway" in tags and len(coords_lonlat) >= 2:
            line_m = wgs84_to_metric_geom(LineString(coords_lonlat), user_lat, user_lon)
            hw = str(tags.get("highway", ""))
            is_bridge_or_hw = (
                hw in ("motorway", "trunk")
                or tags.get("bridge") not in (None, "no")
                or tags.get("tunnel") not in (None, "no")
            )
            dist_m = float(user_pt_m.distance(line_m))
            _, np_m = nearest_points(user_pt_m, line_m)
            n_lon, n_lat = to_wgs84_lonlat(user_lat, user_lon, float(np_m.x), float(np_m.y))
            idx.roads.append({
                "id": f"osm/way/{el.get('id')}",
                "name": tags.get("name") or tags.get("name:en"),
                "highway": hw,
                "width_m": _parse_explicit_road_width(tags),
                "lanes": _parse_lanes(tags),
                "surface": tags.get("surface"),
                "access": tags.get("access"),
                "is_bridge_or_highway": is_bridge_or_hw,
                "line_m": line_m,
                "distance_m": round(dist_m, 1),
                "nearest_point": {"latitude": round(n_lat, 6), "longitude": round(n_lon, 6)},
            })
            continue

        leisure = tags.get("leisure", "")
        landuse = tags.get("landuse", "")
        natural = tags.get("natural", "")
        access = str(tags.get("access", "")).lower()

        if landuse in ("farmland", "farmyard", "orchard", "industrial", "military") or access in ("private", "no"):
            idx.rejected_count += 1
            reason = f"Excluded {tags.get('name') or landuse or 'area'}: access={access or landuse} (not public evacuation space)."
            if reason not in idx.rejection_reasons:
                idx.rejection_reasons.append(reason)
            continue

        cat = None
        if leisure in ("park", "nature_reserve"):
            cat = "park"
        elif leisure == "garden":
            cat = "garden"
        elif leisure == "playground":
            cat = "playground"
        elif leisure in ("pitch", "stadium", "sports_centre"):
            cat = "sports_ground"
        elif leisure in ("recreation_ground", "common", "track") or landuse in ("recreation_ground", "village_green"):
            cat = "recreation_ground"
        elif landuse in ("grass", "meadow", "greenfield") or natural in ("grassland", "heath"):
            cat = "open_land"

        if not cat:
            continue

        if len(coords_lonlat) >= 3:
            if coords_lonlat[0] != coords_lonlat[-1]:
                coords_lonlat.append(coords_lonlat[0])
            wgs84_poly = Polygon(coords_lonlat)
            poly_m = wgs84_to_metric_geom(wgs84_poly, user_lat, user_lon)
            if not poly_m.is_valid:
                poly_m = poly_m.buffer(0)
            if not poly_m.is_empty and poly_m.area >= 20.0:
                idx.open_areas.append({
                    "id": f"osm/{el.get('type', 'way')}/{el.get('id')}",
                    "name": tags.get("name") or tags.get("name:en"),
                    "category": cat,
                    "poly_m": poly_m,
                    "wgs84_poly": metric_to_wgs84_geom(poly_m, user_lat, user_lon),
                    "geojson": polygon_to_geojson(wgs84_poly),
                    "osm_tag": leisure or landuse or natural,
                })
        else:
            nlat = el.get("lat") or (el.get("center") or {}).get("lat")
            nlon = el.get("lon") or (el.get("center") or {}).get("lon")
            if nlat is not None and nlon is not None:
                pt_m = wgs84_to_metric_geom(Point(float(nlon), float(nlat)), user_lat, user_lon)
                idx.open_areas.append({
                    "id": f"osm/{el.get('type', 'node')}/{el.get('id')}",
                    "name": tags.get("name") or tags.get("name:en"),
                    "category": cat,
                    "poly_m": None,
                    "pt_m": pt_m,
                    "lat": float(nlat),
                    "lon": float(nlon),
                    "wgs84_poly": None,
                    "geojson": None,
                    "osm_tag": leisure or landuse or natural,
                })

    if idx.buildings_m:
        idx.building_tree = STRtree(idx.buildings_m)
    if idx.water_m:
        idx.water_tree = STRtree(idx.water_m)
    return idx


def cross_validate_geometry_with_osm(
    geom_m: Any,
    osm_index: OSMIndex,
) -> dict[str, Any]:
    """Cross-validates a polygon or point geometry against mapped buildings, water, parks, and roads."""
    if osm_index.status != "AVAILABLE":
        return {
            "osm_status": "OSM_UNAVAILABLE",
            "building_overlap_ratio": None,
            "nearest_building_m": None,
            "nearby_buildings_50m": None,
            "nearest_water_m": None,
            "nearest_road_m": None,
            "nearest_road_width_m": "UNKNOWN",
            "matched_osm_park": None,
        }

    overlap_area = 0.0
    nearest_bldg_m: Optional[float] = None
    nearby_bldg_50m = 0

    if osm_index.building_tree is not None:
        search_box = geom_m.buffer(60.0)
        for cand_idx in osm_index.building_tree.query(search_box):
            b_poly = osm_index.buildings_m[cand_idx]
            d = float(geom_m.distance(b_poly))
            if d == 0.0 and geom_m.area > 0:
                overlap_area += float(geom_m.intersection(b_poly).area)
            if nearest_bldg_m is None or d < nearest_bldg_m:
                nearest_bldg_m = d
            if d <= 50.0:
                nearby_bldg_50m += 1

    overlap_ratio = round(overlap_area / max(geom_m.area, 1.0), 3) if geom_m.area > 0 else 0.0

    nearest_water_m: Optional[float] = None
    if osm_index.water_tree is not None:
        for cand_idx in osm_index.water_tree.query(geom_m.buffer(250.0)):
            w_poly = osm_index.water_m[cand_idx]
            d = float(geom_m.distance(w_poly))
            if nearest_water_m is None or d < nearest_water_m:
                nearest_water_m = d

    nearest_road_m: Optional[float] = None
    nearest_road_name: Optional[str] = None
    nearest_road_width: str | float = "UNKNOWN"
    for r in osm_index.roads:
        if r["is_bridge_or_highway"]:
            continue
        d = float(geom_m.distance(r["line_m"]))
        if nearest_road_m is None or d < nearest_road_m:
            nearest_road_m = d
            nearest_road_name = r["name"]
            nearest_road_width = r["width_m"] if r["width_m"] is not None else "UNKNOWN"

    matched_park: Optional[dict[str, Any]] = None
    if geom_m.area > 0:
        for oa in osm_index.open_areas:
            if oa.get("poly_m") is None:
                continue
            inter = geom_m.intersection(oa["poly_m"]).area
            if inter > 0.25 * min(geom_m.area, oa["poly_m"].area):
                matched_park = {"id": oa["id"], "name": oa["name"], "category": oa["category"]}
                break

    return {
        "osm_status": "AVAILABLE",
        "building_overlap_ratio": overlap_ratio,
        "nearest_building_m": round(nearest_bldg_m, 1) if nearest_bldg_m is not None else None,
        "nearby_buildings_50m": nearby_bldg_50m,
        "nearest_water_m": round(nearest_water_m, 1) if nearest_water_m is not None else None,
        "nearest_road_m": round(nearest_road_m, 1) if nearest_road_m is not None else None,
        "nearest_road_name": nearest_road_name,
        "nearest_road_width_m": nearest_road_width,
        "matched_osm_park": matched_park,
    }
