import Foundation

// Standalone core checks for hosts with Command Line Tools but without XCTest/Xcode.
// Compile together with Shared/ProbeStore.swift. This does not invoke Siri.
struct CheckFailure: Error { let message: String }
func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw CheckFailure(message: message) }
}
let suite = "DiPlayProbeCLI.\(UUID().uuidString)"
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
var clock = Date(timeIntervalSince1970: 1000)
let app = ProbeStore(defaults: defaults, now: { clock })
let extensionStore = ProbeStore(defaults: defaults, now: { clock })
try check(app.snapshot() == nil, "Disabled by default")
app.armSimulation()
try check(extensionStore.snapshot()?.batteryPercent == 50, "Shared simulated percentage")
try check(extensionStore.snapshot()?.rangeKilometres == 180, "Shared simulated kilometres")
try check(extensionStore.snapshot()?.origin == "SIMULATED", "Explicit synthetic origin")
clock.addTimeInterval(599)
try check(app.snapshot() != nil, "Available before expiry")
clock.addTimeInterval(1)
try check(app.snapshot() == nil, "Withdrawn at expiry")
clock = Date(timeIntervalSince1970: 999)
try check(app.snapshot() == nil, "Clock reversal does not revive a fixture")
app.armSimulation()
app.disableSimulation()
try check(extensionStore.snapshot() == nil, "Disable is shared")
try check(app.matches(carName: " C11 DE PRUEBA "), "Known simulated car resolves")
try check(!app.matches(carName: "mi coche"), "Real or unknown vehicle is rejected")
try check(!app.matches(carName: ""), "Empty supplied car is rejected")
extensionStore.record(.handle, .simulated)
try check(app.lastEvent()?.phase == .handle, "Invocation metadata shared")
try check(app.lastEvent()?.origin == "SIMULATED_PROBE", "Invocation origin is explicit")
print("PASS: 13 core checks; no Siri invocation, iOS build or car connection tested.")
