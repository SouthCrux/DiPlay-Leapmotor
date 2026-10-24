# DiPlay: prueba mínima de SiriKit

Estado: **proyecto preparado, sin compilar para iOS ni instalar**. La Mac solo
tiene Command Line Tools; no está disponible el SDK de iPhone ni XCTest. El
propietario confirmó que tiene una cuenta Apple gratuita. La capacidad Siri no
está disponible para ese tipo de firma según la tabla de Apple consultada el
24/10/2026. App Groups sí figura disponible para cuentas gratuitas; el bloqueo
es específicamente Siri. No se ha comprado una membresía ni modificado el coche.

Validación local del 24/10/2026: núcleo compilado, 13 comprobaciones CLI PASS,
proyecto/plists/esquema válidos y CI Android completo PASS. `swift test` no llegó
a ejecutar las pruebas XCTest porque ese framework no está instalado con estas
Command Line Tools. Las fuentes de app y extensión siguen sin validación contra
el SDK de iOS; no hay IPA ni prueba física de Siri.

## Qué demuestra y qué no

El primer objetivo es comprobar si Siri invoca una extensión nuestra para
`INGetCarPowerLevelStatusIntent`, qué responde y qué ocurre con el teléfono
bloqueado. Apple marca este intent como que siempre requiere desbloqueo. No se
presupone que CarPlay lo exima, ni que una respuesta fuera de CarPlay demuestre
compatibilidad dentro de CarPlay.

La app SwiftUI y su extensión comparten un App Group firmado. Solo se responde
por **C11 de prueba**, con **50 % y 180 km SIMULADOS**, después de activar una
ventana de diez minutos desde la app. Sin activación, tras vencer o ante otro
vehículo, devuelve un fallo. La pantalla identifica claramente los datos como
simulados. Siri recibe una respuesta estándar: durante la prueba hay que usar
el nombre de prueba y no confundir su respuesta con el coche real.

La última fase recibida (received/resolve/confirm/handle), su resultado fijo y
hora quedan visibles al volver a la app. No se guardan frases, VIN, ubicación,
identidad del iPhone ni datos reales. La extensión no puede saber por sí sola
si el teléfono estaba bloqueado o si se usó Ultra; eso se registra manualmente.
No hay API de red, nube, MCP, comandos de coche ni enlace con DiPlay todavía.

## Preparar y ejecutar con Xcode

1. Instalar Xcode completo y su plataforma iOS. El proyecto no necesita XcodeGen,
   CocoaPods ni dependencias descargadas. Se genera con
   `python3 tools/siri-probe/generate_project.py` si se modifica su configuración.
2. Abrir `DiPlaySiriProbe.xcodeproj`. Elegir un equipo con membresía que admita
   Siri. Para desarrollo local, crear `Signing.local.xcconfig` junto a
   `Config.xcconfig` (está excluido de Git):

   ```xcconfig
   DEVELOPMENT_TEAM = TU_TEAM_ID
   SIRI_PROBE_BUNDLE_PREFIX = tu.dominio.diplaysiriprobe
   SIRI_PROBE_APP_GROUP = group.tu.dominio.diplaysiriprobe
   ```

3. En ambos targets comprobar firma automática, Siri y el mismo App Group.
   Revisar el proyecto con `xcodebuild -list -project DiPlaySiriProbe.xcodeproj`.
   Primero compilar sin firma para el SDK disponible; después seleccionar el
   iPhone para la prueba real. La generación y el lint del plist no prueban una
   compilación iOS; las fuentes SwiftUI/Intents siguen pendientes de ese paso.
4. Instalar, abrir, autorizar Siri y activar simulación. La app registra el nombre
   del coche como vocabulario al obtener autorización. Probar las frases de la
   pantalla; si Siri pide otra frase o el nombre de la app, registrar eso.

## Criterios de la prueba física

Usar siempre el coche de prueba. Para cada fila anotar fecha, versión iOS,
respuesta exacta, si pidió desbloquear y última fase que llegó a la extensión:

| Contexto | Desbloqueado | Bloqueado |
| --- | --- | --- |
| iPhone sin CarPlay | Pendiente | Pendiente |
| CarPlay común | Pendiente | Pendiente |
| CarPlay Ultra | Pendiente | Pendiente |

La respuesta debe indicar 50 % y/o 180 km según la pregunta. Confirmar físicamente
la interpretación del porcentaje por Siri y las unidades; no basta con ver que
el handler devuelve success. No inventar capacidades máximas, estado de carga,
kWh de batería ni tiempo restante. Si no funciona bloqueado o Siri no enruta el
intent, evaluar ese resultado antes de desarrollar el enlace con el coche.

Tras validar la invocación, el siguiente incremento sería una conexión local
autenticada y de solo lectura con DiPlay, retirando valores caducados o perdidos.
La simulación nunca deberá convertirse en respaldo de una consulta al coche.

## Comprobaciones locales

Con Xcode completo:

```sh
swift test --package-path tools/siri-probe
```

Con Command Line Tools, el núcleo puede comprobarse sin XCTest:

```sh
swiftc tools/siri-probe/Shared/ProbeStore.swift tools/siri-probe/Checks/main.swift -o /private/tmp/diplay-siri-core-check
/private/tmp/diplay-siri-core-check
```

Esto verifica activación, caducidad, reloj hacia atrás, nombres y metadatos
compartidos. No verifica firma, Siri, bloqueo, CarPlay ni compilación iOS.

## Fuentes Apple

- [Intent de energía y requisito de desbloqueo](https://developer.apple.com/documentation/intents/ingetcarpowerlevelstatusintent)
- [Handler de energía](https://developer.apple.com/documentation/intents/ingetcarpowerlevelstatusintenthandling)
- [Respuesta con porcentaje y distancia](https://developer.apple.com/documentation/intents/ingetcarpowerlevelstatusintentresponse)
- [Capacidades según membresía](https://developer.apple.com/help/account/reference/supported-capabilities-ios)
- [Configurar Siri](https://developer.apple.com/documentation/xcode/configuring-siri-support)
- [Diferencia entre comandos de vehículo y categorías CarPlay, WWDC17](https://developer.apple.com/videos/play/wwdc2017/719/?time=1505)

La explicación WWDC17 no garantiza por sí sola el comportamiento en la versión
actual de iOS; la matriz anterior sigue pendiente de pruebas reales.
