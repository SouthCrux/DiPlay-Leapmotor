# C11: procedencia de datos y lector Android Car de solo lectura

Fecha: 2026-10-16. Base: los originales del
[inventario C11 B11](LEAPMOTOR_C11_INVENTORY.md). El adaptador se desarrolló con
archivos locales. La comprobación ADB inicial descrita abajo fue de solo lectura.
Después se autorizó la instalación del [diagnóstico independiente](LEAPMOTOR_C11_DIAGNOSTICS.md):
su versión 0.2 ya conecta mediante la API pública desde una app ordinaria, pero
no obtiene telemetría EV utilizable. La autonomía devuelve timestamp 0 y se
descarta; energía, capacidad y puerto no están expuestos en esas muestras.
Las versiones 0.3–0.4 verificaron además lectura OEM JNI en proceso separado:
SOC 53,1 %, autonomía 227 km contrastada con el usuario y tres señales de carga
con valor bruto 0. Por decisión del usuario se asume reciente el último dato y
se usará la recepción como referencia de edad; el protocolo no entrega timestamp
de origen. Véase la [ruta OEM](LEAPMOTOR_C11_OEM_SIGNALS.md). El acceso se probó
con SELinux Permissive. La implementación posterior conecta una fuente JNI
parcial al runtime C11; véase «Integración runtime de SOC y autonomía» al final.

## Qué se pudo rastrear

### LMOS: interfaz presente, productor aún no identificado

En `decompiled/lmos/sources/lmos/framework/`, `services/LmosSystemServer.java`
registra `CarExtService`. Su constructor crea `services/carext/CarExtImpl`, instala
un callback y mantiene listas de clientes. Los métodos de CarExtImpl para SOC,
velocidad y marcha reenvían sus argumentos; CarExtService los distribuye a
`car/ICarCallback`.

No se encontraron llamadas que alimenten esos métodos en el conjunto de fuentes
inspeccionado. CarExtImpl no muestra aquí un lector de propiedades, JNI ni una
suscripción al bus. Esto no demuestra que la interfaz sea inoperante: podría
existir otro productor fuera de esos artefactos. Sí impide tratarla como una
fuente de telemetría validada. Faltan productor, unidades, disponibilidad inicial,
permisos efectivos, timestamps y comportamiento al perder la conexión.

### Android Car: cadena de lectura identificada

En el `android.car.jar` extraído, `Car.getCarManager("property")` obtiene el Binder
del servicio de propiedades y construye CarPropertyManager. En CarService:

```text
CarPropertyManager
  → CarPropertyService.getPropertyList / getProperty
  → PropertyHalService.getProperty
  → VehicleHal.get
  → cliente del HAL de vehículo
```

`CarPropertyService.getPropertyConfigList` filtra las configuraciones por el permiso
de lectura del llamante. `getProperty` vuelve a verificarlo antes de leer el HAL.
Por tanto, una propiedad ausente de la lista no prueba que el hardware no la
soporte: también puede estar oculta por permisos. El servicio asocia batería y
autonomía a `CAR_ENERGY`; no hace falta introducir comandos BYD para esta ruta.

El dump general inicial falló en TimeHalService. El dump dirigido posterior evitó
ese fallo y permitió inspeccionar el catálogo de PropertyHalService; los resultados
y sus límites se detallan abajo. La posterior prueba del lector desde la app
de diagnóstico confirma acceso al gestor, sin valores EV con frescura verificable.

### Comprobación ADB del firmware instalado

Evidencia privada: `.private/c11/readonly-probe-20261016T101023Z/`.
`dumpsys car_service --hal PropertyHalService` devolvió 30 configuraciones.
Esto describe las propiedades anunciadas por ese servicio, no lecturas actuales
ni permisos concedidos a DiPlay.

| Dato | Propiedad / ID | Configuración observada |
| --- | --- | --- |
| Autonomía | `RANGE_REMAINING` / 291504904 | Global, READ_WRITE, CONTINUOUS, 1–2 Hz |
| Velocidad | `PERF_VEHICLE_SPEED` / 291504647 | Global, READ, CONTINUOUS, 1–10 Hz |
| Marcha | `GEAR_SELECTION` / 289408000 | Global, READ, ON_CHANGE |
| Consigna de temperatura | `HVAC_TEMPERATURE_SET` / 358614275 | READ_WRITE, ON_CHANGE, áreas 49 y 68, límites 16–32 |
| Ventilador | `HVAC_FAN_SPEED` / 356517120 | READ_WRITE, ON_CHANGE, área 5, niveles 1–7 |
| Climatización encendida | `HVAC_POWER_ON` / 354419984 | READ_WRITE, ON_CHANGE, área 5 |
| Aire acondicionado | `HVAC_AC_ON` / 354419973 | READ_WRITE, ON_CHANGE, área 5 |

Los IDs se contrastaron con VehiclePropertyIds del JAR extraído. Los límites y las
áreas son metadatos; todavía no se validó su correspondencia física con las zonas
del C11. READ_WRITE no autoriza a nuestra app ni implica que se haya escrito nada.

`EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY`, `EV_CHARGE_PORT_CONNECTED` y
`HVAC_TEMPERATURE_CURRENT` no aparecen en este catálogo. En particular, de las
cuatro propiedades del lector EV preparado solo aparece autonomía. No se puede
completar VehicleStatusProvider a partir de este catálogo, ni concluir que los
datos ausentes no existan en otra interfaz OEM.

Las consultas `dumpsys car_service get-property-value <id> <area>` devolvieron
salida vacía incluso con código de salida cero. No se interpretan como valores.
La misma consulta de autonomía a través de la entrada de comandos explícita:

```sh
adb shell cmd car_service get-property-value 291504904 0
```

devolvió código 255 y `SecurityException` con el mensaje:
`The command 'get-property-value' requires non-user build`.
CarShellCommand del APK extraído contiene esa comprobación de Build.IS_USER y
no incluye esta consulta en sus listas de comandos permitidos en builds user.
La marca `test-keys` del fingerprint no elimina esa restricción. La causa de la
salida vacía de dumpsys no queda acreditada por sí sola; la entrada `cmd` sí
demuestra que la lectura por ese comando está bloqueada en este firmware.

El dump dirigido de `CarPropertyService` mostró tres clientes y suscripciones
a velocidad, marcha y varias propiedades HVAC; imprime registros, no valores.
`CarDrivingStateService` mostró estado 0 y un registro de arranque, sin una
muestra actual de velocidad o marcha. Ninguno proporciona aquí telemetría EV
utilizable con timestamps. No se intentó cambiar el tipo de build, elevar
privilegios ni eludir esta restricción.

El siguiente paso requiere identificar una fuente OEM de lectura accesible con
permisos legítimos y verificar muestras, unidades y timestamps. La denegación
del comando shell no determina por sí sola el resultado de CarPropertyManager
desde una app autorizada. La prueba posterior con la versión 0.2 dio conexión
correcta, autonomía STALE con timestamp 0 y las otras tres propiedades NOT_EXPOSED;
véase el [resultado en vivo](LEAPMOTOR_C11_DIAGNOSTICS.md#versión-02-api-pública-verificada-en-el-coche).

### Broker OEM: segunda ruta también restringida

Se recuperaron con JADX en modo fallback tres clases ausentes en la primera
salida, guardadas en `.private/c11/20261016T005916Z/`:

- `MainService-fallback.java`.
- `BinderPoolImpl-fallback.java`.
- `CarControlExternalImpl-fallback.java`.

BinderPoolImpl distingue el broker interno de CarControl y el externo.
CarControlExternalImpl también comprueba `CAR_CONTROL_SERVICE`, pero utiliza
una lista explícita de paquetes externos admitidos. No contiene a DiPlay,
Calendar o Gallery. El broker devuelve cadenas de una caché sin timestamp en su
contrato de lectura. La mera presencia de `getCanData` no acredita acceso,
actualidad, ni semántica del dato. No se invocó esa interfaz ni se intentó
suplantar ningún paquete.

MainService contiene la ruta SOME/IP mediante MsgCallBack y los managers de
comunicación existentes, y reenvíos de eventos a QNX. No se ha demostrado una
correspondencia completa con SOC/autonomía utilizable por nuestra app.
`initThirdApp` lee `persist.sys.lp.config.SOCValue` con un valor por defecto `7660`
y lo envía al broker externo como configuración: no se toma por un porcentaje
vivo de batería. Tampoco se interpreta `mileageDisplay` como autonomía restante.
El fallback es una representación de instrucciones para análisis, no Java
validado para copiar al proyecto.

## Adaptador preparado

La investigación posterior de [señales OEM](LEAPMOTOR_C11_OEM_SIGNALS.md)
identifica el procesamiento de SOC y carga en ExtendControlManager, sus eventos
SOME/IP y las restricciones de acceso/frescura. No demuestra un backend accesible
para DiPlay ni conecta el proveedor al runtime.

Código en `shared/src/main/java/com/shilapi/xcertplay/vehicle/leapmotor/`:

- `ReflectiveCarPropertyAccess`: adapta un CarPropertyManager **ya obtenido y
  autorizado**. Solo utiliza `getPropertyList`, `getProperty` y getters de sus
  descriptores/valores. No carga los JARs extraídos, crea conexiones, inicia
  servicios, concede permisos, registra callbacks ni expone setters.
- `LeapmotorCarPropertyReader`: lee exclusivamente cuatro propiedades globales,
  cuyos IDs coinciden con el JAR extraído. Revisa configuración, acceso, tipo,
  modo de cambio, estado, identidad, área, timestamp y valores finitos/no negativos.

| Propiedad estándar | ID del JAR C11 | Valor que se conserva |
| --- | --- | --- |
| `EV_BATTERY_LEVEL` | 291504905 | Energía en Wh |
| `INFO_EV_BATTERY_CAPACITY` | 291504390 | Capacidad nominal en Wh |
| `RANGE_REMAINING` | 291504904 | Metros; getter convertido a km sin redondear |
| `EV_CHARGE_PORT_CONNECTED` | 287310603 | Puerto conectado, booleano |

Las unidades proceden del contrato Android Vehicle HAL, no de una medición nueva
del C11. Véase la [especificación pública AOSP](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android10-release/automotive/vehicle/2.0/types.hal).
El catálogo posterior solo confirma que anuncia autonomía entre estas cuatro
propiedades. Falta validar valores y unidades reales.

No se deriva SOC mostrado dividiendo energía por capacidad nominal. Esa capacidad
no equivale necesariamente a la capacidad útil actual. Tampoco se convierte
puerto conectado en carga activa ni se estima autonomía máxima.

## Frescura, errores y uso

El llamante debe ejecutar `read()` en un worker. El reloj es
`SystemClock.elapsedRealtimeNanos`, igual dominio que el timestamp de propiedad.
La edad se comprueba después de todas las lecturas bloqueantes, no al comenzar.
Repetir un poll no renueva el timestamp original. Los valores dinámicos sin
timestamp válido, futuros o de más de 60 s se descartan. El límite es configurable.
Solo la capacidad nominal declarada STATIC admite timestamp cero/antiguo.

En propiedades ON_CHANGE, una edad alta puede significar un valor sin cambios;
el lector lo descarta conservadoramente hasta verificar la política de timestamps
del firmware. No convierte esa situación en una afirmación de fallo del sensor.

Cada campo distingue disponible, no expuesto, permiso denegado, configuración
incompatible, no disponible, error, antiguo y valor inválido. No hay caché de
últimos datos válidos que sobreviva a una denegación o pérdida de fuente. Tampoco
se fuerzan ceros o `false` al faltar una lectura.

El resultado `LeapmotorEvTelemetry` es **parcial y de diagnóstico**, no implementa
VehicleStatusProvider. No se conecta automáticamente a LeapmotorVehicleStatusProvider
ni al controlador. Esta vía Android Car no alimenta el runtime. El manifest no
solicita permisos nuevos; solo el diagnóstico solicita CAR_ENERGY. El runtime
parcial posterior utiliza JNI, no este lector.

La app de diagnóstico obtiene y cierra Android Car con un ciclo de vida explícito
y gestiona permisos, desconexión y disponibilidad. Usa la variante pública de
`Car.createCar` con listener de ciclo de vida y sin espera síncrona. Su binding
puede crear el servicio; se documenta ese cambio respecto al sondeo inicial.
No se ha implementado ningún fallback privilegiado.

## Validación y siguiente evidencia necesaria

Las pruebas del lector y del adaptador con fixtures sintéticos cubren unidades, área global, lectura
restringida, configuraciones filtradas/duplicadas, permisos perdidos, desconexión,
estados, timestamps, valores inválidos y la adaptación por reflexión sin JAR OEM.
La regresión añadida a partir del catálogo observado conserva solo autonomía
READ_WRITE, comprueba que no se intentan leer los otros tres IDs y que una
denegación posterior elimina la autonomía disponible. El valor usado en el test
es sintético: no se presenta como una lectura real del coche.
Las siete pruebas anteriores del proveedor EV también pasan en la ejecución
específica. No son pruebas en vivo del C11.

Para una lectura EV completa por Android Car faltan: valores utilizables desde la app con
permisos autorizados, muestras con timestamps y unidades, SOC mostrado, carga
activa, advertencia de autonomía y autonomía máxima verificadas. Hasta entonces
no se construye una lectura completa supuesta para anunciar capacidades iAP2.
HVAC y Ultra siguen fuera de este adaptador.

La última línea completa de AGENTS.md terminó con `BUILD SUCCESSFUL` (33 s):
2.987 tests, cero fallos/errores y uno omitido; lint y ensamblado debug de los tres
targets completados. `git diff --check` y `scripts/check_public_tree.py` pasan.

## Integración runtime de SOC y autonomía

El perfil explícito `leapmotor-c11` de `mobile` selecciona ahora
`LeapmotorTelemetry`, sin fallback BYD ni nuevos permisos. La biblioteca pública
del firmware se declara opcional; no se empaqueta su implementación. Solo se
suscriben SOC (39168/39169/6000) y autonomía (4161/20545/301), con los tipos y
unidades contrastados en el diagnóstico. No se publican eventos ni controles.

`LeapmotorTelemetryService`, no exportado y en el proceso `:c11telemetry`, carga
JNI fuera del proceso de CarPlay. El proceso principal recibe por Messenger una
copia acotada del payload y su tiempo monotónico de recepción. El enlace y la
inicialización tienen límites de 8 s. El cierre libera el binding y termina
exclusivamente el proceso de telemetría, evitando el `stop()` defectuoso del
firmware. Una caída invalida el estado sin iniciar un bucle de reinicios; otra
adquisición explícita (por ejemplo abrir de nuevo o crear otro controlador)
puede reintentar. No se modifica SELinux; solo se verificó acceso en Permissive.

La pantalla inicial y el host precargan la lectura. Cada controlador mantiene
una referencia independiente hasta `close()`, incluso si la Activity se destruye
y CarPlay sigue en segundo plano. Al liberar la última referencia se cierra la
fuente. El bucle iAP2 solo consulta memoria, sin Binder ni espera por JNI.

Política aceptada por el usuario: se considera actual el último valor entregado,
incluido el replay inicial. El OEM puede suprimir SOC sin cambios mientras sigue
enviando autonomía; se conserva ese SOC durante la misma sesión con tráfico.
La pérdida del servicio, una regresión del reloj o 60 s sin ninguno de esos dos
eventos borra ambos campos. Un intervalo superior a 60 s inicia estado nuevo,
incluso si no hubo consultas de snapshot durante el silencio. Un payload inválido
invalida su campo, y un evento fuera de orden invalida todo el estado. Esto mide
la actividad de recepción, no acredita la edad de una medición en el productor.

Se necesitan SOC y autonomía válidos para identificar el vehículo al iPhone.
Carga, advertencia, autonomía máxima y Wh permanecen nulos. La codificación iAP2
omite esos campos; tampoco anuncia las capacidades opcionales de advertencia y
autonomía máxima. No se deducen conectores del país, marca ni ajustes BYD: el
perfil C11 no los anuncia mientras su variante no esté confirmada. El proveedor
BYD conserva sus valores y declaración habituales.

Si la identificación ocurre antes de recibir ambos datos, la conexión continúa
sin anunciar estado EV. Los datos podrán usarse en la siguiente conexión;
la llegada de telemetría no fuerza una reconexión. Si la fuente cae después,
no se envían nuevas actualizaciones. No se ha implementado una orden para borrar
en el iPhone su última lectura ya recibida.

La aceptación por iOS de esta declaración parcial y su visualización siguen
pendientes de una prueba con iPhone. La fuente JNI se probó en el diagnóstico;
esta integración no se considera comprobada en el coche por pasar tests locales.
No implementa HVAC, nuevos displays ni CarPlay Ultra.

Validación de esta implementación: línea completa de CI, incluidos los targets
del diagnóstico, `BUILD SUCCESSFUL` (2 min 32 s). 3.010 tests: 1.538 shared,
1.453 common, 6 home y 13 diagnóstico; cero fallos/errores y uno omitido. Hay
10 tests nuevos sobre mensajes parciales, estado OEM y ciclo de binding, además
de la comprobación de cierre del proveedor en el test existente del controlador.
Una ejecución anterior falló por `Connection reset by peer` en el test existente
`SettingsBackupServerTest.stopDoesNotFailWhileConnectionsAreEnding`; la repetición
completa pasó sin modificar ese test. Lint de mobile: cero errores, 18 avisos;
`git diff --check` y `scripts/check_public_tree.py` pasan.

Se comprobó en el manifest fusionado que el servicio no se exporta y que la
biblioteca OEM es opcional. Firma debug verificada; sin binarios OEM empaquetados.
APK de prueba: `mobile/build/outputs/apk/debug/mobile-debug.apk`, paquete
`com.shihab.diplay.hudtest`, versión `0.2.20-hud-test`. No se instaló esta nueva
integración ni se sustituyó la app del coche. SHA-256:
`ff2f44c59eb08aab7cffdad6c2be6becc8c6fe56896a05c0e7d1b0134046ab4f`.

## Prueba runtime en el coche: 2026-10-16

Se añadió al informe de diagnóstico existente el estado del enlace OEM, la
disponibilidad del snapshot y sus valores de SOC/autonomía. Generar ese informe
no inicia ni reconecta la fuente. Las enumeraciones desconocidas y la ausencia
de timestamp de origen siguen explícitas. No se añadió un ajuste ni envío automático.

Se instaló la compilación de pruebas como `com.shihab.diplay.hudtest` en el
usuario 10, sin sustituir `com.shihab.diplay`. La app principal de pruebas usó
PID 14066 y el servicio separado PID 14135, bajo su propio UID 1010131. Logcat
del servicio confirmó conexión a ambos sockets y registro de suscripciones,
sin el fallo JNI de cierre observado en el diagnóstico inicial.

El informe generado desde la pantalla de Diagnóstico de DiPlay confirmó:

```text
C11 OEM telemetry: source=CONNECTED reading=AVAILABLE socPercent=53.0 rangeKm=226
freshnessPolicy=LAST_RECEIVED_STATE originTimestamp=UNKNOWN silenceTimeoutMs=60000
charging=UNKNOWN rangeWarning=UNKNOWN maxRangeKm=UNKNOWN
```

Esto comprueba recepción por la biblioteca, reenvío entre procesos y decodificación
en el proceso principal; no es una lectura tomada de la app de diagnóstico.
Se guardó el informe en Downloads/DiPlay del coche y una copia privada local en
`.private/c11/oem-source-probe-20261016/runtime-report.txt`, junto a la captura
`runtime-report-visible.png`. No se compartió el informe con terceros.

Al salir mediante Atrás volvió la actividad OEM anterior. Tras completar Android
el cierre, `pidof` ya no encontró `:c11telemetry` y `dumpsys activity services`
mostró `(nothing)` para el paquete de pruebas. La APK queda instalada.

Esta compilación no contiene identidad de autenticación: su informe indica
`CarPlay setup: authentication unavailable`. Por tanto, no se ha comprobado
todavía la aceptación de los datos parciales por el iPhone. El build standalone
requiere seleccionar explícitamente `DIPLAY_AUTH_ASSETS_DIR` o configurar un
backend MFi físico; la identidad no se importa automáticamente de otra app.

CI final con el informe: `BUILD SUCCESSFUL` (2 min 32 s), 3.011 tests, cero
fallos/errores y uno omitido. Se añadió una prueba de que consultar el informe
no arranca la fuente y se extendieron las de IPC para comprobar su contenido.
Una pasada previa falló en `SettingsBackupServerTest.aTlsHelloIsClosedAtOnce`;
la repetición completa pasó sin modificar ese test. Lint y los tres ensamblados
debug completados. `git diff --check` y `scripts/check_public_tree.py` pasan.

SHA-256 del APK instalado para esta prueba:
`7e095852d55dc65b1ad8b49cf626531958ec3d7a9eee3aad66f8c77a7f42e7cf`.

### Autenticación privada para la prueba con iPhone

El propietario autorizó reutilizar la identidad del APK DiPlay ya instalado y
aclaró que su uso diario es AutoKit; perder la configuración de DiPlay no sería
un impedimento. Aun así no fue necesario desinstalar la app anterior. Su firma
Android es distinta de la firma debug local y no se configuró su clave de firma;
la compilación de pruebas permanece en `com.shihab.diplay.hudtest`.

Se copiaron únicamente los dos assets de autenticación esperados a un directorio
privado con permisos restringidos, fuera del código público. Se construyó mediante
`:mobile:assembleStandaloneDebug` con `DIPLAY_AUTH_ASSETS_DIR` explícito, se verificó
la firma y que los assets coincidían con el origen autorizado, y se actualizó
el mismo paquete de pruebas sin borrar sus datos. El APK autenticado y su
procedencia quedan solo en `.private/c11/carplay-reuse-20261016/`.

Al abrirlo, la app creó correctamente su almacenamiento privado de autenticación
y mostró «Listo cuando tú lo estés», sin el error anterior. Esto acredita carga
local de la identidad; todavía no una sesión autenticada por un iPhone.
El APK ordinario de `mobile/build/outputs` se reconstruyó después sin identidad;
se comprobó que no contiene `assets/offline-mfi/`.

Reutilizar la autenticación MFi no copia el emparejamiento AirPlay ni las
preferencias de otra app. La primera conexión a esta instalación de pruebas
puede requerir emparejar el teléfono. Las actualizaciones siguientes deben
mantener este package, firma y datos para conservar esa configuración. AutoKit
no se modificó. La prueba con el teléfono queda pendiente de su disponibilidad
y del método de conexión elegido por el propietario.

### Intercambio EV con el iPhone verificado (sesión del 16–17 de octubre)

El propietario probó USB y después hotspot. Se recuperaron los logs propios
rotados del paquete de pruebas: no se atribuye la conexión a AutoKit ni al DiPlay
anterior. Los horarios siguientes son los del coche, no una referencia UTC.

USB, `session-logs/previous-4.log` en la evidencia privada:

- 19:04:35.154: identificación iAP2 aceptada.
- 19:04:35.451: autenticación MFi aceptada.
- 19:04:35.452: el teléfono envía `0xA100`, solicitando estado del vehículo.
- 19:04:35.454: DiPlay transmite `0xA101`, 226 km y SOC 52,9 %, sin advertencia
  inventada. La representación binaria de Double en el log incluye decimales extra.
- Se abrió el transporte NCM/VPN y se emitió StartSession; no aparece una sesión
  de vídeo AirPlay establecida antes de salir del intento. Que Android detecte
  el iPhone y acepte MFi no equivale a una conexión USB de vídeo funcional.
  La causa del fallo posterior al control iAP2 sigue pendiente. Otros reintentos
  quedaron esperando reenumeración y fueron cerrados; no se atribuyen todos al
  mismo fallo ni se aplica una corrección especulativa.

Hotspot, `wireless-success.log` en la evidencia privada:

- Dos sesiones de la app de pruebas completaron identificación/autenticación y
  establecieron AirPlay; la segunda comenzó a las 19:15:47.
- 19:15:48.069: solicitud `0xA100` del iPhone y respuesta `0xA101` con 226 km y
  SOC 52,8 %. A las 19:16:18.436 se registró otra actualización, unos 30 s después.
- El stream 110 produjo el primer frame y vídeo principal alrededor de 30 fps.
- El teléfono abrió también el stream de mapa 111, recibido alrededor de 30 fps
  pero sin frames mostrados. El log indica que no hay display de proyección de
  cuadro seleccionado; esto no prueba que el C11 carezca de una zona Android
  utilizable. Falta su ruta y geometría explícitas.

La declaración parcial y la petición de actualizaciones por el iPhone quedan
verificadas tanto en control USB como en el túnel Wi-Fi. Las trazas muestran el
envío de los mensajes, no la representación de batería/autonomía en una pantalla
de iOS ni la aceptación de cada valor a nivel de UI. No se anuncia un estado EV
completo, conectores sin confirmar, HVAC o Ultra.

El propietario informó que seleccionar CarPlay Ultra en el iPhone seguía mostrando
CarPlay convencional. Es consistente con la implementación actual: no se ha
implementado ni verificado negociación Ultra, composición local, UI Sync o assets
de vehículo. Un stream 111 de mapas y `0xA101` no acreditan esas capacidades.
