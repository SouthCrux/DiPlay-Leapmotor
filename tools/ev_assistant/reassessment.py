"""Event-driven trip reassessment. No polling, model calls or navigation writes."""
from dataclasses import dataclass
from math import isfinite
from typing import Optional

from .planner import (
    Assessment, Consumption, Evidence, Itinerary, Leg, Policy, Station,
    VehicleSnapshot, assess,
)


@dataclass(frozen=True)
class ActivePlan:
    route_id: str
    route_version: str
    next_stop_id: Optional[str]
    next_arrival_at: float
    consumption: Consumption
    station_current_status: str = "UNKNOWN"


@dataclass(frozen=True)
class RouteProgress:
    route_id: str
    # A version changes for a reroute, not for every GPS update.
    route_version: str
    destination_id: str
    next_stop_id: Optional[str]
    remaining_distance_km: float
    remaining_drive_seconds: float
    next_arrival_at: float
    origin_id: str
    evidence: Evidence


@dataclass(frozen=True)
class ReassessmentPolicy:
    cooldown_seconds: float = 120.0
    eta_change_seconds: float = 300.0
    consumption_increase_fraction: float = 0.10
    reserve_warning_margin_soc: float = 3.0
    recent_consumption_max_age_seconds: float = 120.0


@dataclass(frozen=True)
class Reassessment:
    # SILENT suppresses another prompt, not the separately retained health state.
    action: str
    health: str
    reasons: tuple[str, ...] = ()
    next_leg_energy: Optional[Assessment] = None
    # This module never requests a language-model call or sends a route.
    model_call_requested: bool = False
    navigation_changed: bool = False


def _number(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and isfinite(value)


def _fresh(evidence: Evidence, now: float, maximum_age: float) -> bool:
    return (evidence.verified and bool(evidence.source.strip()) and
            _number(evidence.observed_at) and evidence.observed_at > 0 and
            0 <= now - evidence.observed_at <= maximum_age)


class TripReassessor:
    """Application feeds fresh observations and adopts a new baseline explicitly.

    A decision can recommend evaluating alternatives; it cannot choose a new
    itinerary or promise a charger's future availability. Repeated unchanged
    conditions stay silent. Critical reserve/data loss bypass the ordinary cooldown
    on transition, while their health remains visible on subsequent silent updates.
    """

    def __init__(self, energy_policy: Policy, policy: ReassessmentPolicy = ReassessmentPolicy()):
        values = (policy.cooldown_seconds, policy.eta_change_seconds,
                  policy.consumption_increase_fraction, policy.reserve_warning_margin_soc,
                  policy.recent_consumption_max_age_seconds)
        if (any(not _number(x) or x < 0 for x in values)
                or policy.eta_change_seconds == 0
                or policy.recent_consumption_max_age_seconds == 0
                or policy.consumption_increase_fraction > 1
                or policy.reserve_warning_margin_soc > 100):
            raise ValueError("INVALID_REASSESSMENT_POLICY")
        self._energy_policy, self._policy = energy_policy, policy
        self._last_emitted_signature = None
        self._last_emitted_at = None

    def _decision(self, reasons, health, progress, now, energy=None, observation_marker=None):
        reasons = tuple(dict.fromkeys(reasons))
        if not reasons:
            self._last_emitted_signature = None
            return Reassessment("SILENT", health, next_leg_energy=energy)
        signature = (health, reasons, progress.route_id, progress.route_version,
                     progress.next_stop_id, observation_marker)
        if signature == self._last_emitted_signature:
            return Reassessment("SILENT", health, reasons, energy)
        urgent = health in {"UNKNOWN", "CRITICAL"}
        if (not urgent and self._last_emitted_at is not None
                and now - self._last_emitted_at < self._policy.cooldown_seconds):
            return Reassessment("SILENT", health, reasons, energy)
        self._last_emitted_signature, self._last_emitted_at = signature, now
        action = "UNAVAILABLE" if health == "UNKNOWN" else "REPLAN_RECOMMENDED"
        return Reassessment(action, health, reasons, energy)

    def evaluate(self, baseline: ActivePlan, progress: RouteProgress,
                 vehicle: VehicleSnapshot, recent: Consumption,
                 station: Optional[Station], now: float) -> Reassessment:
        if not _number(now) or now <= 0:
            return Reassessment("UNAVAILABLE", "UNKNOWN", ("INVALID_CLOCK",))
        if (not baseline.route_id or not baseline.route_version or not progress.route_version
                or not _number(progress.next_arrival_at) or progress.next_arrival_at < now
                or not _number(baseline.next_arrival_at)
                or not baseline.consumption.evidence.verified
                or not _number(baseline.consumption.low_kwh_per_100km)
                or not _number(baseline.consumption.high_kwh_per_100km)
                or not 0 < baseline.consumption.low_kwh_per_100km <=
                       baseline.consumption.high_kwh_per_100km):
            return self._decision(("PLAN_OR_PROGRESS_UNVERIFIED",), "UNKNOWN", progress, now)
        if not _fresh(recent.evidence, now, self._policy.recent_consumption_max_age_seconds):
            return self._decision(("RECENT_CONSUMPTION_UNAVAILABLE",), "UNKNOWN", progress, now)

        # This reduced input checks energy to the next actual provider stop. It is
        # not a generated map route or replacement itinerary for navigation.
        next_leg = Itinerary(progress.route_id,
                             progress.next_stop_id or progress.destination_id,
                             (Leg(progress.remaining_distance_km,
                                  progress.remaining_drive_seconds),),
                             progress.evidence, progress.origin_id, progress.evidence)
        energy = assess(next_leg, vehicle, recent, {}, self._energy_policy, now)
        if energy.status in {"INVALID", "UNAVAILABLE"}:
            return self._decision(("ENERGY_INPUTS_UNAVAILABLE",) + energy.limitations,
                                  "UNKNOWN", progress, now, energy)
        reasons = []
        critical = energy.status == "ENERGY_INFEASIBLE"
        if critical:
            reasons.append("CRITICAL_RESERVE")
        elif energy.legs[0].arrival_soc_low <= (self._energy_policy.reserve_soc +
                                              self._policy.reserve_warning_margin_soc):
            reasons.append("LOW_ARRIVAL_MARGIN")

        if progress.next_stop_id is not None:
            if (station is None or station.provider_id != progress.next_stop_id
                    or not _fresh(station.evidence, now,
                                  self._energy_policy.station_max_age_seconds)
                    or station.current_status not in
                       {"AVAILABLE", "OCCUPIED", "OUT_OF_SERVICE", "UNKNOWN"}):
                if critical:
                    return self._decision(reasons + ["CHARGER_STATUS_UNAVAILABLE"],
                                          "CRITICAL", progress, now, energy)
                return self._decision(("CHARGER_STATUS_UNAVAILABLE",), "UNKNOWN",
                                      progress, now, energy)
            if not station.connectors.intersection(vehicle.connectors):
                return self._decision(reasons + ["CHARGER_COMPATIBILITY_UNAVAILABLE"],
                                      "CRITICAL" if critical else "UNKNOWN", progress, now, energy)
            if station.current_status == "UNKNOWN":
                return self._decision(reasons + ["CHARGER_AVAILABILITY_UNKNOWN"],
                                      "CRITICAL" if critical else "UNKNOWN", progress, now, energy)
            # Current status only. No inference of availability at the arrival time.
            if (station.current_status == "OUT_OF_SERVICE" or
                    (station.current_status in {"AVAILABLE", "OCCUPIED"}
                     and progress.next_stop_id == baseline.next_stop_id
                     and station.current_status != baseline.station_current_status)):
                reasons.append("CURRENT_CHARGER_STATUS_CHANGED")
        if recent.high_kwh_per_100km > (baseline.consumption.high_kwh_per_100km *
                                       (1 + self._policy.consumption_increase_fraction)):
            reasons.append("CONSUMPTION_INCREASE")
        if (progress.route_id != baseline.route_id or
                progress.route_version != baseline.route_version):
            reasons.append("ROUTE_CHANGED")
        if progress.next_stop_id != baseline.next_stop_id:
            reasons.append("NEXT_STOP_CHANGED")
        elif abs(progress.next_arrival_at - baseline.next_arrival_at) >= self._policy.eta_change_seconds:
            reasons.append("ETA_CHANGED")
        return self._decision(reasons, "CRITICAL" if critical else "OK", progress, now, energy,
                              station.current_status if progress.next_stop_id is not None else None)
