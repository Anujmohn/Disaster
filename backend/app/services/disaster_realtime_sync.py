"""
Real-Time Disaster Synchronization & Periodic Polling Service.

Coordinates freshness-aware on-demand synchronization (when users open the app)
and periodic background polling across authoritative Indian disaster sources:
- Earthquakes: National Centre for Seismology (NCS) + USGS fallback
- Flood & Cyclone Warnings: NDMA SACHET CAP/RSS
- District Nowcasts & Cyclone Tracks: IMD GeoServer WFS

Prevents overlapping syncs with per-provider locks and isolates provider failures.
"""

from __future__ import annotations

import json
import logging
import ssl
import threading
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.database.geometry import geojson_to_postgis
from app.database.session import SessionLocal
from app.models.core import CrisisEvent, CrisisSourceReport, DataSource
from app.models.enums import CrisisStatus, CrisisType, SeverityLevel
from app.services.cyclone_imd import sync_imd_cyclone_data
from app.services.cyclone_sachet import sync_sachet_cyclone_alerts
from app.services.earthquake_ingestion import (
    ACTIVE_EVENT_WINDOW,
    map_magnitude_to_severity,
    sync_earthquakes_from_ncs,
)
from app.services.flood_imd import sync_imd_flood_data
from app.services.flood_sachet import sync_sachet_flood_alerts
from app.services.source_registry import sync_data_sources

logger = logging.getLogger(__name__)

USGS_INDIA_FEED_URL = (
    "https://earthquake.usgs.gov/fdsnws/event/1/query?"
    "format=geojson&minlatitude=6&maxlatitude=38&minlongitude=68&maxlongitude=98&limit=25"
)

_SYNC_LOCK = threading.Lock()
_BG_STOP_EVENT = threading.Event()
_BG_THREAD: threading.Thread | None = None

_PROVIDER_STATE: dict[str, dict[str, Any]] = {
    "earthquake": {
        "status": "INITIALIZING",
        "active_provider": "NCS",
        "last_attempt_at": None,
        "last_success_at": None,
        "last_error": None,
        "events_synced": 0,
    },
    "sachet": {
        "status": "INITIALIZING",
        "active_provider": "NDMA_SACHET",
        "last_attempt_at": None,
        "last_success_at": None,
        "last_error": None,
        "events_synced": 0,
    },
    "imd": {
        "status": "INITIALIZING",
        "active_provider": "IMD_WFS",
        "last_attempt_at": None,
        "last_success_at": None,
        "last_error": None,
        "events_synced": 0,
    },
}


def fetch_usgs_india_earthquakes(timeout_seconds: float = 8.0) -> list[dict[str, Any]]:
    """Fallback earthquake feed from USGS NEIC filtered to the India bounding box."""
    ctx = ssl.create_default_context()
    req = urllib.request.Request(
        USGS_INDIA_FEED_URL,
        headers={"User-Agent": "DisasterSafetyApp/2.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, context=ctx, timeout=timeout_seconds) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        return data.get("features", [])


def sync_usgs_earthquakes_fallback(session: Session) -> int:
    """
    Upserts USGS India-region earthquake events as a clearly labeled fallback
    when NCS is unreachable, preserving verification status (reviewed vs automatic).
    """
    features = fetch_usgs_india_earthquakes()
    sync_data_sources(session)
    ds = session.execute(select(DataSource).where(DataSource.code == "USGS_NEIC")).scalar_one_or_none()
    if ds is None:
        ds = DataSource(
            code="USGS_NEIC",
            name="USGS National Earthquake Information Center (Fallback)",
            source_type="EARTHQUAKE_API",
            base_url="https://earthquake.usgs.gov/fdsnws/event/1/query",
            enabled=True,
            update_interval_seconds=90,
        )
        session.add(ds)
        session.flush()

    now = datetime.now(timezone.utc)
    synced = 0
    for feat in features:
        props = feat.get("properties") or {}
        geom = feat.get("geometry") or {}
        coords = geom.get("coordinates") or []
        if len(coords) < 2:
            continue
        lon, lat = float(coords[0]), float(coords[1])
        depth = float(coords[2]) if len(coords) >= 3 and coords[2] is not None else None
        mag = float(props["mag"]) if props.get("mag") is not None else None
        ext_id = str(feat.get("id") or "")
        if not ext_id:
            continue

        time_ms = props.get("time")
        occurred_at = (
            datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc)
            if time_ms
            else now
        )
        is_active = (now - occurred_at) <= ACTIVE_EVENT_WINDOW
        verif_raw = str(props.get("status") or "automatic").lower()
        verif_label = "CONFIRMED" if verif_raw == "reviewed" else "PRELIMINARY"
        place = props.get("place") or f"{lat:.2f}, {lon:.2f}"
        title = f"Earthquake M{mag:.1f} - {place}" if mag is not None else f"Earthquake - {place}"

        existing_rep = session.execute(
            select(CrisisSourceReport).where(
                CrisisSourceReport.data_source_id == ds.id,
                CrisisSourceReport.external_event_id == ext_id,
            )
        ).scalar_one_or_none()

        if existing_rep is not None:
            ev = existing_rep.crisis_event
            ev.last_seen_at = now
            ev.severity = map_magnitude_to_severity(mag)
            ev.status = CrisisStatus.ACTIVE if is_active else CrisisStatus.EXPIRED
            existing_rep.fetched_at = now
            existing_rep.raw_payload = {**props, "verification_status": verif_label, "depth_km": depth}
        else:
            ev = CrisisEvent(
                crisis_type=CrisisType.EARTHQUAKE,
                title=title,
                description=f"USGS Fallback ({verif_label}) earthquake event. Depth: {depth} km.",
                severity=map_magnitude_to_severity(mag),
                status=CrisisStatus.ACTIVE if is_active else CrisisStatus.EXPIRED,
                location=geojson_to_postgis({"type": "Point", "coordinates": [lon, lat]}),
                affected_area=None,
                started_at=occurred_at,
                expires_at=occurred_at + ACTIVE_EVENT_WINDOW,
                first_seen_at=now,
                last_seen_at=now,
            )
            session.add(ev)
            session.flush()
            rep = CrisisSourceReport(
                crisis_event_id=ev.id,
                data_source_id=ds.id,
                external_event_id=ext_id,
                source_url=props.get("url"),
                issued_at=occurred_at,
                fetched_at=now,
                raw_payload={**props, "verification_status": verif_label, "depth_km": depth},
            )
            session.add(rep)
        synced += 1

    ds.last_attempt_at = now
    ds.last_successful_fetch = now
    session.commit()
    return synced


def _is_stale(group: str, interval_seconds: int, now: datetime) -> bool:
    last_ok = _PROVIDER_STATE[group]["last_success_at"]
    if last_ok is None:
        return True
    return (now - last_ok).total_seconds() >= interval_seconds


def ensure_fresh_disasters(
    session: Session,
    disaster_types: list[CrisisType] | None = None,
    force: bool = False,
) -> dict[str, Any]:
    """
    Checks freshness of each disaster source and synchronizes stale sources.
    Uses a non-overlapping lock so concurrent requests share fresh results.
    Never lets one failing provider block the remaining providers.
    """
    if not isinstance(session, Session) and not force:
        return get_sync_status_snapshot()

    now = datetime.now(timezone.utc)
    wanted = set(disaster_types) if disaster_types else {CrisisType.EARTHQUAKE, CrisisType.FLOOD, CrisisType.CYCLONE}

    eq_stale = CrisisType.EARTHQUAKE in wanted and (force or _is_stale("earthquake", settings.earthquake_sync_interval_seconds, now))
    sachet_stale = bool(wanted & {CrisisType.FLOOD, CrisisType.CYCLONE}) and (force or _is_stale("sachet", settings.sachet_sync_interval_seconds, now))
    imd_stale = bool(wanted & {CrisisType.FLOOD, CrisisType.CYCLONE}) and (force or _is_stale("imd", settings.imd_sync_interval_seconds, now))

    if not (eq_stale or sachet_stale or imd_stale):
        return get_sync_status_snapshot()

    acquired = _SYNC_LOCK.acquire(blocking=False)
    if not acquired:
        # Another sync is already running; return current snapshot without overlapping
        return get_sync_status_snapshot()

    try:
        # 1. Earthquake (NCS primary -> USGS fallback)
        if eq_stale:
            _PROVIDER_STATE["earthquake"]["last_attempt_at"] = now
            try:
                ncs_res = sync_earthquakes_from_ncs(session=session, max_records=30)
                if ncs_res.success:
                    _PROVIDER_STATE["earthquake"].update({
                        "status": "LIVE",
                        "active_provider": "NCS",
                        "last_success_at": datetime.now(timezone.utc),
                        "last_error": None,
                        "events_synced": ncs_res.created + ncs_res.updated,
                    })
                else:
                    raise RuntimeError("; ".join(ncs_res.errors) or "NCS sync returned unsuccessful")
            except Exception as ncs_exc:
                logger.warning("NCS earthquake sync failed (%s); trying USGS fallback", ncs_exc)
                try:
                    usgs_count = sync_usgs_earthquakes_fallback(session=session)
                    _PROVIDER_STATE["earthquake"].update({
                        "status": "LIVE",
                        "active_provider": "USGS_NEIC_FALLBACK",
                        "last_success_at": datetime.now(timezone.utc),
                        "last_error": f"NCS unavailable ({ncs_exc}); using USGS fallback",
                        "events_synced": usgs_count,
                    })
                except Exception as usgs_exc:
                    _PROVIDER_STATE["earthquake"].update({
                        "status": "CACHED" if _PROVIDER_STATE["earthquake"]["last_success_at"] else "UNAVAILABLE",
                        "last_error": f"NCS: {ncs_exc} | USGS: {usgs_exc}",
                    })

        # 2. NDMA SACHET (Flood + Cyclone CAP/RSS)
        if sachet_stale:
            _PROVIDER_STATE["sachet"]["last_attempt_at"] = now
            try:
                fl_s = sync_sachet_flood_alerts(session=session, max_alerts=15)
                cy_s = sync_sachet_cyclone_alerts(session=session, max_alerts=15)
                if fl_s.status == "ACTIVE" or cy_s.status == "ACTIVE":
                    _PROVIDER_STATE["sachet"].update({
                        "status": "LIVE",
                        "last_success_at": datetime.now(timezone.utc),
                        "last_error": None,
                        "events_synced": fl_s.created + fl_s.updated + cy_s.created + cy_s.updated,
                    })
                else:
                    err_msg = "; ".join(fl_s.errors + cy_s.errors) or fl_s.message or cy_s.message or "SACHET inactive"
                    raise RuntimeError(err_msg)
            except Exception as s_exc:
                logger.warning("SACHET sync failed: %s", s_exc)
                _PROVIDER_STATE["sachet"].update({
                    "status": "CACHED" if _PROVIDER_STATE["sachet"]["last_success_at"] else "UNAVAILABLE",
                    "last_error": str(s_exc),
                })

        # 3. IMD GeoServer WFS (Nowcasts + Cyclone Tracks)
        if imd_stale:
            _PROVIDER_STATE["imd"]["last_attempt_at"] = now
            try:
                fl_i = sync_imd_flood_data(session=session, max_records=100)
                cy_i = sync_imd_cyclone_data(session=session, max_records=10)
                if fl_i.status == "ACTIVE" or cy_i.status == "ACTIVE":
                    _PROVIDER_STATE["imd"].update({
                        "status": "LIVE",
                        "last_success_at": datetime.now(timezone.utc),
                        "last_error": None,
                        "events_synced": fl_i.created + fl_i.updated + cy_i.created + cy_i.updated,
                    })
                else:
                    err_msg = "; ".join(fl_i.errors + cy_i.errors) or fl_i.message or cy_i.message or "IMD WFS inactive"
                    raise RuntimeError(err_msg)
            except Exception as i_exc:
                logger.warning("IMD WFS sync failed: %s", i_exc)
                _PROVIDER_STATE["imd"].update({
                    "status": "CACHED" if _PROVIDER_STATE["imd"]["last_success_at"] else "UNAVAILABLE",
                    "last_error": str(i_exc),
                })
    finally:
        _SYNC_LOCK.release()

    return get_sync_status_snapshot()


def get_sync_status_snapshot() -> dict[str, Any]:
    """Returns current per-provider freshness and overall sync status."""
    statuses = {k: dict(v) for k, v in _PROVIDER_STATE.items()}
    live_count = sum(1 for v in statuses.values() if v["status"] == "LIVE")
    cached_count = sum(1 for v in statuses.values() if v["status"] == "CACHED")
    last_times = [v["last_success_at"] for v in statuses.values() if v["last_success_at"] is not None]
    overall = "LIVE" if live_count > 0 else "CACHED" if cached_count > 0 else "UNAVAILABLE"
    return {
        "overall_status": overall,
        "last_synced_at": max(last_times) if last_times else None,
        "providers": statuses,
    }


def _background_sync_loop() -> None:
    logger.info("Background real-time disaster synchronization loop started.")
    while not _BG_STOP_EVENT.is_set():
        try:
            with SessionLocal() as db:
                ensure_fresh_disasters(db)
        except Exception as exc:
            logger.warning("Periodic disaster sync cycle error: %s", exc)
        _BG_STOP_EVENT.wait(timeout=60.0)
    logger.info("Background real-time disaster synchronization loop stopped.")


def start_disaster_background_sync() -> None:
    global _BG_THREAD
    if _BG_THREAD is not None and _BG_THREAD.is_alive():
        return
    _BG_STOP_EVENT.clear()
    _BG_THREAD = threading.Thread(target=_background_sync_loop, daemon=True, name="disaster-realtime-sync")
    _BG_THREAD.start()


def stop_disaster_background_sync() -> None:
    global _BG_THREAD
    _BG_STOP_EVENT.set()
    if _BG_THREAD is not None:
        _BG_THREAD.join(timeout=2.0)
        _BG_THREAD = None
