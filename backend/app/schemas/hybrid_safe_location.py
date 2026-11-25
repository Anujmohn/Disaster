from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, Field

DisasterMode = Literal["EARTHQUAKE", "FLOOD"]

OriginClassification = Literal[
    "SATELLITE_DETECTED",
    "OSM_VERIFIED",
    "SATELLITE_AND_OSM_MATCHED",
    "DEM_DERIVED",
    "UNVERIFIED",
]

AssessmentStatus = Literal[
    "SUITABLE_WITH_EVIDENCE",
    "CONDITIONALLY_SUITABLE",
    "NOT_RECOMMENDED",
    "INSUFFICIENT_DATA",
    "UNKNOWN",
]


class ImageryMetadata(BaseModel):
    provider: str
    zoom: int = 18
    width_px: int = 0
    height_px: int = 0
    center_lat: float
    center_lon: float
    bbox: dict[str, float] | None = None
    crs: str = "EPSG:4326"
    attribution: str = ""
    acquisition_date: str | None = None
    pixel_to_geo_transform: dict[str, float] | None = None
    status: Literal["AVAILABLE", "IMAGERY_UNAVAILABLE"] = "IMAGERY_UNAVAILABLE"
    unavailable_reason: str | None = None


class SegmentationModelStatus(BaseModel):
    model_name: str
    device: str = "cpu"
    status: Literal[
        "AVAILABLE",
        "MODEL_UNAVAILABLE",
        "INFERENCE_TIMEOUT",
        "INFERENCE_FAILED",
    ] = "MODEL_UNAVAILABLE"
    reason: str | None = None


class HybridSafeLocationRequest(BaseModel):
    latitude: float = Field(ge=-90.0, le=90.0)
    longitude: float = Field(ge=-180.0, le=180.0)
    disaster_type: DisasterMode = "EARTHQUAKE"
    max_radius_m: int = Field(default=2000, ge=100, le=2000)
    min_candidates: int = Field(default=3, ge=1, le=20)
    max_results: int = Field(default=25, ge=1, le=50)


class HybridSafeCandidate(BaseModel):
    id: str
    name: str | None = None
    category: str
    latitude: float
    longitude: float
    nearest_point: dict[str, float]
    polygon_geojson: dict[str, Any] | None = None
    distance_m: float
    area_m2: float | None = None
    origin_classification: OriginClassification
    assessment_status: AssessmentStatus
    applicable_disaster: DisasterMode
    confidence: float | None = None
    evidence_sources: list[str] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)
    rejection_or_conditional_reasons: list[str] = Field(default_factory=list)
    osm_cross_validation: dict[str, Any] = Field(default_factory=dict)
    elevation_m: float | None = None
    elevation_status: str = "UNKNOWN"
    road_width_m: str | float | None = None
    road_attributes: dict[str, Any] | None = None


class DiscoveryDiagnostics(BaseModel):
    osm_features_found: int = 0
    cv_detections_found: int = 0
    merged_candidates_count: int = 0
    duplicates_removed: int = 0
    candidates_rejected: int = 0
    rejection_reasons: list[str] = Field(default_factory=list)
    candidates_returned: int = 0


class HybridDiscoveryResponse(BaseModel):
    success: bool = True
    status: Literal[
        "OK",
        "NO_CANDIDATES_FOUND",
        "NO_SUITABLE_LOCATION_FOUND",
        "INSUFFICIENT_DATA",
    ] = "OK"
    user_location: dict[str, float]
    disaster_type: DisasterMode
    scanned_radii_m: list[int] = Field(default_factory=list)
    effective_radius_m: int = 100
    osm_candidates_count: int = 0
    satellite_candidates_count: int = 0
    merged_candidates_count: int = 0
    diagnostics: DiscoveryDiagnostics = Field(default_factory=DiscoveryDiagnostics)
    imagery_metadata: ImageryMetadata
    segmentation_status: SegmentationModelStatus
    osm_status: Literal["AVAILABLE", "OSM_UNAVAILABLE"] = "OSM_UNAVAILABLE"
    dem_status: Literal["AVAILABLE", "DEM_UNAVAILABLE", "NOT_APPLICABLE"] = "NOT_APPLICABLE"
    total_candidates: int = 0
    candidates: list[HybridSafeCandidate] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    disclaimer: str = (
        "Hybrid satellite, OpenStreetMap, and terrain assessments are decision-support estimates only "
        "and do not certify official shelter designation or structural stability. Follow NDMA/SDMA orders."
    )
