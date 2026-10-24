import SwiftUI
import Intents

@main
struct ProbeApp: App {
    var body: some Scene { WindowGroup { ProbeView() } }
}

struct ProbeView: View {
    private let store = ProbeStore.configured()
    @State private var authorization = "Sin solicitar"
    @State private var refresh = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        let _ = refresh
        return NavigationStack {
            Form {
                Section("Prototipo: datos simulados") {
                    Text("No está conectado al coche. Sirve para probar si Siri invoca nuestra extensión.")
                    Text("Vehículo: C11 de prueba")
                    Text("Valores de prueba: batería 50 %, autonomía 180 km.")
                    if store == nil {
                        Text("App Group no disponible. Revisá firma y capacidades de ambos targets.")
                            .foregroundStyle(.red)
                    }
                }
                Section("Preparación") {
                    Button("Autorizar Siri") {
                        INPreferences.requestSiriAuthorization { status in
                            DispatchQueue.main.async {
                                authorization = status == .authorized ? "Autorizado" : "No autorizado (\(status.rawValue))"
                                if status == .authorized {
                                    INVocabulary.shared().setVocabularyStrings(
                                        NSOrderedSet(array: [ProbeStore.carName]), of: .carName)
                                }
                            }
                        }
                    }
                    Text(authorization)
                    Button("Activar simulación durante 10 minutos") { store?.armSimulation(); refresh = Date() }
                        .disabled(store == nil)
                    Button("Desactivar simulación", role: .destructive) { store?.disableSimulation(); refresh = Date() }
                    Text(store?.snapshot() == nil ? "Simulación desactivada o vencida" : "SIMULACIÓN ACTIVA: 50 % y 180 km")
                }
                Section("Prueba de voz") {
                    Text("Preguntá a Siri: «¿Cuánta carga tiene C11 de prueba usando DiPlay Prueba?»")
                    Text("Después: «¿Qué autonomía tiene C11 de prueba usando DiPlay Prueba?»")
                    Text("Repetir con el iPhone desbloqueado y bloqueado; después con CarPlay común y Ultra. Si Siri pide desbloquear, anotarlo como resultado.")
                }
                Section("Última llamada recibida") {
                    if let event = store?.lastEvent() {
                        Text("\(event.phase.rawValue) · \(event.result.rawValue)")
                        Text(event.timestamp, style: .time)
                    } else { Text("La extensión todavía no registró llamadas.") }
                }
            }
            .navigationTitle("DiPlay Prueba")
            .onReceive(timer) { refresh = $0 }
        }
    }
}
