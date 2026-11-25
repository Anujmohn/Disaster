from __future__ import annotations

import unittest
import uuid
from datetime import datetime, timezone
from decimal import Decimal

from app.models.core import CrisisEvent, DataSource
from app.models.enums import (
    CrisisStatus,
    CrisisType,
    DesignationStatus,
    RiskLevel,
    RouteSafetyStatus,
    StructuralCondition,
    VerificationStatus,
)
from app.models.recommendations import (
    LocationAssessment,
    RecommendationRun,
    SafeLocation,
    SafeLocationSourceReference,
)
from app.schemas.disaster_search import UserLocation
from app.schemas.recommendation import (
    AssessRecommendationsResponse,
    LocationSuitabilityAssessmentItem,
    StageDiagnostics,
    SuitabilityClassification,
)
from app.schemas.route import (
    CalculateRouteResponse,
    GeoJSONLineString,
    RouteClassification,
    RouteDetailItem,
)
from app.schemas.unified_recommendation import (
    UnifiedRecommendationResponse,
    UnifiedRecommendationStatus,
)
from app.services.recommendation_engine import (
    assess_safe_locations_suitability,
    evaluate_candidate_suitability,
    calculate_specification_points,
    extract_location_coordinates,
)
from app.services.unified_recommendation import generate_unified_recommendations


class RecommendationPipeline9ScenariosTests(unittest.TestCase):
    """
    Exhaustive verification of the recommendation pipeline across 9 real-world execution scenarios:
    1. Earthquake in urban area (Delhi).
    2. Earthquake in hilly area (Dehradun).
    3. Flood in river basin (Patna).
    4. Flood in coastal area (Mumbai/Kolkata).
    5. Cyclone in coastal beach area (Puri).
    6. Cyclone in inland area (Bhubaneswar).
    7. Location with full geographical data available.
    8. Location with partial data (elevation missing, capacity unknown).
    9. Remote location with zero initial candidates (progressive radius expansion).
    """

    def setUp(self) -> None:
        self.now = datetime.now(timezone.utc)
        self.mock_osrm_available = lambda **kwargs: (
            [
                {
                    "geometry": {
                        "type": "LineString",
                        "coordinates": [[77.20, 28.61], [77.21, 28.62]],
                    },
                    "distance": 2500.0,
                    "duration": 360.0,
                }
            ],
            None,
        )

    def _create_mock_session(self, locations: list[SafeLocation], active_events: list[CrisisEvent] | None = None):
        """Creates a mock SQLAlchemy session returning the provided locations and events."""
        from unittest.mock import MagicMock

        session = MagicMock()
        # count query
        session.scalar.return_value = len(locations)

        # tier / distance query mock returns (SafeLocation, distance_meters)
        def exec_side_effect(query):
            mock_res = MagicMock()
            rows = []
            for idx, loc in enumerate(locations):
                lat, lon = extract_location_coordinates(loc)
                # mock distance 500m + 500m * idx
                dist_m = 500.0 + idx * 500.0
                rows.append((loc, dist_m))
            mock_res.all.return_value = rows
            return mock_res

        session.execute.side_effect = exec_side_effect

        # scalars query (for active events)
        mock_scalars = MagicMock()
        mock_scalars.all.return_value = active_events or []
        mock_scalars.first.return_value = active_events[0] if active_events else None
        session.scalars.return_value = mock_scalars

        # session.get for location assessment
        session.get.return_value = None
        return session

    def test_scenario_1_earthquake_urban_delhi(self) -> None:
        """Scenario 1: Earthquake in urban area (Delhi) - prioritizes open parks/grounds over buildings."""
        loc_park = SafeLocation(
            id=uuid.uuid4(),
            name="Central Park Connaught Place",
            category="PARK",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            capacity=10000,
            location=None,
        )
        loc_bldg = SafeLocation(
            id=uuid.uuid4(),
            name="Commercial High-Rise",
            category="COMMERCIAL_BUILDING",
            designation_status=DesignationStatus.POTENTIAL,
            verification_status=VerificationStatus.UNVERIFIED,
            structural_condition=None,
            location=None,
        )
        session = self._create_mock_session([loc_park, loc_bldg])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=28.6139,
            longitude=77.2090,
            disaster_type=CrisisType.EARTHQUAKE,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        self.assertGreaterEqual(resp.total_candidates_assessed, 2)
        self.assertIsNotNone(resp.diagnostics)
        # Park should be suitable with evidence
        park_item = next(it for it in resp.assessed_locations if it.category == "PARK")
        self.assertEqual(park_item.suitability_classification, SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE)
        self.assertTrue(any("open space" in ev.lower() or "clearance" in ev.lower() for ev in park_item.supporting_evidence))

    def test_scenario_2_earthquake_hilly_dehradun(self) -> None:
        """Scenario 2: Earthquake in hilly area (Dehradun) - assesses rockfall/slope risks."""
        loc_ground = SafeLocation(
            id=uuid.uuid4(),
            name="Parade Ground Dehradun",
            category="OPEN_GROUND",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            capacity=15000,
            location=None,
        )
        session = self._create_mock_session([loc_ground])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=30.3165,
            longitude=78.0322,
            disaster_type=CrisisType.EARTHQUAKE,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        self.assertEqual(resp.total_candidates_assessed, 1)
        item = resp.assessed_locations[0]
        # In hilly terrain, verified open space receives conditional suitability with slope verification
        self.assertEqual(item.suitability_classification, SuitabilityClassification.CONDITIONALLY_SUITABLE)
        self.assertTrue(any("hilly" in ev.lower() or "rockfall" in ev.lower() for ev in item.supporting_evidence + item.warnings))

    def test_scenario_3_flood_river_basin_patna(self) -> None:
        """Scenario 3: Flood in river basin (Patna) - prioritizes elevated structures/flyovers above low ground."""
        loc_flyover = SafeLocation(
            id=uuid.uuid4(),
            name="AIIMS Elevated Corridor / Flyover",
            category="FLYOVER",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            location=None,
        )
        loc_low_park = SafeLocation(
            id=uuid.uuid4(),
            name="Gandhi Maidan Lowland Lawn",
            category="PARK",
            designation_status=DesignationStatus.POTENTIAL,
            verification_status=VerificationStatus.UNVERIFIED,
            location=None,
        )
        session = self._create_mock_session([loc_flyover, loc_low_park])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=25.5941,
            longitude=85.1376,
            disaster_type=CrisisType.FLOOD,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        self.assertIsNotNone(resp.diagnostics)
        flyover_item = next(it for it in resp.assessed_locations if it.category == "FLYOVER")
        park_item = next(it for it in resp.assessed_locations if it.category == "PARK")

        # Flyover provides vertical refuge
        self.assertEqual(flyover_item.suitability_classification, SuitabilityClassification.CONDITIONALLY_SUITABLE)
        self.assertTrue(any("elevated structure" in ev.lower() or "flyover" in ev.lower() for ev in flyover_item.supporting_evidence))

        # Low-lying park in floodplain is not recommended
        self.assertEqual(park_item.suitability_classification, SuitabilityClassification.NOT_RECOMMENDED)
        self.assertTrue(any("retention" in w.lower() or "runoff" in w.lower() or "low-lying" in w.lower() for w in park_item.warnings + park_item.identified_hazards))

    def test_scenario_4_flood_coastal_mumbai(self) -> None:
        """Scenario 4: Flood in coastal area (Mumbai) - checks coastal drainage gradient and storm surge exposure."""
        loc_bldg = SafeLocation(
            id=uuid.uuid4(),
            name="Bandra Civic Centre",
            category="COMMUNITY_CENTRE",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            location=None,
        )
        session = self._create_mock_session([loc_bldg])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=19.0760,
            longitude=72.8777,
            disaster_type=CrisisType.FLOOD,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        item = resp.assessed_locations[0]
        self.assertIn(item.suitability_classification, (SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE, SuitabilityClassification.CONDITIONALLY_SUITABLE))
        self.assertTrue(any("height from sea level" in ev.lower() or "elevation" in ev.lower() for ev in item.supporting_evidence))

    def test_scenario_5_cyclone_coastal_beach_puri(self) -> None:
        """Scenario 5: Cyclone in coastal beach area (Puri) - strictly excludes coastal open beach."""
        beach_ground = SafeLocation(
            id=uuid.uuid4(),
            name="Golden Beach Open Ground",
            category="OPEN_GROUND",
            designation_status=DesignationStatus.POTENTIAL,
            verification_status=VerificationStatus.UNVERIFIED,
            location=None,
        )
        cyclone_shelter = SafeLocation(
            id=uuid.uuid4(),
            name="Puri District Multi-Purpose Cyclone Shelter",
            category="OFFICIAL_CYCLONE_SHELTER",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            location=None,
        )
        # Active cyclone event
        active_cyc = CrisisEvent(
            id=uuid.uuid4(),
            crisis_type=CrisisType.CYCLONE,
            status=CrisisStatus.ACTIVE,
            severity=RiskLevel.CRITICAL,
            title="Very Severe Cyclonic Storm",
        )
        session = self._create_mock_session([beach_ground, cyclone_shelter], [active_cyc])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=19.8135,
            longitude=85.8312,
            disaster_type=CrisisType.CYCLONE,
            crisis_event_id=active_cyc.id,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        beach_item = next(it for it in resp.assessed_locations if it.name == "Golden Beach Open Ground")
        shelter_item = next(it for it in resp.assessed_locations if it.name == "Puri District Multi-Purpose Cyclone Shelter")

        # Open coastal beach in active cyclone is hard-excluded
        self.assertTrue(beach_item.hard_exclusion_status)
        self.assertEqual(beach_item.suitability_classification, SuitabilityClassification.EXCLUDED)
        self.assertTrue(any("storm surge" in r.lower() or "surge" in r.lower() for r in beach_item.hard_exclusion_reasons))

        # Stilt shelter is evidence-backed
        self.assertEqual(shelter_item.suitability_classification, SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE)
        self.assertTrue(any("stilt" in ev.lower() or "cyclone shelter" in ev.lower() for ev in shelter_item.supporting_evidence))

    def test_scenario_6_cyclone_inland_cuttack(self) -> None:
        """Scenario 6: Cyclone in inland area (Cuttack) - shields from storm surge, prioritizes institutional buildings."""
        loc_school = SafeLocation(
            id=uuid.uuid4(),
            name="Cuttack Government High School",
            category="SCHOOL",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            location=None,
        )
        session = self._create_mock_session([loc_school])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=20.4625,
            longitude=85.8828,
            disaster_type=CrisisType.CYCLONE,
            search_radius_km=10.0,
        )

        self.assertTrue(resp.success)
        item = resp.assessed_locations[0]
        self.assertEqual(item.suitability_classification, SuitabilityClassification.CONDITIONALLY_SUITABLE)
        self.assertTrue(any("inland" in ev.lower() or "shielded" in ev.lower() for ev in item.supporting_evidence))

    def test_scenario_7_full_geographical_data_available(self) -> None:
        """Scenario 7: Candidate with full geographical data (capacity, verified, accessibility) achieves high completeness."""
        loc_full = SafeLocation(
            id=uuid.uuid4(),
            name="Major Civic Emergency Stadium",
            category="OPEN_GROUND",
            designation_status=DesignationStatus.OFFICIAL,
            verification_status=VerificationStatus.VERIFIED,
            capacity=25000,
            location=None,
        )
        session = self._create_mock_session([loc_full])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=28.6139,
            longitude=77.2090,
            disaster_type=CrisisType.EARTHQUAKE,
            search_radius_km=5.0,
        )

        self.assertTrue(resp.success)
        item = resp.assessed_locations[0]
        self.assertEqual(item.suitability_classification, SuitabilityClassification.SUITABILITY_SUPPORTED_BY_EVIDENCE)
        self.assertGreaterEqual(item.data_completeness or 0.0, 0.8)
        self.assertGreaterEqual(item.total_points or 0.0, 75.0)

    def test_scenario_8_partial_data_does_not_collapse_to_insufficient_data(self) -> None:
        """Scenario 8: Candidate with missing capacity, unknown condition evaluates conditionally without collapsing."""
        loc_partial = SafeLocation(
            id=uuid.uuid4(),
            name="Community Primary Health Centre",
            category="HOSPITAL",
            designation_status=DesignationStatus.POTENTIAL,
            verification_status=VerificationStatus.UNVERIFIED,
            capacity=None,
            structural_condition=None,
            location=None,
        )
        session = self._create_mock_session([loc_partial])

        resp = assess_safe_locations_suitability(
            session=session,
            latitude=28.6139,
            longitude=77.2090,
            disaster_type=CrisisType.EARTHQUAKE,
            search_radius_km=5.0,
        )

        self.assertTrue(resp.success)
        self.assertEqual(resp.total_candidates_assessed, 1)
        item = resp.assessed_locations[0]
        # Must evaluate as CONDITIONALLY_SUITABLE, NOT INSUFFICIENT_DATA
        self.assertEqual(item.suitability_classification, SuitabilityClassification.CONDITIONALLY_SUITABLE)
        self.assertTrue(any("unverified" in w.lower() or "missing" in m.lower() for w in item.warnings for m in item.missing_information))
        # Total insufficient data count in response should be 0 because candidate was assessed
        self.assertEqual(resp.total_insufficient_data, 0)

    def test_scenario_9_remote_location_progressive_radius_expansion(self) -> None:
        """Scenario 9: Remote location with 0 candidates triggers progressive scan and returns NO_CANDIDATES_FOUND with diagnostics."""
        session = self._create_mock_session([])

        resp = generate_unified_recommendations(
            session=session,
            latitude=15.0,
            longitude=75.0,
            disaster_type=CrisisType.FLOOD,
            search_radius_km=25.0,
            osrm_client_fn=self.mock_osrm_available,
        )

        self.assertTrue(resp.success)
        # Status must be NO_CANDIDATES_FOUND (not INSUFFICIENT_DATA)
        self.assertEqual(resp.status, UnifiedRecommendationStatus.NO_CANDIDATES_FOUND)
        self.assertEqual(resp.total_candidates_found, 0)
        self.assertEqual(len(resp.recommendations), 0)
        self.assertIsNotNone(resp.diagnostics)
        self.assertGreater(len(resp.diagnostics.search_radii_attempted_m), 1)
        self.assertTrue(any("progressive" in u.lower() or "discovered" in u.lower() for u in resp.overall_uncertainties))


if __name__ == "__main__":
    unittest.main()
