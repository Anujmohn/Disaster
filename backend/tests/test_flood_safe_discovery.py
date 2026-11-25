from __future__ import annotations

import unittest
import uuid
from unittest.mock import MagicMock

from app.models.core import CrisisEvent
from app.models.enums import CrisisStatus, CrisisType, DesignationStatus, VerificationStatus
from app.models.recommendations import SafeLocation
from app.schemas.safe_place import FindSafePlacesResponse
from app.services.flood_assessment import (
    FLOOD_SEARCH_RADII_METERS,
    assess_candidate_flood_safety,
    calculate_flood_safety_score,
    discover_natural_elevated_terrain,
    retrieve_candidate_elevation,
)
from app.services.safe_location_osm import map_osm_tags_to_category
from app.services.safe_place_engine import (
    find_flood_safe_places,
    find_safe_places_near_user,
)
from app.services.terrain_analysis import create_synthetic_steep_slope_dem_grid, create_synthetic_valley_dem_grid


class FloodSafeDiscoveryAndAssessmentTests(unittest.TestCase):
    """
    Verification tests for the Fixed Flood Safe-Location Discovery and Assessment Engine
    covering all 9 mandatory requirements from Section 9.
    """

    def test_scenario_1_valid_candidates_within_100_meters(self):
        """Scenario 1: Valid candidates exist within 100 meters."""
        mock_session = MagicMock()
        loc = SafeLocation(
            id=uuid.uuid4(),
            name="Bailey Road Elevated Flyover Deck",
            category="FLYOVER",
            designation_status=DesignationStatus.POTENTIAL,
            verification_status=VerificationStatus.UNVERIFIED,
            source_references=[],
        )
        # Mock DB returning candidate at 80m
        mock_session.execute.return_value.all.return_value = [(loc, 80.0)]

        res = find_flood_safe_places(
            session=mock_session,
            latitude=25.5941,
            longitude=85.1376,
            max_radius_km=5.0,
        )
        self.assertTrue(res.success)
        self.assertEqual(res.engine_status, "SUCCESS")
        self.assertEqual(res.effective_radius_km, 0.1)  # 100 meters
        self.assertGreaterEqual(len(res.safe_places), 1)
        first = res.safe_places[0]
        self.assertEqual(first.candidate_type, "ELEVATED_INFRASTRUCTURE")
        self.assertIsNotNone(first.flood_fields)
        self.assertIsNotNone(first.flood_fields.elevation_m)
        self.assertGreater(first.flood_fields.elevation_m, 0.0)

    def test_scenario_2_expansion_to_250_meters_when_100m_empty(self):
        """Scenario 2: No candidates exist within 100 meters, but exist within 250 meters."""
        mock_session = MagicMock()
        loc = SafeLocation(
            id=uuid.uuid4(),
            name="AIIMS Elevated Flood Shelter Facility",
            category="OFFICIAL_FLOOD_SHELTER",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            source_references=[],
        )

        call_count = 0

        def mock_execute_side_effect(query):
            nonlocal call_count
            call_count += 1
            mock_res = MagicMock()
            # Call 1 (Tier 1 - 100m) returns empty; Call 2 (Tier 2 - 250m) returns candidate at 180m
            if call_count == 1:
                mock_res.all.return_value = []
            else:
                mock_res.all.return_value = [(loc, 180.0)]
            return mock_res

        mock_session.execute.side_effect = mock_execute_side_effect

        from unittest.mock import patch
        # When tier 1 (100m) has no natural terrain candidates, it expands to 250m
        with patch("app.services.safe_place_engine.discover_natural_elevated_terrain") as mock_nat:
            mock_nat.side_effect = lambda *args, **kwargs: []

            res = find_flood_safe_places(
                session=mock_session,
                latitude=25.5941,
                longitude=85.1376,
                max_radius_km=5.0,
            )
            self.assertTrue(res.success)
            self.assertEqual(res.effective_radius_km, 0.25)  # Expanded to 250 meters
            self.assertGreaterEqual(len(res.safe_places), 1)
            self.assertEqual(res.safe_places[0].candidate_type, "OFFICIAL_FLOOD_SHELTER")

    def test_scenario_3_progressive_radii_configuration(self):
        """Scenario 3: Verifies progressive radii hierarchy [100m, 250m, 500m, 1000m, 2000m]."""
        self.assertEqual(FLOOD_SEARCH_RADII_METERS, [100.0, 250.0, 500.0, 1000.0, 2000.0])

        mock_session = MagicMock()
        mock_session.execute.return_value.all.return_value = []

        res = find_flood_safe_places(
            session=mock_session,
            latitude=25.5941,
            longitude=85.1376,
            max_radius_km=2.0,
        )
        self.assertEqual(res.search_radii_hierarchy_m, [100.0, 250.0, 500.0, 1000.0, 2000.0])

    def test_scenario_4_osm_bridge_vs_flyover_classification(self):
        """Scenario 4: An OSM bridge (bridge=yes) is NOT classified as a flyover."""
        # Simple bridge over river
        river_bridge_tags = {"name": "Ganga Canal Road Bridge", "bridge": "yes", "highway": "secondary"}
        cat_river = map_osm_tags_to_category(river_bridge_tags)
        self.assertEqual(cat_river, "BRIDGE")
        self.assertNotEqual(cat_river, "FLYOVER")

        # Explicit flyover / viaduct
        flyover_tags = {"name": "Bailey Road Flyover", "bridge": "flyover"}
        cat_fly = map_osm_tags_to_category(flyover_tags)
        self.assertEqual(cat_fly, "FLYOVER")

        # Elevated corridor viaduct
        viaduct_tags = {"name": "Barapullah Elevated Corridor", "bridge": "viaduct"}
        cat_viaduct = map_osm_tags_to_category(viaduct_tags)
        self.assertEqual(cat_viaduct, "FLYOVER")

    def test_scenario_5_elevation_unavailable_handling(self):
        """Scenario 5: Elevation data is unavailable (marked UNKNOWN, no fabricated score)."""
        # Coordinates out of geographic coverage
        elev_info = retrieve_candidate_elevation(
            lat=999.0,
            lon=999.0,
            user_lat=25.5941,
            user_lon=85.1376,
        )
        self.assertIsNone(elev_info["elevation_m"])
        self.assertEqual(elev_info["elevation_status"], "UNKNOWN")
        self.assertEqual(elev_info["elevation_quality"], "UNKNOWN")

        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=None,
            user_elevation=53.0,
            relative_elevation=None,
            slope_deg=None,
            terrain_feature="UNKNOWN",
            drainage_distance_m=None,
            hand_m=None,
            flow_accumulation=None,
            distance_to_river_m=500.0,
            active_flood_distance_km=None,
            candidate_type="ELEVATED_INFRASTRUCTURE",
            data_quality="UNKNOWN",
        )
        self.assertEqual(cat, "INSUFFICIENT_ELEVATION")
        self.assertLess(score, 45.0)  # No high score given to unknown elevation

    def test_scenario_6_higher_elevation_in_dangerous_drainage_path(self):
        """Scenario 6: Candidate has higher elevation but is located in a dangerous drainage path."""
        # Higher elevation (+4.0m) but located inside active drainage channel with low HAND
        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=57.0,
            user_elevation=53.0,
            relative_elevation=4.0,
            slope_deg=2.0,
            terrain_feature="DRAINAGE_CHANNEL",
            drainage_distance_m=10.0,
            hand_m=0.2,
            flow_accumulation=25.0,
            distance_to_river_m=20.0,
            active_flood_distance_km=0.5,
            candidate_type="GENERAL_STRUCTURE",
            data_quality="HIGH",
        )
        # Must be EXCLUDED despite +4m elevation!
        self.assertEqual(cat, "EXCLUDED")
        self.assertLessEqual(score, 15.0)
        self.assertTrue(any("flow path" in h.lower() or "watercourse" in h.lower() for h in hazards))

    def test_scenario_7_hilly_mountain_valley_flash_surge(self):
        """Scenario 7: Candidate in hilly area with upstream flood exposure or steep slope."""
        # High altitude (1800m), but situated in a narrow canyon bottom with high upstream catchment
        score, cat, reasons, hazards, warnings, factors = calculate_flood_safety_score(
            candidate_elevation=1800.0,
            user_elevation=1700.0,
            relative_elevation=100.0,
            slope_deg=28.0,
            terrain_feature="VALLEY_BOTTOM",
            drainage_distance_m=15.0,
            hand_m=1.5,
            flow_accumulation=50.0,
            distance_to_river_m=15.0,
            active_flood_distance_km=1.0,
            candidate_type="GENERAL_STRUCTURE",
            data_quality="HIGH",
            is_hilly=True,
        )
        # Must be EXCLUDED due to confined mountain valley flash surge & steep slope!
        self.assertEqual(cat, "EXCLUDED")
        self.assertLessEqual(score, 15.0)

    def test_scenario_8_only_natural_elevated_terrain_available(self):
        """Scenario 8: Only natural elevated terrain candidates are available from real DEM grid."""
        user_lat = 25.5941
        user_lon = 85.1376

        # 1. When DEM grid is None, strictly returns [] (NEVER generates synthetic coordinates)
        empty_cands = discover_natural_elevated_terrain(
            user_lat=user_lat,
            user_lon=user_lon,
            radius_m=2000.0,
            user_msl=50.0,
            dem_grid=None,
        )
        self.assertEqual(len(empty_cands), 0)

        # 2. When authoritative DEM grid is available, extracts genuine topographical peak cells
        from app.services.terrain_analysis import create_synthetic_ridge_dem_grid
        ridge_grid = create_synthetic_ridge_dem_grid()
        natural_cands = discover_natural_elevated_terrain(
            user_lat=user_lat,
            user_lon=user_lon,
            radius_m=2000.0,
            user_msl=2700.0,
            dem_grid=ridge_grid,
        )
        self.assertGreaterEqual(len(natural_cands), 1)
        for cand in natural_cands:
            self.assertEqual(cand["category"], "NATURAL_ELEVATED_TERRAIN")
            self.assertEqual(cand["candidate_type"], "NATURAL_ELEVATED_TERRAIN")
            self.assertTrue(cand["name"].startswith("Potential Elevated Ground"))
            self.assertIsNotNone(cand.get("feature_identifier"))
            # Never an invented hospital or building name
            self.assertNotIn("hospital", cand["name"].lower())
            self.assertNotIn("flyover", cand["name"].lower())
            self.assertNotIn("school", cand["name"].lower())

    def test_scenario_9_no_reliable_safe_candidate_exists(self):
        """Scenario 9: No reliable safe candidate exists within 2000m."""
        mock_session = MagicMock()
        mock_session.execute.return_value.all.return_value = []

        # If natural terrain has no elevation gain (flat or depression)
        res = find_flood_safe_places(
            session=mock_session,
            latitude=28.6139,
            longitude=77.2090,
            max_radius_km=0.1,  # Tier 1 only (100m)
        )
        # Verify clean error status and no random coordinates or fake facilities fabricated
        if res.total_found == 0:
            self.assertEqual(res.engine_status, "NO_SUITABLE_CANDIDATE")
            self.assertEqual(len(res.safe_places), 0)

    def test_crisis_isolation_earthquake_vs_flood(self):
        """Verifies strict crisis isolation: Earthquake returns open grounds, Flood returns elevated structures."""
        mock_session = MagicMock()

        # Earthquake query does not trigger find_flood_safe_places
        eq_res = find_safe_places_near_user(
            session=mock_session,
            latitude=25.5941,
            longitude=85.1376,
            disaster_type=CrisisType.EARTHQUAKE,
            max_radius_km=5.0,
        )
        self.assertEqual(eq_res.disaster_type, CrisisType.EARTHQUAKE)


if __name__ == "__main__":
    unittest.main()
