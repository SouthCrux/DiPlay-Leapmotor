import Intents

final class IntentHandler: INExtension, INGetCarPowerLevelStatusIntentHandling {
    private let store = ProbeStore.configured()

    override func handler(for intent: INIntent) -> Any? {
        guard intent is INGetCarPowerLevelStatusIntent else { return nil }
        store?.record(.received, .invoked)
        return self
    }

    func resolveCarName(for intent: INGetCarPowerLevelStatusIntent,
                        with completion: @escaping (INSpeakableStringResolutionResult) -> Void) {
        guard let store else { completion(.unsupported()); return }
        guard store.matches(carName: intent.carName?.spokenPhrase) else {
            store.record(.resolve, .wrongCar)
            completion(.unsupported())
            return
        }
        store.record(.resolve, .invoked)
        completion(.success(with: INSpeakableString(spokenPhrase: ProbeStore.carName)))
    }

    func confirm(intent: INGetCarPowerLevelStatusIntent,
                 completion: @escaping (INGetCarPowerLevelStatusIntentResponse) -> Void) {
        let available = store?.matches(carName: intent.carName?.spokenPhrase) == true && store?.snapshot() != nil
        store?.record(.confirm, available ? .simulated : .unavailable)
        completion(INGetCarPowerLevelStatusIntentResponse(code: available ? .ready : .failure, userActivity: nil))
    }

    func handle(intent: INGetCarPowerLevelStatusIntent,
                completion: @escaping (INGetCarPowerLevelStatusIntentResponse) -> Void) {
        guard let store, store.matches(carName: intent.carName?.spokenPhrase), let snapshot = store.snapshot() else {
            store?.record(.handle, .unavailable)
            completion(INGetCarPowerLevelStatusIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let response = INGetCarPowerLevelStatusIntentResponse(code: .success, userActivity: nil)
        response.carIdentifier = ProbeStore.carIdentifier
        response.chargePercentRemaining = snapshot.batteryPercent
        response.distanceRemaining = Measurement(value: snapshot.rangeKilometres, unit: .kilometers)
        response.distanceRemainingElectric = response.distanceRemaining
        store.record(.handle, .simulated)
        completion(response)
    }
}
