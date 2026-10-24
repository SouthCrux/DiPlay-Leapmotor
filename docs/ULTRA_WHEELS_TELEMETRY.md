# Telemetría de neumáticos C11

## Lectura QNX recibida en el coche (2026-10-23)

La ruta que entrega valores en este firmware es SomeIP **29185 / 29186 / grupo
29184**, evento periódico **999**. Su payload es Base64 de `CarControlEvent`:
campo protobuf 4 repetido, con cada `Event.event` en campo 1 y `Event.value` en
campo 2. El servicio OEM desagrupa ese paquete antes de llamar a
`LeapCenterManager.onMessageStr`; por eso sus eventos internos no equivalen a
ocho notificaciones independientes del transporte.

Eventos internos 1109/1110/1111/1112 = presión delantera izquierda/derecha y
trasera izquierda/derecha. 1113–1116 corresponden a las temperaturas en ese
orden. En la captura real llegaron **233|0 / 228|0 / 228|0 / 231|0** y cuatro
temperaturas **19|0**. El propietario confirmó kPa en el indicador OEM; la
referencia visual anterior era 231/228/228/231 kPa. La edad de la medición del
sensor sigue siendo desconocida: son valores comunicados por el coche.

`LeapmotorQnxTirePeriod` extrae exclusivamente los ocho campos de neumáticos
de un paquete acotado. `LeapmotorQnxTireState` conserva cada campo por separado,
acepta solo el formato numérico con estado 0 y retira valores ante errores,
pérdida de fuente o 60 s sin recepción. Otros estados no se interpretan como
una alarma concreta. Se mantienen separados de los enteros CAN sin escala.

La barra derecha de CarPlay convencional muestra dos filas, Delante/Detrás,
con las posiciones físicas izquierda/derecha y unidad kPa. No anuncia nuevas
capacidades a Siri ni escribe parámetros TPMS. El propietario confirmó que las
cuatro presiones se ven correctamente («siiiiii perfecto»). Esa validación visual
se registra por separado de las pruebas de código.

Evidencia privada: `ultra-20261022/tpms-current-capture.json`,
`LeapCenterManager-tpms.java`, `InnerCarControlEvent-tpms.java` y
`tyre-pressures-verification.json`.

Los apartados siguientes conservan la investigación histórica de las rutas
anteriores; sus resultados negativos no describen la nueva lectura QNX.

### Temperatura por rueda preparada sin conexión

El seguimiento del diseño Clara añade las temperaturas de los eventos 1113–1116
debajo de sus presiones, con unidad °C. Reutiliza el mismo cache QNX y su retiro
por campo, estado inválido, época retirada, silencio o pérdida de fuente; no abre
otra conexión ni envía comandos al vehículo. Presión y temperatura se conservan
independientes: perder una temperatura no oculta una presión disponible. Cuando
no hay ninguna temperatura, las filas adicionales se retiran; los campos
parciales muestran un guion, nunca cero inventado.

Las capturas reales guardadas contienen 19 en las cuatro temperaturas; el
indicador OEM capturado también muestra °C. Las vistas de prueba usan
19/20/21/22 como datos sintéticos distintos para detectar posiciones incorrectas.
La presentación nueva se verifica localmente en tamaño compacto, completo y RTL
con letra ampliada. Instalación y comprobación visual en el coche quedan
pendientes; este incremento no requiere ni realiza acceso al vehículo.

## Ruta adicional encontrada en el launcher (2026-10-22)

El APK físico `/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk`
contiene `com.leapmotor.appcenter.carcontrol.CarControlClient`. Su
`checkPressAndTempe()` consulta `ICarControl.getCanData` con presión
FL/FR/RL/RR = **800021/800022/800023/800024**, y temperatura =
**800025/800026/800027/800028**. No son los eventos SomeIP 3403–3704.

El cliente enlaza la acción `com.leapmotor.carcontrol.services.BINDER_POOL`
del paquete `com.leapmotor.carcontrol`, usa `IBinderPool.queryBinder(0)`
(transacción 1) y luego `ICarControl.getCanData(id)` (transacción 2).
El manifiesto OEM inspeccionado exporta ese servicio sin permiso por servicio.
Sin embargo, `CarControlImpl.onTransact` exige el permiso OEM de control y un
paquete con prefijo `com.leapmotor` o `com.android.systemui`: **la ruta no está
autorizada para DiPlay**. No se intenta eludir ese control ni suplantar paquetes.
Los datos son cadenas del cache OEM; no deben convertirse en mediciones frescas
por repetir consultas. Falta contrastar una lectura desde DiPlay con el widget.

La herramienta privada de ocho lecturas se compiló, pero el instalador OEM
rechazó su paquete con `INSTALL_FAILED_INVALID_APK: Install app package has to
be legal`. No se instaló ni se eludió ese rechazo. No se modificó el TPMS.
La consulta no debe añadirse a DiPlay sin una vía autorizada por el servicio.
Código OEM extraído privado: `ultra-20261022/Launcher-CarControlClient.java`.

La prueba de CarPlay convencional sí mostró imagen, pero **no** mostró el
indicador local. La lectura exterior había caducado; además, una tarjeta que
requiere margen libre no tiene ubicación garantizada cuando CarPlay ocupa
toda la ventana. Temperatura y presión siguen sin validación visual en DiPlay.
Evidencia: `ultra-20261022/connected-readouts-01.jsonl` y
`conventional-connected-no-readout.png`.

Seguimiento físico 2026-10-22: se capturó el widget OEM a las 21:34 del reloj
del coche (su fecha mostraba 10/21). Mostraba FL=231, FR=228, RL=228, RR=231 kPa;
temperaturas 18/18/19/18 °C. Es una referencia visual OEM con edad de medición
desconocida, no una lectura obtenida por DiPlay. Las capturas actuales de DiPlay
no recibieron eventos TPMS; no se validó un indicador propio ni la correlación
RAW por rueda. Evidencia privada: `ultra-20261022/oem-pressure-screen-2134.png`
y `pressure-physical-reference.json`. No se modificó ni reinició el TPMS.

Estado 2026-10-20: hay un decoder/cache RAW de lecturas y un módulo independiente de flags OEM, sin interfaz de neumáticos,
servicio Apple ni interpretación de unidades. La disponibilidad de sensores en el C11 concreto
y las unidades físicas requieren observación. No se escribe ni se reinicia el TPMS.

## Contrato probado en código OEM

La fuente es `com.leapmotor.carcontrol.module.leapCenter.LeapCenterManager`, del
`classes5.dex` extraído del APK carcontrol capturado el 2026-10-16. SHA-256 del DEX:
`d7ca2b3c8365822b771a362915326d54490e478162e1efcf20fe5b8f880763fc`.
La extracción fallback local está en `/private/tmp/diplay-LeapCenterManager-tpms.java`.
Para reproducirla se debe usar `jadx.cli.JadxCLI` con Java headless, no `java -jar`
(ese comando abre la GUI). Los originales no se modifican.

| Rueda física OEM | Presión RAW | Temperatura RAW |
| --- | ---: | ---: |
| Delantera izquierda | 3403 | 3404 |
| Delantera derecha | 3503 | 3504 |
| Trasera izquierda | 3603 | 3604 |
| Trasera derecha | 3703 | 3704 |

`LeapCenterManager` registra estos ocho eventos como `valueType=1` (líneas 330–368),
y su `onMessageInteger` los consume en el servicio `0x9900` (líneas 3584–3636).
El bean OEM usa posiciones FL=0, FR=1, RL=2, RR=3. Estos índices no son áreas Android.

La suscripción original está en
`.private/c11/20261016T005916Z/SomeIpService-fallback.java:577–604`:
servicio `0x9900` / instancia `0x9901` / grupo `0x9903`, e incluye
`LeapCenterManager.leapCenterCanEvents`.
`SomeIpSubManageImpl.java:178–185` exige exactamente cuatro bytes para `valueType=1`;
`ByteUtils.java:17–18` decodifica entero con signo little endian. Ambos están bajo
`.private/c11/20261016T005916Z/decompiled/carcontrol/sources/com/leapmotor/carctrl/`.

El consumidor admite presión RAW de 0 a 4094 inclusive. 4095 y valores fuera de rango
invalidan el dato. Para temperatura admite −50 a 150 inclusive: **−1 es válido**.
La asignación `-1` que el bean hace al invalidar no permite distinguir por sí sola
una temperatura real −1; el nuevo decoder conserva valor y validez por separado.
Java envía el entero sin conversión a Unity (`603002`–`603009`). No se han probado
bar, kPa, psi ni grados Celsius; el diagnóstico usa `pressureRaw` y `tempRaw`.

La rama del consumidor está condicionada por `optionTpms == 0`; su valor inicial es 1.
`ConfigUtil.isDirectTPMS()` invierte `Configuration.getTPMS()` (línea 259).
La configuración física de este vehículo aún no se acredita. Ausencia de eventos no
prueba presión cero ni un sensor defectuoso. Los IDs antiguos de `CanHeadUtils`
(por ejemplo 22107) pertenecen a otro contrato y no se aceptan aquí.

## Validez y propietario

`LeapmotorSomeIpTireDecoder` valida identidad completa, evento, longitud y límites OEM.
Un mensaje del canal conocido con payload nulo/malformado/inválido retira solamente ese campo.
Mensajes ajenos no alteran ni refrescan el cache.
`LeapmotorSomeIpTireState` mantiene ocho campos independientes y leases por fuente:
cierre/reemplazo impide que callbacks retirados restauren valores. El límite local de
silencio es 60 s; no representa cadencia garantizada ni timestamp de medición OEM.
La edad corresponde exclusivamente a recepción local. No se infieren estados de
fuga, alarma o sensor sano a partir de un número o de ceros iniciales del bean.

Las pruebas cubren las ocho rutas, signo/endian, 0/4094 de presión, −1 de temperatura,
sentinelas, longitud/identidad erróneas, expiración independiente y fuentes retiradas.
La integración pasiva comparte el propietario SomeIP existente y publica diagnóstico
cacheado por rueda con `pressureRaw`/`tempRaw`, estado y edad, sin abrir un segundo
cliente, hacer consultas activas, publicidad iPhone o controles TPMS. El callback OEM
no transporta grupo: el servicio interno lo deriva de la suscripción fija validada
`0x9903`, no de un supuesto byte de payload. El CI completo pasó: 3.439 tests JUnit
registrados, sin fallos/errores y una omisión previa; lint y compilaciones aprobados.
Verificación histórica del primer módulo RAW (no corresponde a una candidata actual):
la candidata `5bbda42e874430245a3617bc6e41b6449f07bc069d2b32fa966fdde9ee83bb61`
se instaló y se verificó contra el APK remoto. La propiedad física
`persist.sys.lp.config.TPMS=0` confirma la rama directa del helper OEM, pero no
se registraron valores de rueda en la observación de 390 s. Ese registro usó el
limitador de diagnóstico anterior a `DiagnosticBatch`: podía imprimir el primer
canal y ocultar un segundo evento ON_CHANGE que llegara durante el mismo segundo
y luego no volviera a cambiar. Por eso no demuestra ausencia de eventos del
firmware. Además, `tools/carplay/capture_events.py` no incluía la etiqueta
`DiPlay-TPMS` ni un parser TPMS al revisar este módulo: las capturas públicas
posteriores tampoco sirven para concluir ausencia de neumáticos. El script privado
`capture_network_metadata.py` sí contenía el parser RAW. No se muestran ceros como
lecturas ni se presupone un sensor defectuoso. La conversión física sigue pendiente.

## Verificación física mínima pendiente

1. Capturar sólo los ocho IDs anteriores desde la suscripción pasiva existente, con
   metadata de ruta, longitud y RAW. Leer el diagnóstico cacheado, sin abrir apps OEM.
2. Comparar simultáneamente cada rueda con la pantalla TPMS OEM y su unidad visible,
   sin alterar presión, configuración o estado del coche. Confirmar si la variante
   C11 es TPMS directo y documentar conversión antes de exponer unidades a personas.
3. Observar pérdida de fuente/invalidez y edad; no refrescar valores por sondeo del cache.
4. Sólo después diseñar proveedor semántico y widgets. El catálogo Apple de neumáticos
   no demuestra canal de contenido, compatibilidad ni zona de composición implementados.

La ruta Android estándar enumera `TIRE_PRESSURE=392168201`,
`CRITICALLY_LOW_TIRE_PRESSURE=392168202` y `TIRE_PRESSURE_DISPLAY_UNITS=289408514`.
El manifiesto OEM declara `CAR_TIRES` con protección `0x12` (signature|privileged).
Root intentó la lectura shell `cmd car_service get-property-value 392168201 1`:
el firmware la rechazó con `SecurityException`, porque exige build no-user.
No se reintenta ni se elude esta restricción. La existencia del ID no concede acceso
a DiPlay, ni acredita unidades/configuración de la ruta SomeIP.


## Revisión independiente del módulo: flags TPMS

`LeapmotorSomeIpTireFlagsDecoder` y `LeapmotorSomeIpTireFlagsState` separan las
banderas del valor de presión/temperatura. No cambian el contrato RAW existente.
La suscripción y los identificadores proceden de `LeapCenterManager` estático,
líneas 370–437; `handleTireEvent`, líneas 1487–1839, asigna por posición las
banderas de fuga, presión y temperatura. `TPMSBean.java:82–87` hace únicamente
estos predicados: presión = fuga cero **y** estado presión cero; temperatura =
estado temperatura cero. Las otras constantes del SDK no se interpretan como
banderas de este consumidor.

| Rueda | Fuga RAW | Estado presión RAW | Estado temperatura RAW |
| --- | ---: | ---: | ---: |
| Delantera izquierda | 3301 | 3302 | 3303 |
| Delantera derecha | 3306 | 3307 | 3308 |
| Trasera izquierda | 3311 | 3312 | 3313 |
| Trasera derecha | 3316 | 3317 | 3318 |

Identidad de suscripción: `0x9900` / `0x9901` / `0x9903`; enteros con signo
little endian de cuatro bytes. Se conservan los códigos no cero como RAW, sin
asignar severidad, alarma concreta, batería agotada ni sensor averiado. No se ha
probado el enum de cada código ni los valores reservados. `ZERO_FLAGS_OBSERVED`
reproduce el chequeo cero del bean; **no certifica salud del sensor**, validez de
una lectura física, calibración o ausencia de avería. `NONZERO_FLAG_OBSERVED`
sólo indica que hay un código no cero observado. `UNKNOWN` prevalece cuando falta
algún constituyente de ese chequeo, aunque haya otra bandera RAW no cero.

A diferencia de los ceros iniciales del bean OEM, este cache empieza vacío y exige
observación explícita de cada bandera. Expira cada canal a los 60 s de recepción
local, retira un canal ante payload malformado o timestamp local inválido y cancela
callbacks de propietarios anteriores. No existe timestamp OEM de medición en este
callback. Las lecturas del cache no renuevan la recepción. Se añaden seis pruebas:
12 rutas y posiciones, ausencia de defaults saludables, ambos constituyentes de
presión, aislamiento de canales, expiración y callbacks retirados/reordenados.
El CI coordinado posterior pasó: 3.508 pruebas JUnit, cero fallos/errores y una
omisión preexistente. El capturador público pasó 32 pruebas Python. La candidata
2bc9f9a9 está preparada, sin instalar; recepción física de flags aún pendiente.

La integración de transporte ya añade los doce IDs a la suscripción pasiva y a
su filtro, mantiene un epoch separado del cache RAW y publica las banderas en
`DiPlay-TPMS-FLAGS`. El archivo nuevo no abre conexiones ni escribe al TPMS por sí
mismo. El cableado local no acredita una nueva fuente física validada.

## Unidades y siguiente evidencia necesaria

El APK OEM contiene `lib/arm64-v8a/libil2cpp.so` y
`assets/bin/Data/Managed/Metadata/global-metadata.dat`. El Java envía RAW a Unity;
los nombres `RefreshTires`, `TPMS` y `Pressure` del metadata no prueban la escala,
el offset ni la unidad. Todavía no se normaliza presión ni temperatura. El rango
−50..150 tampoco basta para declarar Celsius.

Prueba concreta siguiente, con coche estacionado: capturar la etiqueta
`DiPlay-TPMS` con el diagnóstico por lotes corregido y las banderas añadidas,
abrir la pantalla TPMS OEM para comparar simultáneamente las cuatro posiciones
y la unidad visible. No reiniciar aprendizaje, escribir controles ni modificar
presiones. Si la pantalla OEM muestra valores y no llega ningún callback, registrar
por separado estado de suscripción/primer callback y contrastar el transporte;
no atribuirlo al sensor a partir del silencio. Si el OEM tampoco muestra valores,
documentar ese estado sin forzar movimiento ni configurar un sondeo HAL genérico.

La evidencia local de la revisión queda en
`.private/c11/ultra-20261020/wheels-module-review/evidence.json` y el inventario
acotado de Unity en `unity-metadata-review.json` dentro del mismo directorio. Son
análisis de los APKs ya capturados, sin acceso nuevo al coche ni a datos personales.


## Extractor y hoja de correlación reproducibles

`tools/carplay/tpms_evidence.py` procesa **offline** el APK OEM capturado. Verifica
los ocho controles de `Button_603.csv` y extrae sólo el literal exacto
`SZ{0}/Line/SZ/MyText_kPa{0}` del metadata IL2CPP versión 27. En este APK aparece
como literal 6974: acredita un nombre de etiqueta kPa dentro de Unity, **no** una
conversión desde RAW ni que todas las variantes usen la misma escala. No se ha
hallado una cadena completa RAW → conversión → texto de unidad. Por eso sigue
sin existir normalización a kPa/bar/psi o Celsius.

El extractor también admite el JSONL estructurado de `capture_events.py`. Selecciona
el último registro válido por rueda/cantidad en orden de captura; un estado retirado
reemplaza al valor anterior. Produce una hoja de ocho canales con campos vacíos para
valor, unidad y hora visibles en la pantalla OEM. No calcula ratios, no rellena datos
OEM y no compara capturas tomadas en horas diferentes como si fueran simultáneas.
No copia claves arbitrarias, texto de logs ni payloads del archivo de entrada.

```sh
python3 tools/carplay/tpms_evidence.py \
  --apk CAPTURED_OEM_APK \
  --events STRUCTURED_CAPTURE_JSONL \
  --output NEW_EVIDENCE_JSON \
  --comparison-csv NEW_COMPARISON_CSV
```

Los archivos de salida deben ser nuevos. `--events` es opcional para reproducir
sólo el contrato estático. La ejecución sobre el APK capturado produjo
`wheels-module-review/unity-contract-extracted.json` y
`wheels-module-review/oem-comparison-template.csv` bajo el directorio privado de
esta revisión. El CSV queda vacío de lecturas: no hubo nueva captura física.
Siete pruebas locales del extractor y la correlación aprobaron. Se inventariaron
además señales adyacentes de odómetro/mantenimiento/refrigerante/freno en
`wheels-module-review/maintenance-inventory.md`, distinguiendo constantes del SDK,
consumidores y ramas REEV; no se crearon intervalos de mantenimiento.


## Seguimiento nativo IL2CPP del consumidor exacto

Se reconstruyó offline el índice de métodos IL2CPP27 del APK capturado, vinculando
metadata, módulo `Assembly-CSharp.dll` y punteros ELF. El consumidor es
`leapMotor.UI_MyCar_CK.RefreshTires`, token `0x06002aa0`, RVA `0xa79038`;
`RefreshBtnState`, token `0x06002aa9`, RVA `0xa79ee8`, toma el texto de
`Proto.BtnData.get_Value` (RVA `0x1284f34`). **No recibe directamente el payload
SomeIP**: su entrada es una cadena del modelo Unity. El Java ya identificado
vincula 3403/3503/3603/3703 con 603002–603005, y 3404/3504/3604/3704 con
603006–603009. La tabla Button603 confirma estos ocho controles; falta observar
la cadena completa entre callback y modelo durante una captura física.

Dentro de `RefreshTires`, `String.Split` separa `valor|flag`; el segundo componente
pasa por `Convert.ToInt32` y `Convert.ToBoolean`. El primero se entrega como texto
al componente `MyText`; se intenta parsear también como `Single` para la animación
`RollNum`. No se halló multiplicación, división ni offset del número en este
consumidor. Los campos se resolvieron a `mTires` (0x98), `mKpas` (0xa0), `mWDs`
(0xa8) y `mCKIsTrouble` (0xd0). El metadata de `UnityEngine.UI.MyText` no declara
un override `set_text`; la instancia/vtable efectiva no se capturó en ejecución.
No se traduce el booleano visual a una alarma o certificado de sensor sano.

`OnChangeUnitType`, token `0x06002a8f`, RVA `0xa77658`, **no cambia unidades TPMS**:
actúa sobre `IsGL` y controles de odómetro/mantenimiento. Sus claves 100_54 y
100_580 se resuelven a km/mi en el paquete de idiomas OEM. No se debe interpretar
ese método como cambio entre Celsius/Fahrenheit ni kPa/bar/psi.

La extracción UnityFS8/LZ4 del paquete de idiomas encontró además claves
`28_1=kPa` y `28_2=℃/°C`. Esto refuerza la hipótesis de unidades visibles, pero
no demuestra el binding serializado exacto de esas etiquetas al prefab TPMS
activo de esta variante, ni la transformación anterior a `Proto.BtnData.Value`.
No se encontró una especificación adicional de sentinelas físicos o de salud.
Por ello **no se añadió normalización** ni se cambió la exposición RAW existente.

Los resultados, hashes y métodos se conservan en
`wheels-module-review/native-temperature-tpms-conclusion.json`. Los scripts privados
`reproduce_native_trace.py` y `reproduce_language_labels.py` reproducen el análisis
sobre el SHA del APK fijado; fallan si cambia el APK. No instalan bibliotecas,
usan ADB ni acceden a archivos personales. Los binarios OEM permanecen privados.

La captura física mínima pendiente es una sola observación estacionada de la
pantalla TPMS OEM con sus unidades y hora visibles, mientras se registran los ocho
canales RAW y flags mediante la captura pública actualizada. Comparar por rueda y
edad local; si un campo se retira, no usar el último valor bueno. No cambiar presión,
reiniciar aprendizaje ni forzar movimiento para fabricar diferencias.


## Avance 2026-10-21: puente anterior a Proto.Value revisado

La investigación continuó por una ruta nueva: el puente **Java** entre el consumidor
SomeIP y el texto enviado a Unity. `UnityMgr` es una interfaz; la implementación
real capturada es `UnityMgrImpl`, en classes6.dex. Su
`sendStatusMessage(eventId, value)` guarda el mismo texto y llama
`checkAndAddBtnData`. Éste encuentra las plantillas del evento y hace
`BtnData.newBuilder(template).setValue(value)`, sin conversión numérica. El envío
inmediato y el cache de página conservan el mismo `BtnData`.

`UnityMessenger.sendStatusMessage` serializa ese PageData, aplica Base64 y llama
`SDKMgr.RefreshDataToUnity`. La entrada nativa SDKMgr, RVA `0x819f5c`, conserva
el argumento y lo reenvía como comando 7 a `Platform2Unity`, RVA `0x83da40`.
Esto **cierra la duda sobre una escala/offset en ese puente Java previo a
Proto.Value**. No implica que se haya capturado la entrega completa en ejecución
ni prueba la unidad del productor SomeIP. El binding serializado activo de las
etiquetas de unidad sigue pendiente; por eso no se cambió RAW a kPa o Celsius.

Evidencia exacta: `UnityMgrImpl-before-proto-review.java:106–128` y `1147–1174`,
`UnityMessenger-before-proto-review.java:204–213`, más
`java-upstream-chain-20261021.json` en el directorio privado de la revisión. El
script `reproduce_java_upstream_20261021.sh` verifica el SHA del APK, extrae
classes6.dex y reproduce únicamente esas tres clases con JADX headless y memoria
acotada. No se ejecutó Gradle, ADB ni red, ni se instalaron bibliotecas.
