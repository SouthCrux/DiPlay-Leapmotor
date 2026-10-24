# LeapMoto: EV planning, reassessment and Responses API bridge

Status: offline energy assessment, trip reassessment and injectable Responses adapter implemented;
no live account, voice, map or car integration. Date: 2026-10-20.

The assistant is named **LeapMoto**, pronounced **LipMoto** in Spanish. Its current
scope is EV navigation only: preparation before departure and reassessment during
the trip. Vehicle actuation is deferred to the separate car-integration work.

The requested experience is: “trazá ruta a San Pablo”, resolve the intended
destination, propose charging stops from real providers using the car's battery
and measured consumption, then send the selected itinerary to a supported map.

**This increment does not provide that live experience yet.** The energy-assessment
core is in `tools/ev_assistant/planner.py`, and the Responses API bridge is in
`tools/ev_assistant/responses.py`. Development and tests made no network requests,
read no accounts or credentials, and neither installed nor controlled anything in
the car. No Android source or Settings changes are included. An optional HTTP
transport is implemented for a future explicitly provisioned backend.

## Implemented behavior

`TripTools.plan_trip(...)` orchestrates injected destination, route and station
providers. `assess(...)` evaluates their supplied itinerary. With no providers it
returns `UNAVAILABLE / PROVIDERS_NOT_CONFIGURED`; there is no production fallback
route or charger list. Synthetic fixtures exist only in the unit tests.

- Geocoding must yield exactly one destination. Multiple matches produce
  `DESTINATION_AMBIGUOUS`; “San Pablo” does not imply a city or country.
- The route includes a separately verified, fresh origin. This module neither
  reads the user's position nor guesses it from the destination.
- SOC must have explicit `PERCENT` units, verified provenance and a fresh timestamp.
  Usable battery capacity must be independently verified. Gross capacity, remaining
  range and fuel capacity are not substitutes for usable traction-battery energy.
- Consumption must supply a verified low/high kWh/100 km range, observation time
  and a description of the historical/recent measurement coverage. There is no
  consumption estimator or vehicle history collection in this increment.
- The provider supplies road distances, driving-time estimates and complete stop
  itineraries, including any charger detours. This module evaluates those options;
  it does not geocode, generate geometry, discover stations or optimize a road graph.
- Each leg consumes `distance_km × consumption_kWh_per_100km / 100`. Conservative
  arrival SOC uses the high consumption bound and must stay above an explicit
  reserve. No reserve is derived from the unverified approximately 83 km idea.
- A charging stop requires a supplied station ID and verified compatible connector.
  A fresh current outage rejects that stop. Stale status becomes `UNKNOWN`.
  Current `AVAILABLE` or `OCCUPIED` never becomes availability at arrival.
- Charging assumes a configurable departure SOC, default 85%, without reducing an
  already higher SOC. It is an assumption, not a verified charging capability.
  Without a charging curve, charge duration and all later arrival estimates remain
  unknown. Drive-only timestamps are explicitly lower bounds, not arrival promises.
- `CANDIDATE` means energy-feasible under the stated consumption and charging
  assumptions. It never means navigation started, a charger will be free, a
  reservation exists, or a physical charge is guaranteed. Every result keeps these
  distinctions and the sources/timestamps used.

The freshness defaults (SOC/origin 30 seconds, route 5 minutes, charger status
90 seconds, consumption 7 days) are initial local policy, not manufacturer limits.
They require tuning against actual telemetry and provider contracts. Capacity
verification currently has no expiry policy beyond a valid past timestamp; battery
aging and usable-energy recalibration remain future adapter responsibilities.

## Architecture decision

Deciders: project owner. Proposed decision: a voice/API backend interprets the
request and chooses narrow application tools; deterministic application code owns
telemetry validation, EV calculations, provider calls and actuation.

The official OpenAI documentation describes voice architectures and application
function tools: the application executes a function and returns its result. That
fits a DiPlay-owned backend without giving the language model shell or arbitrary
vehicle access. See [Voice agents](https://developers.openai.com/api/docs/guides/voice-agents)
and [Realtime with tools](https://developers.openai.com/api/docs/guides/realtime-mcp),
consulted 2026-10-20. No particular model or hosted service deployment is selected.
The implemented adapter uses the Responses API for a text turn; Realtime voice
and audio-session ownership remain separate future integrations.

| Option | Assessment |
| --- | --- |
| OpenAI API voice/backend with narrow function tools | Proposed. Supports application-owned validation and action results; needs an authorized API setup, connectivity, budget and real providers. |
| Automate the ChatGPT user interface | Rejected for this integration. It adds UI/session coupling and does not establish map, charger or vehicle contracts. |
| Give the model raw ADB, OEM writes or charger account access | Rejected. Model output cannot confer permissions, physical success, charger availability or reservations. |

The trade-off is additional adapter work in exchange for explicit, testable
boundaries. Natural language stays flexible while the model does not invent SOC,
energy, route geometry, bookings or success acknowledgements.

The implemented model-visible `plan_ev_trip` accepts only `destination_query`.
`BoundPlannerTool` binds trusted current telemetry, consumption, policy and clock
before invoking the planner. These values cannot be passed in model arguments:
unknown fields, duplicate JSON fields, invalid values and unknown tools are
rejected. A selected provider destination-ID flow remains future work.

## Responses API increment

`EVResponsesAssistant.run_text_turn(...)` is the programmatic entry point. The
transport is injectable, provider must explicitly be `openai`, and model must be
supplied by the embedding application. It runs a bounded sequence of Responses
requests, dispatches the narrow planning tool and returns generated text together
with separate structured tool results. `REPLIED` means text was received, not
that a route, reservation or vehicle action succeeded. A UI must use structured
tool results for action state; model prose alone is not physical evidence.

Following the official [function-calling guide](https://developers.openai.com/api/docs/guides/function-calling),
the request declares a strict flat Responses function schema and the application
returns `function_call_output` with the original `call_id`. Following the
[reasoning guide](https://developers.openai.com/api/docs/guides/reasoning), it
replays complete output items, including encrypted reasoning and message phase,
when continuing with `store: false`, and explicitly requests
`include: ["reasoning.encrypted_content"]`. The current guide accepts that include
for compatibility even though stateless responses include encrypted content by
default. Both sources were consulted 2026-10-20.

The loop rejects incomplete responses, replayed/invalid call IDs, parallel calls,
unsupported output types and oversized payloads. Turn/call/token budgets are
bounded. Unknown tools cannot expose vehicle writes or install another tool.
There are no automatic retries, and errors omit raw provider/HTTP exception text.

`OpenAIHTTPTransport` implements a backend-only POST to the fixed
`https://api.openai.com/v1/responses` endpoint with explicit in-memory credentials,
a timeout, bounded response size and disabled redirects. It discovers no key,
reads no environment credentials and does nothing on import or construction.
It must not be placed in the Android APK with a permanent key. No credential has
been supplied and no actual OpenAI request has been made. `store: false` is a
request option, not a claim of global zero retention or guaranteed account policy.

See `tools/ev_assistant/README.md` for the injection boundary and executable
offline protocol test. No HTTP service, authenticated public endpoint, microphone
capture, speech output or Android UI is shipped by this increment.

## Regional provider priorities supplied by the owner

The owner reported these installed apps. Their platform and current API access
are unknown. This is a declarative discovery list, not seven implemented adapters.

| Internal discovery ID | User-provided app label |
| --- | --- |
| `ute_mueve` | UteMueve |
| `cargo` | Cargo |
| `electromaps` | electromaps |
| `plugshare` | plugshare |
| `zletric` | zletric |
| `eone` | eone |
| `abrp` | abrp |

For these apps, regional coverage, the user's API authorization, live charger
status, predicted availability and reservation capabilities remain **UNKNOWN**.
Having an app installed does not expose its account, grant API access or permit
automation. The six charger/community providers have no implemented adapters.

ABRP is a higher-priority planning candidate because its
[official API page](https://web.abetterrouteplanner.com/resources/api) publishes a
Planning API v2 with JSON output, energy modeling, charging optimization, traffic
and weather inputs. Its telemetry integration requires API authorization and a
user token; OAuth scopes and deep-link parameters are also published. This
establishes documented integration contracts, not access for this project,
regional charger coverage, reservations, future availability or a route applied
in an iPhone navigation app. No ABRP adapter or account connection is implemented.
The source was consulted 2026-10-20.

Future selection should use region and actual capability coverage, without
forcing a single operator. Station deduplication should prefer a namespaced EVSE
identifier, and use verified location/connector evidence only when that identifier
is absent. Keep each provider's status, timestamp, forecast and reservation
separate, including conflicts. Never merge “available now” from one provider with
a reservation or future-availability claim from another. Regional provider
discovery is extensible; this increment performs no app scraping or account access.

## Before departure versus during a trip

Before departure, `TripTools` evaluates provider-supplied route/stop candidates
against the vehicle snapshot and a measured consumption interval. This produces
an energy estimate with provenance; navigation acceptance is still separate.

During a trip, `TripReassessor.evaluate(...)` in `reassessment.py` accepts an
application-owned active-plan baseline, route progress, fresh vehicle/consumption
observations and a provider's current next-stop status. It deterministically
checks the energy needed to reach that actual next stop. It does not invent a
replacement itinerary, generate geometry, query a model or alter navigation.

Relevant changes are a low/critical conservative arrival reserve, consumption
above the planned interval, a verified current charging-status change, a reroute,
a changed next stop or a sufficiently changed provider ETA. Repeated unchanged
conditions and ordinary progress stay silent. A configurable cooldown suppresses
minor repeated recommendations; a transition to critical reserve or unknown data
quality bypasses it. Structured health remains `CRITICAL` or `UNKNOWN` even when
another prompt is suppressed. A fresh `UNKNOWN` charger status withdraws
availability confidence while retaining a separately valid energy assessment.

Defaults are 120 seconds cooldown, 5 minutes ETA change, more than 10% increase
above planned high consumption, 3 SOC percentage points above reserve as warning,
and 120 seconds maximum age for recent consumption. These are application policy,
not OEM or operator specifications. The existing energy/SOC/origin/station
freshness policies are checked independently. A route version must change for a
reroute, not for every GPS fix. The application explicitly adopts a new baseline
after accepting a real updated itinerary or progressing to the next trip phase.

`REPLAN_RECOMMENDED` means evaluate alternatives; it does not choose or send one.
`UNAVAILABLE` means confidence in required inputs has been lost, not that a
previous energy estimate proves the battery empty. Current charger state is
never a prediction for arrival time. There is no timer, always-on scheduler,
Android background process or per-second LLM request in this module. All tests
feed scripted observations; continuous live operation is unvalidated.

Useful future inputs include traffic, road slope/elevation, wind/rain, ambient
and battery temperature, measured HVAC load, actual usable battery energy,
charging curves/rate limits, opening hours and site access, connector-specific
outages, operator reliability and reachable alternatives. Each must come from an
authorized source with units, timestamp and uncertainty. The model must not fill
missing values. They are not implemented merely because they appear in this list.

## Remaining integrations and dependencies

1. Add a real telemetry adapter with unit/freshness guarantees, independently
   verified C11 usable capacity, and measured historical/recent consumption.
   Physical SOC observed elsewhere in DiPlay is not yet wired into this module.
2. Choose authorized geocoding/routing and charger providers. Destination/origin
   selection, supported connectors, detour distances, opening/access restrictions,
   operator coverage and API terms must come from those contracts. No specific EV
   app integration or account authorization is assumed.
3. Add charging curves, temperature/rate constraints and route conditions before
   estimating charge duration. Add arrival-time availability only if a provider
   offers a genuine prediction, labelled with confidence and timestamp. Actual
   reservations are a separate provider operation with their own authorization.
4. Add a navigation receiver that declares destination/waypoint support and
   acknowledges the applied route. iPhone/CarPlay and each map app need separate
   verification; Android deep links do not establish iPhone waypoint support.
5. Provision and host the voice/API backend through the user's authorized setup. This
   work does not inspect API keys, passwords, environment secrets or personal
   files, and makes no billable API calls. Existing ChatGPT access is not treated
   as proof of API credentials or billing availability.
Vehicle controls are outside LeapMoto's current scope. The separate C11 integration
and its permission/broker limitations remain documented in
`LEAPMOTOR_C11_ULTRA_STATUS.md`; adding conversational intent does not resolve them.

No precise positions, voice recordings, route history or charger account data are
logged by this module. Provider exception text is deliberately omitted from
results because it could contain location, account or credential information.
Production adapters still need cancellation, timeouts, privacy policy and bounded
request handling; the synchronous offline interfaces are not a network runtime.

## Validation

Run from the repository root, without network access:

```sh
python3 -m unittest discover -s tools/ev_assistant -p 'test_*.py'
```

60 tests cover a provider-driven two-leg candidate, conservative energy bounds,
reserve, absent/stale/unverified data, incompatible connectors, current versus
future station availability, unknown ETA after charging, destination ambiguity,
origin freshness, missing providers, redacted provider failures and the complete
Responses tool-call continuation. HTTP tests inject a fake opener and never use
the network. Protocol tests also cover argument injection, reasoning preservation,
call-ID replay, response/turn/call limits and missing vehicle data. Scripted trip
updates verify cooldown, critical reserve, consumption changes, ETA changes,
charger-state freshness, connector compatibility and unknown availability. These are
offline contract tests, not a live OpenAI, navigation, voice or charging validation.
Android CI is coordinated by the main agent for the shared working tree.
