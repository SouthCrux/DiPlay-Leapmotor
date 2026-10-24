import Foundation

/// This first probe has no network client and never represents real vehicle telemetry.
public final class ProbeStore {
    public static let carName = "C11 de prueba"
    public static let carIdentifier = "diplay-simulated-c11"
    public static let lifetime: TimeInterval = 600
    private let defaults: UserDefaults
    private let now: () -> Date

    public struct Snapshot: Equatable {
        public let batteryPercent: Float
        public let rangeKilometres: Double
        public let origin = "SIMULATED"
    }

    public struct Event: Codable, Equatable {
        public enum Phase: String, Codable { case received, resolve, confirm, handle }
        public enum Result: String, Codable { case invoked, simulated, unavailable, wrongCar }
        public let phase: Phase
        public let result: Result
        public let timestamp: Date
        public let origin: String
    }

    public init(defaults: UserDefaults, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
    }

    /// No fallback to standard defaults: app and extension must share the signed App Group.
    public static func configured(bundle: Bundle = .main) -> ProbeStore? {
        guard let group = bundle.object(forInfoDictionaryKey: "ProbeAppGroup") as? String,
              group.hasPrefix("group."), !group.contains("$("),
              FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil,
              let defaults = UserDefaults(suiteName: group) else { return nil }
        return ProbeStore(defaults: defaults)
    }

    public func armSimulation() { defaults.set(now(), forKey: "simulationArmedAt") }
    public func disableSimulation() { defaults.removeObject(forKey: "simulationArmedAt") }

    public func snapshot() -> Snapshot? {
        guard let armed = defaults.object(forKey: "simulationArmedAt") as? Date else { return nil }
        let age = now().timeIntervalSince(armed)
        guard age.isFinite, age >= 0, age < Self.lifetime else { return nil }
        return Snapshot(batteryPercent: 50, rangeKilometres: 180)
    }

    /// Only the named simulated car is answered, never an arbitrary real vehicle.
    public func matches(carName: String?) -> Bool {
        guard let carName else { return true }
        return carName.trimmingCharacters(in: .whitespacesAndNewlines)
            .compare(Self.carName, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }

    /// Fixed metadata only. No transcript, phone identity, VIN, location or real car values.
    public func record(_ phase: Event.Phase, _ result: Event.Result) {
        let event = Event(phase: phase, result: result, timestamp: now(), origin: "SIMULATED_PROBE")
        if let data = try? JSONEncoder().encode(event) { defaults.set(data, forKey: "lastProbeEvent") }
    }

    public func lastEvent() -> Event? {
        guard let data = defaults.data(forKey: "lastProbeEvent") else { return nil }
        return try? JSONDecoder().decode(Event.self, from: data)
    }
}
