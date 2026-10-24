# C11: módulos locales de lectura

## Seguimiento con coche conectado, 2026-10-24

La APK instalada de código 40 ya incluía los diez lectores. Una captura pasiva
de 35 segundos recibió seis canales de energía: consumos y duraciones de los
contadores actual, Recharge y A. El consumo actual recibido fue aproximadamente
13,76 kWh/100 km, usando la unidad de presentación documentada por la tabla OEM.
No se contrastó físicamente con la pantalla original. Los demás módulos no
emitieron observaciones durante esa ventana; eso no demuestra que estén ausentes.

El siguiente incremento (código 41) conecta el consumo del trayecto actual a la
barra de CarPlay, debajo de la autonomía. La lectura se retira al perder la
fuente, vencer sus 60 segundos de silencio o recibir un valor no finito. No se
calcula a partir del porcentaje de batería. Los contadores Recharge y A quedan
en diagnóstico. La captura y el informe están en
`.private/c11/ultra-20261022/modules-live-authorized-20261024.jsonl` y
`modules-live-report-20261024.json`. El resto de este documento conserva el
historial de preparación offline; sus estados «sin instalar» corresponden a
aquellos incrementos, no a la APK agregada posterior.

La compilación del código 41 y el CI completo terminaron correctamente (3.731
pruebas, cero fallos/errores, 46 omitidas). Se revisaron capturas compacta y
completa con fuente grande y RTL. El primer intento de instalación quedó pendiente por pérdida de red. Tras
volver a conectar el coche, se instaló correctamente el código 41, se verificó
la versión mediante Package Manager y se abrió CarPlayHostActivity.
APK preparada: `DiPlay-C11-0.2.20-c11.20261024-consumption-dev.apk` en el mismo
directorio privado. Estado en `trip-consumption-verification-20261024.json`.

El propietario observó `-1` en el consumo con el coche sin moverse. La revisión
42 excluye negativos de esta lectura visible, tanto al consultar la caché como
al presentar el valor. Conserva cero y positivos; no convierte un dato negativo
en un consumo cero inventado. El valor raw sigue disponible para diagnóstico.
Las pruebas cubren positivo → negativo → cero y retirada por silencio/fuente.

Fecha: 2026-10-23. El usuario pidió completar todos los módulos en local mientras
el coche no estaba disponible, sin consultas sucesivas. No se accedió al coche
ni se instaló esta versión durante el trabajo.

APK local más reciente, sin instalar:
`.private/c11/ultra-20261022/DiPlay-C11-wipers-camera-prep-dev-20261023.apk`.
Hash y validación en `wipers-camera-preparation-verification.json` junto a la APK.

## Estado consolidado

Los diez módulos tienen preparación local de lectura y diagnóstico. El catálogo
incluye **83 señales INT32 y 8 mediciones DOUBLE**, sin contar los lectores
anteriores de batería, autonomía, temperatura exterior y neumáticos. La cortinilla
2801 se reutiliza: se elimina su duplicación en la lista de suscripción y se
conserva su consumidor anterior además del nuevo informe.

| Orden | Módulo | Lecturas conectadas al diagnóstico local | Falta validar o identificar |
| --- | --- | --- | --- |
| 1 | Carga | Estado, conexiones AC/DC, bloqueo del enchufe AC, cargador de a bordo, tiempo restante y límite objetivo: 7 lecturas | Enumeraciones/transiciones y potencia; contraste físico de minutos y límite en pantalla |
| 2 | Puertas y cierres | Cuatro puertas, sensor del baúl, posición y estado del portón eléctrico, capó, cierre centralizado y dos seguros infantiles: 11 lecturas | Correspondencia física abierto/cerrado/bloqueado, enumeraciones y equipamiento |
| 3 | Ventanas, techo y espejos | Cuatro posiciones, vidrio, cortinilla y calefacción de ambos espejos: 8 códigos | Sentido físico de extremos, equipamiento, códigos de calefacción y plegado de espejos |
| 4 | Asientos | Calefacción/ventilación de cuatro plazas, dos códigos de ocupación delantera y tres posiciones del asiento delantero izquierdo: 13 lecturas | Equipamiento, plazas físicas, códigos, extremos y función del ajuste de base; automatismos sin lectura de retorno verificada |
| 5 | Luces | Bajas, altas, antiniebla trasera, interruptores de giro y dos luces de lectura traseras y retorno de faros: 8 códigos | Enumeraciones; interruptor no implica lámpara encendida; color/brillo ambiental y luces de lectura delanteras sin lectura de retorno verificada |
| 6 | Energía y consumo | Distancia, consumo y duración de viaje actual, contador Recharge y A; candidatos OEM de autonomía y selector: 12 lecturas (mapeo CLTC/AIAC pendiente) | Entrega efectiva, contraste físico de unidades OEM, periodo del contador Recharge, sentinelas y reinicio de viaje; potencia instantánea y regeneración pendientes |
| 7 | Limpiaparabrisas | Seis códigos de aviso de mantenimiento/activación delanteros y traseros | Velocidad, estado normal, AUTO, lluvia y códigos físicos pendientes; sin controles |
| 8 | Conducción | Marcha, READY, controlador de frenos y velocidad: 3 códigos y 1 double | Enumeraciones/unidad de velocidad, aplicación física de freno, Auto Hold y modo de conducción |
| 9 | Avisos | Cinco estados de cinturones | Equipamiento, significado y asignación según variante; no se emiten alertas interpretadas |
| 10 | Climatización | Encendido, AUTO, A/C, distribución, desempañado delantero/trasero, dos estados de recirculación, nivel PM2.5, limpieza automática y cuatro estados traseros, retorno de SYNC, limpieza inteligente y temperatura mostrada atrás: 16 códigos y 1 double; conserva temperaturas y ventilador anteriores | SYNC preparado en diagnóstico; códigos, variante y recepción física pendientes; códigos de recirculación, correspondencia física de zonas y control pendientes de contraste |

«Conectado al diagnóstico local» significa código compilable y probado con
mensajes simulados. No demuestra recepción física, una nueva función en pantalla
ni disponibilidad de control. Carga y puertas conservan sus fachadas propias;
los restantes estados INT32 usan `LeapmotorSomeIpSubsystemState`; viajes y velocidad usan
`LeapmotorSomeIpMeasurementsState`. Climatización está al final del orden de trabajo.

## Evidencia estática y límites

Las rutas INT32 de estados usan servicio `0x9900`, instancia `0x9901`, grupo `0x9903`.
Los contratos de carga, puertas, ventanas, luces, marcha y cinturones se documentan
en el [informe de preparación](LEAPMOTOR_C11_PREPARED_MODULES.md).

### Ampliación de carga: tiempo restante y límite objetivo

`ExtendControlManager.extendEvents` registra 5908 y 11307 como INT32 en la ruta
`0x9900/0x9901/0x9903`. En el fallback local:

- 5908, rama `L129` (líneas 2308 en adelante), alimenta Unity 315007 y
  `charge_time_remain`. El código `0xFFFFFF` envía `--` a la pantalla y se trata
  como dato no disponible: retira la lectura anterior. El resto conserva el valor
  raw. La tabla OEM identifica la unidad de pantalla como minutos, como se detalla
  a continuación; falta contrastar la lectura físicamente.
- 11307, rama `L24` (líneas 2194 en adelante), acepta 50–100 y actualiza Unity
  315015, el límite objetivo usado por `ControlManager.syncChargePercent`.
  Se llama `CHARGE_TARGET_PERCENT`; los valores fuera de ese rango retiran la
  lectura anterior. El porcentaje real de batería sigue siendo el evento 6000.

Fuentes: `.private/c11/20261016T005916Z/ExtendControlManager-fallback.java`,
su inicializador contrastado con el archivo simple y `ControlManager-fallback.java`,
método `syncChargePercent`. La ruta de escritura observada allí no se ejecuta.
Tampoco se usa `syncChargeTime` para deducir unidades: ese método calcula horarios
de carga programada y no demuestra la unidad del tiempo restante 5908.

La evidencia independiente de unidad es `assets/UnityTable/Button_315.csv` del
mismo APK OEM: fila 315007, control `Charge_ShowTime`, describe el tiempo mostrado
en minutos (restante de carga o cuenta atrás de reserva). La ruta 5908 lo reenvía
sin conversión a ese control. La fila 315015 identifica `Charge_Percent_PrElectric`
como la carga objetivo programada. Esto acredita el contrato de presentación
del software OEM, no una validación física ni la salud de la medición.
Los valores por defecto de la tabla, 0 y 90, nunca alimentan nuestras cachés.
SHA-256 de la tabla inspeccionada:
`4d10795ef168329ac2d2d98491c47edea7c91b4c7e03ed53a98ee80026c24c63`.

Las dos lecturas atraviesan el suscriptor existente, la caché por campo, el informe,
el capturador y el panel local. No se envían como capacidad Siri/iPhone ni se
habilita el ajuste del límite. Son preparación local, sin contraste físico.

### Ampliación de carrocería y cierres

Se contrastaron las suscripciones INT32 de `BaseControlManager.baseEvents` con
`onMessageInteger` y los nombres de `CanEvent` del mismo firmware:

| Canal | Evento | Consumidor comprobado |
| --- | --- | --- |
| Capó | 3824 | `OPCODE_HPC_FrontCoverSts`, rama `L446`; solo 0/1 se envían a Unity 312051 |
| Cierre del vehículo | 304 | `OPCODE_HPC_VehLockSts`, rama `Lc60`; actualiza `carLockStatus`, escena y `strCarVehicleLock` |
| Seguro infantil trasero izquierdo | 610 | Rama `Lbce`; actualiza `RLChildLockStatus` |
| Seguro infantil trasero derecho | 511 | Rama `Lc18`; actualiza `RRChildLockStatus` |

Fuente: `.private/c11/ultra-20261020/BaseControlManager-sunshade-fallback.java`,
inicializador y ramas 3937, 4729 y 4793 en adelante; contraste con el archivo
`CanEvent.java` del inventario OEM. La variante alternativa de cierre 3855 no
se intercambia con 304. El control 303 no es un estado y no se admite como lectura.

El capó rechaza códigos fuera de 0–1 y retira su valor anterior; no se asigna
todavía cuál significa abierto/cerrado. Los demás campos conservan códigos raw
sin inferir booleanos ni un estado «todo bloqueado». La app OEM combina ambos
seguros infantiles para una clave persistida; no reproducimos esa agregación ni
usamos dicha clave como lectura actual. Cada campo tiene su propia edad y época
de fuente. No se añaden comandos de cierre, apertura ni bloqueo.

### Asientos

Se extrajo únicamente el DEX que **define** `SeatManager` (`classes2.dex`) del APK
OEM previamente inventariado; una referencia a la clase en otro DEX no se tomó
como su definición. El análisis con JADX 1.5.6 en modo fallback se guarda en
`.private/c11/ultra-20261022/SeatManager-offline-fallback.java`, SHA-256
`cd296211311638fe79543b437a5833e7fb019b670d5eec934f142ccff34755fd`.
No se incorpora implementación OEM al código público.

El inicializador `seatEvents` registra los eventos 10500–10507 como enteros.
`onMessageInteger` los acepta entre 0 y 3 y alimenta calefacción/ventilación de
las cuatro plazas, con comprobaciones de equipamiento. Las plazas delanteras se
nombran conductor/acompañante porque las ramas usan `getDriverSeatHeart`,
`getPassengerSeatHeart`, `getDriverSeatVent` y `getPassengerSeatVent`.
Las traseras siguen sus consumidores `blSeat...` y `brSeat...`.
No se interpreta un nivel como temperatura, ni un callback como prueba de que la
variante incorpora ese asiento. No se incluyen solicitudes de control 1508/1509
o 10630/10632 como estados.

#### Ocupación delantera y automatismos

`ExtendControlManager.extendEvents` registra los INT32 3806 y 3815. El consumidor
actualiza `isPersonOnMainSeat` para el primero y notifica las plazas delanteras
a Bluetooth y al broker. Las escenas izquierda/derecha cambian según
`ConfigUtil.isLRrudder`; los canales se llaman `FRONT_PRIMARY_OCCUPANCY_STATUS`
y `FRONT_SECONDARY_OCCUPANCY_STATUS` hasta contrastar la asignación física.
Las ramas OEM reconocen 0 y 2; nuestros lectores conservan otros códigos como
observaciones sin validar, sin convertirlos en «vacío» ni «ocupado».
No se usan para contar pasajeros, dar permisos ni autorizar acciones.

Fuente: `ExtendControlManager-simple.java`, `onMessageInteger`, contrastado
con el fallback (`case 3806 → L3de`, `case 3815 → L39a`). No se reutilizan
eventos de cinturones como ocupación, ni niveles de calefacción como presencia.

En `SeatManager.syncAutoHeart` y `syncAutoVent`, los valores de UI 1002008/1002009
alimentan publicaciones 1520/1521. Son rutas de órdenes, no lectores de retorno
verificados. Los eventos 10510/10512 son posiciones de asientos de otra variante
(gated C16), no modos automáticos. No se incorporan con una interpretación errónea.
El panel indica que el retorno de los automatismos sigue pendiente.

### Luces de lectura e iluminación ambiental

`BaseControlManager.baseEvents` registra 2702 y 2703 como INT32 en la ruta
`0x9900/0x9901/0x9903`. Las ramas `L8e1` y `L891` reenvían respectivamente
esos códigos hacia Unity 325029 y 325028 si existe iluminación ambiental; sin
esa opción usan 312016 y 312017. También notifican el evento original al broker.

La tabla OEM `assets/UnityTable/Button_325.csv` identifica las filas como
`Ambient_SW_Back_L` y `Ambient_SW_Back_R`, con descripciones de luces de lectura
traseras izquierda/derecha. El prefijo `Ambient_` no convierte esas luces en
mediciones de color o brillo de la iluminación ambiental. En `Button_312.csv`,
312017 corresponde igualmente a la luz de lectura trasera derecha.

Los lectores conservan los dos códigos por separado, sin etiquetar desconocidos
como encendido/apagado ni modificar colores. No se asignan por suposición los
eventos 2612/2613 a luces de lectura delanteras.

Se revisó además `AtLampUtils`, extraído de `classes5.dex` del mismo APK OEM.
Su inicialización establece valores locales para RGB, intensidad y efectos;
`onRGBChange` y `onIntensityChange` programan tareas, y los métodos de aplicación
publican órdenes mediante `CanPubImpl`. Esos valores iniciales o guardados no
acreditan una lectura actual del hardware. No quedó verificada una ruta de
retorno para color/brillo; el panel lo indica al seleccionar luces.
No se carga ni ejecuta ese controlador OEM.

Fuente privada: `.private/c11/ultra-20261022/AtLampUtils-offline-fallback.java`,
JADX 1.5.6 fallback, SHA-256
`f70a26ccf47e2b87a1b4561e37f8d70778b5a886d384de8be149b2401465d65b`.
Se contrastó con `BaseControlManager-sunshade-fallback.java`, su inicializador,
sus consumidores y las dos tablas UI. Falta validar entrega, variantes y códigos
en el coche. No se añade control de ninguna lámpara.

### Retorno de faros y selección AUTO

Se añade `MAIN_LAMP_FEEDBACK`, evento 3813 INT32 en servicio `0x9900`,
instancia `0x9901`, grupo `0x9903`. `BaseControlManager.baseEvents` lo declara
como INT32; `onMessageInteger`, rama `L57a`, reenvía el código a Unity 312020.
Se guarda por separado de 3102, sin mapear códigos a AUTO/encendido/apagado.
Formato incorrecto retira solamente este campo; no renueva la edad de 3102.

El evento 3102 ya preparado asigna tanto `mAutoLamp` como `ptLampState` en
la rama `L642`. Esos nombres no prueban por sí solos el significado físico.
`AtLampUtils` utiliza `ptLampState` para decidir efectos interiores, sin aportar
una enumeración inequívoca de AUTO. El nombre diagnóstico anterior
`LOW_BEAM_STATUS` se conserva por compatibilidad, pero sigue siendo un código
sin interpretar y no se muestra como afirmación de luces bajas encendidas.

La tabla OEM `Button_312.csv` identifica 312010/011/012/013 como selección de
apagado/posición/bajas/automático; son controles. `syncMainLamp` publica las
órdenes CAN 1001–1004 y actualiza la interfaz. **Esos IDs de órdenes no se añaden
como lectores SomeIP**: 1001–1003 ya identifican otras señales en la ruta de
climatización. Los espacios de identificadores no son intercambiables.

La selección AUTO y el encendido físico siguen pendientes de contraste.
No se añaden órdenes, indicadores del conductor ni capacidades Siri.
Fuente: `.private/c11/ultra-20261020/BaseControlManager-sunshade-fallback.java`,
`.private/c11/ultra-20261022/AtLampUtils-offline-fallback.java` y
`ControlManager-fallback.java`. El nuevo retorno sólo se ha probado localmente.

### Viajes y velocidad

`LeapCenterManager.onMessageDouble` acepta servicio 4161 y reenvía al cuadro:

| Canal | Evento | Consumidor/UI OEM |
| --- | --- | --- |
| Distancia actual | 12289 | `CIS_EVENTID_PRESENTKM`, 604001 |
| Consumo actual | 12291 | `CIS_EVENTID_PRESENTKWH`, 604003 |
| Distancia Recharge | 12295 | `CIS_EVENTID_LASTKM`, 604007 |
| Consumo Recharge | 12297 | `CIS_EVENTID_LASTKWH`, 604009 |
| Distancia A | 12307 | `CIS_EVENTID_KMA`, 604013 |
| Consumo A | 12309 | `CIS_EVENTID_KWHA`, 604015 |

La lista `leapCenterEvents` declara tipo DOUBLE para los seis. La ruta de cuadro
ya usada por autonomía es servicio 4161 / instancia 20545 / grupo 17920, según
`SomeIpService`; se añaden estos IDs como candidatos de recepción. Su presencia
en el consumidor y la lista estática no prueba que se entreguen con la UI OEM
cerrada o en esta variante. Fuente inspeccionada:
`.private/c11/ultra-20261022/LeapCenterManager-tpms.java`, líneas 177 y 1363 en adelante,
y `.private/c11/20261016T005916Z/SomeIpService-fallback.java`, bloque de la ruta 4161.

Velocidad usa 4030, DOUBLE en `ExtendControlManager.extendEvents` y
`onMessageDouble`, sobre la ruta `0x9900`. No se aplica la transformación de
velocidad de cuadro `raw*1.04+0.25`. La lectura queda raw y no se conecta al
controlador de estacionamiento ni autoriza acciones.

La tabla original `assets/UnityTable/Button_604.csv` del APK OEM identifica las
unidades de presentación: distancias en km y consumos en **kWh/100 km**. El nombre
interno `KWH` no significa energía total. Se corrigen los nombres de diagnóstico
`*_ENERGY` a `*_CONSUMPTION`; el lector de capturas acepta los nombres antiguos.
El grupo `Recharge` no se etiqueta «viaje anterior»: su periodo queda pendiente.

Se añaden los tiempos 12290 → 604002 (actual), 12296 → 604008 (Recharge) y
12308 → 604014 (A), declarados INT32 en la misma ruta 4161. `onMessageInteger`
y `dealSpecialData` los presentan en minutos y convierten los negativos en
indisponibles. El decoder exige cuatro bytes y retira negativos; rechaza doubles
en estos eventos. Los consumos/distancias siguen exigiendo ocho bytes y mantienen
los finitos sin inventar límites físicos a partir de recortes de presentación.

SHA-256 de `Button_604.csv`: `20d4d963f893bc29e62026325e72263770e0032f82c54fc47198dd30781a26bf`.
Sus valores por defecto no se usan como mediciones.

Las unidades OEM no demuestran contraste físico ni recepción en esta variante.
No se calculan Wh restantes, capacidad útil ni consumo por diferencias de SOC.
La velocidad permanece sin unidad validada. La fixture INT32 de estados mantiene
80 filas; los tres tiempos se prueban aparte en el decoder de mediciones.

### Posiciones del asiento delantero izquierdo

Se añaden tres INT32 de `BaseControlManager.baseEvents` en
`0x9900 / 0x9901 / 0x9903`, con límites 0–100 según su consumidor:

| Evento | Nombre diagnóstico | Consumidor OEM | Identificación estática |
| --- | --- | --- | --- |
| 2300 | `FRONT_LEFT_SEAT_SLIDE_POSITION` | `L9be`, Unity 321013 | `SCU_FLSeatHorPercent`; asiento hacia delante/atrás |
| 2302 | `FRONT_LEFT_SEAT_BASE_FRONT_POSITION` | `L987`, Unity 321011 | `SCU_FLSeatbaseFrontPercent`; UI `Seat_State_LegPercent` |
| 2306 | `FRONT_LEFT_SEAT_BACKREST_POSITION` | `L950`, Unity 321012 | `SCU_FLSeatBackPercent`; respaldo |

La tabla original `Button_321.csv` describe 321011 como porcentaje de apoyo de
piernas, mientras el inventario llama al evento base delantera. Se presenta como
«base/apoyo», sin afirmar un mecanismo concreto, altura, centímetros o grados.
El lado izquierdo procede del nombre de señal OEM; no se generaliza a conductor
para otras posiciones de volante. Los extremos físicos siguen pendientes.

El consumidor comprueba equipamiento `DriverSeatMemory` para notificar clientes.
No se afirma que todas las variantes entreguen estas posiciones ni que haya una
memoria activa. Se reciben pasivamente, con caducidad por eje y rechazo de
porcentajes inválidos. No renuevan edades de calefacción/ventilación u otros ejes.
No se añaden movimientos, guardado/recuperación de memoria ni controles a Siri.

2304 aparece en el inventario y suscripción como base trasera, pero el callback
inspeccionado no tiene una rama equivalente: queda pendiente de identificar su
consumidor antes de incorporarlo. Fuentes: `BaseControlManager-sunshade-fallback.java`,
`can/external/CanEvent.java` y `assets/UnityTable/Button_321.csv` del APK original.

### Bloqueo del enchufe AC y estado del cargador de a bordo

Se añaden `AC_PLUG_LOCK_STATUS` 2509 y `ONBOARD_CHARGER_STATUS` 6100 como
INT32 en `ExtendControlManager.extendEvents`, ruta `0x9900 / 0x9901 / 0x9903`.
En `onMessageInteger`, 2509 asigna `slowACChgLockSts` (rama `L421`); el código
OEM sólo copia ese estado al ajuste global cuando la conexión AC tiene su código
esperado. DiPlay conserva directamente el retorno por campo, sin tomar el
ajuste guardado como lectura física actual. `CanEvent` lo nombra
`OPCODE_BZCU_ACChgLockSts`.

6100 se identifica como `OPCODE_OBC_WorkSts`. Su consumidor reconoce el código
3 para actualizar Unity 317004; no contiene aquí una enumeración completa.
Se guarda raw, separado de conexión AC/DC y `CHARGE_STATUS`. Ninguno de estos
valores aislados se convierte a «cargando» ni se anuncia como capacidad nueva
al iPhone. Códigos desconocidos no se convierten por defecto en desbloqueado.

`handleSlowChargePlusUnLock` envía una orden QNX 1213: no se ejecuta ni se
confunde ese ID con el inventario CAN de igual número. 11308 figura en el
inventario como selección de carga reservada y no se añade como estado de carga
activa sin un consumidor verificado. No se intenta desbloquear el cable.

Recepción y significado físico de los códigos siguen pendientes de contraste.
Las pruebas verifican que refrescar el bloqueo no renueve la edad del estado
OBC o conexión, y que un payload incorrecto retire sólo su campo.

### Autonomía CLTC/AIAC: candidatos OEM separados


**Corrección del propietario (2026-10-23):** el coche ofrece CLTC y una opción
de consumo asistido por IA, denominada aquí **AIAC** (AI Assisted Consumption,
abreviatura local). No se confirma que el nombre comercial exacto sea ése.
El nombre interno OEM `WLTC` de los candidatos encontrados no identifica por
sí solo la opción AIAC: su correspondencia queda pendiente. El informe deja
claro ese límite y no presenta el coche como si ofreciera una opción WLTC.

Se añaden al diagnóstico `CLTC_RANGE` 11102, `WLTC_RANGE` 11103 y
`RANGE_STANDARD` 11104. `ExtendControlManager.extendEvents` declara los tres
INT32 en la ruta `0x9900 / 0x9901 / 0x9903`. `onMessageInteger` asigna cada
campo sin mezclar sus valores; `CanEvent` los identifica como
`ICU_CLTC_ElectticityMileage`, `ICU_WLTC_ElectticityMileage` y
`ICU_Switch_CLTCOrWLTC`.

`handleUpdateCurrEvKm` notifica autonomía al cliente Binder 20 únicamente bajo
las condiciones OEM `isExtendedRange()` e `isForeign()`. En esa rama, selector
0/2 toma CLTC y 1/3 toma WLTC. Esa condición de variante impide generalizarlo
al coche sin contraste. DiPlay conserva el selector raw, incluso códigos
desconocidos, y no elige automáticamente una autonomía para la barra o Siri.

Los dos valores se presentan como kilómetros OEM, pendientes de contraste
físico. -1 es indisponible en el consumidor; el decoder retira negativos y
acepta cero como observación raw, sin concluir que la batería esté vacía.
Un cambio del selector no renueva la edad de ninguna distancia. No se
convierten CLTC/WLTC entre sí ni se estima capacidad o consumo desde esos datos.

Se mantiene intacta la fuente actual de batería/autonomía y el protocolo al
iPhone. Las pruebas de integración comparan la instantánea antes y después de
recibir estas nuevas lecturas y de retirar una distancia inválida.
Fuentes: `ExtendControlManager-fallback.java`, inicializador y métodos
`onMessageInteger` / `handleUpdateCurrEvKm`; `can/external/CanEvent.java`.

### Baúl eléctrico: porcentaje y estado propios

Se añaden `TAILGATE_POSITION` 815 y `POWER_TAILGATE_STATUS` 802 como INT32
sobre `0x9900 / 0x9901 / 0x9903`. Ambos figuran en `BaseControlManager.baseEvents`.
En `onMessageInteger`, rama `Lb75`, 815 acepta 0–100 y reenvía el valor sin
transformación a Unity 323007. `CanEvent.OPCODE_PTG_OpenClosePercent` y
`Button_323.csv` (`BackDoor_Percent_Height`) respaldan el porcentaje de apertura
OEM; extremos y entrega en esta variante siguen pendientes de contraste físico.
Se retiran porcentajes fuera de 0–100, sin conservar un valor anterior engañoso.

La rama `Lb9f` recibe 802 (`OPCODE_PTG_RearDoorSts`). El consumidor OEM aplica
una transformación para notificar clientes bajo el ID 3823. DiPlay conserva
**802 y 3823 por separado**, sin reproducir esa transformación ni deducir qué
código es abriendo, cerrando, detenido, abierto o cerrado. No se sustituye el
sensor existente `TAILGATE_STATUS` por el estado del actuador.

La recepción de un porcentaje nuevo no renueva la edad de los dos estados.
Los callbacks y payloads malformados se tratan por campo, bajo la misma época
de fuente que las puertas. El equipamiento OEM condiciona el uso del portón;
la preparación local no demuestra que el vehículo entregue todos los eventos.
Sólo lectura de diagnóstico, sin acción de apertura/cierre ni anuncio a Siri.

Fuentes: `BaseControlManager-sunshade-fallback.java`, `CanEvent.java` y
`assets/UnityTable/Button_323.csv` del APK original archivado.

### Espejos: calefacción por lado y plegado pendiente

Se añaden 1700 → `LEFT_MIRROR_HEAT_STATUS` y 1800 →
`RIGHT_MIRROR_HEAT_STATUS`, INT32 de `BaseControlManager.baseEvents`, ruta
`0x9900 / 0x9901 / 0x9903`. Las ramas `La62` / `L9f5` de `onMessageInteger`
asignan `leftMirrHeatStatus` / `rightMirrHeatStatus` y notifican clientes cuando
el equipamiento declara `MirrorHeat`. El inventario OEM coincide con
`OPCODE_LZCU_LmirrHeatStatus` / `OPCODE_RZCU_RMirrHeatStatus`.

Se conservan códigos independientes, incluidos los desconocidos; la selección
agregada de interfaz que combina ambos lados no se usa como lectura individual.
No hay conversión a booleano ni temperatura del espejo. El informe conserva
los códigos fuera de 0–100: no son posiciones de ventanas. Los límites de
ventanas y cortinilla siguen aplicándose únicamente a sus posiciones.

El inventario contiene `LMirrFoldSts` 1706 y `RMirrFoldSts` 1806, pero no se
verificó tipo, suscripción y consumidor para esa recepción en las fuentes
inspeccionadas. Además, `SettingsManager` publica CAN 1706 en una acción de
mantenimiento; no se interpreta una preferencia guardada `strCar1706` como
posición física del espejo. El plegado queda pendiente de contrato de lectura.
No se envía ninguna orden ni se cambia el estado del coche.

Fuentes: `BaseControlManager-sunshade-fallback.java`,
`SettingsManager-temperature-review.java` y `can/external/CanEvent.java`.
La agrupación diagnóstica `WINDOWS_ROOF` mantiene su identificador para
compatibilidad y se presenta como «Ventanillas, techo y espejos».

### Modo de conducción y regeneración: contrato de lectura pendiente

Revisión local 2026-10-23, sin conexión al coche. Las fuentes inspeccionadas
permiten separar órdenes de lecturas, pero no confirman todavía un evento de
retorno para ECO/Normal/Sport o nivel de regeneración:

| Identificador | Evidencia OEM | Tratamiento local |
| --- | --- | --- |
| 1701 | `syncDriverMode` publica a CAN; también aparece en el manejador de acciones Unity | Orden candidata, sin suscripción nueva |
| 1902 / 1903 | `syncDriverMode` publica por SomeIP; constantes `MMI_DRIVER_MODE_SET` / `CUSTOM` | Órdenes, no estado físico confirmado |
| 1703 | `MMI_ENERGYRECOVERYBRAKE`, publicado por `syncDriverMode` y acciones Unity | Orden de regeneración candidata, sin lector |
| 1609 | `syncEnergyMode` publica; controla otra selección de energía | No confundir con consumo ni modo ECO/Normal/Sport |
| 9010 / 9011 / 9013 | Constantes `SET_INNER`, `SET_SHADOW`, `ENERGYRECOVERYBRAKE_SHADOW` | Tipo, ruta y consumidor de retorno sin verificar |

`ExtendControlManager.preUnityInit` inicializa 313001 a «1» y otros botones a
«0»; `syncDriverMode` también actualiza botones y preferencias de cuenta.
Estos valores no demuestran el modo aceptado por las ECU. La notificación Binder
110 se emite dentro de la ruta de órdenes, por lo que tampoco se toma como ACK.
Las tablas de suscripción y callbacks inspeccionados de Base/Extend no bastan
para establecer ese contrato; no se afirma que sea imposible en otros servicios.

Se mantienen las lecturas ya preparadas de marcha, READY y velocidad. El modo
queda pendiente de identificar un evento de retorno con servicio, instancia,
grupo, tipo y consumidor; después habrá que contrastarlo físicamente.
Fuentes y hashes: `driving-mode-static-review.json` en la carpeta privada de
artefactos. No se publicaron órdenes ni se añadieron indicadores supuestos.

### Controlador de frenos, EPB y Auto Hold

Se prepara `BRAKE_CONTROLLER_STATUS`, evento 4101 INT32 sobre
`0x9900 / 0x9901 / 0x9903`. `ExtendControlManager.extendEvents` lo registra
como INT32. `SettingsManager.onMessageInteger` recibe ese servicio y evento,
asigna `mEsciStatus` y llama a `checkEPBStatus`; este último publica el estado
UI 377031. La constante OEM es `OPCODE_ESCi_Status`, no una enumeración probada
de «freno aplicado». Por eso se conserva el código entero sin convertirlo a
booleano, estacionado, fallo ni autorización para controles.

La ruta `syncEPB` **publica** 1620 (valor 2, con condiciones OEM de estado y
velocidad). No se ejecuta ni se registra como lector. `syncAutoHold` publica
CAN 1600 y actualiza el control UI 313016 (`Drive_Toggle_Autohold` en
`Button_313.csv`). Esa selección no confirma que Auto Hold esté sujetando el
coche. En la recepción SomeIP de `BaseControlManager`, 1600 alimenta estados
de carga inalámbrica: el número de una orden CAN no identifica por sí solo
una lectura SomeIP de la misma función.

El inventario `CanEvent` contiene 4019/4020/4021 con nombres AVH Status /
Available / FunctionStatus. Son candidatos de investigación; no se añade una
suscripción sin comprobar servicio, grupo, tipo y consumidor en este firmware.
También se excluyen de esta función 4602/4800/4805: sus consumidores locales
son ACC y LKA, no freno de estacionamiento.

Fuentes locales: `ExtendControlManager-fallback.java`,
`SettingsManager-temperature-review.java`, `ControlManager-fallback.java` y
`com/leapmotor/can/external/CanEvent.java`. Sólo diagnóstico de lectura;
Auto Hold y aplicación física de EPB siguen pendientes. No se cambia la política
de estacionamiento ni se anuncian capacidades nuevas al iPhone.

### Climatización trasera: preparación condicionada al equipamiento

Se añaden cuatro INT32 de `AirManager.airEvents` sobre
`0x9900 / 0x9901 / 0x9903`:

| Evento | Nombre diagnóstico | Consumidor y restricciones OEM |
| --- | --- | --- |
| 9801 | `REAR_HVAC_POWER_STATUS` | Rama `L4a7`, códigos 0/1; Unity 102001 |
| 9805 | `REAR_HVAC_FAN_LEVEL` | `L4f4`, niveles 0–7; Unity 102007 |
| 9802 | `REAR_HVAC_AIR_DISTRIBUTION` | `L53b`, código de distribución; Unity 102003/102006 |
| 9803 | `REAR_HVAC_AUTO_STATUS` | `L5c4`, códigos 0/1; Unity 102002 |

El consumidor notifica varios clientes sólo cuando `hasAirZone()` tiene el
valor esperado. La suscripción estática y el decoder no demuestran que este C11
tenga climatización trasera independiente ni que entregue estas señales. Todas
comienzan indisponibles; no se muestra equipamiento supuesto por recibir defaults.

Los códigos de distribución 0/1/2 reciben tratamiento OEM de cara/pies; se
conserva el código raw sin presentar todavía una distribución física validada.
Códigos desconocidos permanecen observaciones, sin convertirse en OFF. En cambio,
las ramas de encendido/AUTO y ventilador rechazan fuera de sus intervalos; el
decoder retira esas lecturas inválidas. La edad se mantiene por campo, separada
del climatizador delantero. No se añaden órdenes ni anuncios de capacidades.

9800 es DOUBLE de temperatura mostrada trasera; la ampliación siguiente lo
prepara sin escalado. Fuente: `AirManager-climate-readonly-fallback.java` y
`CanEvent.java`.

**Actualización SYNC:** se completó la identificación del registro en
`UserInfoManager.baseEvents`; se prepara su retorno en la sección siguiente.
La recepción física y su semántica en este C11 siguen pendientes.

### SYNC: registro y consumidor de retorno identificados

Se añade `HVAC_SYNC_FEEDBACK`, evento 7606 INT32. La revisión adicional del APK
original encontró `new SubEvent(7606, 1)` en `UserInfoManager.baseEvents`, no en
`AirManager.airEvents`. `SomeIpService` agrega esa lista a la ruta
`0x9900 / 0x9901 / 0x9903`, completando la evidencia de suscripción que faltaba.
El código decompilado queda en `UserInfoManager-sync-offline-fallback.java`.

`AirManager.onMessageInteger` acepta ese servicio y evento. Si `AirZone == 1`,
notifica indisponible al cliente Binder 101. En otras variantes trata raw 2 como
texto «0» y raw 1 como «1». La constante del inventario es `HPC_TMSSynTemp`.
DiPlay conserva el entero recibido, incluidos códigos desconocidos: no afirma
SYNC activo/inactivo ni equipamiento disponible sin contraste físico.

La ruta de orden `AirControlManager.syncTemp` usa otra transformación (entrada
menos uno para Binder y actualización local de `mSyncState`) y puede enviar QNX
1406. Esos números no se reutilizan como interpretación automática del retorno.
No se ejecuta esa ruta ni se modifican las consignas. Tampoco se deriva SYNC de
temperaturas iguales. 1005 sigue sin un consumidor verificado para este fin.

El nuevo campo caduca por separado; cambios de otras señales no renuevan su
edad y un payload incorrecto retira sólo SYNC. Las pruebas comienzan con dos
consignas iguales y comprueban que no se invente un estado SYNC por igualdad.
Fuente del registro: `classes6.dex` del APK OEM archivado; tipo, ruta y consumidor
identificados estáticamente. Validación en coche pendiente; no resuelve todavía
el control de climatización que falló en las pruebas anteriores.

### Temperatura trasera mostrada: lectura separada de sensores y órdenes

Se añade `REAR_HVAC_DISPLAY_TEMPERATURE`, evento 9800 DOUBLE de `AirManager.airEvents`,
ruta `0x9900 / 0x9901 / 0x9903`. `onMessageDouble` acepta valores entre 18 y 32,
los convierte a entero sólo para mostrarlos en Unity 102009 y notifica al cliente
9800 si la variante declara la zona correspondiente. `Button_102.csv` identifica
102009 como `HVAC_B13_ScrollView_Temperature` (temperatura de la pantalla trasera).
Se documenta como temperatura mostrada/consigna OEM; no es la temperatura ambiente
real de las plazas traseras ni demuestra un sensor de habitáculo adicional.

DiPlay exige ocho bytes little-endian, valor finito y rango 18–32. Conserva
fracciones (por ejemplo 20,5) y no reproduce el truncamiento visual OEM. No aplica
multiplicación/división por dos: `syncBackAirTemp` multiplica por dos en la ruta
**de orden QNX 1454**, distinta de esta recepción. Se corrige la referencia previa
a «escalado» en el párrafo del evento recibido; ese escalado pertenece al envío.

La lectura usa el cache de mediciones, pero el informe la agrupa en CLIMATE;
las mediciones de viaje permanecen en ENERGY y la velocidad en DRIVING. Los
payloads incorrectos, fuera de rango o de fuente retirada no preservan el dato.
Los estados delanteros y sus temperaturas no se modifican ni se renuevan por
recibir la temperatura trasera. Sin órdenes ni nueva capacidad al iPhone.

Fuentes: `AirManager-climate-readonly-fallback.java`,
`AirControlManager-sync-offline-fallback.java`, `CanEvent.java` y `Button_102.csv`.
Equipamiento, recepción y correspondencia física siguen pendientes de contraste.

### Limpiaparabrisas: avisos y contratos pendientes

Se añade el módulo diagnóstico `WIPERS`, manteniendo climatización al final.
Seis INT32 de `SettingsManager.settingsEvents` usan `0x9900 / 0x9901 / 0x9903`:

| Evento | Canal | Identificación OEM |
| --- | --- | --- |
| 1521 | `FRONT_WIPER_MAINTENANCE_OFF_FAILURE` | Salida de mantenimiento delantero |
| 1522 | `FRONT_WIPER_MAINTENANCE_ON_FAILURE` | Entrada de mantenimiento delantero |
| 1523 | `FRONT_WIPER_START_FAILURE` | Activación delantera |
| 1524 | `REAR_WIPER_MAINTENANCE_ON_FAILURE` | Entrada de mantenimiento trasero |
| 1525 | `REAR_WIPER_MAINTENANCE_OFF_FAILURE` | Salida de mantenimiento trasero |
| 1526 | `REAR_WIPER_START_FAILURE` | Activación trasera |

`onMessageInteger` acepta esos IDs y, para raw 1 con `WiperMaintenFlag > 0`,
llama a `showWiperMaintenInfo`. Es un consumidor de avisos condicionado por una
operación de mantenimiento, no un medidor de velocidad o una prueba de salud.
Los códigos se conservan sin deducir fallo activo, ausencia de fallo o estado
normal; cero no se presenta como «funciona bien». Caducidad y fuente por campo.

Los IDs de inventario 3826/3843/3844, 2603/2604, 2725/2726 y 3100/3101 son
candidatos para estado, selectores y lluvia. No se verificó el conjunto de ruta,
tipo y consumidor para incorporarlos como lecturas efectivas. No se publican
órdenes de limpieza, lavado o mantenimiento. Captura y pruebas actuales son
simuladas; validación en coche pendiente.

C11 Diagnóstico 0.6 añade una [escucha de doce candidatos de estados y lluvia](LEAPMOTOR_C11_WIPERS_PROBE.md), con bytes sin interpretar y límites por ventana.
La recepción real sigue pendiente; no se añaden como lecturas efectivas de DiPlay.

Las cámaras se preparan por una vía distinta del CAN: ver
[Captura de cámaras C11](LEAPMOTOR_C11_CAMERAS.md).

### MAX A/C, limpieza inteligente y ECO

MAX A/C comparte el evento 1003 ya preparado como `HVAC_AC_STATUS`:
`AirManager.onMessageInteger`, rama `L191`, asigna `isACMax = (raw == 2)`.
El consumidor también distingue otros códigos A/C y combina su presentación con
AUTO. Se conserva el entero raw, sin reducirlo a booleano ni crear otra
suscripción duplicada. El informe lo etiqueta «A/C y MAX A/C (código)» y aclara
que el significado en el coche aún requiere contraste. Se prueban explícitamente
recepción y conservación del código 2.

Se añade `HVAC_SMART_CLEAR_STATUS` 1219, INT32 de `AirManager.airEvents`, ruta
`0x9900 / 0x9901 / 0x9903`. En `onMessageInteger`, rama `L237`, asigna
`mSmartClearState` y actualiza Unity 101001. Está separado de 1217 (limpieza
automática), recirculación y PM2.5. Se conserva raw, incluidos códigos desconocidos.

La app OEM inicia un contador local de 180 segundos en esa rama; **no es una
medición de tiempo restante recibida del coche**. DiPlay no lo reproduce ni
inventa una cuenta atrás. El consumidor OEM también puede publicar CAN 1120:
DiPlay no ejecuta ese consumidor; sólo recibe el payload con su suscriptor propio,
sin publicar órdenes ni copiar temporizadores o efectos secundarios OEM.

ECO tiene una ruta de orden `syncEco` (QNX 1419), notificación local y un campo de
solicitud `ECOMModeReq` en datos de perfil. El inventario contiene `TMS_ECOMSts`
1218 y `HPC_ECOMModeReq` 7612. En las fuentes inspeccionadas no se ha identificado
el conjunto de suscripción y consumidor de retorno que confirme ECO aplicado:
no se añade una lectura desde una preferencia o solicitud de perfil.

Fuentes: `AirManager-climate-readonly-fallback.java`,
`AirControlManager-sync-offline-fallback.java`, `CanEvent.java` y `AirInfo.java`.
No se habilita control de MAX A/C, ECO o limpieza; recepción y códigos físicos
siguen pendientes de validación.

### Aire interior: PM2.5 por categorías y limpieza automática

Se añaden `CABIN_PM25_LEVEL` 1107 y `HVAC_AUTO_CLEAR_STATUS` 1217, declarados
INT32 en `AirManager.airEvents`, ruta `0x9900 / 0x9901 / 0x9903`.
`onMessageInteger` procesa 1107 en la rama `L2d2`: acepta sólo 1–6, guarda
`raw - 1` en `airQuality` y reenvía ese índice a Unity 101017. DiPlay conserva
el **código recibido 1–6**, no el índice 0–5 de presentación. Cero, negativos
y valores mayores de seis retiran el dato y no significan aire limpio.

No se conoce una concentración, escala AQI ni umbrales físicos por categoría.
No se publica µg/m³, una valoración sanitaria ni alarmas a partir de ese código.
El informe lo identifica expresamente como nivel OEM pendiente de contraste.

1217 coincide con `CanEvent.OPCODE_TMS_AutoClearSts`. La rama `L3f6` notifica
clientes y actualiza Unity 101026 según configuración AQS; no aporta una
enumeración general de estados. Se guarda raw por separado de recirculación,
A/C y PM2.5, sin deducir por defecto «purificando». Su equipamiento y significado
físico siguen pendientes de validación.

AQS 11200/11201 y fallos/ventilador PM2.5 1108/1109 aparecen en el inventario,
pero no se identificó su consumidor de recepción en el `AirManager` inspeccionado.
No se añaden como sensores sólo por sus nombres. Tampoco se deriva humedad de
ninguna de estas señales. No hay órdenes nuevas, ni se corrige por esta vía el
setter de temperatura o SYNC que falló en las pruebas anteriores.

El filtro de capturas admite cifras en el nombre del canal para `PM25`, siempre
bajo la lista cerrada de canales conocidos. Fuentes:
`AirManager-climate-readonly-fallback.java` y `can/external/CanEvent.java`.

### Climatización al final

Se contrastó `AirManager.airEvents` con `onMessageInteger` en el fallback local:
1001 (`mAirConditionerOpen`/`strCarAirSwitch`), 1002 (`mAirAutoState`),
1003 (`mACState`), 1011 (`mWindMode`), 1012 (`mFDefrostState`) y
1013 (`strCarRearDefrost`). Se guardan códigos sin convertir desconocidos en OFF.
No se ejecuta ninguna rama del código OEM ni sus publicaciones; únicamente se
usa el suscriptor JNI pasivo que ya tiene DiPlay.

Se conservan los lectores anteriores 11205/11206, ventilador 1008 y ambiente
1221/1016. Ninguno de estos cambios valida el setter de temperatura ni corrige
por sí solo el fallo físico de control observado anteriormente. No se anuncian
nuevas capacidades de climatización al iPhone.

Ampliación local: `AirManager.airEvents` registra 1010 y 1310 como INT32. En
`onMessageInteger`, 1010 actualiza `isCycleState` y, cuando no está AUTO,
`strCarAirInner`. El evento 1310 actualiza `isCycleAuto`; la app OEM publica un
valor separado hacia sus clientes cuando AUTO está activo. Nuestros lectores
conservan ambos códigos de recepción por separado: no sintetizan una posición
efectiva, no traducen códigos desconocidos a OFF y no leen esa clave persistida.

SYNC incorpora ahora el retorno 7606 descrito arriba. El campo local
`AirManager.mSyncState` se actualiza en
`AirControlManager.syncTemp`, junto con una notificación local y el envío opcional
de la orden QNX 1406. Eso no es una confirmación de recepción del coche.
La suscripción a 1005 existe, pero no se identificó un consumidor de retorno
que la vincule a SYNC; su número o nombre por sí solos no acreditan el estado.
Tampoco se infiere SYNC porque ambas temperaturas sean iguales.

Fuente adicional: `.private/c11/ultra-20261022/AirControlManager-sync-offline-fallback.java`,
JADX 1.5.6 fallback de `classes5.dex`, SHA-256
`09de885eb408023cb4a2e41d9b616dbecfa2aa6acae870d5d90f2b736e13fbfc`.
El panel de revisión indica esta limitación al seleccionar climatización.

## Recepción y diagnóstico

- Una única conexión JNI, con eventos deduplicados por suscripción.
- Caches por campo con épocas de fuente y límite local de silencio de 60 segundos.
- Callbacks antiguos, horas futuras/fuera de orden y payloads malformados no
  mantienen una lectura disponible. Otros campos no renuevan su edad.
- DOUBLE requiere exactamente 8 bytes little-endian y valor finito. INT32
  requiere 4 bytes; posiciones tienen límite 0–100 y niveles de asiento 0–3
  según sus consumidores. Vidrio y demás enums no reciben límites inventados.
- Los lotes de los módulos nuevos registran cambios de estado/valor, evitando
  repetir todos los campos por cada callback o por cambiar solo su edad.
- La generación de informes lee memoria y no abre conexiones.

Tags: `DiPlay-CHARGING`, `DiPlay-DOORS`, `DiPlay-SUBSYSTEM` y
`DiPlay-MEASUREMENTS`. `capture_events.py` admite únicamente canales y módulos
enumerados, formas numéricas acotadas y estados coherentes; rechaza texto extra.
Exporta `semanticsVerified=false` y `writeControlAvailable=false`; en las
mediciones también `unitsVerified=false`. No conserva paquetes, texto de voz,
ubicación o identificadores de teléfono.

## Pruebas y siguiente sesión

Una sola captura ya puede revisarse por todos los módulos mediante
`tools/carplay/module_evidence.py`: cobertura, cambios de código, retiradas y
tráfico del protocolo del vehículo, sin conectarse de nuevo al coche. El
[ejemplo generado](LEAPMOTOR_C11_CAPTURE_REVIEW_EXAMPLE.md) usa exclusivamente
datos simulados. Se corrigió el filtro de logcat para incluir los cuatro tags
nuevos; las capturas hechas con la lista anterior podían omitirlos.

La fixture `c11-prepared-modules-simulated.tsv` cubre los 80 canales enteros con
valores **sintéticos**. Las pruebas de mediciones reproducen siete valores
decimales sintéticos y comprueban separación de rutas/canales, errores de tamaño,
NaN/infinito, fechas inválidas, vencimiento individual y épocas.
Las pruebas Messenger comprueban preservación de batería/autonomía y cortinilla,
un solo binding, pérdida de fuente y cancelación de logs pendientes.

Resultado local: comando completo de CI Android aprobado (3.677 tests JUnit,
0 fallos, 0 errores, 46 omitidos); 42 pruebas Python del capturador aprobadas.
Compilación standalone con los recursos locales existentes de CarPlay aprobada.
APK archivada, **sin instalar**:
`.private/c11/ultra-20261022/DiPlay-C11-local-modules-readonly-dev-20261023.apk`.
Hash y verificación en `local-modules-readonly-verification.json` junto a la APK.

La ampliación posterior de carga pasó nuevamente la CI completa: 3.679 tests
JUnit, 0 fallos/errores, 46 omitidos; 60 pruebas Python (capturador, informe y TPMS)
aprobadas. APK actualizada, **sin instalar**:
`.private/c11/ultra-20261022/DiPlay-C11-charge-time-target-readonly-dev-20261023.apk`.
Hash y evidencia en `charge-time-target-readonly-verification.json` junto a la APK.

El incremento de recirculación aprobó de nuevo la CI completa: 3.680 tests JUnit,
sin fallos/errores, 46 omitidos. Capturador 44 y generador de informes 9 pruebas
aprobadas. La APK standalone de recirculación queda archivada sin instalar.

La ampliación de capó/cierres aprobó nuevamente la CI completa: 3.681 tests JUnit,
sin fallos/errores, 46 omitidos; 61 pruebas Python aprobadas. El panel volvió a
pasar las pruebas de los nueve módulos y ventana de 320px. APK standalone de
carrocería/cierres archivada sin instalar; es la versión local más reciente.

El incremento de luces de lectura traseras pasó la CI completa: 3.682 tests
JUnit, sin fallos/errores, 46 omitidos. Capturador 45 e informe 9 pruebas
aprobadas; panel verificado en navegador y a 320px. APK de iluminación interior
archivada sin instalar; sustituye a la anterior como versión local más reciente.

El incremento de ocupación delantera pasó la CI completa: 3.683 tests JUnit,
sin fallos/errores, 46 omitidos; 62 pruebas Python aprobadas. Panel verificado
en navegador y a 320px. APK de ocupación delantera archivada sin instalar;
es la versión local más reciente.

Al volver el coche: instalar la APK local de prueba y verificar entrega de los
módulos, empezando por carga y puertas. Validar transiciones antes de mostrar
estados interpretados o anunciar capacidades. La prueba de Siri de autonomía
sigue pendiente; sus nuevos registros se conservan en esta versión. No se
requiere cambiar a Ultra para ejecutar estas capturas.

### Validación de tiempos y consumos (2026-10-23)

Se añaden los tres tiempos de viaje y se corrige la interpretación de consumo
a kWh/100 km de presentación OEM. Las capturas antiguas siguen siendo legibles.
El informe simulado cubre 62 canales; no representa datos del coche.
CI completo: 3.684 pruebas, 0 fallos/errores, 46 omitidas; 63 pruebas Python
y revisión de los nueve módulos en navegador, incluida ventana de 320 px, PASS.
APK standalone compilada y archivada sin instalar. Recepción y contraste físico
siguen pendientes.

### Validación de retorno de faros (2026-10-23)

Lectura 3813 preparada únicamente para diagnóstico. El informe simulado cubre
63 canales; no afirma selección AUTO ni estado físico de faros. CI completo:
3.686 pruebas, cero fallos/errores y 46 omitidas; 63 pruebas Python y revisión
en navegador PASS. APK standalone compilada y archivada sin instalar.
La interpretación física de 3813 y 3102 sigue pendiente.

### Validación de estado del controlador de frenos (2026-10-23)

Evento 4101 preparado para diagnóstico; no confirma EPB aplicado ni Auto Hold
sujetando el coche. Informe simulado: 64 canales. CI completo PASS:
3.688 pruebas, cero fallos/errores y 46 omitidas; 63 pruebas Python y revisión
en navegador PASS. APK standalone archivada sin instalar. Fuentes, hash y
validación en `brake-status-readonly-verification.json`, junto a la APK.

### Validación de calefacción de espejos (2026-10-23)

Eventos 1700 y 1800 preparados por separado para diagnóstico. Informe simulado:
66 canales. CI completo PASS: 3.690 pruebas, cero fallos/errores y 46 omitidas;
64 pruebas Python y revisión en navegador PASS. APK standalone archivada sin
instalar. Recepción física, códigos y plegado siguen pendientes. Fuentes, hash
y validación en `mirror-heat-readonly-verification.json`, junto a la APK.

### Validación del baúl eléctrico (2026-10-23)

Eventos 815 y 802 preparados para diagnóstico y separados del sensor 3823.
Informe simulado: 68 canales. CI completo PASS: 3.692 pruebas, cero fallos/errores
y 46 omitidas; 65 pruebas Python y revisión en navegador PASS. APK standalone
archivada sin instalar. Recepción física, extremos y códigos del portón pendientes.
Fuentes, hash y validación en `power-tailgate-readonly-verification.json`.

### Validación de autonomías CLTC/WLTC (2026-10-23)

Eventos 11102/11103/11104 preparados para diagnóstico, sin reemplazar la
autonomía ni el SOC existentes. Informe simulado: 71 canales. CI completo PASS:
3.694 pruebas, cero fallos/errores y 46 omitidas; 66 pruebas Python y revisión
en navegador PASS. Se actualizó una expectativa antigua que mantenía vacío el
catálogo INT32 de energía; las lecturas nuevas deben comenzar indisponibles.
APK standalone archivada sin instalar. Recepción y correspondencia física del
selector pendientes. Fuentes, hash y validación en
`range-standards-readonly-verification.json`.

### Validación de bloqueo AC y cargador de a bordo (2026-10-23)

Eventos 2509 y 6100 preparados para diagnóstico, sin interpretar carga activa
a partir del bloqueo. Informe simulado: 73 canales. Corrección del propietario:
las opciones del coche son CLTC y AIAC; el canal OEM llamado WLTC no está
confirmado como AIAC. Informe y documentación ajustados con esa distinción.
CI completo PASS: 3.696 pruebas, cero fallos/errores y 46 omitidas; 67 pruebas
Python y revisión en navegador PASS. APK standalone archivada sin instalar.
Recepción física y enumeraciones pendientes. Fuentes, hash y validación en
`charge-lock-obc-readonly-verification.json`.

### Validación de posiciones del asiento (2026-10-23)

Eventos 2300/2302/2306 preparados como porcentajes de diagnóstico, sin mover
asientos ni activar memorias. Informe simulado: 76 canales. CI completo PASS:
3.698 pruebas, cero fallos/errores y 46 omitidas; 68 pruebas Python y revisión
en navegador PASS. APK standalone archivada sin instalar. Recepción, extremos
y mecanismo de base/apoyo pendientes. Fuentes, hash y validación en
`seat-position-readonly-verification.json`.

### Validación de aire interior (2026-10-23)

Eventos 1107/1217 preparados como nivel OEM PM2.5 y código de limpieza automática.
No hay concentración, interpretación sanitaria ni humedad derivada. Informe
simulado: 78 canales. CI completo PASS: 3.700 pruebas, cero fallos/errores y
46 omitidas; 69 pruebas Python y revisión en navegador PASS. Se corrigió el
filtro para admitir cifras en nombres de canal conocidos, incluido PM25.
APK standalone archivada sin instalar. Recepción física y significado de las
categorías pendientes. Fuentes, hash y validación en
`cabin-air-readonly-verification.json`.

### Validación de climatización trasera (2026-10-23)

Cuatro estados traseros preparados para diagnóstico, sin asumir equipamiento
ni emitir órdenes. Informe simulado: 82 canales. CI completo PASS: 3.702 pruebas,
cero fallos/errores y 46 omitidas; 70 pruebas Python y revisión en navegador PASS.
APK standalone archivada sin instalar. Recepción, equipamiento y distribución
física pendientes; temperatura trasera no añadida. Candidato SYNC 7606 documentado
sin suscribir. Fuentes, hash y validación en `rear-climate-readonly-verification.json`.

### Validación de temperatura trasera mostrada (2026-10-23)

Evento 9800 DOUBLE preparado sin escalado, conservando fracciones entre 18 y 32.
No es una lectura de temperatura ambiente de la cabina. Informe simulado: 83
canales. CI completo PASS: 3.704 pruebas, cero fallos/errores y 46 omitidas;
71 pruebas Python y revisión en navegador PASS. El informe asigna la medición
a CLIMATE y conserva viajes en ENERGY. Se corrigió la descripción anterior
del escalado: pertenece a la orden QNX, no al evento recibido. APK standalone
archivada sin instalar. Recepción, equipamiento y contraste físico pendientes.
Fuentes, hash y validación en `rear-temperature-readonly-verification.json`.

### Validación de retorno SYNC (2026-10-23)

Registro INT32 del evento 7606 localizado en `UserInfoManager.baseEvents` y
ruta confirmada por agregación en `SomeIpService`. Retorno preparado como
código de diagnóstico, sin convertir a activo/inactivo ni cambiar consignas.
Informe simulado: 84 canales. CI completo PASS: 3.706 pruebas, cero fallos/errores
y 46 omitidas; 72 pruebas Python y revisión en navegador PASS. Se actualizó la
expectativa antigua de ausencia total del campo SYNC: ahora debe comenzar
indisponible y no inferirse por temperaturas iguales. APK standalone archivada
sin instalar. Recepción física y semántica por variante pendientes. Fuentes,
hash y validación en `sync-feedback-readonly-verification.json`.

### Validación de limpieza inteligente y MAX A/C (2026-10-23)

Retorno 1219 preparado y separado de limpieza automática 1217. MAX A/C
identificado en el consumidor del evento 1003 existente (raw 2), sin nueva
suscripción ni interpretación física generalizada. ECO permanece pendiente de
contrato de retorno. Informe simulado: 85 canales. CI completo PASS: 3.708
pruebas, cero fallos/errores y 46 omitidas; 73 pruebas Python y revisión en
navegador PASS. APK standalone archivada sin instalar. No se copian órdenes
ni el contador local OEM de 180 segundos. Fuentes, hash y validación en
`smart-clean-readonly-verification.json`.

### Validación de limpiaparabrisas y preparación de cámaras (2026-10-23)

Módulo WIPERS con seis avisos de mantenimiento/activación; estado normal,
velocidad, AUTO y lluvia todavía sin contrato de recepción verificado. Informe
simulado: 91 canales en diez módulos. Se añade el capturador SDK puntual y una
pantalla manual en C11 Diagnóstico 0.5, que guarda bytes originales y manifiesto
con identificador de intento/hash. No genera JPEG/PNG ni afirma captura real
validada. SDK, formato y coexistencia OEM pendientes de ensayo con el coche.

CI completo PASS: 3.720 pruebas, cero fallos/errores y 46 omitidas; 74 pruebas
Python; revisión de diez módulos en navegador PASS. Ocho pruebas sintéticas del
capturador y dos de la pantalla verifican que no se capture al abrir, copia de
buffer, firmas SDK, timeout, limpieza y guardado de bytes. Se configuró Android
32 en las pruebas de pantalla, como en las pruebas existentes, tras detectar
una incompatibilidad de Robolectric/Android por defecto con el JDK local.

Dos APK archivadas sin instalar: DiPlay y C11 Diagnóstico 0.5. Hashes, fuentes y
validación en `wipers-camera-preparation-verification.json`. No se contactó el
coche, no se accionaron limpiaparabrisas y no se capturaron imágenes reales.
Ver [captura de cámaras](LEAPMOTOR_C11_CAMERAS.md) para alcance y prueba pendiente.

### Escucha candidata de limpiaparabrisas (2026-10-23)

C11 Diagnóstico 0.6 añade ventanas manuales de 5/30 segundos para doce IDs
de estado/selector/lluvia, con bytes sin interpretar. No se añaden al catálogo
de DiPlay ni se afirman estados normales o tipos verificados. Informe limitado
por endpoint/ID, payload de 1–8 bytes y ocho cambios por candidato. Siete pruebas
sintéticas comprueban filtros, límites, cierre, guardado y apertura sin escucha
automática. CI completo PASS: 3.727 pruebas, cero fallos/errores y 46 omitidas.
APK diagnóstica archivada sin instalar: `C11-Diagnostico-wiper-probe-0.6-dev-20261023.apk`.
Hashes y alcance en `wiper-candidate-probe-verification.json`. No se contactó el
coche ni se accionaron mecanismos. La captura de cámara sigue conservando
muestras brutas, con formato físico pendiente.

### Conversión offline de muestras de cámaras (2026-10-23)

Se añade `tools/carplay/camera_frame.py`: PNG de prueba para NV12/NV21/I420/YV12
contiguos de 8 bits, con geometría, matriz y rango explícitos. Comprueba identidad
y hash del manifiesto, nunca sigue rutas del JSON ni sobrescribe directorios.
Mantiene formato físico, canal y colorimetría sin verificar. Ocho pruebas nuevas
PASS, CI Android completo PASS y patrón de color sintético comprobado mediante
lector PNG independiente y visualmente. No se contactó el coche ni se modificó
la captura Android. Evidencia en `camera-frame-decoder-verification.json`.

### Investigación de potencia, corriente y voltaje (2026-10-23)

Se identifican doce constantes OEM de batería y cargador, distinguiendo valores
candidatos de límites máximos. No se encontró contrato de recepción suficiente
en los fuentes revisados: ruta, tipo, escala y signo siguen pendientes. No se
añaden suscripciones, estimaciones ni canales a los 91 existentes. Inventario y
criterios de validación en [energía](LEAPMOTOR_C11_ENERGY_CANDIDATES.md).
