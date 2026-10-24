"""Synthetic data stays in tests; no fixture is a claim about the C11 or a charger."""
from dataclasses import replace
import unittest

from tools.ev_assistant.planner import (
    Assessment, Consumption, Destination, Evidence, Itinerary, Leg, Policy,
    Station, TripTools, VehicleSnapshot, assess,
)


NOW = 100_000.0


class PlannerTest(unittest.TestCase):
    def setUp(self):
        e = Evidence("TEST_ONLY_vehicle", NOW, True)
        self.vehicle = VehicleSnapshot(60, "PERCENT", 60, frozenset({"CCS2"}), e, e)
        self.consumption = Consumption(16, 20, Evidence("TEST_ONLY_measured", NOW, True),
                                       "synthetic historical + recent coverage")
        self.policy = Policy(reserve_soc=10)
        self.route = Itinerary("TEST_ONLY_route", "TEST_ONLY_destination", (
            Leg(120, 5400, "TEST_ONLY_charger"), Leg(180, 8100)),
            Evidence("TEST_ONLY_map", NOW, True), "TEST_ONLY_origin",
            Evidence("TEST_ONLY_location", NOW, True))
        self.station = Station("TEST_ONLY_charger", frozenset({"CCS2"}), "AVAILABLE",
                               Evidence("TEST_ONLY_operator", NOW, True))

    def run_plan(self, **overrides):
        arguments = dict(itinerary=self.route, vehicle=self.vehicle,
                         consumption=self.consumption,
                         stations={self.station.provider_id: self.station},
                         policy=self.policy, now=NOW)
        arguments.update(overrides)
        return assess(**arguments)

    def test_two_leg_candidate_conserves_energy_and_exposes_eta_unknowns(self):
        result = self.run_plan()
        self.assertEqual(result.status, "CANDIDATE")
        self.assertAlmostEqual(result.legs[0].arrival_soc_low, 20)
        self.assertAlmostEqual(result.legs[0].arrival_soc_high, 28)
        self.assertAlmostEqual(result.legs[1].arrival_soc_low, 25)
        self.assertAlmostEqual(result.legs[1].arrival_soc_high, 37)
        self.assertEqual(result.legs[0].arrival_at, NOW + 5400)
        self.assertIsNone(result.legs[1].arrival_at)
        self.assertEqual(result.legs[1].earliest_drive_only_arrival_at, NOW + 13500)
        self.assertFalse(result.navigation_started)
        self.assertIn("CHARGE_TARGET_ASSUMED_REACHABLE", result.limitations)
        self.assertEqual(len(result.provenance), 6)

    def test_current_available_does_not_imply_future_availability_or_reservation(self):
        first = self.run_plan().legs[0]
        self.assertEqual(first.current_availability, "AVAILABLE")
        self.assertEqual(first.availability_at_arrival, "UNKNOWN")
        self.assertEqual(first.reservation, "NONE")

    def test_low_consumption_feasible_but_high_consumption_infeasible_rejects(self):
        result = self.run_plan(vehicle=replace(self.vehicle, soc=45))
        self.assertEqual(result.status, "ENERGY_INFEASIBLE")
        self.assertEqual(result.limitations, ("ARRIVAL_BELOW_RESERVE",))

    def test_exact_reserve_is_accepted(self):
        result = self.run_plan(vehicle=replace(self.vehicle, soc=50))
        self.assertEqual(result.status, "CANDIDATE")
        self.assertEqual(result.legs[0].arrival_soc_low, 10)

    def test_stale_future_unverified_or_wrong_unit_soc_is_unavailable(self):
        for evidence, unit in (
            (Evidence("OEM", NOW - 31, True), "PERCENT"),
            (Evidence("OEM", NOW + 1, True), "PERCENT"),
            (Evidence("OEM", NOW, False), "PERCENT"),
            (Evidence("OEM", NOW, True), "FRACTION"),
            (Evidence("OEM", 0, True), "PERCENT"),
        ):
            with self.subTest(evidence=evidence, unit=unit):
                result = self.run_plan(vehicle=replace(self.vehicle, soc_evidence=evidence,
                                                       soc_unit=unit))
                self.assertEqual(result.limitations, ("SOC_UNVERIFIED_OR_STALE",))

    def test_nan_inf_and_boolean_soc_never_produce_a_candidate(self):
        for value in (float("nan"), float("inf"), True, -1, 101):
            with self.subTest(value=value):
                self.assertEqual(self.run_plan(vehicle=replace(self.vehicle, soc=value)).status,
                                 "UNAVAILABLE")

    def test_capacity_must_be_verified_usable_energy(self):
        result = self.run_plan(vehicle=replace(self.vehicle,
                               capacity_evidence=Evidence("advertised gross", NOW, False)))
        self.assertEqual(result.limitations, ("USABLE_CAPACITY_UNVERIFIED",))

    def test_missing_or_stale_consumption_is_unavailable(self):
        for model in (replace(self.consumption, low_kwh_per_100km=0),
                      replace(self.consumption, high_kwh_per_100km=float("nan")),
                      replace(self.consumption, evidence=Evidence("measured", NOW - 8 * 86400, True)),
                      replace(self.consumption, coverage="")):
            with self.subTest(model=model):
                self.assertEqual(self.run_plan(consumption=model).limitations,
                                 ("CONSUMPTION_UNVERIFIED_OR_STALE",))

    def test_route_freshness_is_independent_of_vehicle(self):
        result = self.run_plan(itinerary=replace(self.route,
                              evidence=Evidence("map", NOW - 301, True)))
        self.assertEqual(result.limitations, ("ROUTE_UNVERIFIED_OR_STALE",))

    def test_origin_must_be_verified_and_fresh_not_inferred_from_destination(self):
        for evidence in (Evidence("location", NOW - 31, True),
                         Evidence("location", NOW, False)):
            result = self.run_plan(itinerary=replace(self.route, origin_evidence=evidence))
            self.assertEqual(result.limitations, ("ORIGIN_UNVERIFIED_OR_STALE",))

    def test_station_current_status_stale_is_explicitly_unknown(self):
        station = replace(self.station, evidence=Evidence("operator", NOW - 91, True))
        result = self.run_plan(stations={station.provider_id: station})
        self.assertEqual(result.status, "CANDIDATE")
        self.assertEqual(result.legs[0].current_availability, "UNKNOWN")
        self.assertIn("STATION_STATUS_STALE", result.limitations)

    def test_current_outage_rejects_but_current_occupied_is_not_future_fact(self):
        for status, expected in (("OUT_OF_SERVICE", "UNAVAILABLE"),
                                 ("OCCUPIED", "CANDIDATE")):
            station = replace(self.station, current_status=status)
            result = self.run_plan(stations={station.provider_id: station})
            self.assertEqual(result.status, expected)

    def test_missing_station_and_incompatible_connectors_do_not_become_stops(self):
        self.assertEqual(self.run_plan(stations={}).limitations, ("STATION_DATA_UNAVAILABLE",))
        station = replace(self.station, connectors=frozenset({"CHADEMO"}))
        self.assertEqual(self.run_plan(stations={station.provider_id: station}).limitations,
                         ("CONNECTOR_COMPATIBILITY_UNVERIFIED",))

    def test_station_identifier_must_match_exact_requested_stop(self):
        station = replace(self.station, provider_id="somewhere else")
        self.assertEqual(self.run_plan(stations={self.station.provider_id: station}).limitations,
                         ("STATION_DATA_UNAVAILABLE",))

    def test_no_charge_trip_has_arrival_without_missing_curve_warning(self):
        route = replace(self.route, legs=(Leg(100, 5000),))
        result = self.run_plan(itinerary=route, stations={})
        self.assertEqual(result.status, "CANDIDATE")
        self.assertEqual(result.legs[0].arrival_at, NOW + 5000)
        self.assertNotIn("CHARGE_TARGET_ASSUMED_REACHABLE", result.limitations)

    def test_route_cannot_skip_intermediate_stop_or_finish_at_charger(self):
        for legs in ((Leg(1, 1), Leg(1, 1)), (Leg(1, 1, "charger"),)):
            self.assertEqual(self.run_plan(itinerary=replace(self.route, legs=legs)).status,
                             "INVALID")

    def test_malformed_leg_and_policy_are_rejected(self):
        self.assertEqual(self.run_plan(itinerary=replace(self.route, legs=(Leg(-1, 50),))).status,
                         "INVALID")
        self.assertEqual(self.run_plan(policy=replace(self.policy, reserve_soc=90)).status,
                         "INVALID")

    def tools(self, destinations=None, routes=None, stations=None):
        class DestinationSource:
            def resolve(inner, query):
                return destinations if destinations is not None else (
                    Destination("TEST_ONLY_destination", query),)

        class RouteSource:
            def itineraries(inner, destination, vehicle):
                return routes if routes is not None else (self.route,)

        class StationSource:
            def lookup(inner, ids):
                return stations if stations is not None else {self.station.provider_id: self.station}

        return TripTools(DestinationSource(), RouteSource(), StationSource())

    def invoke(self, tools):
        return tools.plan_trip("San Pablo", self.vehicle, self.consumption, self.policy, NOW)

    def test_unconfigured_provider_does_not_manufacture_route(self):
        self.assertEqual(self.invoke(TripTools()),
                         (Assessment("UNAVAILABLE", limitations=("PROVIDERS_NOT_CONFIGURED",)),))

    def test_full_offline_tool_flow_uses_provider_stops(self):
        result, = self.invoke(self.tools())
        self.assertEqual(result.status, "CANDIDATE")
        self.assertEqual(result.legs[0].station_id, "TEST_ONLY_charger")
        self.assertFalse(result.navigation_started)

    def test_ambiguous_geocoding_is_not_silently_resolved(self):
        destinations = (Destination("a", "São Paulo"), Destination("b", "San Pablo"))
        result, = self.invoke(self.tools(destinations=destinations))
        self.assertEqual(result.limitations, ("DESTINATION_AMBIGUOUS",))

    def test_no_routes_and_destination_mismatch_are_explicit(self):
        self.assertEqual(self.invoke(self.tools(routes=()))[0].limitations,
                         ("ROUTES_UNAVAILABLE",))
        route = replace(self.route, destination_id="different city")
        self.assertEqual(self.invoke(self.tools(routes=(route,)))[0].limitations,
                         ("DESTINATION_MISMATCH",))

    def test_provider_exception_never_leaks_account_or_location_text(self):
        class FailingProvider:
            def resolve(self, query):
                raise RuntimeError("private account/token and exact location")

        result, = self.invoke(TripTools(FailingProvider(), object()))
        self.assertEqual(result.limitations, ("PROVIDER_FAILURE",))
        self.assertNotIn("private", repr(result))


if __name__ == "__main__":
    unittest.main()
