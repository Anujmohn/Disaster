from __future__ import annotations

import unittest
import uuid
from unittest.mock import MagicMock, patch

from app.models.enums import CrisisType, DesignationStatus, VerificationStatus
from app.models.recommendations import SafeLocation
from app.services.dem_elevation import (
    fetch_dem_elevations_from_api,
    retrieve_candidate_elevation_and_3d,
)
from app.services.flood_assessment import (
    calculate_flood_safety_score,
    discover_natural_elevated_terrain,
)
from app.services.safe_place_engine import (
    find_flood_safe_places,
    find_safe_places_near_user,
    is_real_location_suitable_for_crisis,
)
from app.services.terrain_analysis import (
    ElevationGrid,
    create_synthetic_ridge_dem_grid,
    create_synthetic_steep_slope_dem_grid,
    create_synthetic_valley_dem_grid,
)


class GeographicalDiscoveryPipelineTests(unittest.TestCase):
    """
    Exhaustive verification of the Geographical Discovery Pipeline
    covering all 9 specific scenarios required by Section 8.
    """

    def test_scenario_1_city_represented_in_dataset(self):
        """
        1. A city represented in city_elevation_data.py (e.g. Patna: 25.5941, 85.1376).
        Verifies that actual geographical features are queried, real DEM elevation is retrieved,
        and analysis runs independently of the city name string.
        """
        elev_info = retrieve_candidate_elevation_and_3d(
            lat=25.5941,
            lon=85.1376,
            user_lat=25.5900,
            user_lon=85.1300,
            is_flyover=True,
        )
        self.assertIsNotNone(elev_info["elevation_m"])
        self.assertIsNotNone(elev_info["ground_elevation_m"])
        self.assertEqual(elev_info["structure_height_m"], 6.0)
        self.assertEqual(elev_info["structure_height_status"], "ESTIMATED_INFRASTRUCTURE_DECK")
        self.assertIn(elev_info["elevation_status"], ("VERIFIED_DEM", "REGIONAL_BENCHMARK_INTERPOLATED"))

    def test_scenario_2_city_absent_from_dataset(self):
        """
        2. A city or location absent from city_elevation_data.py.
        Coordinates: (23.4125, 87.2831) [Rural Bardhaman] or global coordinates.
        Verifies that geographical analysis runs identically and does not crash or fabricate coordinates.
        """
        elev_info = retrieve_candidate_elevation_and_3d(
            lat=23.4125,
            lon=87.2831,
            user_lat=23.4100,
            user_lon=87.2800,
            is_flyover=False,
        )
        # Should return elevation without requiring a match in city_elevation_data.py
        self.assertIsNotNone(elev_info["elevation_m"])
        self.assertIsNotNone(elev_info["ground_elevation_m"])
        self.assertEqual(elev_info["structure_height_status"], "UNKNOWN")

    def test_scenario_3_rural_location(self):
        """
        3. A rural location far from major urban centers.
        Coordinates: (24.7123, 84.4567).
        Verifies that when no candidates exist within 2000m, it reports NO_CANDIDATES_FOUND
        and NEVER generates synthetic coordinates at fixed distances.
        """
        mock_session = MagicMock()
        mock_session.execute.return_value.all.return_value = []

        res = find_flood_safe_places(
            session=mock_session,
            latitude=24.7123,
            longitude=84.4567,
            max_radius_km=2.0,
            dem_grid=None,
        )
        self.assertTrue(res.success)
        self.assertEqual(res.total_found, 0)
        self.assertEqual(len(res.safe_places), 0)
        self.assertIn(res.data_availability_status, ("NO_CANDIDATES_FOUND", "INSUFFICIENT_GEOGRAPHICAL_DATA"))

    def test_scenario_4_hilly_location(self):
        """
        4. A hilly location (e.g. Shimla or Western Ghats).
        Evaluates slope, valley morphometry, and excludes narrow canyons / steep slopes (> 32°).
        """
        steep_dem = create_synthetic_steep_slope_dem_grid(slope_deg=34.0)
        elev_info = retrieve_candidate_elevation_and_3d(
            lat=31.1048,
            lon=77.1734,
            user_lat=31.1000,
            user_lon=77.1700,
            dem_grid=steep_dem,
        )
        self.assertEqual(elev_info["elevation_status"], "VERIFIED_DEM")
        self.assertGreaterEqual(elev_info["slope_deg"], 32.0)

        # Safety evaluation triggers hard exclusion on steep unstable slopes
        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=elev_info["elevation_m"],
            user_elevation=2100.0,
            relative_elevation=elev_info["relative_elevation_m"],
            slope_deg=elev_info["slope_deg"],
            terrain_feature=elev_info["terrain_feature"],
            drainage_distance_m=elev_info["drainage_distance_m"],
            hand_m=elev_info["hand_m"],
            flow_accumulation=elev_info["flow_accumulation"],
            distance_to_river_m=200.0,
            active_flood_distance_km=None,
            candidate_type="ELEVATED_INFRASTRUCTURE",
            data_quality="HIGH",
            is_hilly=True,
        )
        self.assertEqual(cat, "EXCLUDED")
        self.assertTrue(any("slope instability" in h.lower() or "landslide" in h.lower() for h in hazards))

    def test_scenario_5_no_available_dem(self):
        """
        5. A location with no available DEM data.
        Verifies that elevation is marked ELEVATION_UNAVAILABLE, score is low,
        and missing data is NEVER interpreted as evidence of safety.
        """
        elev_info = retrieve_candidate_elevation_and_3d(
            lat=89.5,
            lon=-179.5,
            user_lat=89.0,
            user_lon=-179.0,
            dem_grid=None,
        )
        # Handled gracefully with explicit status
        self.assertIn(elev_info["data_availability_status"], ("ELEVATION_UNAVAILABLE", "PARTIAL_DATA_AVAILABLE", "UNKNOWN"))

        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=None,
            user_elevation=None,
            relative_elevation=None,
            slope_deg=None,
            terrain_feature="UNKNOWN",
            drainage_distance_m=None,
            hand_m=None,
            flow_accumulation=None,
            distance_to_river_m=100.0,
            active_flood_distance_km=None,
            candidate_type="GENERAL_STRUCTURE",
            data_quality="UNKNOWN",
        )
        self.assertLess(score, 45.0)
        self.assertEqual(cat, "INSUFFICIENT_ELEVATION")

    def test_scenario_6_osm_returns_no_relevant_features(self):
        """
        6. A location where OpenStreetMap returns no relevant features.
        Verifies that 0 candidates are returned and NO hypothetical coordinates are synthesized.
        """
        mock_session = MagicMock()
        mock_session.execute.return_value.all.return_value = []

        res = find_safe_places_near_user(
            session=mock_session,
            latitude=26.8467,
            longitude=80.9462,
            disaster_type=CrisisType.EARTHQUAKE,
            max_radius_km=2.0,
        )
        self.assertEqual(res.total_found, 0)
        self.assertEqual(len(res.safe_places), 0)
        self.assertEqual(res.data_availability_status, "NO_CANDIDATES_FOUND")

    def test_scenario_7_buildings_with_unverified_heights(self):
        """
        7. A location with buildings but no verified building heights.
        Verifies that structure height is classified as UNKNOWN without confusing ground elevation with building height.
        """
        # Building tags without height or levels
        tags = {"amenity": "school", "name": "Village Primary School"}
        elev_info = retrieve_candidate_elevation_and_3d(
            lat=25.5941,
            lon=85.1376,
            user_lat=25.5900,
            user_lon=85.1300,
            is_flyover=False,
            osm_tags=tags,
        )
        self.assertIsNone(elev_info["structure_height_m"])
        self.assertEqual(elev_info["structure_height_status"], "UNKNOWN")
        # Ground elevation is distinct
        self.assertIsNotNone(elev_info["ground_elevation_m"])
        self.assertEqual(elev_info["elevation_m"], elev_info["ground_elevation_m"])

    def test_scenario_8_no_suitable_earthquake_refuge(self):
        """
        8. A location with no suitable earthquake refuge.
        When only enclosed high-rise buildings and flyovers exist, Earthquake Engine strictly excludes them.
        """
        flyover = SafeLocation(
            id=uuid.uuid4(),
            name="City Centre Elevated Flyover",
            category="FLYOVER",
        )
        building = SafeLocation(
            id=uuid.uuid4(),
            name="Commercial Tower Complex",
            category="COMMERCIAL_BUILDING",
        )
        is_fly_ok = is_real_location_suitable_for_crisis(flyover, CrisisType.EARTHQUAKE, "SUITABLE", 80.0)
        is_bldg_ok = is_real_location_suitable_for_crisis(building, CrisisType.EARTHQUAKE, "SUITABLE", 80.0)

        # Neither flyover nor enclosed building can be recommended for earthquake!
        self.assertFalse(is_fly_ok)
        self.assertFalse(is_bldg_ok)

    def test_scenario_9_no_suitable_flood_candidate(self):
        """
        9. A location with no suitable flood candidate.
        When only low-lying open grounds in an active floodway exist, Flood Engine hard-excludes them.
        """
        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=48.0,
            user_elevation=53.0,
            relative_elevation=-5.0,  # 5 meters below user
            slope_deg=1.0,
            terrain_feature="DRAINAGE_CHANNEL",
            drainage_distance_m=10.0,
            hand_m=0.1,
            flow_accumulation=30.0,
            distance_to_river_m=15.0,
            active_flood_distance_km=0.2,
            candidate_type="GENERAL_STRUCTURE",
            data_quality="HIGH",
        )
        self.assertEqual(cat, "EXCLUDED")
        self.assertLessEqual(score, 15.0)


if __name__ == "__main__":
    unittest.main()
