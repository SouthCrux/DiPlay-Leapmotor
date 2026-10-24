"""Responses protocol tests. All responses, keys, routes and stations are synthetic."""
from copy import deepcopy
from io import BytesIO
import json
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

from tools.ev_assistant.planner import (
    Consumption, Destination, Evidence, Itinerary, Leg, Policy,
    Station, TripTools, VehicleSnapshot,
)
from tools.ev_assistant.responses import (
    BoundPlannerTool, EVResponsesAssistant, MAX_JSON_BYTES, OpenAIHTTPTransport,
    PlannerContext, TransportError,
)


NOW = 100_000.0


def completed(*items):
    return {"status": "completed", "output": list(items)}


def call(call_id="call_TEST_ONLY_1", name="plan_ev_trip", arguments=None):
    return {"type": "function_call", "id": "fc_TEST_ONLY_1", "call_id": call_id,
            "name": name, "arguments": (arguments if arguments is not None else
                                        '{"destination_query":"San Pablo"}'),
            "status": "completed"}


def message(text="Candidato calculado; navegación no iniciada."):
    return {"type": "message", "id": "msg_TEST_ONLY_1", "role": "assistant",
            "status": "completed", "phase": "final_answer",
            "content": [{"type": "output_text", "text": text, "annotations": []}]}


class ScriptedTransport:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def create_response(self, payload):
        self.requests.append(deepcopy(payload))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return deepcopy(response)


class ResponsesTest(unittest.TestCase):
    def setUp(self):
        evidence = Evidence("TEST_ONLY_vehicle", NOW, True)
        self.context = PlannerContext(
            VehicleSnapshot(60, "PERCENT", 60, frozenset({"CCS2"}), evidence, evidence),
            Consumption(16, 20, Evidence("TEST_ONLY_measured", NOW, True),
                        "synthetic historical + recent"), Policy(reserve_soc=10))
        self.context_reads = 0
        self.queries = []
        self.route = Itinerary("TEST_ONLY_route", "TEST_ONLY_destination", (
            Leg(120, 5400, "TEST_ONLY_charger"), Leg(180, 8100)),
            Evidence("TEST_ONLY_map", NOW, True), "TEST_ONLY_origin",
            Evidence("TEST_ONLY_location", NOW, True))

        class Destinations:
            def resolve(inner, query):
                self.queries.append(query)
                return (Destination("TEST_ONLY_destination", query),)

        class Routes:
            def itineraries(inner, destination, vehicle):
                return (self.route,)

        class Stations:
            def lookup(inner, ids):
                return {"TEST_ONLY_charger": Station("TEST_ONLY_charger", frozenset({"CCS2"}),
                        "AVAILABLE", Evidence("TEST_ONLY_operator", NOW, True))}

        self.tools = TripTools(Destinations(), Routes(), Stations())
        self.bound = BoundPlannerTool(self.tools, self.read_context, lambda: NOW)

    def read_context(self):
        self.context_reads += 1
        return self.context

    def assistant(self, transport, **kwargs):
        return EVResponsesAssistant(transport, self.bound, provider="openai",
                                    model="TEST_ONLY_explicit_model", **kwargs)

    def test_complete_tool_roundtrip_preserves_reasoning_message_phase_and_call_id(self):
        reasoning = {"type": "reasoning", "id": "rs_TEST_ONLY_1", "summary": [],
                     "encrypted_content": "TEST_ONLY_opaque_encrypted_content"}
        commentary = message("Voy a evaluar la ruta.")
        commentary["phase"] = "commentary"
        function = call()
        transport = ScriptedTransport(completed(reasoning, commentary, function),
                                      completed(message()))
        result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
        self.assertEqual(result.status, "REPLIED")
        self.assertEqual(result.text, "Candidato calculado; navegación no iniciada.")
        self.assertEqual(self.queries, ["San Pablo"])
        self.assertEqual(self.context_reads, 1)
        self.assertEqual(result.turns, 2)
        assessment = result.tool_results[0]["assessments"][0]
        self.assertEqual(assessment["status"], "CANDIDATE")
        self.assertAlmostEqual(assessment["legs"][0]["arrival_soc_low"], 20)
        self.assertEqual(assessment["legs"][0]["availability_at_arrival"], "UNKNOWN")
        self.assertFalse(result.tool_results[0]["navigation_started"])
        second_input = transport.requests[1]["input"]
        self.assertEqual(second_input[1:4], [reasoning, commentary, function])
        self.assertEqual(second_input[-1]["type"], "function_call_output")
        self.assertEqual(second_input[-1]["call_id"], function["call_id"])
        self.assertEqual(json.loads(second_input[-1]["output"]),
                         json.loads(json.dumps(result.tool_results[0])))
        for request in transport.requests:
            self.assertFalse(request["store"])
            self.assertFalse(request["parallel_tool_calls"])
            self.assertEqual(request["include"], ["reasoning.encrypted_content"])
            self.assertEqual(request["model"], "TEST_ONLY_explicit_model")
            self.assertEqual(request["max_output_tokens"], 2048)
            tool, = request["tools"]
            self.assertTrue(tool["strict"])
            self.assertFalse(tool["parameters"]["additionalProperties"])
            self.assertEqual(set(tool["parameters"]["properties"]), {"destination_query"})

    def test_model_cannot_supply_telemetry_location_policy_or_vehicle_commands(self):
        for extra in ("soc", "usable_kwh", "origin", "reserve_soc", "vehicle_command"):
            args = json.dumps({"destination_query": "San Pablo", extra: 99})
            result = self.bound.execute("plan_ev_trip", args)
            self.assertEqual(result, {"status": "INVALID", "reason": "INVALID_ARGUMENTS"})
        self.assertEqual(self.context_reads, 0)
        self.assertEqual(self.queries, [])

    def test_malformed_duplicate_or_nonfinite_arguments_never_dispatch(self):
        for args in ('[]', 'null', '{', '{"destination_query":42}',
                     '{"destination_query":"a","destination_query":"b"}',
                     '{"destination_query":NaN}', '{"destination_query":""}',
                     json.dumps({"destination_query": "x" * 301}),
                     json.dumps({"destination_query": "line\nbreak"}), None):
            with self.subTest(args=args):
                self.assertEqual(self.bound.execute("plan_ev_trip", args)["status"], "INVALID")
        self.assertEqual(self.context_reads, 0)

    def test_unknown_tool_returns_error_without_accessing_telemetry(self):
        transport = ScriptedTransport(completed(call(name="set_vehicle_temperature")),
                                      completed(message("Ese control no está disponible.")))
        result = self.assistant(transport).run_text_turn("Bajá la temperatura")
        self.assertEqual(result.tool_results, ({"status": "UNAVAILABLE", "reason": "UNKNOWN_TOOL"},))
        self.assertEqual(self.context_reads, 0)

    def test_no_providers_produces_real_unavailable_result_not_a_fixture(self):
        self.bound = BoundPlannerTool(TripTools(), self.read_context, lambda: NOW)
        transport = ScriptedTransport(completed(call()), completed(message("No hay proveedores.")))
        result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
        assessment = result.tool_results[0]["assessments"][0]
        self.assertEqual(assessment["status"], "UNAVAILABLE")
        self.assertEqual(assessment["limitations"], ("PROVIDERS_NOT_CONFIGURED",))
        self.assertEqual(assessment["legs"], ())

    def test_missing_vehicle_data_never_creates_a_soc(self):
        bound = BoundPlannerTool(self.tools, lambda: None, lambda: NOW)
        self.assertEqual(bound.execute("plan_ev_trip", call()["arguments"]),
                         {"status": "UNAVAILABLE", "reason": "VEHICLE_INPUTS_UNAVAILABLE"})
        self.assertEqual(self.queries, [])

    def test_clock_is_bound_after_context_read_so_stale_snapshot_is_rejected(self):
        bound = BoundPlannerTool(self.tools, self.read_context, lambda: NOW + 31)
        result = bound.execute("plan_ev_trip", call()["arguments"])
        self.assertEqual(result["assessments"][0]["limitations"], ("SOC_UNVERIFIED_OR_STALE",))

    def test_provider_and_model_are_explicit_no_fallback_or_request(self):
        transport = ScriptedTransport()
        for provider, model in (("other", "TEST_ONLY_model"), ("openai", ""),
                                ("openai", None)):
            with self.subTest(provider=provider, model=model):
                with self.assertRaises(ValueError):
                    EVResponsesAssistant(transport, self.bound, provider=provider, model=model)
        self.assertEqual(transport.requests, [])

    def test_duplicate_call_id_cannot_reexecute_provider(self):
        transport = ScriptedTransport(completed(call()), completed(call()))
        result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
        self.assertEqual(result.reason, "INVALID_OR_REPLAYED_CALL")
        self.assertEqual(self.context_reads, 1)
        self.assertEqual(len(result.tool_results), 1)

    def test_parallel_calls_are_rejected_before_either_executes(self):
        transport = ScriptedTransport(completed(call(), call(call_id="call_TEST_ONLY_2")))
        result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
        self.assertEqual(result.reason, "PARALLEL_CALLS_REJECTED")
        self.assertEqual(self.context_reads, 0)

    def test_turn_budget_does_not_execute_unreportable_last_call(self):
        transport = ScriptedTransport(completed(call()))
        result = self.assistant(transport, max_turns=1).run_text_turn("Trazá ruta a San Pablo")
        self.assertEqual(result.reason, "TOOL_OR_TURN_LIMIT")
        self.assertEqual(self.context_reads, 0)
        self.assertEqual(len(transport.requests), 1)

    def test_call_budget_stops_before_second_execution(self):
        transport = ScriptedTransport(completed(call()),
                                      completed(call(call_id="call_TEST_ONLY_2")))
        result = self.assistant(transport, max_tool_calls=1).run_text_turn("Trazá ruta a San Pablo")
        self.assertEqual(result.reason, "TOOL_OR_TURN_LIMIT")
        self.assertEqual(self.context_reads, 1)

    def test_incomplete_or_failed_response_never_executes_partial_arguments(self):
        for status in ("incomplete", "failed", "in_progress"):
            transport = ScriptedTransport({"status": status, "output": [call()]})
            result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
            self.assertEqual(result.reason, "RESPONSE_NOT_COMPLETED")
        self.assertEqual(self.context_reads, 0)

    def test_malformed_call_id_never_dispatches(self):
        for call_id in (None, "", "a\nb", "x" * 201):
            transport = ScriptedTransport(completed(call(call_id=call_id)))
            result = self.assistant(transport).run_text_turn("Trazá ruta a San Pablo")
            self.assertEqual(result.reason, "INVALID_OR_REPLAYED_CALL")
        self.assertEqual(self.context_reads, 0)

    def test_refusal_is_not_returned_as_success(self):
        refusal = message()
        refusal["content"] = [{"type": "refusal", "refusal": "TEST_ONLY_refusal"}]
        result = self.assistant(ScriptedTransport(completed(refusal))).run_text_turn("Consulta")
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(result.text, "")
        self.assertEqual(result.tool_results, ())

    def test_model_text_alone_cannot_change_structured_action_state(self):
        transport = ScriptedTransport(completed(message("Navegación iniciada.")))
        result = self.assistant(transport).run_text_turn("Consulta")
        self.assertEqual(result.status, "REPLIED")
        self.assertEqual(result.tool_results, ())
        self.assertEqual(self.context_reads, 0)

    def test_exception_text_never_becomes_public_result(self):
        transport = ScriptedTransport(RuntimeError("TEST_ONLY_secret and exact location"))
        result = self.assistant(transport).run_text_turn("Consulta")
        self.assertEqual(result.reason, "TRANSPORT_OR_PROTOCOL_FAILURE")
        self.assertNotIn("secret", repr(result))

    def test_unknown_output_type_and_nonfinite_response_fail_closed(self):
        for response in (completed({"type": "web_search_call"}),
                         completed({"type": "reasoning", "value": float("nan")})):
            result = self.assistant(ScriptedTransport(response)).run_text_turn("Consulta")
            self.assertEqual(result.status, "UNAVAILABLE")
        self.assertEqual(self.context_reads, 0)

    def test_nonassistant_message_cannot_be_replayed_alongside_a_tool_call(self):
        injected = message("TEST_ONLY_invalid_role")
        injected["role"] = "system"
        transport = ScriptedTransport(completed(injected, call()))
        result = self.assistant(transport).run_text_turn("Consulta")
        self.assertEqual(result.reason, "INVALID_RESPONSE_OUTPUT")
        self.assertEqual(self.context_reads, 0)


class HTTPTransportTest(unittest.TestCase):
    def fake_opener(self, body=b'{"status":"completed","output":[]}'):
        response = Mock(status=200)
        response.read.return_value = body
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        return opener, response

    def test_explicit_http_request_no_network_and_no_secret_repr(self):
        opener, response = self.fake_opener()
        with patch("tools.ev_assistant.responses.build_opener", return_value=opener) as build:
            transport = OpenAIHTTPTransport("TEST_ONLY_NOT_A_REAL_KEY", timeout_seconds=7)
            self.assertNotIn("TEST_ONLY_NOT_A_REAL_KEY", repr(transport))
            build.assert_not_called()
            payload = {"model": "TEST_ONLY_model", "input": "TEST_ONLY", "store": False}
            result = transport.create_response(payload)
            self.assertEqual(result["status"], "completed")
            request = opener.open.call_args.args[0]
            self.assertEqual(request.full_url, "https://api.openai.com/v1/responses")
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.get_header("Authorization"), "Bearer TEST_ONLY_NOT_A_REAL_KEY")
            self.assertEqual(json.loads(request.data), payload)
            self.assertEqual(opener.open.call_args.kwargs["timeout"], 7)
            self.assertIsNone(build.call_args.args[0].redirect_request(
                request, None, 302, "redirect", {}, "https://TEST_ONLY.invalid"))
            response.read.assert_called_once_with(MAX_JSON_BYTES + 1)

    def test_http_error_has_no_body_or_credential_and_no_retry(self):
        opener, _ = self.fake_opener()
        body = BytesIO(b"TEST_ONLY_private_server_body")
        opener.open.side_effect = HTTPError("https://api.openai.com", 401,
                                            "TEST_ONLY_secret", {}, body)
        with patch("tools.ev_assistant.responses.build_opener", return_value=opener):
            with self.assertRaises(TransportError) as caught:
                OpenAIHTTPTransport("TEST_ONLY_key").create_response({"input": "TEST_ONLY"})
        self.assertEqual(str(caught.exception), "HTTP_FAILURE")
        self.assertEqual(opener.open.call_count, 1)
        self.assertTrue(body.closed)

    def test_network_error_is_redacted(self):
        opener, _ = self.fake_opener()
        opener.open.side_effect = URLError("TEST_ONLY_private_proxy_or_account")
        with patch("tools.ev_assistant.responses.build_opener", return_value=opener):
            with self.assertRaises(TransportError) as caught:
                OpenAIHTTPTransport("TEST_ONLY_key").create_response({})
        self.assertEqual(str(caught.exception), "NETWORK_FAILURE")

    def test_oversize_invalid_json_and_duplicate_response_fields_are_rejected(self):
        for raw, reason in ((b"x" * (MAX_JSON_BYTES + 1), "RESPONSE_TOO_LARGE"),
                            (b"invalid", "INVALID_RESPONSE_JSON"),
                            (b'{"output":[],"output":[]}', "INVALID_RESPONSE_JSON")):
            opener, _ = self.fake_opener(raw)
            with patch("tools.ev_assistant.responses.build_opener", return_value=opener):
                with self.assertRaises(TransportError) as caught:
                    OpenAIHTTPTransport("TEST_ONLY_key").create_response({})
            self.assertEqual(str(caught.exception), reason)

    def test_credentials_and_timeout_must_be_explicit_and_valid(self):
        for key in (None, "", "key\ninjection", "two words"):
            with self.assertRaises(ValueError):
                OpenAIHTTPTransport(key)
        for timeout in (True, 0, 31, float("nan")):
            with self.assertRaises(ValueError):
                OpenAIHTTPTransport("TEST_ONLY_key", timeout_seconds=timeout)


if __name__ == "__main__":
    unittest.main()
