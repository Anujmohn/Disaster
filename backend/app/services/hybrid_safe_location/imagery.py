"""
Stage 1: Satellite Imagery Acquisition and Geographical Alignment.

Acquires real satellite/aerial imagery centered on the user's coordinates:
- Google Maps Static Satellite API (when GOOGLE_MAPS_API_KEY is configured)
- Microsoft Azure Maps Imagery API (when AZURE_MAPS_KEY is configured)
- Esri World Imagery (public XYZ tiles, attribution required)

Never uses OSM standard cartographic street drawings.
Always computes and preserves the exact pixel-to-geographic affine transform.
"""

from __future__ import annotations

import logging
import math
import struct
import urllib.parse
import urllib.request
import zlib
from dataclasses import dataclass
from typing import Any, Callable, Optional

import numpy as np

from app.config import settings
from app.schemas.hybrid_safe_location import ImageryMetadata

logger = logging.getLogger(__name__)


@dataclass
class AcquiredImagery:
    rgb: Optional[np.ndarray]
    metadata: ImageryMetadata


def latlon_to_web_mercator_pixel(lat: float, lon: float, zoom: int) -> tuple[float, float]:
    """Converts WGS84 (lat, lon) to global Web Mercator pixel coordinates at zoom level."""
    n = 256.0 * (2.0**zoom)
    px = (lon + 180.0) / 360.0 * n
    sin_lat = math.sin(math.radians(max(-85.05112878, min(85.05112878, lat))))
    py = (0.5 - math.log((1.0 + sin_lat) / (1.0 - sin_lat)) / (4.0 * math.pi)) * n
    return px, py


def web_mercator_pixel_to_latlon(px: float, py: float, zoom: int) -> tuple[float, float]:
    """Converts global Web Mercator pixel coordinates back to WGS84 (lat, lon)."""
    n = 256.0 * (2.0**zoom)
    lon = (px / n) * 360.0 - 180.0
    lat_rad = math.atan(math.sinh(math.pi * (1.0 - 2.0 * py / n)))
    lat = math.degrees(lat_rad)
    return lat, lon


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def decode_image_bytes(raw_bytes: bytes) -> tuple[Optional[np.ndarray], Optional[str]]:
    """
    Decodes PNG or JPEG bytes into an (H, W, 3) uint8 RGB array.
    Uses Pillow if installed; falls back to pure-Python stdlib PNG decoder.
    """
    try:
        from io import BytesIO
        from PIL import Image  # type: ignore

        img = Image.open(BytesIO(raw_bytes)).convert("RGB")
        return np.asarray(img, dtype=np.uint8), None
    except ImportError:
        pass
    except Exception as exc:
        return None, f"Pillow image decode failed: {exc}"

    # Pure-Python 8-bit RGB/RGBA PNG fallback
    if len(raw_bytes) >= 24 and raw_bytes[:8] == b"\x89PNG\r\n\x1a\n":
        pos = 8
        width = height = bit_depth = color_type = 0
        idat: list[bytes] = []
        while pos + 8 <= len(raw_bytes):
            length, ctype = struct.unpack(">I4s", raw_bytes[pos : pos + 8])
            pos += 8
            if pos + length + 4 > len(raw_bytes):
                break
            chunk = raw_bytes[pos : pos + length]
            pos += length + 4
            if ctype == b"IHDR":
                width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
                if bit_depth != 8 or interlace != 0 or color_type not in (2, 6):
                    return None, "Unsupported PNG bit depth/interlacing"
            elif ctype == b"IDAT":
                idat.append(chunk)
            elif ctype == b"IEND":
                break
        if not width or not height or not idat:
            return None, "Corrupt PNG stream"
        try:
            raw = zlib.decompress(b"".join(idat))
        except zlib.error as exc:
            return None, f"PNG zlib error: {exc}"
        bpp = 3 if color_type == 2 else 4
        stride = width * bpp
        if len(raw) < height * (1 + stride):
            return None, "Truncated PNG scanlines"
        recon = bytearray(height * stride)
        for y in range(height):
            rs = y * (1 + stride)
            ft = raw[rs]
            src = raw[rs + 1 : rs + 1 + stride]
            do = y * stride
            if ft == 0:
                recon[do : do + stride] = src
            elif ft == 1:
                for x in range(stride):
                    left = recon[do + x - bpp] if x >= bpp else 0
                    recon[do + x] = (src[x] + left) & 0xFF
            elif ft == 2:
                for x in range(stride):
                    up = recon[do - stride + x] if y > 0 else 0
                    recon[do + x] = (src[x] + up) & 0xFF
            elif ft == 3:
                for x in range(stride):
                    left = recon[do + x - bpp] if x >= bpp else 0
                    up = recon[do - stride + x] if y > 0 else 0
                    recon[do + x] = (src[x] + ((left + up) >> 1)) & 0xFF
            elif ft == 4:
                for x in range(stride):
                    left = recon[do + x - bpp] if x >= bpp else 0
                    up = recon[do - stride + x] if y > 0 else 0
                    upleft = recon[do - stride + x - bpp] if (y > 0 and x >= bpp) else 0
                    recon[do + x] = (src[x] + _paeth(left, up, upleft)) & 0xFF
            else:
                return None, f"Unsupported PNG filter {ft}"
        arr = np.frombuffer(bytes(recon), dtype=np.uint8).reshape((height, width, bpp))
        return arr[:, :, :3].copy(), None

    return None, "JPEG/satellite tile decoding requires Pillow (PIL), which is not installed."


def _build_centered_metadata(
    provider: str,
    attribution: str,
    center_lat: float,
    center_lon: float,
    zoom: int,
    width_px: int,
    height_px: int,
    acquisition_date: Optional[str] = None,
) -> ImageryMetadata:
    cx_px, cy_px = latlon_to_web_mercator_pixel(center_lat, center_lon, zoom)
    min_px = cx_px - width_px / 2.0
    max_px = cx_px + width_px / 2.0
    min_py = cy_px - height_px / 2.0
    max_py = cy_px + height_px / 2.0

    max_lat, min_lon = web_mercator_pixel_to_latlon(min_px, min_py, zoom)
    min_lat, max_lon = web_mercator_pixel_to_latlon(max_px, max_py, zoom)

    return ImageryMetadata(
        provider=provider,
        zoom=zoom,
        width_px=width_px,
        height_px=height_px,
        center_lat=center_lat,
        center_lon=center_lon,
        bbox={
            "min_lat": round(min_lat, 7),
            "min_lon": round(min_lon, 7),
            "max_lat": round(max_lat, 7),
            "max_lon": round(max_lon, 7),
        },
        crs="EPSG:4326",
        attribution=attribution,
        acquisition_date=acquisition_date,
        pixel_to_geo_transform={
            "origin_px_x": min_px,
            "origin_px_y": min_py,
            "zoom": float(zoom),
            "min_lon": min_lon,
            "max_lat": max_lat,
            "deg_per_px_lon": (max_lon - min_lon) / float(width_px),
            "deg_per_px_lat": (max_lat - min_lat) / float(height_px),
        },
        status="AVAILABLE",
    )


def acquire_centered_satellite_imagery(
    lat: float,
    lon: float,
    zoom: int = 18,
    crop_size_px: int = 512,
    tile_fetcher: Optional[Callable[[int, int, int], bytes]] = None,
) -> AcquiredImagery:
    """
    Fetches real satellite imagery centered strictly on (lat, lon).
    If tile_fetcher is not provided, tries:
      1. Google Maps Static Satellite API (PNG, if GOOGLE_MAPS_API_KEY configured)
      2. Microsoft Azure Maps Satellite API (if AZURE_MAPS_KEY configured)
      3. Esri World Imagery XYZ 3x3 stitch & exact center crop around (lat, lon)
    """
    ua = "DisasterSafetyApp-HybridGeo/2.0"
    google_key = getattr(settings, "google_maps_api_key", None)
    azure_key = getattr(settings, "azure_maps_key", None)

    if tile_fetcher is None and google_key:
        url = "https://maps.googleapis.com/maps/api/staticmap?" + urllib.parse.urlencode({
            "center": f"{lat},{lon}",
            "zoom": zoom,
            "size": f"{crop_size_px}x{crop_size_px}",
            "maptype": "satellite",
            "format": "png",
            "key": google_key,
        })
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua})
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                rgb, err = decode_image_bytes(resp.read())
                if rgb is not None:
                    meta = _build_centered_metadata(
                        "Google Maps Static Satellite",
                        "© Google Satellite Imagery",
                        lat, lon, zoom, rgb.shape[1], rgb.shape[0],
                    )
                    return AcquiredImagery(rgb=rgb, metadata=meta)
        except Exception as exc:
            logger.warning("Google Static Satellite fetch failed: %s", exc)

    if tile_fetcher is None and azure_key:
        url = "https://atlas.microsoft.com/map/static/png?" + urllib.parse.urlencode({
            "api-version": "2022-08-01",
            "layer": "basic",
            "style": "satellite",
            "zoom": zoom,
            "center": f"{lon},{lat}",
            "width": crop_size_px,
            "height": crop_size_px,
            "subscription-key": azure_key,
        })
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua})
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                rgb, err = decode_image_bytes(resp.read())
                if rgb is not None:
                    meta = _build_centered_metadata(
                        "Microsoft Azure Maps Satellite",
                        "© Microsoft / TomTom / Maxar",
                        lat, lon, zoom, rgb.shape[1], rgb.shape[0],
                    )
                    return AcquiredImagery(rgb=rgb, metadata=meta)
        except Exception as exc:
            logger.warning("Azure Maps Satellite fetch failed: %s", exc)

    # Esri World Imagery XYZ stitching & exact center crop
    cx_px, cy_px = latlon_to_web_mercator_pixel(lat, lon, zoom)
    center_tx = int(cx_px // 256)
    center_ty = int(cy_px // 256)
    half = crop_size_px // 2

    min_tx = int((cx_px - half) // 256)
    max_tx = int((cx_px + half - 1) // 256)
    min_ty = int((cy_px - half) // 256)
    max_ty = int((cy_px + half - 1) // 256)

    grid_w = (max_tx - min_tx + 1) * 256
    grid_h = (max_ty - min_ty + 1) * 256
    canvas = np.zeros((grid_h, grid_w, 3), dtype=np.uint8)

    def _default_esri_fetch(z: int, x: int, y: int) -> bytes:
        url = f"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        req = urllib.request.Request(url, headers={"User-Agent": ua})
        with urllib.request.urlopen(req, timeout=6.0) as resp:
            return resp.read()

    fetcher = tile_fetcher or _default_esri_fetch
    last_err = None
    tiles_ok = 0

    for ty in range(min_ty, max_ty + 1):
        for tx in range(min_tx, max_tx + 1):
            try:
                raw_tile = fetcher(zoom, tx, ty)
                tile_rgb, dec_err = decode_image_bytes(raw_tile)
                if tile_rgb is None:
                    last_err = dec_err
                    continue
                ox = (tx - min_tx) * 256
                oy = (ty - min_ty) * 256
                th, tw = min(256, tile_rgb.shape[0]), min(256, tile_rgb.shape[1])
                canvas[oy : oy + th, ox : ox + tw] = tile_rgb[:th, :tw]
                tiles_ok += 1
            except Exception as exc:
                last_err = str(exc)

    expected_tiles = (max_tx - min_tx + 1) * (max_ty - min_ty + 1)
    if tiles_ok < expected_tiles:
        return AcquiredImagery(
            rgb=None,
            metadata=ImageryMetadata(
                provider="Esri World Imagery",
                zoom=zoom,
                center_lat=lat,
                center_lon=lon,
                attribution="Source: Esri, Maxar, Earthstar Geographics",
                status="IMAGERY_UNAVAILABLE",
                unavailable_reason=last_err or f"Only {tiles_ok}/{expected_tiles} satellite tiles retrieved.",
            ),
        )

    # Crop exact centered window around (cx_px, cy_px)
    crop_x0 = int(round(cx_px - half)) - min_tx * 256
    crop_y0 = int(round(cy_px - half)) - min_ty * 256
    cropped = canvas[crop_y0 : crop_y0 + crop_size_px, crop_x0 : crop_x0 + crop_size_px].copy()

    meta = _build_centered_metadata(
        "Esri World Imagery",
        "Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community",
        lat, lon, zoom, cropped.shape[1], cropped.shape[0],
    )
    return AcquiredImagery(rgb=cropped, metadata=meta)
