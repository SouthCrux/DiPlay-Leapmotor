import Foundation
import XCTest
@testable import SiriProbeCore

final class ProbeStoreTests: XCTestCase {
    func isolated(_ body: (UserDefaults) -> Void) {
        let name = "DiPlaySiriProbeTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        body(defaults)
    }

    func testDisabledUntilExplicitlyArmedAndExpiresWithoutInventingRealData() {
        isolated { defaults in
            var clock = Date(timeIntervalSince1970: 1000)
            let store = ProbeStore(defaults: defaults, now: { clock })
            XCTAssertNil(store.snapshot())
            store.armSimulation()
            XCTAssertEqual(store.snapshot()?.origin, "SIMULATED")
            XCTAssertEqual(store.snapshot()?.batteryPercent, 50)
            XCTAssertEqual(store.snapshot()?.rangeKilometres, 180)
            clock.addTimeInterval(599)
            XCTAssertNotNil(store.snapshot())
            clock.addTimeInterval(1)
            XCTAssertNil(store.snapshot())
            clock = Date(timeIntervalSince1970: 999)
            XCTAssertNil(store.snapshot())
            store.armSimulation()
            store.disableSimulation()
            XCTAssertNil(store.snapshot())
        }
    }

    func testSeparateAppAndExtensionStoresShareOptInAndFixedDiagnosticOnly() {
        isolated { defaults in
            let app = ProbeStore(defaults: defaults)
            let extensionStore = ProbeStore(defaults: defaults)
            app.armSimulation()
            XCTAssertNotNil(extensionStore.snapshot())
            extensionStore.record(.handle, .simulated)
            XCTAssertEqual(app.lastEvent()?.phase, .handle)
            XCTAssertEqual(app.lastEvent()?.origin, "SIMULATED_PROBE")
            app.disableSimulation()
            XCTAssertNil(extensionStore.snapshot())
        }
    }

    func testNeverAnswersForAnUnrelatedVehicle() {
        isolated { defaults in
            let store = ProbeStore(defaults: defaults)
            XCTAssertTrue(store.matches(carName: nil))
            XCTAssertTrue(store.matches(carName: " C11 DE PRUEBA "))
            XCTAssertFalse(store.matches(carName: "mi coche"))
            XCTAssertFalse(store.matches(carName: "C11"))
            XCTAssertFalse(store.matches(carName: ""))
        }
    }
}
