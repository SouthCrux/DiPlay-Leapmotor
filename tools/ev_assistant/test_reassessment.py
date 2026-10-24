"""Scripted synthetic trip observations; not a continuously connected live trip."""
from dataclasses import replace
import unittest

from tools.ev_assistant.planner import Consumption, Evidence, Policy, Station, VehicleSnapshot
from tools.ev_assistant.reassessment import (
    ActivePlan, ReassessmentPolicy, RouteProgress, TripReassessor,
)


NOW = 100_000.0


class ReassessmentTest(unittest.TestCase):
    def setUp(self):
        self.energy_policy = Policy(reserve_soc=10)
        self.engine = TripReassessor(self.energy_policy)
        self.consumption = Consumption(16, 20, Evidence("TEST_ONLY_measured", NOW, True),
                                       "synthetic recent + historical")
        self.baseline = ActivePlan("TEST_ONLY_route", "v1", "TEST_ONLY_EVSE", NOW + 5400,
                                   self.consumption, "AVAILABLE")

    def update(self, offset=0, soc=60, high=20, remaining=120, status="AVAILABLE",
               version="v1", eta_offset=0, station_age=0, soc_age=0,
               consumption_age=0, route_age=0, station_verified=True, **overrides):
        now = NOW + offset
        evidence = Evidence("TEST_ONLY_car", now - soc_age, True)
        vehicle = VehicleSnapshot(soc, "PERCENT", 60, frozenset({"CCS2"}), evidence, evidence)
        recent = replace(self.consumption, high_kwh_per_100km=high,
                         evidence=Evidence("TEST_ONLY_recent", now - consumption_age, True))
        progress = RouteProgress("TEST_ONLY_route", version, "TEST_ONLY_destination",
                                 "TEST_ONLY_EVSE", remaining, 5400 - offset,
                                 NOW + 5400 + eta_offset, "TEST_ONLY_origin",
                                 Evidence("TEST_ONLY_map", now - route_age, True))
        station = Station("TEST_ONLY_EVSE", frozenset({"CCS2"}), status,
                          Evidence("TEST_ONLY_operator", now - station_age, station_verified))
        values = dict(baseline=self.baseline, progress=progress, vehicle=vehicle,
                      recent=recent, station=station, now=now)
        values.update(overrides)
        return self.engine.evaluate(**values)

    def test_ordinary_progress_and_small_consumption_variation_stay_silent(self):
        first = self.update()
        second = self.update(offset=10, remaining=119, soc=59.8, high=20.3, eta_offset=30)
        for decision in (first, second):
            self.assertEqual(decision.action, "SILENT")
            self.assertEqual(decision.health, "OK")
            self.assertFalse(decision.model_call_requested)
            self.assertFalse(decision.navigation_changed)

    def test_measured_consumption_outside_planned_interval_recommends_reassessment(self):
        decision = self.update(high=22.1)
        self.assertEqual(decision.action, "REPLAN_RECOMMENDED")
        self.assertIn("CONSUMPTION_INCREASE", decision.reasons)
        self.assertEqual(decision.next_leg_energy.status, "CANDIDATE")

    def test_fresh_evse_change_notifies_once_not_on_every_update(self):
        first = self.update(status="OCCUPIED")
        again = self.update(offset=1, status="OCCUPIED", remaining=119.9)
        self.assertEqual(first.action, "REPLAN_RECOMMENDED")
        self.assertIn("CURRENT_CHARGER_STATUS_CHANGED", first.reasons)
        self.assertEqual(again.action, "SILENT")
        # A current observation still does not establish arrival-time availability.
        self.assertFalse(first.navigation_changed)

    def test_changed_noncritical_conditions_wait_for_explicit_cooldown(self):
        self.assertEqual(self.update(status="OCCUPIED").action, "REPLAN_RECOMMENDED")
        pending = self.update(offset=1, status="OCCUPIED", version="v2")
        self.assertEqual(pending.action, "SILENT")
        self.assertIn("ROUTE_CHANGED", pending.reasons)
        emitted = self.update(offset=120, status="OCCUPIED", version="v2")
        self.assertEqual(emitted.action, "REPLAN_RECOMMENDED")

    def test_critical_reserve_bypasses_cooldown_and_health_persists_when_silent(self):
        self.update(status="OCCUPIED")
        critical = self.update(offset=1, soc=45, status="OCCUPIED")
        repeated = self.update(offset=2, soc=44.9, status="OCCUPIED")
        self.assertEqual(critical.action, "REPLAN_RECOMMENDED")
        self.assertEqual(critical.health, "CRITICAL")
        self.assertIn("CRITICAL_RESERVE", critical.reasons)
        self.assertEqual(repeated.action, "SILENT")
        self.assertEqual(repeated.health, "CRITICAL")

    def test_stale_soc_withdraws_energy_confidence_without_model_or_navigation_action(self):
        self.update(status="OCCUPIED")
        lost = self.update(offset=1, soc_age=31)
        repeated = self.update(offset=2, soc_age=32)
        self.assertEqual(lost.action, "UNAVAILABLE")
        self.assertEqual(lost.health, "UNKNOWN")
        self.assertIn("SOC_UNVERIFIED_OR_STALE", lost.reasons)
        self.assertEqual(repeated.action, "SILENT")
        self.assertEqual(repeated.health, "UNKNOWN")
        self.assertFalse(lost.model_call_requested)

    def test_stale_or_unverified_evse_status_cannot_trigger_a_false_outage(self):
        for kwargs in ({"station_age": 91}, {"station_verified": False}):
            self.engine = TripReassessor(self.energy_policy)
            result = self.update(status="OUT_OF_SERVICE", **kwargs)
            self.assertEqual(result.action, "UNAVAILABLE")
            self.assertEqual(result.reasons, ("CHARGER_STATUS_UNAVAILABLE",))
            self.assertNotIn("CURRENT_CHARGER_STATUS_CHANGED", result.reasons)

    def test_fresh_unknown_availability_preserves_energy_but_withdraws_status_confidence(self):
        result = self.update(status="UNKNOWN")
        self.assertEqual(result.action, "UNAVAILABLE")
        self.assertEqual(result.health, "UNKNOWN")
        self.assertIn("CHARGER_AVAILABILITY_UNKNOWN", result.reasons)
        self.assertEqual(result.next_leg_energy.status, "CANDIDATE")
        self.assertNotIn("CRITICAL_RESERVE", result.reasons)

    def test_recent_consumption_and_route_progress_require_their_own_freshness(self):
        recent = self.update(consumption_age=121)
        self.assertEqual(recent.action, "UNAVAILABLE")
        self.assertEqual(recent.reasons, ("RECENT_CONSUMPTION_UNAVAILABLE",))
        progress = self.update(offset=1, route_age=31)
        self.assertEqual(progress.action, "UNAVAILABLE")
        self.assertIn("ORIGIN_UNVERIFIED_OR_STALE", progress.reasons)

    def test_significant_eta_shift_is_relevant_without_predicting_charger_status(self):
        result = self.update(eta_offset=300)
        self.assertEqual(result.action, "REPLAN_RECOMMENDED")
        self.assertIn("ETA_CHANGED", result.reasons)
        self.assertFalse(result.model_call_requested)

    def test_low_arrival_margin_warns_before_reserve_is_breached(self):
        result = self.update(soc=52)
        self.assertEqual(result.health, "OK")
        self.assertEqual(result.action, "REPLAN_RECOMMENDED")
        self.assertIn("LOW_ARRIVAL_MARGIN", result.reasons)
        self.assertNotIn("CRITICAL_RESERVE", result.reasons)

    def test_fresh_outage_and_later_different_fresh_state_are_distinct_events(self):
        self.baseline = replace(self.baseline, station_current_status="UNKNOWN")
        available = self.update(status="AVAILABLE")
        outage = self.update(offset=120, status="OUT_OF_SERVICE")
        self.assertEqual(available.action, "REPLAN_RECOMMENDED")
        self.assertEqual(outage.action, "REPLAN_RECOMMENDED")
        self.assertIn("CURRENT_CHARGER_STATUS_CHANGED", outage.reasons)

    def test_critical_reserve_is_retained_even_when_station_status_is_lost(self):
        decision = self.update(soc=45, station_age=91)
        self.assertEqual(decision.health, "CRITICAL")
        self.assertEqual(decision.action, "REPLAN_RECOMMENDED")
        self.assertIn("CRITICAL_RESERVE", decision.reasons)
        self.assertIn("CHARGER_STATUS_UNAVAILABLE", decision.reasons)

    def test_incompatible_station_and_invalid_configuration_fail_closed(self):
        for connectors in (frozenset({"CHADEMO"}), frozenset()):
            self.engine = TripReassessor(self.energy_policy)
            wrong = Station("TEST_ONLY_EVSE", connectors, "AVAILABLE",
                            Evidence("TEST_ONLY_operator", NOW, True))
            result = self.update(station=wrong)
            self.assertEqual(result.action, "UNAVAILABLE")
            self.assertIn("CHARGER_COMPATIBILITY_UNAVAILABLE", result.reasons)
        with self.assertRaises(ValueError):
            TripReassessor(self.energy_policy, ReassessmentPolicy(cooldown_seconds=-1))


if __name__ == "__main__":
    unittest.main()
