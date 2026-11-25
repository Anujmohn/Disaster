from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, Field

Category = Literal["shelter", "public_building", "open_high_ground", "flyover"]
ALL_CATEGORIES: list[str] = ["shelter", "public_building", "open_high_ground", "flyover"]


class Point(BaseModel):
    lat: float = Field(ge=-90.0, le=90.0)
    lon: float = Field(ge=-180.0, le=180.0)


class CrisisLocation(Point):
    radius_km: float = Field(2.0, gt=0.0, le=50.0, description="Estimated danger-zone radius")


class SafeLocationRequest(BaseModel):
    user_location: Point
    crisis_location: CrisisLocation
    search_radius_km: float = Field(5.0, ge=1.0, le=15.0)
    max_results_per_category: int = Field(3, ge=1, le=10)
    categories: list[Category] = Field(default_factory=lambda: list(ALL_CATEGORIES))
    min_height_gain_m: float = Field(
        2.0,
        ge=0.0,
        le=50.0,
        description="Min height above crisis point to count as 'high'",
    )
    use_routing: bool = True
    include_route_geometry: bool = False


class CandidateRoute(BaseModel):
    distance_km: float
    duration_min: float | None = None
    min_distance_to_crisis_km: float
    enters_danger_zone: bool
    geometry: list[list[float]] | None = None
    profile: str = "driving"
    source: str = "osrm"


class CandidateLocation(BaseModel):
    id: str
    category: str
    name: str
    subtype: str
    lat: float
    lon: float
    distance_user_km: float
    distance_crisis_km: float
    elevation_m: float | None = None
    height_above_crisis_m: float | None = None
    height_above_local_low_m: float | None = None
    building_levels: float | None = None
    safety_level: str
    direction: str
    bearing_deg: int
    google_maps_url: str
    route: CandidateRoute | None = None
    score: float
    warnings: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ElevationSummary(BaseModel):
    user: float | None = None
    crisis: float | None = None
    local_low: float | None = None


class CategorizedRecommendations(BaseModel):
    shelters: list[CandidateLocation] = Field(default_factory=list)
    public_buildings: list[CandidateLocation] = Field(default_factory=list)
    open_high_ground: list[CandidateLocation] = Field(default_factory=list)
    flyovers: list[CandidateLocation] = Field(default_factory=list)


class FloodSafeLocationsResponse(BaseModel):
    user_location: dict[str, float]
    crisis_location: dict[str, float]
    user_in_danger_zone: bool
    distance_user_to_crisis_km: float
    elevation_m: ElevationSummary
    best_recommendation: CandidateLocation | None = None
    nearest_safe: CandidateLocation | None = None
    recommendations: dict[str, list[CandidateLocation]]
    ranked: list[CandidateLocation] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    disclaimer: str
