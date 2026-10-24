# LeapMoto development entry point

LeapMoto (pronounced LipMoto in Spanish) currently covers EV navigation only.
This package includes a deterministic itinerary evaluator, an event-driven trip
reassessor and a small OpenAI Responses bridge. It is not connected to an account,
microphone, vehicle or map.
There is no default model, credential discovery, bundled API key or production
fixture. `TripTools()` without providers returns unavailable.

From the repository root, execute the complete injected-transport round trip:

```sh
python3 -m unittest tools.ev_assistant.test_responses.ResponsesTest.test_complete_tool_roundtrip_preserves_reasoning_message_phase_and_call_id -v
```

That test exercises `EVResponsesAssistant.run_text_turn`, dispatches
`plan_ev_trip(destination_query)` into the actual planner with synthetic providers,
returns `function_call_output` with the original call ID and completes the text
response. It verifies the energy calculation and replay of reasoning/message
items. It does not contact OpenAI or any charger/map service.

Run the whole suite:

```sh
python3 -m unittest discover -s tools/ev_assistant -p 'test_*.py'
```

An embedding backend supplies the dependencies at this boundary:

```python
from tools.ev_assistant.responses import BoundPlannerTool, EVResponsesAssistant

bound = BoundPlannerTool(configured_trip_tools, read_trusted_planner_context, clock)
assistant = EVResponsesAssistant(
    explicitly_configured_transport,
    bound,
    provider="openai",
    model=explicitly_selected_model,
)
result = assistant.run_text_turn(user_text)
```

`read_trusted_planner_context` returns a verified `PlannerContext`, or `None` when
the required telemetry/capacity/consumption is unavailable. It must not construct
that context from model arguments. `clock` returns current UTC epoch seconds.
The transport implements `create_response(payload) -> dict`. No provider can be
silently substituted when one is missing.

The optional `OpenAIHTTPTransport` can be constructed later with an explicitly
provisioned in-memory backend API key. It performs real HTTPS when called and may
incur API charges; no such calls were made in development or tests. Do not pass a
permanent API key in shell arguments, environment examples, source files or the
APK. Runtime credential provisioning, account authorization and deployment remain
outside this increment.

Treat `result.text` as generated prose. Only `result.tool_results` contains the
planner's structured assessment; even `CANDIDATE` never means navigation started,
a station is reserved or a physical vehicle command ran. The only exposed tool
evaluates an itinerary. Vehicle writes and navigation dispatch are absent.

Architecture, regional provider priorities and remaining dependencies:
[ULTRA_EV_ASSISTANT.md](../../docs/ULTRA_EV_ASSISTANT.md).
