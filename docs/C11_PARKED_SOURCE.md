# C11 read-only parked source

`LeapmotorParkedIntegration.acquire(context)` creates an explicit C11 session lease.
`lease.readParked()` reads memory only and returns `Boolean?`; close the lease with
the owning session. There is no boot/Bluetooth start, setting, video activation or
reconnection loop. This source is prepared locally and has not been validated in
the vehicle.

The platform connection loads only the optional installed `android.car` API and
uses the locally verified `Car.createCar(Context, ServiceConnection, Handler)`,
`connect()`, `getCarManager("property")` and `disconnect()` methods. Connection,
manager retrieval and read polling run on a worker; disconnect uses a separate
cleanup worker so a blocked Binder read cannot hold the UI thread. Missing library,
connection timeout/loss, denied permission and close all leave the result unknown.
At most four sources may own platform access concurrently. Their slots remain held
until bootstrap, the read worker and actual disconnect have all retired, even if a
Binder read survives disconnect. Cleanup uses one worker; there are no global retries.

Required manifest declarations are the normal permission
`android.car.permission.CAR_POWERTRAIN` and optional library `android.car`
(`required="false"`). The extracted C11 CarService manifest declares this permission
with protection level `0x0`; its property mapping assigns `GEAR_SELECTION`
289408000 to it. No climate privilege or OEM broker access is requested here.

The reader verifies global integer READ/ON_CHANGE configuration, property/area,
status, supported gear value and OEM elapsed-realtime timestamp. Only gear 4 is P;
other known gears are false, unknown/invalid values are null. Zero and future OEM
timestamps are never accepted.

Two explicit policies keep timestamp semantics separate. The typed reader defaults
to `OEM_EVENT_TIMESTAMP`, rejecting an event older than five seconds. The platform
integration uses `CURRENT_HAL_SNAPSHOT`: each successful synchronous HAL get is a
current state delivery, with a separate local completion time and a maximum get
operation duration of five seconds. A previous ON_CHANGE event timestamp can be
old; it is retained unchanged. The cached result expires five seconds after HAL
read completion if polling stalls. Failed reads replace the cache with unknown.
Local delivery time is never presented as a new vehicle measurement timestamp.

This policy follows the extracted platform implementation, which forwards every
`CarPropertyManager.getProperty` call through `CarPropertyService.getProperty`,
`PropertyHalService.getProperty`, `VehicleHal.get` and `HalClient.getValue` to a
synchronous vehicle HAL get. The Java service does not return its listener cache.
`CarPropertyUtils` copies the HAL timestamp unchanged into `CarPropertyValue`.
Inferring current state from a successful AVAILABLE HAL response assumes the
vendor honors that platform contract; actual C11 readings remain unverified.
This is not permission to enable playback before vehicle validation.

`lease.diagnosticSnapshot()` reads metadata without opening a connection: source
status, declared permission availability, reading state, OEM event age, local HAL
delivery age, parked result and policy name. It contains no VIN or location. This
allows a later coordinated car trial to distinguish an old but successfully read
ON_CHANGE event from a blocked source or permission error.

Evidence: captured `hal-config.txt` includes property 289408000 READ/ON_CHANGE,
configArray including 4; extracted `VehicleGear.java` defines GEAR_PARK=4;
`PropertyPermissionMapping.java` maps the property to CAR_POWERTRAIN. Existing
gear dumps contain no current sample. Unit fixtures are simulated and do not
prove vehicle access or playback readiness.

## Captured metadata

The Activity's observation lease emits only the fixed `C11 parked observation:`
summary. `tools/carplay/capture_events.py` recognizes it as
`c11_parked_observation`: source status, permission flag, reading state, nullable
parked value, the fixed freshness policy and the two distinct ages. Matching is
anchored to the complete message; unknown enum values, trailing payloads and
noncanonical or excessive ages are rejected. Ages are nullable or integer
milliseconds bounded to 365 days. This capture event proves only what the local
source reported; it does not establish physical vehicle acceptance or permission
to activate video.

The synchronous-read evidence is in the extracted C11 sources under
`.private/c11/20261016T005916Z/decompiled/`:

- `android-car/sources/android/car/hardware/property/CarPropertyManager.java:360`
  forwards `getProperty(int,int)` to the service.
- `car-service/sources/com/android/car/CarPropertyService.java:347` validates the
  configuration, asserts the read permission and calls the property HAL.
- `car-service/sources/com/android/car/hal/PropertyHalService.java:91` invokes
  `mVehicleHal.get(halPropId,areaId)`.
- `car-service/sources/com/android/car/hal/VehicleHal.java:339` invokes
  `mHalClient.getValue(...)`.
- `car-service/sources/com/android/car/hal/HalClient.java:93` handles the actual HAL
  get response/status and rejects failure or a missing response.
- `car-service/sources/com/android/car/hal/CarPropertyUtils.java:36` copies the
  HAL timestamp into the returned property value without renewing it.

These are implementation evidence for the platform path, not new evidence about
how this vehicle's vendor HAL obtains or refreshes its underlying CAN state.
