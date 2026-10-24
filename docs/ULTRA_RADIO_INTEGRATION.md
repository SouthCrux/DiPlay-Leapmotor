# Radio in the C11 Ultra integration

Current increment: an optional protocol module connected to the existing single
`VehicleStatePrototype`/`VehicleStateRuntime` owner through an explicit, default-null
backend parameter. The aggregate owns one plugin-0 description, config/register
response and subscription/update set for range, climate and radio. No production
backend is wired through `CarPlayMediaEngine`, and the default has no radio
advertisement. No C11 receiver was acquired, no tune command was sent to the car,
and no new radio capability was advertised. The existing radio icon on an iPhone does not
prove that this vehicle radio integration exists.

## Verified local protocol reference

The primary implementation reference is f-io/LIVI revision `4bcd1335`, preserved
locally as `.private/c11/ultra-20261017/livi-vehicle_state.rs`:

- `media()` at line 306 describes a DAB source, accessory type `0x03000001`,
  service `0x14000006`, source identifier `DAB` and semantic type 6.
- `now_playing()` at line 339 describes accessory `0x0F000001`, service
  `0x14000001`, current source, title/artist/album and playback state.
- `radio_controls()` at line 368 assigns play/pause/next/previous/tune identifiers
  4360–4364 with types `0x0F000032`, `0x0F000033`, `0x0F000035`, `0x0F000036`
  and `0x0F00004B`. The tune parameters are `identifier` and `sourceIdentifier`.
- `radio_item()` at line 395 defines station identifier/name/short name, type
  `dab`, ensemble and category. Images are optional in this module.
- `Control.describe()` at line 234 defines sender=0, hasResponse=true and request
  parameter names/formats. `control()` at line 1343 returns successful command
  IDs as null entries in `controlResponse.values`, errors as -6735.

Instance IDs are local protocol IDs, not OEM property IDs. The module follows
only this DAB contract. It does not label an FM receiver as DAB, invent frequency
units, or advertise tune-to-frequency/change-source controls whose real backend
semantics are not implemented. It does not guess a `radio:` application URL.

The Apple reference resources have charging/tires/car-body widget URLs but no
verified radio/climate launch URL in the inspected corpus. Widget input regions
and local vehicle services are separate dependencies. `requestUI` handling must
be diagnosed by actual request metadata; these radio descriptors do not resolve
an unrelated generic request being routed to Android HOME.

## Implemented module and boundaries

`UltraRadioBackend` is an explicitly injected contract for an already authorized
DAB receiver. No default implementation selects a tuner, binds an OEM service,
loads an extracted SDK or requests elevated permissions. `verifiedDabCapabilities`
must return null without independent verification; each supported command is
explicit. Getters, dispatch and cancellation must be cached/nonblocking.

`UltraRadioProtocol` provides bounded OPACK configuration, registration, read-only
characteristics, change notifications and asynchronous controls. It accepts at
most 128 unique bounded stations, bounds text by UTF-8 bytes, admits only fresh
real observations, and advertises no accessories without a verified source.
Unknown metadata has no synthetic initial value. Capability change, loss/staleness
of the source or withdrawal of a subscribed value retires the owner so a phone
cannot keep displaying a stale live value. Close suppresses reads and callbacks;
the injecting caller separately owns and closes the backend itself.

Controls are single-flight. Unsupported/foreign-source/malformed/multi-command
requests never reach the receiver. Dispatch does not change cached state. Success
requires both backend confirmation and a newer real observation matching play,
pause or tuned identifier; next/previous additionally require an observed station
change. Timeout and close cancel the pending request; late callbacks are ignored.
Replies retain their request lane, subscriptions update on the high lane, and
pending replies are bounded. This is deliberately stronger than LIVI's immediate
queue acknowledgement: it must not be mistaken for a completed tuner change.

The module has no timers or network ownership of its own. The existing runtime
polls the aggregate, which merges deferred replies and emits a single high-lane
update for its combined subscription set. Radio descriptors/IIDs are merged with
range/climate under the same plugin-0 dispatcher. Mixed registration has one
transaction and response; radio characteristics stay read-only, climate writes
retain their own confirmation path, and ambiguous mixed controls dispatch nothing.
If an announced radio source disappears, the aggregate retires both vehicle lanes
even while range remains valid. Withdrawal of an already subscribed value also
retires the owner, preventing a stale range/title from remaining live. Reset
cancels pending radio controls and clears responses/subscriptions; late callbacks
do not leak into a fresh registration. Runtime close retires the module itself.
A recovered radio requires a new protocol owner after source retirement.

The default remains `radio=null`. A source missing at initial description time
cannot silently appear later in that owner. Runtime/protocol injection accepts an
explicit nanosecond clock for tests and brokers; the default is elapsed realtime,
which must match the backend's observation clock. The isolated module envelope
must never be installed as a competing second plugin-0 handler.

## Tests and real-world blockers

New tests cover exact descriptors and command parameter formats, OPACK round trips,
unknown metadata, delayed observation, rejection of optimistic ACKs, timeout,
revocation/retirement and invalid snapshots. Aggregate tests combine range, climate
and radio, verify one mixed register/update, concurrent confirmed climate/radio
operations, ambiguous-control rejection and source-loss cancellation. Two local
encrypted two-lane tests use real authenticated sockets and a simulated receiver:
the isolated module and the actual aggregate runtime exercise config/register,
requested station, observed change, encrypted confirmation/update, source loss
and channel teardown. All twenty radio tests passed within the full coordinated
CI run (3,416 registered tests, zero failures/errors, one pre-existing skip,
plus lint and debug builds). None is evidence of C11 tuner control or iPhone
acceptance.

No authorized C11 DAB receiver source is established in the current project.
The available OEM binder analysis proves package/permission filtering, not an
application-authorized tuner contract. A broker must establish receiver type,
permission, station IDs and metadata, event timestamps and supported commands,
then confirm each actual change. For an FM-only car, independently verified FM
schema and units are required before expanding this DAB-only module. A working
icon, resource package, media URL or generic Android player is not that proof.
