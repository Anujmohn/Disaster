from __future__ import annotations

import asyncio
import unittest
from unittest.mock import patch

from fastapi import HTTPException

from app.api.v1.safe_locations import (
    check_safe_location_key,
    safe_locations_get,
    safe_locations_post,
    validate_india,
)
from app.config import settings
from app.main import app
from app.schemas.flood_safe import (
    CrisisLocation,
    FloodSafeLocationsResponse,
    Point,
    SafeLocationRequest,
)
from app.services.flood_safe_service import (
    bearing_deg,
    building_levels,
    build_query,
    classify,
    compass,
    fast_km,
    find_safe_locations,
    grid_points,
    haversine_km,
    in_india_bbox,
    min_dist_to_polyline_km,
    to_xy_km,
)


class FakeTestProviders:
    """Synthetic terrain & OSM provider for deterministic testing."""

    async def overpass(self, query: str) -> list[dict]:
        return [
            {
                "type": "node",
                "id": 1,
                "lat": 27.26,
                "lon": 79.06,
                "tags": {"amenity": "shelter", "name": "Cyclone Shelter"},
            },
            {
                "type": "way",
                "id": 2,
                "center": {"lat": 27.25, "lon": 79.05},
                "tags": {
                    "amenity": "school",
                    "name": "Govt High School",
                    "building:levels": "3",
                },
            },
            {
                "type": "way",
                "id": 3,
                "center": {"lat": 27.24, "lon": 79.04},
                "tags": {
                    "highway": "primary",
                    "bridge": "yes",
                    "layer": "1",
                    "name": "City Flyover",
                },
            },
            {
                "type": "way",
                "id": 4,
                "center": {"lat": 27.27, "lon": 79.07},
                "tags": {"leisure": "park", "name": "Community Ground"},
            },
            {
                "type": "node",
                "id": 5,
                "lat": 27.2251,
                "lon": 79.0151,
                "tags": {"amenity": "hospital", "name": "Inside Danger Zone Hospital"},
            },
            {
                "type": "node",
                "id": 6,
                "lat": 27.20,
                "lon": 79.00,
                "tags": {"amenity": "shelter", "shelter_type": "bus_stop"},
            },
        ]

    async def elevations(self, pts: list[tuple[float, float]]) -> list[float]:
        # Ground rises toward northeast; crisis point sits in low area
        return [150.0 + (la - 27.2) * 400.0 + (lo - 79.0) * 400.0 for la, lo in pts]

    async def route(
        self, a: tuple[float, float], b: tuple[float, float]
    ) -> dict:
        dist = haversine_km(a[0], a[1], b[0], b[1]) * 1.2
        return {
            "distance_km": dist,
            "duration_min": 10.0,
            "coords": [[a[1], a[0]], [b[1], b[0]]],
        }

    async def verify_country(self, lat: float, lon: float) -> bool:
        return in_india_bbox(lat, lon)


class FloodSafeApiTests(unittest.TestCase):
    def test_geospatial_math(self) -> None:
        # 1. India bounding box
        self.assertTrue(in_india_bbox(28.61, 77.21))  # Delhi
        self.assertTrue(in_india_bbox(19.07, 72.88))  # Mumbai
        self.assertFalse(in_india_bbox(51.5074, -0.1278))  # London
        self.assertFalse(in_india_bbox(40.7128, -74.0060))  # New York

        # 2. Haversine distance
        dist = haversine_km(28.61, 77.21, 19.07, 72.88)
        self.assertAlmostEqual(dist, 1150.0, delta=50.0)

        # 3. Bearing and Compass
        self.assertEqual(compass(0.0), "N")
        self.assertEqual(compass(90.0), "E")
        self.assertEqual(compass(180.0), "S")
        self.assertEqual(compass(270.0), "W")

        # 4. Local projection and fast km
        dx, dy = to_xy_km(20.0, 80.0, 20.01, 80.01)
        self.assertGreater(dx, 0.0)
        self.assertGreater(dy, 0.0)
        self.assertAlmostEqual(fast_km(20.0, 80.0, 20.01, 80.01), (dx**2 + dy**2) ** 0.5)

        # 5. Grid generation
        pts = grid_points(20.0, 80.0, radius_km=2.0, step_km=1.0)
        self.assertGreater(len(pts), 0)

        # 6. Polyline distance
        poly_dist = min_dist_to_polyline_km((0.0, 0.0), [[-1.0, 1.0], [1.0, 1.0]])
        self.assertIsNotNone(poly_dist)
        self.assertGreater(poly_dist, 100.0)

    def test_overpass_query_and_classification(self) -> None:
        q = build_query(20.0, 80.0, 3000, ["shelter", "public_building", "open_high_ground", "flyover"])
        self.assertIn("around:3000,20.0,80.0", q)
        self.assertIn("emergency", q)
        self.assertIn("bridge", q)

        # Classifications
        self.assertEqual(classify({"highway": "primary", "bridge": "yes", "layer": "1"}), ("flyover", "flyover / elevated road"))
        self.assertEqual(classify({"highway": "secondary", "bridge": "yes", "name": "Gandhi Flyover"}), ("flyover", "flyover / elevated road"))
        self.assertEqual(classify({"emergency": "disaster_shelter"}), ("shelter", "disaster shelter"))
        self.assertEqual(classify({"amenity": "hospital"}), ("public_building", "hospital"))
        self.assertEqual(classify({"leisure": "park"}), ("open_high_ground", "park"))
        self.assertEqual(classify({"amenity": "shelter", "shelter_type": "bus_stop"}), None)

        # Building levels
        self.assertEqual(building_levels({"building:levels": "4"}), 4.0)
        self.assertEqual(building_levels({"height": "16"}), 5.0)
        self.assertIsNone(building_levels({}))

    def test_pipeline_execution_and_safety_assessment(self) -> None:
        req = SafeLocationRequest(
            user_location=Point(lat=27.23, lon=79.02),
            crisis_location=CrisisLocation(lat=27.225, lon=79.015, radius_km=1.0),
            search_radius_km=5.0,
        )
        fake_prov = FakeTestProviders()
        out = asyncio.run(find_safe_locations(req, fake_prov))

        self.assertIn("recommendations", out)
        rec = out["recommendations"]

        # 1. Shelter recommended
        self.assertTrue(rec["shelters"])
        self.assertEqual(rec["shelters"][0]["name"], "Cyclone Shelter")

        # 2. Multi-storey building bonus & note
        pub = rec["public_buildings"][0]
        self.assertEqual(pub["building_levels"], 3.0)
        self.assertTrue(any("upper floors" in note for note in pub["notes"]))

        # 3. Flyover warnings
        fly = rec["flyovers"][0]
        self.assertTrue(any("HIGH RISK" in w for w in fly["warnings"]))

        # 4. Danger zone exclusion
        all_ranked_names = [c["name"] for c in out["ranked"]]
        self.assertNotIn("Inside Danger Zone Hospital", all_ranked_names)

        # 5. Best recommendation is not flyover
        self.assertIsNotNone(out["best_recommendation"])
        self.assertNotEqual(out["best_recommendation"]["category"], "flyover")

        # 6. Nearest safe candidate exists
        self.assertIsNotNone(out["nearest_safe"])

        # 7. User danger zone check: at 0.73km distance with 1.0km crisis radius, user is inside
        self.assertTrue(out["user_in_danger_zone"])
        self.assertTrue(any("INSIDE the danger zone" in w for w in out["warnings"]))

    def test_user_inside_danger_zone(self) -> None:
        req = SafeLocationRequest(
            user_location=Point(lat=27.226, lon=79.016),
            crisis_location=CrisisLocation(lat=27.225, lon=79.015, radius_km=1.0),
            search_radius_km=5.0,
        )
        fake_prov = FakeTestProviders()
        out = asyncio.run(find_safe_locations(req, fake_prov))
        self.assertTrue(out["user_in_danger_zone"])
        self.assertTrue(any("INSIDE the danger zone" in w for w in out["warnings"]))

    def test_route_entering_danger_zone_penalized(self) -> None:
        # Place user so straight line passes directly through crisis center (0, 0)
        class DirectDangerRouteProvider(FakeTestProviders):
            async def route(self, a, b):
                # Route passes right through crisis point (27.225, 79.015)
                return {
                    "distance_km": 4.0,
                    "duration_min": 8.0,
                    "coords": [[79.010, 27.220], [79.015, 27.225], [79.020, 27.230]],
                }

        req = SafeLocationRequest(
            user_location=Point(lat=27.220, lon=79.010),
            crisis_location=CrisisLocation(lat=27.225, lon=79.015, radius_km=1.0),
            search_radius_km=5.0,
        )
        out = asyncio.run(find_safe_locations(req, DirectDangerRouteProvider()))
        flat = out["ranked"]
        self.assertTrue(len(flat) > 0)
        # Verify route flagged as entering danger zone
        danger_routed = [c for c in flat if c["route"] and c["route"]["enters_danger_zone"]]
        self.assertTrue(len(danger_routed) > 0)
        self.assertTrue(any("danger zone" in w for w in danger_routed[0]["warnings"]))

    def test_openapi_schema_contains_endpoints(self) -> None:
        schema = app.openapi()
        paths = schema.get("paths", {})
        self.assertIn("/api/v1/safe-locations", paths)
        root_path = paths["/api/v1/safe-locations"]
        self.assertIn("post", root_path)
        self.assertIn("get", root_path)

    def test_api_handlers_post_and_get(self) -> None:
        req = SafeLocationRequest(
            user_location=Point(lat=27.23, lon=79.02),
            crisis_location=CrisisLocation(lat=27.225, lon=79.015, radius_km=1.0),
            search_radius_km=5.0,
        )
        fake_prov = FakeTestProviders()

        with patch("app.api.v1.safe_locations.get_default_providers", return_value=fake_prov):
            # Test POST handler
            res_post = asyncio.run(safe_locations_post(req))
            self.assertIsInstance(res_post, FloodSafeLocationsResponse)
            self.assertIsNotNone(res_post.best_recommendation)
            self.assertEqual(res_post.user_location["lat"], 27.23)

            # Test GET handler
            res_get = asyncio.run(
                safe_locations_get(
                    user_lat=27.23,
                    user_lon=79.02,
                    crisis_lat=27.225,
                    crisis_lon=79.015,
                    crisis_radius_km=1.0,
                    search_radius_km=5.0,
                )
            )
            self.assertIsInstance(res_get, FloodSafeLocationsResponse)
            self.assertEqual(res_get.crisis_location["radius_km"], 1.0)

    def test_validate_india_rejection(self) -> None:
        req_outside = SafeLocationRequest(
            user_location=Point(lat=51.5074, lon=-0.1278),  # London
            crisis_location=CrisisLocation(lat=27.225, lon=79.015, radius_km=1.0),
        )
        fake_prov = FakeTestProviders()
        with self.assertRaises(HTTPException) as ctx:
            asyncio.run(validate_india(req_outside, fake_prov))
        self.assertEqual(ctx.exception.status_code, 422)
        self.assertIn("outside India", ctx.exception.detail)

    def test_api_key_check(self) -> None:
        with patch.object(settings, "safe_location_api_key", "secret123"):
            # Invalid key raises 401
            with self.assertRaises(HTTPException) as ctx:
                check_safe_location_key("wrong_key")
            self.assertEqual(ctx.exception.status_code, 401)

            # None key raises 401
            with self.assertRaises(HTTPException) as ctx:
                check_safe_location_key(None)
            self.assertEqual(ctx.exception.status_code, 401)

            # Correct key passes without error
            check_safe_location_key("secret123")


if __name__ == "__main__":
    unittest.main()
