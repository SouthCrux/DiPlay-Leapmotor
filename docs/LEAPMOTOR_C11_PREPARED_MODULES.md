# C11: preparación local de los siguientes módulos

Estado actual tras completar todos los módulos: [integración local consolidada](LEAPMOTOR_C11_LOCAL_MODULES.md).
El resto de este documento conserva el alcance de los incrementos iniciales de catálogo, carga y puertas.

Fecha: 2026-10-23. Trabajo offline solicitado por el usuario tras retirar el coche.
Climatización queda al final. No se consultó el vehículo durante este trabajo.

## Orden y alcance

| Orden | Módulo | Preparación local | Pendiente |
| --- | --- | --- | --- |
| 1 | Carga | Decoder, caché por campo y suscripción pasiva conectada al diagnóstico local; sin instalar | Transiciones, enumeraciones, potencia, tiempo restante y límite |
| 2 | Puertas y cierres | Decoder, caché por puerta y suscripción pasiva conectada al diagnóstico local; sin instalar | Significado de códigos y distinción entre cierre, bloqueo y posición; capó y cierre centralizado sin ruta confirmada |
| 3 | Ventanas y techo | Cuatro posiciones 0–100; vidrio y cortinilla separados | Sentido físico de extremos, equipamiento y transiciones |
| 4 | Asientos | Módulo reservado en el catálogo | Falta confirmar consumidores de calefacción/ventilación; interfaz SDK no demuestra acceso |
| 5 | Luces | Observaciones de bajas, altas, antiniebla trasera e interruptores de giro | Enumeraciones; interruptor no demuestra que la lámpara esté encendida; iluminación ambiental pendiente |
| 6 | Energía y consumo | Módulo reservado en el catálogo | Rutas, unidades y disponibilidad de consumo/potencia/regeneración; no se estima consumo con diferencias de SOC |
| 7 | Conducción | Marcha y estado del vehículo/READY como códigos raw | Enumeraciones, velocidad y modo de conducción; nunca se usan estos candidatos para autorizar acciones |
| 8 | Avisos | Cinco códigos raw de cinturones | Equipamiento, asignación de plazas y semántica; no son alertas de seguridad interpretadas |
| 9 | Climatización | Se conserva el trabajo existente; sin nuevos canales en este catálogo | Retomar después de los anteriores |

En el primer incremento los 26 canales quedaron offline. El siguiente incremento
conectó los tres canales de carga al suscriptor JNI y al diagnóstico. El incremento
de puertas añade únicamente sus cinco canales. Ambos permanecen sin instalar en
el coche. No se añaden comandos, permisos, pantallas ni capacidades Siri.
El comportamiento instalado de batería, autonomía, neumáticos, audio y mapas no cambia.
Asientos, energía y climatización devuelven listas vacías en este nuevo catálogo;
eso expresa ausencia de nuevos canales preparados, no ausencia de equipamiento.

## Rutas inspeccionadas

Todos los canales nuevos usan servicio `0x9900`, instancia `0x9901`, grupo `0x9903`
y enteros little-endian de cuatro bytes. Se contrastó el consumidor y la lista
de suscripciones, no solo una constante de nombre parecido.

| Módulo | Eventos | Consumidor local inspeccionado |
| --- | --- | --- |
| Carga | 5702 / 6207 / 5905 | `ExtendControlManager` → `charge_status` / `slowChargeStatus` / `fastChargeStatus`; [evidencia previa](LEAPMOTOR_C11_OEM_SIGNALS.md) |
| Puertas/baúl | 3860 / 3820 / 3821 / 3822 / 3823 | `BaseControlManager.onMessageInteger`: `leftDoorStatus`, escenas de puerta acompañante/traseras, `backDoorStatus`; `CanEvent` identifica conductor y acompañante |
| Ventanillas | 2900 / 3000 / 2901 / 3001 | `left_front_win_pos` / `right_front_win_pos` / `left_back_win_pos` / `right_back_win_pos`, validación 0–100 y salida UI `100-raw` |
| Vidrio/cortinilla | 2800 / 2801 | Ramas independientes `getSkylight()` / `getSunshade()`; [contrato previo](ULTRA_ROOF_SUNSHADE.md) |
| Luces | 3102 / 3814 / 2727 / 3807 / 3808 | Escenas `LowBeamCtrl`, `HighBeamCtrl`, `RearFogCtrlSts`, `TurnLeftSwtSts`, `TurnRightSwtSts` |
| Conducción | 5211 / 5301 | `gearLevel` y escenas de marcha; rama `isReady` |
| Cinturones | 9004 / 9005 / 9006 / 9007 / 9008 | `mainSeatBeltRSt`, `subSeatBeltRSt`, `backLeftSeatBeltRSt`, `backMidSeatBeltRSt`, `backRightSeatBeltRSt`; asignación condicionada por variante/plazas |

Fuentes privadas inspeccionadas, sin copiar implementación OEM al fork:

- `.private/c11/ultra-20261020/BaseControlManager-sunshade-fallback.java`:
  inicializador de suscripciones y `onMessageInteger` (líneas 3349 en adelante).
- `.private/c11/20261016T005916Z/ExtendControlManager-simple.java`:
  `extendEvents` y ramas de carga, contrastadas con el fallback según el informe OEM.
- `.private/c11/20261016T005916Z/decompiled/carcontrol/sources/com/leapmotor/can/external/CanEvent.java`:
  contraste de nombres de conductor/acompañante; no se importan estos tipos al proyecto.

Estas correspondencias son evidencia estática del firmware analizado. No confirman
que todos los eventos se entreguen en este C11. Las observaciones previas de carga
fueron códigos 0 sin transiciones; la cortinilla sí tuvo una recepción previa.
Ni una entrega ni un código 0 prueban salud del sensor o disponibilidad del control.

## Decoder y reproducción

`LeapmotorPreparedModules.kt` contiene el catálogo y decoder; `LeapmotorPreparedModuleState`
mantiene épocas de fuente y vencimiento individual de 60 segundos. Es una política
local de retirada, no la cadencia OEM ni la edad de la medición. Callbacks de una fuente
anterior se ignoran. Mensajes malformados o fechas inválidas retiran solo su campo.
Los valores sin cambio también renuevan su recepción cuando llega un callback.

Todos los valores válidos quedan `OBSERVED_UNVERIFIED`. No se convierte un entero
desconocido en «cerrado», «no cargando», «cinturón abrochado» ni marcha P.
El decoder valida 0–100 únicamente para posiciones cuyo consumidor lo comprueba.
No se aplica ese rango al vidrio 2800. No se asigna el lado físico del conductor
por el nombre `leftDoorStatus`; los canales delanteros se llaman conductor/acompañante.

`shared/src/test/resources/c11-prepared-modules-simulated.tsv` es una secuencia
**sintética** con los 26 canales. No contiene capturas del coche ni prueba sus códigos.
`LeapmotorPreparedModulesTest` la reproduce y comprueba identidad de fuente,
campos malformados, posiciones fuera de rango, épocas, silencio independiente,
reloj hacia atrás y callbacks fuera de orden.

Prueba local específica:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testDebugUnitTest \
  --tests 'com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorPreparedModulesTest'
```

## Siguiente incremento con el coche

Empezar por carga: instalar la versión local con los tres eventos conectados al
suscriptor existente y contrastar sus transiciones. La ausencia de carga no se infiere de un
cable desconectado ni de códigos desconocidos. Después contrastar puertas; la
preparación de ventanas sigue por separado. Los módulos se habilitan para UI/Siri solo con semántica validada;
esta preparación no atribuye a Siri acceso a estos datos.

## Carga: conexión local al diagnóstico

`LeapmotorSomeIpChargingState` expone exclusivamente los tres eventos de carga;
rechaza el resto del catálogo preparado incluso si se entrega un mensaje válido.
`LeapmotorTelemetryService` los añade al suscriptor ya existente, sin abrir otra
conexión. `LeapmotorTelemetry` conserva su caché y emite lotes limitados por
`DiagnosticBatch`, cancelados al sustituir o perder la fuente.

Ejemplo de formato, **simulado**:

```text
C11 CHARGING status=OBSERVED_UNVERIFIED channel=CHARGE_STATUS raw=1 receivedAgeMs=0
```

El tag es `DiPlay-CHARGING`. El informe local incluye los tres canales; el
capturador `tools/carplay/capture_events.py` exporta solo los campos permitidos
como `c11_charging_observation`, con `semanticsVerified=false` y
`writeControlAvailable=false`. Rechaza contenido adicional, edades incompatibles,
valores fuera de INT32 y estados contradictorios.

El campo `charging` de `VehicleStatusSnapshot` continúa siendo `null`; no se
convierte un código raw en estado anunciado al iPhone. Pruebas de integración
con Messenger verifican recepción, payload de tamaño incorrecto, reemplazo de
fuente, vencimiento independiente, cancelación de logs y conservación de SOC/autonomía.
No hubo acceso al coche ni prueba física en este incremento.

## Puertas y baúl: conexión local al diagnóstico

`LeapmotorSomeIpDoorState` admite exclusivamente los cinco eventos de puertas/baúl
del catálogo: conductor 3860, acompañante 3820, trasera izquierda 3821,
trasera derecha 3822 y baúl 3823. No se intercambian conductor/acompañante por
izquierda/derecha. El baúl conserva el nombre `TAILGATE_STATUS`: su observación
no establece por sí sola posición, apertura o bloqueo.

Se añaden al suscriptor existente, con caché y edad independientes por campo,
época de fuente, invalidación por payload incorrecto y limpieza al perder la
conexión. Los mensajes de una fuente retirada y sus logs pendientes no reviven datos.
Ni los eventos de carga ni los de otra puerta renuevan la edad de una puerta silenciosa.

El informe incluye las cinco observaciones. Los lotes de log usan el tag
`DiPlay-DOORS`; el capturador exporta `c11_door_observation`, con códigos INT32,
estado y edad de recepción, `semanticsVerified=false`, `writeControlAvailable=false`.
No exporta el texto original ni infiere los booleanos «abierto» o «bloqueado».

Pruebas con Messenger cubren separación de los cinco canales, conservación de
carga/SOC/autonomía, invalidación individual, vencimiento y reemplazo de fuente.
El lector rechaza eventos de los demás módulos e identidades de servicio incorrectas.
La captura prueba límites numéricos, estados contradictorios y contenido adicional.
No se modifica la UI, el protocolo Siri ni se añade una ruta de control. No hubo
acceso al coche ni instalación en este incremento; los cinco códigos siguen
pendientes de contrastar físicamente.
