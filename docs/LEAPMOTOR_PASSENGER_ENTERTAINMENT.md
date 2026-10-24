# Entretenimiento en la pantalla del acompañante

Estado: investigación local del 19 de octubre de 2026. No se accedió al coche,
no se modificaron sus aplicaciones y no se activó una nueva capacidad del iPhone.

## Objetivo y destino físico

El objetivo es mantener navegación y controles en la pantalla del conductor,
mientras el acompañante dispone de aplicaciones y vídeo en su propia pantalla.
Eso requiere un destino independiente; duplicar CarPlay no satisface el objetivo.

La inspección previa del coche identificó el acompañante como Android display 3,
`local:130`, panel 1920×720 y área de aplicaciones 1920×576 en la configuración
observada. Los insets explican la diferencia; no hay que cambiar la resolución
principal para llenar este panel. Los IDs lógicos deben volver a resolverse y
validarse al conectar: no se debe elegir cualquier pantalla 1920×720, pues el
tablero de instrumentos tiene esa misma geometría. Véase
[el registro de geometría](LEAPMOTOR_C11_ULTRA_STATUS.md).

## Tres rutas distintas

| Ruta | Evidencia actual | Límite actual |
| --- | --- | --- |
| Aplicaciones Android del acompañante | El launcher de desarrollo `com.santiago.passenger` usa `ActivityOptions.setLaunchDisplayId(3)` y ofrece accesos a música, navegador, mapas y plataformas de vídeo | Es software Android del coche; los accesos no demuestran instalación, reproducción ni compatibilidad de cada plataforma |
| Vídeo del iPhone mediante CarPlay/AirPlay | DiPlay implementa negociación `videoPlayback`, recepción de medios y reproductor Android; ahora existe selección local explícita del acompañante | Una única reproducción global; el destino Android no equivale a un rol iPhone independiente y la integración C11 todavía no ofrece vídeo |
| Interfaz iPhone/Ultra independiente para el acompañante | No se encontró contrato probado en la implementación ni en la documentación pública consultada | No hay rol, layout, selector de aplicaciones o segunda sesión independiente verificados |

El launcher actual puede seguir siendo el punto de entrada de aplicaciones nativas.
No hay que presentarlo como si sus aplicaciones provinieran del iPhone.

## Lo que permite Apple públicamente

Apple describe vídeo por AirPlay en vehículos compatibles cuando no se conduce,
y aplicaciones CarPlay de vídeo para usar con el vehículo estacionado.
[CarPlay para desarrolladores y fabricantes](https://developer.apple.com/carplay/).

La sesión oficial de iOS 27 explica que las aplicaciones de vídeo necesitan
AirPlay y que una interfaz de navegación de contenido usa el framework CarPlay.
Su aparición depende del entitlement de vídeo y de la capacidad anunciada por el
vehículo. Si el vehículo retira la disponibilidad, la reproducción pasa a audio.
Esto no equivale a habilitar cualquier aplicación del iPhone ni establece un
destino independiente para el acompañante.
[Rev up your CarPlay app, WWDC26](https://developer.apple.com/videos/play/wwdc2026/212/).

Apple también advierte que algunas aplicaciones de vídeo no admiten AirPlay.
La duplicación de pantalla y la transmisión de un medio son funciones distintas.
[Uso de AirPlay](https://support.apple.com/en-us/102661).

La falta de un contrato público de acompañante en estas fuentes es un límite de
la evidencia consultada, no una prueba de que Apple nunca pueda admitirlo. No se
debe inventar una clave de rol para intentar declararlo.

## Qué hace el código de DiPlay

- `VideoInCar.info()` anuncia capacidades de reproducción y un booleano de
  disponibilidad, sin área o pantalla de destino.
- `AirPlaySession` negocia `videoPlayback` y procesa `requestUI videoplayback:`.
  Los mensajes de control se identifican por stream, pero el callback no transporta
  una identidad de pantalla física.
- `CarPlayMediaEngine.videoDataStream()` acepta los canales de control/settings.
  Un stream de control separado no constituye una pantalla de contenido separada.
- `CarPlayVideo` conserva un solo `streamId`, medio, reproductor y actividad.
  La ruta normal mantiene su lanzamiento habitual. La selección explícita del
  acompañante usa `setLaunchDisplayId` únicamente tras validar el destino.
- `CarPlayVideoActivity` reproduce medios con Media3 y devuelve errores de formato,
  red o decodificación. La ruta actual no implementa un receptor FairPlay con
  licencias: las URL de claves `skd` se rechazan. No se puede prometer reproducción
  de Netflix o Disney+ desde el iPhone basándose en esa implementación.
- La oferta y el estado P provienen actualmente de `BydOutputSettings` y
  `BydNavigationOutputs`. `VehicleIntegrationPolicy` excluye esos accesos cuando
  la variante es C11; el estado estacionado es desconocido y el vídeo no se ofrece.

No se encontró un símbolo llamado exactamente `VideoPlaybackSupport` en las
fuentes actuales examinadas. Los contratos efectivos son `VideoInCar`,
`VideoPlaybackAvailability` y `CarPlayVideoListener`.

## Ruta local implementada

`CarPlayVideo.selectPassengerDestinationForCurrentSession(context)` valida el
destino medido y selecciona el acompañante para la sesión actual. Debe llamarse
explícitamente antes de reproducir; devuelve `false` si no puede seleccionar la
ruta o si existe un lanzamiento/reproductor dueño de ella. No hay llamada
automática, control Settings nuevo ni modificación del estado P.
Un intento explícito fallido bloquea el vídeo de esa sesión y cierra cualquier
reproductor anterior: ignorar el `false` no produce una salida en la principal.
`selectDefaultDestinationForCurrentSession()` restaura explícitamente el destino
normal cuando no hay un lanzamiento activo. Reconectar reinicia la selección.

Cada controlador recibe un listener con generación propia. Los eventos del
controlador retirado, las actividades tardías y los leases reemplazados se
descartan. La actividad verifica su display antes de construir Media3; si Android
la inicia en otro display, termina sin crear el reproductor. La retirada o cambio
del display validado pausa y cierra el vídeo. Un evento de retirada invalida al
dueño aunque la consulta posterior ya devuelva un display disponible.
La pérdida revoca el lease y bloquea la ruta antes del cierre asíncrono de Android;
un `setRate` tardío no reactiva esa actividad. La recuperación requiere una nueva
selección explícita válida, la selección explícita del destino normal o una nueva
sesión.

`CarPlayVideoListener.onVideoSessionStarted()` renueva la generación para cada
AirPlaySession real, incluso al reconectar sobre el mismo CarPlayController.
El controlador asigna primero la sesión activa y serializa inicio, fin, controles
y petición de UI de vídeo con su lock de ciclo de vida. El fin tardío de una sesión
retirada no cierra su reemplazo. Cada sesión nueva restablece el destino normal:
la selección de acompañante debe repetirse explícitamente después de su inicio y
volver a validar el registro; no se hereda un opt-in anterior.
El gate retira inmediatamente la disponibilidad al cambiar o terminar la sesión.
Cada lectura lleva un epoch: un P obtenido por una lectura anterior que termina
después del reset no vuelve a habilitar vídeo. Las lecturas continuas normales de
BYD siguen funcionando entre esos límites; la nueva sesión necesita una lectura
nueva para habilitar reproducción.

`CarPlayController.currentAirPlaySession()` ofrece al anfitrión que adopta una
sesión en segundo plano su sesión viva actual, sin reproducir callbacks de UI.
No depende de que exista una máscara de esquinas del iPhone ni de un estilo
visual particular. Devuelve null al terminar o cerrar la sesión/controlador.

### Fuente P opcional, todavía sin activación C11 automática

`CarPlayVideo.attach(context, controller, parkedSourceFactory = ...)` permite
inyectar una fábrica opcional de `CarPlayVideoParkedSource`. La interfaz sólo
expone `readParked(): Boolean?` sobre datos ya confirmados y `close()`; ninguno
debe esperar I/O de plataforma. Sin fábrica, el comportamiento BYD habitual sigue
siendo el predeterminado. No se cambió la condición que ofrece vídeo al iPhone.

La fábrica se adquiere de forma diferida en el worker del gate, únicamente tras
el inicio real de una sesión AirPlay. Cada sesión tiene como máximo un intento de
adquisición. Si falla, el estado sigue desconocido sin volver a BYD. Fin,
reemplazo o cierre del controlador liberan su fuente; una adquisición o lectura
que termine tarde no puede aportar P al reemplazo. Cerrar la actividad anfitriona
no libera una sesión que continúa mediante CarPlayBackgroundSession.

Esta fábrica de disponibilidad todavía no está conectada a la fuente C11. Por
separado, `CarPlayHostActivity` ya adquiere una lease de observación diagnóstica de
`LeapmotorParkedIntegration` cuando Ultra fue habilitado manualmente, existe una
sesión AirPlay real y el anfitrión está visible. La cierra al pausar, terminar,
reemplazar la sesión o destruir la Activity. Esa lease no alimenta el gate de
vídeo ni cambia su oferta al iPhone. No requiere fingir un destino de acompañante
para observar el estado; habilitar reproducción sí sigue requiriendo provisión
física y validación del contrato P. El permiso normal `CAR_POWERTRAIN` y el lector
existente no demuestran por sí mismos una lectura fiable en el coche.

La fuente activa usa `CURRENT_HAL_SNAPSHOT`: timestamp del evento OEM y tiempo
local de entrega del GET síncrono se conservan separados. Una respuesta HAL
AVAILABLE válida puede conservar un evento ON_CHANGE antiguo, pero el resultado
cacheado vence si no llega una lectura válida en cinco segundos; un GET demasiado
lento, timestamp cero/futuro, error o pérdida de permiso produce desconocido.
Esta política se basa en la ruta plataforma GET→HAL sin caché Java de listeners;
falta confirmar el comportamiento del HAL real. Los diagnósticos guardan sólo
estado, permiso, P nullable y ambas edades, nunca payloads ni identificadores.

Cada MediaSource captura un dueño de sesión, ítem y lease para las peticiones de
playlist/claves que resuelve el iPhone. Cambiar el ítem o sesión cancela las
solicitudes pendientes; las respuestas y envíos tardíos no se atribuyen al nuevo
controlador. No se añadieron capacidades de descifrado o acceso a contenido.

El camino normal conserva soporte Android 25. La lectura de display usa la API
30 sólo cuando está disponible y mantiene el camino anterior en versiones
anteriores. El lanzamiento dirigido está protegido por la API 26; el provisioning
C11 exige Android 30 o posterior y el firmware medido.

### Provisioning pendiente de medir en el coche

El archivo `c11-passenger-route.json` debe estar en `context.noBackupFilesDir` de
DiPlay. El selector no lo crea ni lo repara. Tiene un máximo de 2.048 bytes y exige:

| Campo | Valor o procedencia |
| --- | --- |
| `schemaVersion` | `1` |
| `physicalUniqueId` | `local:130`, verificado en la medición |
| `fingerprint` | Fingerprint exacto del C11 admitido por `LeapmotorClusterDisplay.FINGERPRINT`; debe coincidir con el dispositivo actual |
| `bootCount` | `Settings.Global.BOOT_COUNT` del arranque medido, no un valor de ejemplo |
| `logicalDisplayId` | ID medido del acompañante en ese arranque; la observación histórica de `3` no es un valor fijo |
| `modeId` | Modo real medido para ese display |
| `width`, `height` | `1920`, `720` del panel físico |

La prueba de identidad es un registro local de una medición autorizada, no una
atestación criptográfica. La API pública `Display` de este firmware no proporciona
el `uniqueId` físico al selector: el registro vincula esa identidad al ID y modo
medidos. Si cambia la topología, hay que volver a medirla; no reutilizar por tamaño
un registro de otra pantalla. En ejecución se comprueba que el display siga
existiendo, sea válido, no privado ni principal, tenga la rotación esperada y
coincida su modo físico.

No se ha creado este registro en el coche ni se ha seleccionado físicamente la
nueva ruta. Las pruebas locales cubren identidad/configuración, lanzamiento,
generaciones, pérdida del destino y actividad en display incorrecto.

## Dependencias que siguen pendientes

1. Medir y provisionar la ruta actual del acompañante antes de activar la API
   explícita. Durante una ruta seleccionada, ausencia, cambio o denegación de
   lanzamiento no sustituyen el destino por principal, cluster o HDMI.
2. Mantener el launcher nativo y su selección de aplicaciones como ruta Android
   independiente. Comprobar cada actividad en el display real; no mover una tarea
   de navegación del conductor ni reutilizar su afinidad como fallback.
3. Conectar una acción explícita del usuario a la API local implementada y respetar
   su resultado. Esa acción no debe alterar la geometría principal ni presentar
   la ruta Android como un rol Ultra negociado.
4. Conectar y validar una fuente C11 real y fresca de P mediante la fábrica opcional
   para el contrato CarPlay
   existente. Desconocido no significa estacionado. Lanzar el reproductor en el
   acompañante no justifica anunciar disponibilidad de vídeo al conducir: la vía
   pública consultada sigue condicionada a estacionamiento.
5. Validar que el iPhone mantiene su UI de navegación mientras el receptor reproduce
   en el acompañante. Es una dependencia experimental pendiente; el código actual
   no demuestra esa simultaneidad ni dos navegaciones de aplicaciones iPhone.
6. Definir el audio: el Media3 actual no selecciona una zona independiente de audio.
   Probar interrupciones de Siri, llamadas y navegación, y evitar que reproducción
   del pasajero capture sus controles o suprima sus avisos. Compartir altavoces no
   es independencia de audio.
7. Ofrecer una salida clara al launcher nativo, liberar recursos al terminar la
   sesión y no abrir vídeo o DiPlay automáticamente al conectar Bluetooth.

## Validación necesaria antes de anunciarlo

Las pruebas deben demostrar el display elegido, el encuadre con insets reales,
toques confinados, reproducción de un medio compatible, pérdida del destino,
reconexión y limpieza. Principal y tablero deben conservar navegación durante
las transiciones del acompañante. También deben cubrir P conocido, cambio de P
y pérdida de la lectura, sin usar valores simulados en el coche.

Cada proveedor de contenido requiere una prueba propia de reproducción y
autorización. Que exista un icono o se reciba una URL no demuestra compatibilidad.
Los diagnósticos deben guardar sólo metadatos de destino, estado y errores; no
credenciales, URL de medios ni contenido personal.

Los cambios Kotlin de routing son locales. Pasaron el CI completo conjunto:
3.370 pruebas registradas, cero fallos/errores y una omisión previa, además de
lint y builds. Incluye reconexión, retirada de fuente P y rechazo de respuestas
del reproductor anterior. No acredita una prueba física ni aceptación del
destino por el iPhone; C11 continúa sin oferta de vídeo.


## Verificación conjunta local anterior (histórica)

Pasaron las 19 pruebas de destino/pantalla del acompañante dentro del CI completo
(3.336 pruebas registradas, sin fallos ni errores, una omisión preexistente).
La consulta del display devuelve desconocido si el contexto visual desaparece,
sin crear el reproductor del acompañante ni buscar otra pantalla.

Esa verificación anterior incluyó 12 pruebas simuladas de la primera fuente P,
cuya política estricta por evento podía invalidar una P sin cambios. La política
actual de entrega HAL y la observación manual por sesión descritas arriba forman
parte del nuevo conjunto ya validado localmente; aquí no se les atribuyen los
resultados anteriores. La [fuente C11 de P](C11_PARKED_SOURCE.md) sigue sin alimentar el gate
de vídeo y no tiene lectura física validada. C11 permanece sin oferta de vídeo.
Las nuevas protecciones de reconexión y propiedad de solicitudes pasaron el CI
conjunto; su validación física continúa pendiente.
