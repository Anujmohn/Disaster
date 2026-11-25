from __future__ import annotations

import asyncio
import struct
import unittest
import zlib

import numpy as np

from app.main import app
from app.schemas.hybrid_safe_location import HybridSafeLocationRequest
from app.services.hybrid_safe_location.geometry import (
    analyze_polygon_metrics,
    mask_to_wgs84_polygon,
)
from app.services.hybrid_safe_location.imagery import acquire_centered_satellite_imagery
from app.services.hybrid_safe_location.pipeline import discover_hybrid_safe_locations
from app.services.hybrid_safe_location.segmentation import (
    SegmentationDetection,
    get_zero_shot_segmenter,
)


def _make_png_tile(w: int = 256, h: int = 256, r: int = 50, g: int = 150, b: int = 60) -> bytes:
    row = bytes([r, g, b]) * w
    raw = b"".join(b"\x00" + row for _ in range(h))
    comp = zlib.compress(raw)

    def _chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr) + _chunk(b"IDAT", comp) + _chunk(b"IEND", b"")


class HybridSafeLocationTests(unittest.TestCase):
    def test_a_imagery_centered_alignment_and_unavailable(self) -> None:
        tile_png = _make_png_tile()
        acquired = acquire_centered_satellite_imagery(
            lat=27.227,
            lon=79.027,
            zoom=18,
            crop_size_px=512,
            tile_fetcher=lambda z, x, y: tile_png,
        )
        self.assertEqual(acquired.metadata.status, "AVAILABLE")
        self.assertEqual(acquired.metadata.width_px, 512)
        self.assertEqual(acquired.metadata.height_px, 512)
        self.assertIsNotNone(acquired.metadata.bbox)
        self.assertIsNotNone(acquired.metadata.pixel_to_geo_transform)
        bbox = acquired.metadata.bbox
        self.assertLess(bbox["min_lat"], 27.227)
        self.assertGreater(bbox["max_lat"], 27.227)
        self.assertLess(bbox["min_lon"], 79.027)
        self.assertGreater(bbox["max_lon"], 79.027)

        # Unavailable case
        def _fail_fetch(z: int, x: int, y: int) -> bytes:
            raise OSError("Network unreachable")

        unavail = acquire_centered_satellite_imagery(
            lat=27.227, lon=79.027, zoom=18, crop_size_px=512, tile_fetcher=_fail_fetch
        )
        self.assertEqual(unavail.metadata.status, "IMAGERY_UNAVAILABLE")
        self.assertIsNone(unavail.rgb)

    def test_b_and_c_mask_to_polygon_boundary_distance_and_dedup(self) -> None:
        tile_png = _make_png_tile()
        acquired = acquire_centered_satellite_imagery(
            lat=27.227, lon=79.027, zoom=18, crop_size_px=512, tile_fetcher=lambda z, x, y: tile_png
        )
        # Create a mask for a large open field near the center
        mask = np.zeros((512, 512), dtype=bool)
        mask[180:300, 180:300] = True  # ~120x120 px (~70m x 70m)

        poly = mask_to_wgs84_polygon(mask, acquired.metadata)
        self.assertIsNotNone(poly)
        metrics = analyze_polygon_metrics(poly, user_lat=27.227, user_lon=79.027, clip_radius_m=500.0)
        self.assertIsNotNone(metrics)
        self.assertGreater(metrics["area_m2"], 1000.0)
        self.assertLessEqual(metrics["distance_m"], 100.0)

        # Verify model status reports honestly when PyTorch/LangSAM is missing
        seg_status = get_zero_shot_segmenter().get_status()
        self.assertIn(seg_status.status, ("AVAILABLE", "MODEL_UNAVAILABLE"))

    def test_c_large_park_near_user_not_rejected_for_distant_centroid(self) -> None:
        # Park boundary starts ~30m east of user, extends 300m east (centroid ~180m away > 100m!)
        elements = [
            {
                "type": "way",
                "id": 9001,
                "tags": {"leisure": "park", "name": "Large Boundary Park"},
                "geometry": [
                    {"lat": 27.2268, "lon": 79.0273},  # ~30m east
                    {"lat": 27.2268, "lon": 79.0303},  # ~330m east
                    {"lat": 27.2274, "lon": 79.0303},
                    {"lat": 27.2274, "lon": 79.0273},
                    {"lat": 27.2268, "lon": 79.0273},
                ],
            }
        ]
        req = HybridSafeLocationRequest(
            latitude=27.2270, longitude=79.0270, disaster_type="EARTHQUAKE", max_radius_m=100, min_candidates=1
        )
        resp = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: _make_png_tile(),
                osm_elements_override=(elements, "AVAILABLE"),
            )
        )
        self.assertEqual(resp.effective_radius_m, 100)
        self.assertEqual(resp.total_candidates, 1)
        self.assertEqual(resp.candidates[0].name, "Large Boundary Park")
        self.assertLess(resp.candidates[0].distance_m, 50.0)

    def test_d_earthquake_assessment_with_satellite_and_osm(self) -> None:
        tile_png = _make_png_tile()

        def _fake_seg(rgb: np.ndarray, prompts: list[str]) -> list[SegmentationDetection]:
            m = np.zeros((512, 512), dtype=bool)
            m[200:290, 200:290] = True
            return [
                SegmentationDetection(
                    category="open_field",
                    prompt="Large open field",
                    confidence=0.88,
                    mask=m,
                    bbox_px=(200, 200, 290, 290),
                )
            ]

        elements = [
            {
                "type": "way",
                "id": 101,
                "tags": {"building": "yes"},
                "geometry": [
                    {"lat": 27.2260, "lon": 79.0260},
                    {"lat": 27.2260, "lon": 79.0262},
                    {"lat": 27.2262, "lon": 79.0262},
                    {"lat": 27.2262, "lon": 79.0260},
                ],
            }
        ]
        req = HybridSafeLocationRequest(
            latitude=27.2270, longitude=79.0270, disaster_type="EARTHQUAKE", min_candidates=1
        )
        resp = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: tile_png,
                custom_segmenter=_fake_seg,
                osm_elements_override=(elements, "AVAILABLE"),
            )
        )
        self.assertEqual(resp.dem_status, "NOT_APPLICABLE")
        self.assertEqual(resp.status, "OK")
        self.assertEqual(resp.candidates[0].origin_classification, "SATELLITE_DETECTED")
        self.assertEqual(resp.candidates[0].assessment_status, "SUITABLE_WITH_EVIDENCE")
        self.assertIsNotNone(resp.candidates[0].polygon_geojson)

    def test_e_flood_assessment_unknown_when_dem_missing(self) -> None:
        elements = [
            {
                "type": "way",
                "id": 201,
                "tags": {"leisure": "park", "name": "Riverbank Park"},
                "geometry": [
                    {"lat": 27.2269, "lon": 79.0271},
                    {"lat": 27.2269, "lon": 79.0276},
                    {"lat": 27.2273, "lon": 79.0276},
                    {"lat": 27.2273, "lon": 79.0271},
                ],
            }
        ]
        req = HybridSafeLocationRequest(
            latitude=27.2270, longitude=79.0270, disaster_type="FLOOD", min_candidates=1
        )
        resp_no_dem = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: b"",
                osm_elements_override=(elements, "AVAILABLE"),
                dem_fetcher_override=lambda pts: {},
            )
        )
        self.assertEqual(resp_no_dem.dem_status, "DEM_UNAVAILABLE")
        self.assertEqual(resp_no_dem.candidates[0].elevation_status, "UNKNOWN")
        self.assertEqual(resp_no_dem.candidates[0].assessment_status, "INSUFFICIENT_DATA")

        # With DEM available and higher elevation
        resp_with_dem = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: b"",
                osm_elements_override=(elements, "AVAILABLE"),
                dem_fetcher_override=lambda pts: {p: (155.0 if i > 0 else 150.0) for i, p in enumerate(pts)},
            )
        )
        self.assertEqual(resp_with_dem.dem_status, "AVAILABLE")
        self.assertEqual(resp_with_dem.candidates[0].assessment_status, "CONDITIONALLY_SUITABLE")

    def test_f_all_sources_unavailable_no_fabricated_locations(self) -> None:
        req = HybridSafeLocationRequest(latitude=27.227, longitude=79.027, disaster_type="EARTHQUAKE")
        resp = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: b"",
                osm_elements_override=([], "OSM_UNAVAILABLE"),
            )
        )
        self.assertEqual(resp.total_candidates, 0)
        self.assertEqual(resp.candidates, [])
        self.assertEqual(resp.status, "INSUFFICIENT_DATA")
        self.assertEqual(resp.imagery_metadata.status, "IMAGERY_UNAVAILABLE")

    def test_road_assessment_and_farmland_exclusion(self) -> None:
        elements = [
            {
                "type": "way",
                "id": 301,
                "tags": {"highway": "primary", "name": "Wide Avenue", "width": "16", "surface": "asphalt"},
                "geometry": [{"lat": 27.2271, "lon": 79.0268}, {"lat": 27.2271, "lon": 79.0272}],
            },
            {
                "type": "way",
                "id": 302,
                "tags": {"highway": "residential", "name": "Narrow Lane"},
                "geometry": [{"lat": 27.2272, "lon": 79.0269}, {"lat": 27.2272, "lon": 79.0271}],
            },
            {
                "type": "way",
                "id": 303,
                "tags": {"landuse": "farmland", "name": "Private Farm"},
                "geometry": [
                    {"lat": 27.2268, "lon": 79.0271},
                    {"lat": 27.2268, "lon": 79.0276},
                    {"lat": 27.2273, "lon": 79.0276},
                    {"lat": 27.2273, "lon": 79.0271},
                ],
            },
        ]
        req = HybridSafeLocationRequest(
            latitude=27.2270, longitude=79.0270, disaster_type="EARTHQUAKE", min_candidates=1
        )
        resp = asyncio.run(
            discover_hybrid_safe_locations(
                req,
                tile_fetcher=lambda z, x, y: b"",
                osm_elements_override=(elements, "AVAILABLE"),
            )
        )
        names = [c.name for c in resp.candidates]
        self.assertIn("Wide Avenue", names)
        self.assertIn("Narrow Lane", names)
        self.assertNotIn("Private Farm", names)
        wide = next(c for c in resp.candidates if c.name == "Wide Avenue")
        narrow = next(c for c in resp.candidates if c.name == "Narrow Lane")
        self.assertEqual(wide.assessment_status, "CONDITIONALLY_SUITABLE")
        self.assertEqual(narrow.assessment_status, "INSUFFICIENT_DATA")
        self.assertEqual(narrow.road_width_m, "UNKNOWN")
        self.assertGreaterEqual(resp.diagnostics.candidates_rejected, 1)

    def test_openapi_routes_preserve_hospital_nearby_and_register_discover(self) -> None:
        paths = app.openapi().get("paths", {})
        self.assertIn("/api/v1/safe-locations/nearby", paths)
        self.assertIn("/api/v1/safe-locations/discover", paths)
        self.assertIn("post", paths["/api/v1/safe-locations/discover"])
        self.assertIn("get", paths["/api/v1/safe-locations/discover"])


if __name__ == "__main__":
    unittest.main()
