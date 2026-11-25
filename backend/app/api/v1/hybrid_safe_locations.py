from __future__ import annotations

from fastapi import APIRouter, Query

from app.schemas.hybrid_safe_location import (
    DisasterMode,
    HybridDiscoveryResponse,
    HybridSafeLocationRequest,
)
from app.services.hybrid_safe_location import discover_hybrid_safe_locations

router = APIRouter(prefix="/api/v1/safe-locations", tags=["hybrid-safe-locations"])


@router.post(
    "/discover",
    response_model=HybridDiscoveryResponse,
    summary="Hybrid Satellite + Zero-Shot Segmentation + OSM + DEM Safe-Location Discovery",
    description=(
        "Discovers and assesses candidate safe open spaces using centered satellite imagery, "
        "zero-shot segmentation, Shapely metric polygon conversion, single-query OSM cross-validation, "
        "and separate Earthquake/Flood geographical assessments across progressive radii (100m-2000m)."
    ),
)
async def discover_safe_locations_post(
    req: HybridSafeLocationRequest,
) -> HybridDiscoveryResponse:
    return await discover_hybrid_safe_locations(req)


@router.get(
    "/discover",
    response_model=HybridDiscoveryResponse,
    summary="Hybrid Safe-Location Discovery via GET query parameters",
)
async def discover_safe_locations_get(
    latitude: float = Query(ge=-90.0, le=90.0),
    longitude: float = Query(ge=-180.0, le=180.0),
    disaster_type: DisasterMode = Query("EARTHQUAKE"),
    max_radius_m: int = Query(2000, ge=100, le=2000),
    min_candidates: int = Query(3, ge=1, le=20),
    max_results: int = Query(25, ge=1, le=50),
) -> HybridDiscoveryResponse:
    req = HybridSafeLocationRequest(
        latitude=latitude,
        longitude=longitude,
        disaster_type=disaster_type,
        max_radius_m=max_radius_m,
        min_candidates=min_candidates,
        max_results=max_results,
    )
    return await discover_hybrid_safe_locations(req)
