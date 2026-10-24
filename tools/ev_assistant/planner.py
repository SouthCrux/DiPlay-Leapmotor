"""Deterministic assessment of provider-supplied EV itineraries.

Providers own geocoding, geometry, road distance, detours and station lookup.
This module never synthesizes those facts and never starts navigation.
All times are UTC epoch seconds. Freshness windows are local policy, not OEM specs.
"""
from dataclasses import dataclass
from math import isfinite
from typing import Optional, Protocol


@dataclass(frozen=True)
class Evidence:
    source: str
    observed_at: float
    verified: bool = False


@dataclass(frozen=True)
class VehicleSnapshot:
    soc: float
    soc_unit: str
    usable_kwh: float
    connectors: frozenset[str]
    soc_evidence: Evidence
    capacity_evidence: Evidence


@dataclass(frozen=True)
class Consumption:
    low_kwh_per_100km: float
    high_kwh_per_100km: float
    evidence: Evidence
    # Adapter must describe measured historical/recent coverage, not an LLM guess.
    coverage: str


@dataclass(frozen=True)
class Destination:
    provider_id: str
    label: str


@dataclass(frozen=True)
class Leg:
    distance_km: float
    drive_seconds: float
    # None means final destination; otherwise a real provider station identifier.
    charge_station_id: Optional[str] = None


@dataclass(frozen=True)
class Itinerary:
    provider_id: str
    destination_id: str
    legs: tuple[Leg, ...]
    evidence: Evidence
    # Opaque trusted-provider origin ID; do not copy precise coordinates into logs.
    origin_id: str
    origin_evidence: Evidence


@dataclass(frozen=True)
class Station:
    provider_id: str
    connectors: frozenset[str]
    # AVAILABLE, OCCUPIED, OUT_OF_SERVICE, or UNKNOWN. This is current status only.
    current_status: str
    evidence: Evidence


@dataclass(frozen=True)
class Policy:
    reserve_soc: float
    charge_departure_soc: float = 85.0
    soc_max_age_seconds: float = 30.0
    route_max_age_seconds: float = 300.0
    station_max_age_seconds: float = 90.0
    consumption_max_age_seconds: float = 7 * 86400.0
    origin_max_age_seconds: float = 30.0


@dataclass(frozen=True)
class LegAssessment:
    arrival_soc_low: float
    arrival_soc_high: float
    station_id: Optional[str]
    current_availability: str
    # First arrival can use provider driving time. Later arrivals need charge durations.
    earliest_drive_only_arrival_at: float
    arrival_at: Optional[float]
    availability_at_arrival: str = "UNKNOWN"
    reservation: str = "NONE"


@dataclass(frozen=True)
class Assessment:
    status: str
    itinerary_id: Optional[str] = None
    legs: tuple[LegAssessment, ...] = ()
    limitations: tuple[str, ...] = ()
    provenance: tuple[Evidence, ...] = ()
    navigation_started: bool = False


def _number(value: object) -> bool:
    return isinstance(value, (float, int)) and not isinstance(value, bool) and isfinite(value)


def _fresh(evidence: Evidence, now: float, maximum_age: float) -> bool:
    return (bool(evidence.source.strip()) and _number(evidence.observed_at)
            and evidence.observed_at > 0 and 0 <= now - evidence.observed_at <= maximum_age)


def assess(itinerary: Itinerary, vehicle: VehicleSnapshot, consumption: Consumption,
           stations: dict[str, Station], policy: Policy, now: float) -> Assessment:
    """Check a supplied charging itinerary using the conservative consumption bound.

    CANDIDATE means energy-feasible under these explicit assumptions; it does not
    mean a charger will be free, a trip is booked, or a map accepted the waypoints.
    """
    def unavailable(reason: str, status: str = "UNAVAILABLE") -> Assessment:
        return Assessment(status, itinerary.provider_id, limitations=(reason,))

    ages = (policy.soc_max_age_seconds, policy.route_max_age_seconds,
            policy.station_max_age_seconds, policy.consumption_max_age_seconds,
            policy.origin_max_age_seconds)
    if (not _number(now) or now <= 0 or any(not _number(x) or x <= 0 for x in ages)
            or not _number(policy.reserve_soc) or not _number(policy.charge_departure_soc)
            or not 0 <= policy.reserve_soc < policy.charge_departure_soc <= 100):
        return unavailable("INVALID_POLICY_OR_CLOCK", "INVALID")
    if (vehicle.soc_unit != "PERCENT" or not _number(vehicle.soc)
            or not 0 <= vehicle.soc <= 100 or not vehicle.soc_evidence.verified
            or not _fresh(vehicle.soc_evidence, now, policy.soc_max_age_seconds)):
        return unavailable("SOC_UNVERIFIED_OR_STALE")
    if (not _number(vehicle.usable_kwh) or vehicle.usable_kwh <= 0
            or not vehicle.capacity_evidence.verified
            or not _fresh(vehicle.capacity_evidence, now, float("inf"))):
        return unavailable("USABLE_CAPACITY_UNVERIFIED")
    low, high = consumption.low_kwh_per_100km, consumption.high_kwh_per_100km
    if (not _number(low) or not _number(high) or not 0 < low <= high
            or not consumption.coverage.strip() or not consumption.evidence.verified
            or not _fresh(consumption.evidence, now, policy.consumption_max_age_seconds)):
        return unavailable("CONSUMPTION_UNVERIFIED_OR_STALE")
    if (not itinerary.provider_id or not itinerary.destination_id or not itinerary.legs
            or len(itinerary.legs) > 100 or not itinerary.evidence.verified
            or not _fresh(itinerary.evidence, now, policy.route_max_age_seconds)):
        return unavailable("ROUTE_UNVERIFIED_OR_STALE")
    if (not itinerary.origin_id.strip() or not itinerary.origin_evidence.verified
            or not _fresh(itinerary.origin_evidence, now, policy.origin_max_age_seconds)):
        return unavailable("ORIGIN_UNVERIFIED_OR_STALE")
    # All intermediate legs must end at a charger; final leg must end at destination.
    if (itinerary.legs[-1].charge_station_id is not None
            or any(leg.charge_station_id is None for leg in itinerary.legs[:-1])):
        return unavailable("INVALID_ITINERARY_STOPS", "INVALID")

    soc_low = soc_high = vehicle.soc
    driving_seconds = 0.0
    charged = False
    results = []
    provenance = [vehicle.soc_evidence, vehicle.capacity_evidence,
                  consumption.evidence, itinerary.evidence, itinerary.origin_evidence]
    limitations = ["CONSUMPTION_RANGE_IS_AN_ESTIMATE", "PROVIDER_DRIVING_TIME_ESTIMATE",
                   "NAVIGATION_NOT_STARTED"]
    for leg in itinerary.legs:
        if (not _number(leg.distance_km) or leg.distance_km < 0
                or not _number(leg.drive_seconds) or leg.drive_seconds < 0
                or (leg.distance_km > 0 and leg.drive_seconds == 0)):
            return unavailable("INVALID_ROUTE_LEG", "INVALID")
        soc_low -= leg.distance_km * high / vehicle.usable_kwh
        soc_high -= leg.distance_km * low / vehicle.usable_kwh
        if soc_low < policy.reserve_soc - 1e-9:
            return unavailable("ARRIVAL_BELOW_RESERVE", "ENERGY_INFEASIBLE")
        driving_seconds += leg.drive_seconds
        current_status = "NOT_APPLICABLE"
        if leg.charge_station_id is not None:
            station = stations.get(leg.charge_station_id)
            if station is None or station.provider_id != leg.charge_station_id:
                return unavailable("STATION_DATA_UNAVAILABLE")
            if (not station.evidence.verified or not station.connectors
                    or not station.connectors.intersection(vehicle.connectors)):
                return unavailable("CONNECTOR_COMPATIBILITY_UNVERIFIED")
            if station.current_status not in {"AVAILABLE", "OCCUPIED", "OUT_OF_SERVICE", "UNKNOWN"}:
                return unavailable("INVALID_STATION_STATUS", "INVALID")
            if _fresh(station.evidence, now, policy.station_max_age_seconds):
                current_status = station.current_status
            else:
                current_status = "UNKNOWN"
                limitations.append("STATION_STATUS_STALE")
            # Known current outage is not accepted as a usable charging stop.
            if current_status == "OUT_OF_SERVICE":
                return unavailable("STATION_OUT_OF_SERVICE")
            provenance.append(station.evidence)
            limitations.extend(("FUTURE_CHARGER_AVAILABILITY_UNKNOWN", "NO_RESERVATION",
                                "CHARGING_CURVE_MISSING_ETAS_AFTER_CHARGING_UNKNOWN",
                                "CHARGE_TARGET_ASSUMED_REACHABLE"))
        results.append(LegAssessment(soc_low, soc_high, leg.charge_station_id,
                                     current_status, now + driving_seconds,
                                     None if charged else now + driving_seconds))
        if leg.charge_station_id is not None:
            # Never lower charge by charging at a stop. Full optimization is out of scope.
            soc_low = max(soc_low, policy.charge_departure_soc)
            soc_high = max(soc_high, policy.charge_departure_soc)
            charged = True
    return Assessment("CANDIDATE", itinerary.provider_id, tuple(results),
                      tuple(dict.fromkeys(limitations)), tuple(provenance))


class DestinationProvider(Protocol):
    def resolve(self, query: str) -> tuple[Destination, ...]: ...


class RouteProvider(Protocol):
    def itineraries(self, destination: Destination,
                    vehicle: VehicleSnapshot) -> tuple[Itinerary, ...]: ...


class StationProvider(Protocol):
    def lookup(self, station_ids: tuple[str, ...]) -> dict[str, Station]: ...


class TripTools:
    """Application-owned tool: providers are injected by trusted application code.

    This is an internal application API, not a model-call dispatcher. A future
    model tool must expose only destination_query; trusted application code binds
    vehicle, consumption, policy and clock before calling this method.
    """

    def __init__(self, destinations: Optional[DestinationProvider] = None,
                 routes: Optional[RouteProvider] = None,
                 stations: Optional[StationProvider] = None):
        self.destinations = destinations
        self.routes = routes
        self.stations = stations

    def plan_trip(self, destination_query: str, vehicle: VehicleSnapshot,
                  consumption: Consumption, policy: Policy, now: float) -> tuple[Assessment, ...]:
        if not isinstance(destination_query, str) or not destination_query.strip():
            return (Assessment("INVALID", limitations=("DESTINATION_REQUIRED",)),)
        if self.destinations is None or self.routes is None:
            return (Assessment("UNAVAILABLE", limitations=("PROVIDERS_NOT_CONFIGURED",)),)
        try:
            destinations = self.destinations.resolve(destination_query)
            if len(destinations) != 1:
                reason = "DESTINATION_AMBIGUOUS" if destinations else "DESTINATION_NOT_FOUND"
                return (Assessment("UNAVAILABLE", limitations=(reason,)),)
            destination = destinations[0]
            itineraries = self.routes.itineraries(destination, vehicle)
            if not itineraries:
                return (Assessment("UNAVAILABLE", limitations=("ROUTES_UNAVAILABLE",)),)
            if len(itineraries) > 20:
                return (Assessment("INVALID", limitations=("TOO_MANY_ROUTE_OPTIONS",)),)
            results = []
            for itinerary in itineraries:
                if itinerary.destination_id != destination.provider_id:
                    results.append(Assessment("INVALID", itinerary.provider_id,
                                              limitations=("DESTINATION_MISMATCH",)))
                    continue
                ids = tuple(dict.fromkeys(leg.charge_station_id for leg in itinerary.legs
                                          if leg.charge_station_id is not None))
                stations = self.stations.lookup(ids) if ids and self.stations else {}
                results.append(assess(itinerary, vehicle, consumption, stations, policy, now))
            return tuple(results)
        except Exception:
            # Provider messages may contain location/account data; do not echo or log.
            return (Assessment("UNAVAILABLE", limitations=("PROVIDER_FAILURE",)),)
