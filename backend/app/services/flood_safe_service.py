"""
Consolidated Flood Safe Location Engine for India.

Evaluates authentic geographical candidates across shelters, public buildings,
open high ground, and flyovers (last resort) with elevation-grid analysis,
HAND proxy height, flood danger-zone exclusion, and route hazard verification.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from app.config import settings
from app.schemas.flood_safe import (
    ALL_CATEGORIES,
    Category,
    CrisisLocation,
    Point,
    SafeLocationRequest,
)

logger = logging.getLogger(__name__)

R_KM = 6371.0088
INDIA_BBOX = (6.4, 68.0, 37.2, 97.5)

CATEGORY_BASE = {
    "shelter": 15,
    "public_building": 10,
    "open_high_ground": 8,
    "flyover": 0,
}

RESPONSE_KEY = {
    "shelter": "shelters",
    "public_building": "public_buildings",
    "open_high_ground": "open_high_ground",
    "flyover": "flyovers",
}

FLYOVER_WARNINGS = [
    "HIGH RISK: flyovers/bridges are temporary refuge only, not shelters.",
    "Piers can be undermined by fast water (scour); structures may be unsafe during or after floods.",
    "Never park or stay on the deck if water is touching it or the structure vibrates or cracks.",
    "Roads on and under flyovers carry traffic, debris and fallen power lines; keep clear of the carriageway.",
    "Do not use if the flyover crosses a river or canal; the approach ramps may be cut off.",
    "Move on to a shelter or public building as soon as the route is safe.",
]

DISCLAIMER = (
    "Estimates based on OpenStreetMap and ~30-90 m terrain data. In flat regions small "
    "embankments are not captured. Follow orders from local authorities (SDMA/NDMA/district "
    "administration). Emergency: 112 (national), 1078 (NDMA)."
)

_BAD_SHELTER_TYPES = (
    "public_transport|weather_shelter|picnic_shelter|basic_hut|lean_to|bus_stop"
)


# ============================================================================
# 1. Geospatial & Math Utilities
# ============================================================================


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Computes great-circle distance between two coordinates in kilometers."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_KM * math.asin(math.sqrt(a))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Computes initial bearing in degrees from point 1 to point 2."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def compass(bearing: float) -> str:
    """Converts bearing degrees into an 8-wind compass direction."""
    directions = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
    idx = int((bearing + 22.5) // 45) % 8
    return directions[idx]


def in_india_bbox(lat: float, lon: float) -> bool:
    """Validates if coordinates fall within the sovereign bounding box of India."""
    min_lat, min_lon, max_lat, max_lon = INDIA_BBOX
    return min_lat <= lat <= max_lat and min_lon <= lon <= max_lon


def to_xy_km(lat0: float, lon0: float, lat: float, lon: float) -> tuple[float, float]:
    """Local equirectangular flat projection in km relative to (lat0, lon0)."""
    dx = (lon - lon0) * 111.320 * math.cos(math.radians(lat0))
    dy = (lat - lat0) * 110.574
    return dx, dy


def fast_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Fast euclidean distance in km for nearby points (<50 km)."""
    dx, dy = to_xy_km(lat1, lon1, lat2, lon2)
    return math.hypot(dx, dy)


def grid_points(
    lat: float, lon: float, radius_km: float, step_km: float
) -> list[tuple[float, float]]:
    """Generates an elevation sampling grid of coordinates within radius_km."""
    pts: list[tuple[float, float]] = []
    n = int(radius_km // step_km)
    for i in range(-n, n + 1):
        for j in range(-n, n + 1):
            dy, dx = i * step_km, j * step_km
            if math.hypot(dx, dy) > radius_km:
                continue
            pt_lat = lat + dy / 110.574
            pt_lon = lon + dx / (111.320 * math.cos(math.radians(lat)))
            pts.append((pt_lat, pt_lon))
    return pts


def min_dist_to_polyline_km(
    center: tuple[float, float], coords: list[list[float]]
) -> Optional[float]:
    """
    Computes minimum Euclidean distance from center (lat, lon) to a polyline
    composed of [lon, lat] pairs.
    """
    if not coords:
        return None
    clat, clon = center
    xy = [to_xy_km(clat, clon, c[1], c[0]) for c in coords]
    if len(xy) == 1:
        return math.hypot(*xy[0])
    best = float("inf")
    for (x1, y1), (x2, y2) in zip(xy, xy[1:]):
        dx, dy = x2 - x1, y2 - y1
        seg = dx * dx + dy * dy
        t = 0.0 if seg == 0 else max(0.0, min(1.0, -(x1 * dx + y1 * dy) / seg))
        best = min(best, math.hypot(x1 + t * dx, y1 + t * dy))
    return best


def _clamp(val: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, val))


def _percentile(vals: list[float], p: float) -> Optional[float]:
    if not vals:
        return None
    s = sorted(vals)
    idx = int(_clamp(round((len(s) - 1) * p), 0, len(s) - 1))
    return s[idx]


def _num(v: Any) -> Optional[float]:
    if v is None:
        return None
    m = re.search(r"-?\d+(?:[.,]\d+)?", str(v))
    return float(m.group().replace(",", ".")) if m else None


def _center(el: dict[str, Any]) -> tuple[Optional[float], Optional[float]]:
    if "lat" in el and "lon" in el:
        return el["lat"], el["lon"]
    c = el.get("center")
    if c and "lat" in c and "lon" in c:
        return c["lat"], c["lon"]
    return None, None


# ============================================================================
# 2. OSM Querying and Tag Classification
# ============================================================================


def build_query(lat: float, lon: float, radius_m: int, cats: list[str]) -> str:
    """Builds an optimized Overpass QL query covering the requested categories."""
    a = f"(around:{radius_m},{lat},{lon})"
    q: list[str] = []
    if "shelter" in cats:
        q += [
            f'nwr["emergency"~"^(assembly_point|disaster_shelter)$"]{a};',
            f'nwr["amenity"="shelter"]["shelter_type"!~"^({_BAD_SHELTER_TYPES})$"]["bus"!="yes"]{a};',
            f'nwr["social_facility"="shelter"]{a};',
            f'nwr["amenity"="community_centre"]{a};',
            f'nwr["name"~"cyclone shelter|flood shelter|relief camp|rain basera|night shelter|aashray|ashray",i]{a};',
        ]
    if "public_building" in cats:
        q += [
            f'nwr["amenity"~"^(hospital|school|college|university|townhall|courthouse|police|fire_station)$"]{a};',
            f'nwr["office"="government"]{a};',
            f'nwr["building"~"^(government|public|civic|hospital|school|university|college)$"]{a};',
        ]
    if "open_high_ground" in cats:
        q += [
            f'nwr["leisure"~"^(park|pitch|stadium|sports_centre|track)$"]{a};',
            f'nwr["landuse"="recreation_ground"]{a};',
            f'nwr["natural"~"^(peak|hill)$"]{a};',
        ]
    if "flyover" in cats:
        q += [
            f'way["highway"~"^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link)$"]'
            f'["bridge"]["bridge"!="no"]["layer"~"^[1-9]"]{a};',
            f'way["highway"]["bridge"]["name"~"flyover|overbridge|over bridge|elevated",i]{a};',
        ]
    return f"[out:json][timeout:40];({''.join(q)});out center tags 1500;"


def classify(tags: dict[str, Any]) -> Optional[tuple[str, str]]:
    """
    Classifies OSM tags into (category, subtype) or None.
    Priority: flyover > shelter > public_building > open_high_ground.
    """
    name = (tags.get("name") or "").lower()
    hw, bridge = tags.get("highway"), tags.get("bridge")
    if hw and bridge not in (None, "no") and (
        (_num(tags.get("layer")) or 0) >= 1
        or re.search(r"flyover|overbridge|over bridge|elevated|viaduct", name)
    ):
        return "flyover", "flyover / elevated road"

    if tags.get("emergency") in ("assembly_point", "disaster_shelter"):
        return "shelter", str(tags["emergency"]).replace("_", " ")
    if tags.get("social_facility") == "shelter":
        return "shelter", "social shelter"
    if (
        tags.get("amenity") == "shelter"
        and tags.get("shelter_type") not in _BAD_SHELTER_TYPES.split("|")
        and tags.get("bus") != "yes"
    ):
        return "shelter", "shelter"
    if tags.get("amenity") == "community_centre":
        return "shelter", "community centre"
    if re.search(
        r"cyclone shelter|flood shelter|relief camp|rain basera|night shelter|aashray|ashray",
        name,
    ):
        return "shelter", "relief / night shelter"

    amen, bld = tags.get("amenity"), tags.get("building")
    if amen in (
        "hospital",
        "school",
        "college",
        "university",
        "townhall",
        "courthouse",
        "police",
        "fire_station",
    ):
        return "public_building", str(amen).replace("_", " ")
    if tags.get("office") == "government":
        return "public_building", "government office"
    if bld in (
        "government",
        "public",
        "civic",
        "hospital",
        "school",
        "university",
        "college",
    ):
        return "public_building", f"{bld} building"

    if tags.get("natural") in ("peak", "hill"):
        return "open_high_ground", str(tags["natural"])
    if tags.get("leisure") in ("park", "pitch", "stadium", "sports_centre", "track"):
        return "open_high_ground", str(tags["leisure"]).replace("_", " ")
    if tags.get("landuse") == "recreation_ground":
        return "open_high_ground", "recreation ground"
    return None


def building_levels(tags: dict[str, Any]) -> Optional[float]:
    """Extracts or estimates building storey levels."""
    lv = _num(tags.get("building:levels") or tags.get("levels"))
    if lv is None:
        h = _num(tags.get("height") or tags.get("building:height"))
        if h:
            lv = round(h / 3.2)
    return lv


# ============================================================================
# 3. External Data Providers
# ============================================================================


class FloodSafeProviders:
    """External data providers: Overpass (OSM), DEM Elevation, OSRM Routing, Nominatim."""

    def __init__(self) -> None:
        self._cache: dict[Any, tuple[float, Any]] = {}
        self._elev_cache: dict[tuple[float, float], float] = {}
        self.user_agent = "DisasterSafetyApp/2.0 (+https://github.com/disaster-safety-app)"
        self.http_timeout = 30.0

    async def close(self) -> None:
        """Cleanup resources."""
        self._cache.clear()
        self._elev_cache.clear()

    def _get(self, key: Any) -> Any:
        hit = self._cache.get(key)
        if hit and hit[0] > time.time():
            return hit[1]
        return None

    def _set(self, key: Any, val: Any, ttl: float) -> None:
        if len(self._cache) > 1000:
            self._cache.clear()
        self._cache[key] = (time.time() + ttl, val)

    def _http_get_json(self, url: str, params: Optional[dict[str, Any]] = None) -> Any:
        full_url = url
        if params:
            full_url = f"{url}?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(
            full_url,
            headers={"User-Agent": self.user_agent, "Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=self.http_timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _http_post_json(
        self, url: str, json_data: Optional[dict[str, Any]] = None, form_data: Optional[dict[str, Any]] = None
    ) -> Any:
        if json_data is not None:
            data_bytes = json.dumps(json_data).encode("utf-8")
            headers = {"User-Agent": self.user_agent, "Content-Type": "application/json"}
        elif form_data is not None:
            data_bytes = urllib.parse.urlencode(form_data).encode("utf-8")
            headers = {"User-Agent": self.user_agent, "Content-Type": "application/x-www-form-urlencoded"}
        else:
            data_bytes = b""
            headers = {"User-Agent": self.user_agent}

        req = urllib.request.Request(url, data=data_bytes, headers=headers)
        with urllib.request.urlopen(req, timeout=self.http_timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    async def verify_country(self, lat: float, lon: float) -> Optional[bool]:
        """True/False if Nominatim says in/not in India; None if unknown (fail-open)."""
        key = ("country", round(lat, 2), round(lon, 2))
        cached = self._get(key)
        if cached is not None:
            return cached

        def _do_reverse() -> Optional[bool]:
            try:
                nom_url = getattr(settings, "nominatim_url", "https://nominatim.openstreetmap.org")
                data = self._http_get_json(
                    f"{nom_url}/reverse",
                    {"format": "jsonv2", "lat": lat, "lon": lon, "zoom": 3, "addressdetails": 1},
                )
                code = (data.get("address", {}).get("country_code") or "").lower()
                if not code:
                    return None
                return code == "in"
            except Exception as e:
                logger.warning("Country verification check failed: %s", e)
                return None

        result = await asyncio.to_thread(_do_reverse)
        if result is not None:
            self._set(key, result, 86400)
        return result

    async def overpass(self, query: str) -> list[dict[str, Any]]:
        """Queries Overpass API across mirrors with caching."""
        key = ("op", query)
        cached = self._get(key)
        if cached is not None:
            return cached

        raw_urls = getattr(
            settings,
            "overpass_urls",
            "https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter",
        )
        if isinstance(raw_urls, list):
            urls = raw_urls
        else:
            urls = [u.strip() for u in raw_urls.split(",") if u.strip()]

        def _query_endpoint(u: str) -> list[dict[str, Any]]:
            data = self._http_post_json(u, form_data={"data": query})
            return data.get("elements", [])

        last_exc = None
        for endpoint in urls:
            try:
                elements = await asyncio.to_thread(_query_endpoint, endpoint)
                ttl = getattr(settings, "overpass_cache_ttl", 600)
                self._set(key, elements, ttl)
                return elements
            except Exception as exc:
                last_exc = exc
                logger.warning("Overpass endpoint %s failed: %s", endpoint, exc)

        raise RuntimeError(f"All Overpass endpoints failed: {last_exc}")

    async def elevations(
        self, pts: list[tuple[float, float]]
    ) -> list[Optional[float]]:
        """Batch elevation retrieval using Open-Meteo DEM and fallback to Open-Elevation."""
        keys = [(round(a, 5), round(b, 5)) for a, b in pts]
        missing = list(dict.fromkeys(k for k in keys if k not in self._elev_cache))
        batches = [missing[i : i + 100] for i in range(0, len(missing), 100)]

        def _fetch_batch(batch: list[tuple[float, float]]) -> list[Optional[float]]:
            # Primary: Open-Meteo (Copernicus 90 m DEM)
            try:
                data = self._http_get_json(
                    "https://api.open-meteo.com/v1/elevation",
                    {
                        "latitude": ",".join(str(p[0]) for p in batch),
                        "longitude": ",".join(str(p[1]) for p in batch),
                    },
                )
                return data.get("elevation", [])
            except Exception as e:
                logger.debug("Open-Meteo elevation error (%s); trying Open-Elevation", e)

            # Fallback: Open-Elevation
            try:
                payload = {"locations": [{"latitude": a, "longitude": b} for a, b in batch]}
                data = self._http_post_json("https://api.open-elevation.com/api/v1/lookup", json_data=payload)
                return [x.get("elevation") for x in data.get("results", [])]
            except Exception as e:
                logger.warning("Open-Elevation fallback also failed: %s", e)
                return [None] * len(batch)

        for batch in batches:
            results = await asyncio.to_thread(_fetch_batch, batch)
            for k, val in zip(batch, results):
                if val is not None:
                    self._elev_cache[k] = round(float(val), 1)

        return [self._elev_cache.get(k) for k in keys]

    async def route(
        self, a: tuple[float, float], b: tuple[float, float]
    ) -> Optional[dict[str, Any]]:
        """
        Calculates OSRM driving route from point a to point b (lat, lon).
        Returns {distance_km, duration_min, coords[[lon,lat]]} or None.
        """
        def _fetch_route() -> Optional[dict[str, Any]]:
            try:
                root_url = getattr(settings, "osrm_base_url", "http://router.project-osrm.org").rstrip("/")
                url = (
                    f"{root_url}/route/v1/driving/"
                    f"{a[1]:.6f},{a[0]:.6f};{b[1]:.6f},{b[0]:.6f}"
                )
                data = self._http_get_json(
                    url, {"overview": "full", "geometries": "geojson", "steps": "false"}
                )
                routes = data.get("routes", [])
                if not routes:
                    return None
                rt = routes[0]
                return {
                    "distance_km": rt["distance"] / 1000.0,
                    "duration_min": rt["duration"] / 60.0,
                    "coords": rt["geometry"]["coordinates"],
                }
            except Exception as exc:
                logger.debug("OSRM routing note: %s", exc)
                return None

        return await asyncio.to_thread(_fetch_route)


# Default singleton instance
_GLOBAL_PROVIDERS: Optional[FloodSafeProviders] = None


def get_default_providers() -> FloodSafeProviders:
    global _GLOBAL_PROVIDERS
    if _GLOBAL_PROVIDERS is None:
        _GLOBAL_PROVIDERS = FloodSafeProviders()
    return _GLOBAL_PROVIDERS


# ============================================================================
# 4. Assessment, Scoring, and Recommendation Pipeline
# ============================================================================


def _score(c: dict[str, Any], req: SafeLocationRequest) -> float:
    """Calculates relative safety recommendation score (0 - 100)."""
    s = float(CATEGORY_BASE[c["category"]])
    if c["height_above_crisis_m"] is not None:
        s += _clamp(c["height_above_crisis_m"] / 15.0, -1.0, 1.0) * 35.0
    if c.get("_nb_gain") is not None:
        s += _clamp(c["_nb_gain"] / 10.0, -1.0, 1.0) * 10.0
    s += (1.0 - _clamp(c["distance_user_km"] / req.search_radius_km, 0.0, 1.0)) * 25.0
    s += _clamp((c["distance_crisis_km"] - req.crisis_location.radius_km) / 5.0, 0.0, 1.0) * 15.0

    lv = c.get("building_levels")
    if c["category"] == "public_building" and lv and lv >= 3:
        s += min(lv, 6) * 1.5
    if c["category"] == "flyover":
        s *= 0.6
    if c.get("route") and c["route"].get("enters_danger_zone"):
        s -= 20.0

    return round(_clamp(s, 0.0, 100.0), 1)


def _public(c: dict[str, Any], geometry: bool) -> dict[str, Any]:
    """Filters internal evaluation fields and strips route geometry if requested."""
    out = {k: v for k, v in c.items() if not k.startswith("_")}
    if out.get("route") and not geometry:
        out["route"] = {k: v for k, v in out["route"].items() if k != "geometry"}
    return out


async def find_safe_locations(
    req: SafeLocationRequest, prov: Optional[Any] = None
) -> dict[str, Any]:
    """
    Main flood safe location discovery and assessment pipeline.
    """
    if prov is None:
        prov = get_default_providers()

    u, cz = req.user_location, req.crisis_location
    warnings: list[str] = []
    cats = list(req.categories)
    radius_km = req.search_radius_km
    danger_km = cz.radius_km
    buffer_km = 0.3

    d_uc = haversine_km(u.lat, u.lon, cz.lat, cz.lon)
    if d_uc <= danger_km:
        warnings.append(
            "You appear to be INSIDE the danger zone. Move away immediately, "
            "toward higher ground. Call 112 if in danger."
        )

    # 1. Discover authentic POIs via Overpass
    pois: list[dict[str, Any]] = []
    try:
        els = await prov.overpass(build_query(u.lat, u.lon, int(radius_km * 1000), cats))
        seen: set[tuple[str, float, float]] = set()
        for el in els:
            tags = el.get("tags") or {}
            cl = classify(tags)
            lat, lon = _center(el)
            if not cl or lat is None or lon is None or cl[0] not in cats:
                continue
            key = (cl[0], round(lat, 4), round(lon, 4))
            if key in seen:
                continue
            seen.add(key)
            d_crisis = haversine_km(cz.lat, cz.lon, lat, lon)
            if d_crisis < danger_km + buffer_km:
                # POI is inside crisis danger zone: strictly exclude
                continue
            category, subtype = cl
            pois.append({
                "id": f"{el.get('type', 'x')}/{el.get('id')}",
                "category": category,
                "name": tags.get("name") or tags.get("name:en") or subtype.title(),
                "subtype": subtype,
                "lat": lat,
                "lon": lon,
                "distance_crisis_km": round(d_crisis, 2),
                "tags": tags,
            })
    except Exception as e:
        logger.warning("Overpass POI discovery failed: %s", e)
        warnings.append(f"OpenStreetMap data unavailable ({e}); only terrain-based results returned.")

    for p in pois:
        p["distance_user_km"] = round(haversine_km(u.lat, u.lon, p["lat"], p["lon"]), 2)
    pois.sort(key=lambda p: p["distance_user_km"])
    pois = pois[:250]

    # 2. Elevation grid around user and discovered points
    step = max(0.25, radius_km / 10.0)
    grid = grid_points(u.lat, u.lon, radius_km, step)
    pts = [(u.lat, u.lon), (cz.lat, cz.lon)] + [(p["lat"], p["lon"]) for p in pois] + grid
    elevs = await prov.elevations(pts)

    user_elev = elevs[0]
    crisis_elev = elevs[1]
    poi_elev = elevs[2 : 2 + len(pois)]
    grid_elev = elevs[2 + len(pois) :]

    grid_pairs = [(g, e) for g, e in zip(grid, grid_elev) if e is not None]
    if crisis_elev is None or not grid_pairs:
        warnings.append("Elevation data unavailable; results are ranked by distance only.")

    local_low = _percentile([e for _, e in grid_pairs], 0.10)
    ref = crisis_elev if crisis_elev is not None else user_elev

    def terrain(lat: float, lon: float, elev: Optional[float]):
        gain = None if elev is None or ref is None else round(elev - ref, 1)
        above_low = None if elev is None or local_low is None else round(elev - local_low, 1)
        nb = [e for (g, e) in grid_pairs if fast_km(lat, lon, g[0], g[1]) <= 1.0]
        nb_mean = sum(nb) / len(nb) if nb else None
        nb_gain = None if nb_mean is None or ref is None else round(nb_mean - ref, 1)
        isolation = None if elev is None or nb_mean is None else round(elev - nb_mean, 1)
        return gain, above_low, nb_gain, isolation

    def safety(gain: Optional[float], above_low: Optional[float]) -> str:
        if gain is None:
            return "unknown"
        if gain >= req.min_height_gain_m and (above_low is None or above_low >= 3):
            return "high"
        return "moderate" if gain >= 0 else "low"

    # 3. Build candidate records from discovered POIs
    cands: list[dict[str, Any]] = []
    for p, e in zip(pois, poi_elev):
        gain, above_low, nb_gain, iso = terrain(p["lat"], p["lon"], e)
        if p["category"] == "open_high_ground" and (gain is None or gain < req.min_height_gain_m):
            continue  # Open ground must actually provide positive elevation gain above crisis
        tags = p.pop("tags")
        w: list[str] = []
        notes: list[str] = []
        lv = building_levels(tags)
        if lv and lv >= 3 and p["category"] in ("public_building", "shelter"):
            notes.append(f"About {int(lv)} floors: use upper floors if water rises.")
        if p["category"] == "flyover":
            w.extend(FLYOVER_WARNINGS)
        if iso is not None and iso > 5:
            w.append("Isolated rise: surrounding land is lower, so access may be cut off by water.")
        if gain is not None and gain < 0:
            w.append("Lower than the crisis point; may flood. Use only if nothing better is reachable.")
        for k in ("capacity", "opening_hours", "phone", "contact:phone", "operator"):
            if tags.get(k):
                notes.append(f"{k}: {tags[k]}")
        cands.append({
            **p,
            "elevation_m": e,
            "height_above_crisis_m": gain,
            "height_above_local_low_m": above_low,
            "building_levels": lv,
            "safety_level": safety(gain, above_low),
            "warnings": w,
            "notes": notes,
            "_nb_gain": nb_gain,
        })

    # 4. Terrain-only high ground from grid peaks (for rural / sparse OSM regions)
    if "open_high_ground" in cats and ref is not None:
        picked: list[tuple[tuple[float, float], float]] = []
        for g, e in sorted(grid_pairs, key=lambda x: -x[1]):
            if e - ref < req.min_height_gain_m:
                break
            if haversine_km(cz.lat, cz.lon, g[0], g[1]) < danger_km + buffer_km:
                continue
            if any(fast_km(g[0], g[1], q[0][0], q[0][1]) < 0.6 for q in picked):
                continue
            picked.append((g, e))
            if len(picked) >= 15:
                break

        for g, e in picked:
            gain, above_low, nb_gain, iso = terrain(g[0], g[1], e)
            w = ["Terrain estimate only: verify on arrival that the area is open, accessible and dry."]
            if iso is not None and iso > 5:
                w.append("Isolated rise: surrounding land is lower, so access may be cut off by water.")
            cands.append({
                "id": f"terrain/{g[0]:.5f},{g[1]:.5f}",
                "category": "open_high_ground",
                "name": "High ground (terrain estimate)",
                "subtype": "elevated open area",
                "lat": g[0],
                "lon": g[1],
                "distance_user_km": round(haversine_km(u.lat, u.lon, g[0], g[1]), 2),
                "distance_crisis_km": round(haversine_km(cz.lat, cz.lon, g[0], g[1]), 2),
                "elevation_m": e,
                "height_above_crisis_m": gain,
                "height_above_local_low_m": above_low,
                "building_levels": None,
                "safety_level": safety(gain, above_low),
                "warnings": w,
                "notes": [],
                "_nb_gain": nb_gain,
            })

    # 5. Calculate bearings and initial candidate scoring
    for c in cands:
        b = bearing_deg(u.lat, u.lon, c["lat"], c["lon"])
        c["direction"] = compass(b)
        c["bearing_deg"] = round(b)
        c["google_maps_url"] = (
            f"https://www.google.com/maps/dir/?api=1&travelmode=walking"
            f"&origin={u.lat},{u.lon}&destination={c['lat']},{c['lon']}"
        )
        c["route"] = None
        c["score"] = _score(c, req)

    def top_per_category() -> dict[str, list[dict[str, Any]]]:
        out = {}
        for cat in cats:
            items = sorted((c for c in cands if c["category"] == cat), key=lambda c: -c["score"])
            out[cat] = items[: req.max_results_per_category]
        return out

    # 6. Route evaluation for shortlisted candidates; flag routes entering danger zone
    shortlist = {id(c): c for items in top_per_category().values() for c in items}
    if req.use_routing and shortlist:
        sem = asyncio.Semaphore(4)

        async def do_route(c: dict[str, Any]):
            async with sem:
                r = await prov.route((u.lat, u.lon), (c["lat"], c["lon"]))
            if r:
                m = min_dist_to_polyline_km((cz.lat, cz.lon), r["coords"])
                enters = m is not None and m < danger_km
                c["route"] = {
                    "distance_km": round(r["distance_km"], 2),
                    "duration_min": round(r["duration_min"], 1),
                    "min_distance_to_crisis_km": round(m, 2) if m is not None else 0.0,
                    "enters_danger_zone": enters,
                    "geometry": r["coords"],
                    "profile": "driving",
                    "source": "osrm",
                }
            else:
                m = min_dist_to_polyline_km(
                    (cz.lat, cz.lon), [[u.lon, u.lat], [c["lon"], c["lat"]]]
                )
                enters = m is not None and m < danger_km
                c["route"] = {
                    "distance_km": round(c["distance_user_km"], 2),
                    "duration_min": None,
                    "min_distance_to_crisis_km": round(m, 2) if m is not None else 0.0,
                    "enters_danger_zone": enters,
                    "geometry": None,
                    "profile": "straight_line",
                    "source": "fallback",
                }
            if c["route"]["enters_danger_zone"]:
                c["warnings"].append(
                    "The route passes through the danger zone. Avoid it and "
                    "take another way or use the next option."
                )
            c["score"] = _score(c, req)

        await asyncio.gather(*[do_route(c) for c in shortlist.values()])

    # 7. Assemble final response
    per_cat = top_per_category()
    flat = sorted((c for items in per_cat.values() for c in items), key=lambda c: -c["score"])
    non_fly = [c for c in flat if c["category"] != "flyover"]
    best = (non_fly or flat or [None])[0]
    safe_ok = [
        c
        for c in non_fly
        if c["safety_level"] in ("high", "moderate")
        and not (c["route"] and c["route"]["enters_danger_zone"])
    ]
    nearest = min(safe_ok, key=lambda c: c["distance_user_km"]) if safe_ok else None

    if not flat:
        warnings.append(
            "No safe candidates found in range. Increase search_radius_km or move away "
            "from the crisis location toward higher ground and call 112."
        )
    elif best and best["safety_level"] in ("moderate", "low", "unknown"):
        warnings.append(
            "Terrain differences here are small or data is limited; treat results as "
            "estimates and follow official instructions."
        )
    if per_cat.get("flyover"):
        warnings.append("Flyovers are listed as a LAST-RESORT temporary refuge, with risk warnings.")

    g = req.include_route_geometry
    return {
        "user_location": u.model_dump(),
        "crisis_location": cz.model_dump(),
        "user_in_danger_zone": d_uc <= danger_km,
        "distance_user_to_crisis_km": round(d_uc, 2),
        "elevation_m": {
            "user": user_elev,
            "crisis": crisis_elev,
            "local_low": local_low,
        },
        "best_recommendation": _public(best, g) if best else None,
        "nearest_safe": _public(nearest, g) if nearest else None,
        "recommendations": {
            RESPONSE_KEY[cat]: [_public(c, g) for c in items]
            for cat, items in per_cat.items()
        },
        "ranked": [_public(c, g) for c in flat[:10]],
        "warnings": warnings,
        "disclaimer": DISCLAIMER,
    }
