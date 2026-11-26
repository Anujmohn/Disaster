from __future__ import annotations

import unittest
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

from app.main import app
from app.models.enums import CrisisType
from app.schemas.ingestion import IngestionSyncResponse, SourceSyncSummary
from app.services import disaster_realtime_sync as sync_mod


class RealtimeDisasterSyncTests(unittest.TestCase):
    def setUp(self) -> None:
        for k in sync_mod._PROVIDER_STATE:
            sync_mod._PROVIDER_STATE[k]["last_attempt_at"] = None
            sync_mod._PROVIDER_STATE[k]["last_success_at"] = None
            sync_mod._PROVIDER_STATE[k]["last_error"] = None
            sync_mod._PROVIDER_STATE[k]["status"] = "INITIALIZING"
            sync_mod._PROVIDER_STATE[k]["events_synced"] = 0

    def test_sync_status_route_registered(self) -> None:
        paths = app.openapi().get("paths", {})
        self.assertIn("/api/v1/disasters/sync-status", paths)
        self.assertIn("get", paths["/api/v1/disasters/sync-status"])

    def test_freshness_reuse_and_provider_failure_isolation(self) -> None:
        db = MagicMock()
        ok_summary = SourceSyncSummary(
            status="ACTIVE",
            fetched=3,
            created=2,
            updated=1,
        )

        with (
            patch.object(sync_mod, "sync_earthquakes_from_ncs", side_effect=RuntimeError("NCS timeout")),
            patch.object(sync_mod, "sync_usgs_earthquakes_fallback", return_value=4) as mock_usgs,
            patch.object(sync_mod, "sync_sachet_flood_alerts", side_effect=RuntimeError("SACHET 503")),
            patch.object(sync_mod, "sync_sachet_cyclone_alerts", return_value=ok_summary),
            patch.object(sync_mod, "sync_imd_flood_data", return_value=ok_summary),
            patch.object(sync_mod, "sync_imd_cyclone_data", return_value=ok_summary),
        ):
            snap = sync_mod.ensure_fresh_disasters(db, force=True)
            self.assertEqual(snap["overall_status"], "LIVE")
            self.assertEqual(snap["providers"]["earthquake"]["active_provider"], "USGS_NEIC_FALLBACK")
            self.assertEqual(snap["providers"]["earthquake"]["events_synced"], 4)
            self.assertEqual(snap["providers"]["sachet"]["status"], "UNAVAILABLE")
            self.assertEqual(snap["providers"]["imd"]["status"], "LIVE")
            mock_usgs.assert_called_once()

            # Second immediate call within freshness window must NOT re-call USGS/IMD
            snap2 = sync_mod.ensure_fresh_disasters(db, disaster_types=[CrisisType.EARTHQUAKE])
            self.assertEqual(snap2["providers"]["earthquake"]["status"], "LIVE")
            mock_usgs.assert_called_once()

    def test_background_sync_start_stop(self) -> None:
        with patch.object(sync_mod, "ensure_fresh_disasters"):
            sync_mod.start_disaster_background_sync()
            self.assertIsNotNone(sync_mod._BG_THREAD)
            self.assertTrue(sync_mod._BG_THREAD.is_alive())
            sync_mod.stop_disaster_background_sync()
            self.assertIsNone(sync_mod._BG_THREAD)


if __name__ == "__main__":
    unittest.main()
