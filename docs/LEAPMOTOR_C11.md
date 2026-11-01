# Leapmotor C11 EV 2024: investigación e integración

Fecha: 2026-10-16. Base inspeccionada: DiPlay `1b1ac5f2`.
Rama: `leapmotor/c11-integration`. Original: `https://github.com/shihabal3amri/DiPlay.git`
como `upstream`. La investigación inicial fue local; después se autorizó un
[inventario ADB de solo lectura y extracción de APKs OEM](LEAPMOTOR_C11_INVENTORY.md).
AutoKit queda fuera.
Fork creado y verificado: [SouthCrux/DiPlay-Leapmotor](https://github.com/SouthCrux/DiPlay-Leapmotor),
configurado como `origin` y destino por defecto de push (`remote.pushDefault`).
El push a `upstream` está bloqueado mediante una URL local de destino inválida;
su URL de lectura se conserva. El trabajo permanece local, sin commits, pushes ni PRs.

## Qué significa soporte de Ultra

La [revisión actualizada de recursos y viabilidad](LEAPMOTOR_C11_ULTRA_STATUS.md#reassessment-with-new-public-code-and-apple-resources)
del 2026-10-17 identifica código público reciente de LIVI para estado moderno,
UI Sync y temas, además de un paquete Apple con fuentes Lua inspeccionadas.
Esto abre una vía concreta de prototipo; las observaciones de la sesión C11
descritas a continuación siguen siendo de CarPlay convencional.

[xcertplay](https://github.com/shilapi/xcertplay/blob/master/README.md) documenta que
puede provocar el aviso de Ultra y que la pila está incompleta y sin probar. La copia
en [UPSTREAM-README.md](UPSTREAM-README.md) conserva esa advertencia. No identifica
una secuencia reproducible que complete una sesión Ultra en este C11.
En esta base no se ha localizado una ruta de código identificada como activación
Ultra; atribuir el aviso a un bit concreto sin una traza sería una hipótesis.

Apple describe una arquitectura con vídeo remoto por pantalla, renderizado local,
paquetes de assets del vehículo autenticados, composición de capas, UI Sync y un
protocolo de estado del vehículo. También incluye controles de climatización y UI
propia del fabricante. Véase [WWDC24: arquitectura de la siguiente generación de
CarPlay](https://developer.apple.com/videos/play/wwdc2024/10111/).
Esta descripción pública no especifica por sí sola el protocolo binario necesario.
[Apple remite al programa MFi para integrar Ultra en sistemas de
vehículos](https://developer.apple.com/carplay/).

Conclusión: ni el aviso del iPhone, ni dos vídeos CarPlay, ni los mensajes de batería
iAP2 son pruebas de Ultra funcional. No se ha identificado en el código un ajuste
que complete esa implementación. No se añadirán flags de capacidades inventados.

## Evidencia del código y brechas

### Destinos de pantalla acordados

El coche tiene tres destinos: infoentretenimiento central (`local:129`,
2560×1440), acompañante (`local:130`, 1920×720) y capa Android del cuadro
(`local:131`, 1920×720). Los dos últimos coinciden en resolución, pero son
pantallas diferentes y deben seleccionarse por identidad verificada.

Para CarPlay convencional se destina únicamente el recuadro de navegación de
720×720 en (1200,0) del cuadro a tarjetas de próxima maniobra o al mapa
alternativo. Esas medidas no describen el panel completo. El objetivo Ultra
abarca la integración y composición del cuadro completo; no está implementado
y no se puede equiparar a ampliar esta superposición por encima de los
indicadores OEM. La resolución y las capas físicas completas de esa futura
composición requieren validación independiente de la captura Android.

El objetivo de esta iteración es Ultra en dos pantallas independientes: central
y acompañante. Las apps Android locales, su APK auxiliar y AutoKit quedan fuera
de alcance. No se intenta vincularlas con apps CarPlay. El cuadro se conserva
como un tercer destino independiente para una fase posterior.

Los widgets solicitados son velocidad, batería y autonomía. SOC y autonomía
tienen lecturas OEM e intercambio iAP2 comprobados; falta el transporte y la
presentación propios de Ultra. La velocidad no tiene todavía una lectura en
vivo validada en este backend: no se representa como cero ni se sustituye por
velocidad GPS sin acuerdo y verificación de unidades.

El botón CarPlay de regreso al sistema usa por defecto el nombre Leapmotor y
su emblema oficial en el perfil C11. Conserva las imágenes personalizadas y los
nombres propios; un nombre BYD heredado del valor predeterminado se adapta al
perfil. El cambio se presenta al iPhone en la siguiente conexión. Emblema tomado
de [Leapmotor](https://www.leapmotor.net/images/default-source/b10/logo-white.svg),
sin el texto horizontal, renderizado como PNG de 192×192 para el protocolo.

### Arranque del hotspot al abrir DiPlay

Se habilitó en la instalación de prueba la opción existente
`diplay_car_hotspot/auto_enable` y se concedió WRITE_SETTINGS. Android 12 rechaza
la ruta antigua de startTethering de una app, pero el fallback existente a ADB
local ejecutó CarHotspotStartMain y recibió resultado 0. Tras abrir DiPlay desde
el hotspot apagado se verificó `ROLE_SOFTAP_TETHERED`, interfaz activa y
`car hotspot auto-enable: Car hotspot is on`. Cerrar la app no apagó el AP;
volver a abrirla conservó el hotspot. Se reutilizan SSID, seguridad y contraseña
del sistema, sin crear un punto de acceso alternativo.

La clave ADB propia de DiPlay se autorizó mediante el diálogo del sistema, con
permiso persistente. El firmware tenía `persist.adb.tcp.port=5555` pero no
`service.adb.tcp.port`; UsbDebuggingActivity cancelaba inmediatamente la
confirmación al recibir USB_STATE desconectado. Reiniciar ADB con `tcpip 5555`
permitió que el diálogo permaneciera abierto y guardar la autorización. No se
extrajo la clave privada ADB de la app. La persistencia tras un reinicio completo
del coche todavía requiere prueba.

El apagado automático general ya estaba deshabilitado: UI y `mTimeoutEnabled`
coincidían en false. Se capturó otro apagado a las 20:28:23 del reloj del coche,
cinco minutos después del encendido. SoftApManager registra `Instance idle
timout`, retira `wlan3(5180)` del bridge y después `interface error, stop and
report failure`. El temporizador de retirada de instancias del modo bridged
está habilitado, con delay 300000 ms, independientemente del apagado general.
Esto localiza la secuencia del fallo; la recuperación no corrige el error del
driver/firmware ni garantiza continuidad sin interrupciones. No se altera la
configuración de bandas o seguridad del sistema.

El comportamiento solicitado es que el hotspot esté activo mientras se usa
DiPlay; al cerrar la app puede apagarse. ForegroundHotspotRecovery comprueba
cada cinco segundos el estado mientras una pantalla DiPlay está visible. Solo
en el perfil C11, con el arranque automático habilitado, conexión inalámbrica y
modo de hotspot del coche, solicita de nuevo el arranque si Android confirma
que está apagado. Un estado desconocido no provoca cambios. No inicia diálogos
de autorización ni modifica la configuración del AP. Al dejar de estar visible
la Activity cancela la recuperación; no instala un servicio que lo mantenga
encendido tras salir. Tras un fallo espera un minuto antes de volver a intentar.
Las cinco pruebas de ciclo de vida verifican recuperación, cancelación,
ausencia de solicitudes ante un estado activo/desconocido y espera tras fallo.

Las rutas siguientes son relativas a la raíz del repositorio.

| Área | Código existente | Qué falta para el objetivo C11 |
| --- | --- | --- |
| Declaración de pantallas | `shared/.../airplay/AirPlayConfig.kt`: `main` y un `cluster` opcional. `AirPlayInfoPlist.kt`: UUID principal/alternativo, tipos 110/111 | Identidades y negociación verificadas para tres salidas independientes; no basta con añadir un tercer diccionario |
| Recepción de vídeo | `shared/.../airplay/AirPlaySession.kt`: `handleStreams`; `CarPlayMediaEngine.kt`: `StreamKey(session, type)`; `shared/.../media/AndroidMediaSink.kt`: decodificadores/superficies por tipo | Distinguir múltiples displays del mismo tipo en todos los niveles, cierre/reemplazo y reconexión; verificar límites de decodificación simultánea |
| Mapa secundario | `CarPlayClusterDisplay.kt`: contenido `maps:/car/instrumentcluster/map`, `altScreenURLs` y `showUI` en `AirPlaySession.kt` | Aprovechable para una primera prueba de mapa convencional; no demuestra Ultra ni un tercer stream |
| Salida Android | `common/.../CarPlayHostActivity.kt`: `clusterDisplayConfig`, `ensureClusterPresentation`; `ClusterMapPresentation.kt`: `findDisplay` | Inventario real del C11: proceso/SoC que controla cada panel, display público/privado, permisos, tamaño y área segura. No asumir IDs persistentes, resoluciones ni accesibilidad desde Android |
| Acoplamiento BYD | `ClusterMapPresentation.kt` selecciona capas DiLink; `CarPlayClusterDisplay.kt` usa área segura y dimensiones físicas BYD por defecto | Un selector y geometría Leapmotor explícitos, sin seleccionar cualquier display ni cubrir testigos OEM |
| Batería/autonomía | `shared/.../transport/Iap2VehicleStatus.kt`: `VehicleStatusProvider`, identificación condicional y suscripción 0xA100/0xA101/0xA102 | Fuente C11 verificada; unidades, carga, frescura y variante de conectores. El modelo EV no ofrece velocidad, puertas o climatización |
| Proveedor actual | `shared/.../hud/BydBattery.kt` consulta `autoservice` con IDs CAN/CANFD BYD. La política C11 impide activar ese proveedor desde los ajustes existentes | Inyección de proveedor Leapmotor explícita una vez comprobada su fuente. Nunca usar el proveedor BYD como fallback |
| Navegación y controles OEM | `shared/.../hud/BydNavigationOutputs.kt` reúne HUD, cuadro, llamadas, música, velocidad y aparcado | Separar decodificación iAP2 reutilizable de salidas OEM. No invocar bridges BYD para Leapmotor |
| Ultra | La declaración y sesión observadas implementan CarPlay y mapa alternativo | Negociación específica, modelo/configuración del vehículo, transporte de estado/acciones, assets auténticos, renderer local, compositor, UI Sync, recuperación y fallback OEM |
| Climatización | `VehicleStatusSnapshot` no contiene HVAC; no se identificó un backend HVAC Leapmotor | Lectura y comandos independientes, rangos por zona, permisos, confirmación del estado aplicado y errores. No fingir éxito ni derivar comandos de los BYD |

`shared/...` significa `shared/src/main/java/com/shilapi/xcertplay/`;
`common/...` significa `common/src/main/java/com/shilapi/xcertplay/`.
La ausencia de implementación Ultra es una conclusión de esta inspección, no una
garantía de que un protocolo propietario pueda completarse con la información pública.
El canal `VideoSettingsChannel` es de vídeo en el coche, no UI Sync de Ultra.

También se consultó xcertplay en el commit
[`d58cd449c391b3341e1a0e5fa611db612f7ee82a`](https://github.com/shilapi/xcertplay/tree/d58cd449c391b3341e1a0e5fa611db612f7ee82a):
su `AirPlayConfig`/`AirPlayInfoPlist` sigue declarando principal y alternativa.
Incluye una abstracción más reciente `vehicle/source/VehicleDataSource.kt` con
fuentes seleccionables y perfiles Android Car. Es una referencia útil para la
futura inyección C11, pero no verifica que este firmware exponga Android Car ni
proporciona por sí sola acceso a HVAC. No se importa esa infraestructura entera
ni se cambia la base DiPlay en esta primera etapa.

## Primer cambio implementado

### Revisión de Ultra y botón del asistente

La revisión posterior mantiene estos resultados concretos:

- `AirPlayInfoPlist.build` declara main 110 y cluster 111. `AirPlaySession`
  solicita los contenidos clásicos de mapas; `CarPlayMediaEngine.StreamKey`
  distingue vídeo por sesión y tipo, sin identidad independiente de cada panel.
  Añadir otro descriptor o mover 111 al acompañante no implementa dos pantallas
  Ultra interactivas.
- El log privado de la conexión real anterior contiene únicamente las tres
  URLs clásicas de mapas en la petición `/info`. No se registró en esa prueba
  negociación de assets, UI Sync o estado Ultra. Esto describe esa conexión;
  no demuestra una limitación permanente del iPhone.
- Apple explica composición local/remota, assets y protocolo de estado, pero
  la charla pública no proporciona la codificación y secuencia completa para
  implementar la sesión. La documentación técnica se canaliza por MFi. Véanse
  [arquitectura WWDC24](https://developer.apple.com/videos/play/wwdc2024/10111/)
  y [CarPlay para fabricantes](https://developer.apple.com/carplay/).
- El upstream consultado sigue describiendo el disparo del aviso Ultra como
  incompleto y sin probar; su configuración sigue exponiendo main y cluster.
  No se identificó una implementación funcional para importar. Investigación
  independiente del autor de carplayd distingue el estado iAP2 clásico del
  modelo VDC moderno, que también señala como pendiente de implementar:
  [estado del proyecto](https://github.com/lvalen91/carplayd),
  [análisis del autor](https://github.com/lvalen91/carplayd/blob/main/docs/APPLE_BEHAVIORAL_REFERENCE.md).
  Sus inferencias no se convierten en contratos confirmados del C11 ni en
  capacidades anunciadas por este fork.
- No se encontró CarPlay Simulator ni Xcode instalados en este equipo. Sin
  una especificación suficiente o una referencia ejecutable verificable, esta
  revisión no habilita Ultra. No se alteran flags de la negociación actual
  para aparentar ese resultado.

Para el botón se acordó pulsación corta = Siri y doble pulsación = ChatGPT,
no pulsación larga. Queda por elegir si ChatGPT debe ejecutarse en el iPhone o
en Android del coche; esa elección determina el mecanismo de lanzamiento.

El CarInputService del firmware inventariado intercepta KEYCODE_VOICE_ASSIST
(231) antes del envío normal a ventanas. La pulsación corta llama a
`launchDefaultVoiceAssistantHandler`, que resuelve el asistente del usuario y
usa `AssistUtils.showSessionForActiveService`, con flag 32. Esto ofrece una
ruta candidata mediante un VoiceInteractionService de DiPlay elegido como
asistente predeterminado. La API es
[pública en Android](https://developer.android.com/reference/android/service/voice/VoiceInteractionService).
No se ha cambiado aún el asistente ni implementado ese puente; hay que
verificar primero que el botón físico sigue esa ruta. El registro de eventos
de proyección exige CAR_PROJECTION (signature|privileged), por lo que no se
supone disponible para una app ordinaria. El servicio de accesibilidad podría
no recibir un evento que ya haya consumido Android Car.

Como candidato de velocidad, ExtendControlManager.onMessageDouble procesa
servicio 39168, evento 4030; mantiene carSpeed y deriva carIcuSpeed mediante
`int(speed * 1.04 + 0.25)`. Se conserva como evidencia estática: faltan lectura
en vivo, unidades confirmadas y contraste con el cuadro antes de usarlo en
widgets. No se agregan escrituras al coche.

Al revisar este alcance, `adb devices -l` no mostró dispositivos. No se
instaló una APK, no se activó Ultra y no se verificó el botón en el coche.

`shared/.../vehicle/leapmotor/LeapmotorVehicleStatusProvider.kt` implementa el contrato
existente usando lecturas normalizadas, una caché y reloj monótono inyectable:

- Sin lecturas, con datos caducados/futuros/inválidos o tras perder la fuente, devuelve
  `null`; la identificación existente no anuncia estado EV sin una lectura disponible.
- Rechaza NaN, infinitos, desbordamientos del formato iAP2 y energía incoherente.
- No estima capacidad ni autonomía máxima a partir del porcentaje. La energía
  desconocida permanece ausente en el mensaje iAP2.
- El futuro adaptador debe proporcionar todos los campos obligatorios verificados,
  incluida la advertencia de autonomía y el estado de carga; debe llamar `clear()`
  al perder permisos/fuente o desconocer alguno. Un cambio aislado de un campo no
  debe renovar la edad de los demás: `observedAtMillis` representa el campo más antiguo.
- El límite de frescura inicial es 60 segundos para estado EV, configurable al conocer
  la cadencia de la fuente. No sirve como límite para señales rápidas del cuadro.
- No consulta Android, ADB, CAN, servicios OEM o red, ni escribe en el vehículo.
  No está conectado al runtime: añadir una clase no habilita telemetría en el C11.

Sus pruebas cubren identificación con/sin datos, caducidad, pérdida de fuente,
regresión de reloj, validación y codificación iAP2 con energía desconocida.
No se modifica la UI de Settings ni se habilitan capacidades Ultra.

## Segundo cambio: navegación independiente de las salidas OEM

El controlador arrancaba `BydNavigationOutputs` en su constructor y acoplaba la
tarjeta de siguiente giro a ese objeto. Ahora:

- `VehicleNavigationOutput` es la salida OEM inyectable del controlador. La opción
  `NONE` no inicia lectores, workers, bindings ni broadcasts del fabricante.
- El manifest de `mobile` declara explícitamente `leapmotor-c11` mediante
  `com.shilapi.xcertplay.VEHICLE_INTEGRATION`. `VehicleIntegrationPolicy` selecciona
  `NONE` para C11 y para valores explícitos desconocidos; conserva BYD para los
  samples upstream sin metadato o con selección explícita `byd`. No adivina la marca
  por versión Android, nombres de paquetes o preferencias.
- `CarPlayTurnOverlay` recibe las maniobras del iPhone desde el controlador sin
  pasar por los emisores OEM. La Activity observa su estado directamente. Se
  conserva la caducidad, retención durante un corte inalámbrico y limpieza al cerrar.
  Reutiliza el parser iAP2 y las correspondencias de iconos existentes, que todavía
  tienen nombres BYD; estas funciones no envían comandos al coche.
- La entrada de arranque `BydNavigationOutputs.onAppOpened` queda anulada en el
  perfil C11. Las opciones antiguas de batería/velocidad/aparcado no activan sus
  lectores. La shell de fondo BYD rechaza ese perfil antes de conectar ADB, y la
  suspensión Bluetooth heredada no se ejecuta ni recupera journals BYD en C11.

La prueba `LeapmotorNavigationIsolationTest` crea un controlador con metadatos
C11, le entrega maniobras iAP2, comprueba la tarjeta y su limpieza, y verifica que
no hubo bindings ni broadcasts OEM. `VehicleIntegrationPolicyTest` cubre la
selección, valores desconocidos, preferencias antiguas y rechazo previo a ADB.
Los tests existentes de expiración se ejecutan directamente sobre el estado
independiente de la tarjeta.

Este paso aísla el arranque y las salidas de navegación del controlador; no supone
que toda la aplicación esté portada. Los diagnósticos BYD manuales y las rutas
físicas DiLink siguen siendo código heredado pendiente de retirar o proteger en
el perfil C11 antes de instalar. No se ha seleccionado un display físico C11,
conectado el proveedor EV nuevo ni implementado HVAC.

## Salida experimental al recuadro de navegación del cuadro

### Perspectiva y vista general: revisión del 25/10/2026

Las fotografías aportadas muestran una vista del recorrido desde arriba en el cuadro,
con el destino visible, y una vista cercana en perspectiva en la pantalla central.
Las distancias restantes de ambas fotografías son distintas: no son una captura
sincronizada que permita atribuir una transición concreta a DiPlay.

El C11 recibe el mapa secundario tipo 111 (`maps:/car/instrumentcluster/map`) en
`LeapmotorClusterMapOverlay`, mediante un `SurfaceView` que ocupa el recuadro 720×720.
No hay transformación de inclinación ni una conversión local de 3D a 2D en esa ruta.
La cámara y el contenido se dibujan en la aplicación de navegación del iPhone. La
[documentación de Apple sobre el cuadro](https://developer.apple.com/videos/play/wwdc2022/10016/)
describe una escena independiente de la aplicación y delegados para los controles
de zoom y las áreas seguras.

Existe una orden de zoom dirigida al UUID del cuadro: `changeMapZoomLevel`, expuesta
por `CarPlayController.zoomDashboardMap`. No se identificó en el código inspeccionado
una orden confirmada para forzar la inclinación 3D. El siguiente contraste debe pedir
un acercamiento del mapa secundario y observar si Maps vuelve al seguimiento cercano;
un cambio de zoom no se considera una solución 3D validada. No se altera por esta
hipótesis la ruta del display, el tamaño del stream ni las tarjetas ya aceptadas.
La revisión se hizo sin usar la pantalla ni enviar comandos al coche, que estaba
reservado para otra modificación con SystemUpdate.

El candidato 47 añade «Acercar mapa del cuadro» y «Alejar mapa del cuadro» en
Avanzado, dentro de la tarjeta existente del mapa del cuadro cuando está habilitado.
Cada pulsación solicita un paso de zoom para la sesión actual; abrir los ajustes
no envía órdenes. No requiere reconectar ni cambia la geometría o las posiciones
de las tarjetas. Se verificaron capturas locales compactas y amplias con letra
grande, incluida una captura RTL, y pasaron las comprobaciones Android completas.
La versión 47 se instaló posteriormente por ADB, con autorización del propietario.
La respuesta real de Maps a estos controles y la perspectiva del mapa siguen
pendientes de prueba en el coche.

### Recuperación de la ruta tras reiniciar: revisión del 27/10/2026

El propietario volvió a informar que el cuadro no mostraba contenido. La versión
47 renovaba la ruta específica del arranque una sola vez en `onCreate`: una falta
temporal de acceso ADB o una pantalla todavía no disponible dejaba esa ruta sin
renovar durante el resto de la actividad. Esto es una debilidad reproducible de
la recuperación, pero no demuestra la causa única del intento físico sin registros.

El candidato 48 comprueba la ruta al reanudar el host y reintenta su medición con
esperas de 1, 2, 4, 8, 16 y hasta 30 segundos mientras el host está visible. Los
eventos de aparición o cambio de pantalla también pueden iniciar la comprobación.
Solo se permite una medición simultánea; al pausar o destruir el host se cancelan
los reintentos y una respuesta tardía no restaura una ventana oculta. Se conservan
las comprobaciones de firmware, arranque, identidad física `local:131`, dimensiones
y modo. No se infieren IDs lógicos ni se modifica el recuadro o las tarjetas.

Si la ruta queda verificada después de negociar un stream virtual, se conserva el
comportamiento existente de reconectar CarPlay para solicitar el stream del cuadro.
El nuevo coordinador no actúa en los otros perfiles de coche. La validación física
sigue pendiente y no debe darse el mapa por recuperado solo por compilar.

Las comprobaciones completas del candidato combinado con Portón pasaron: 3753
pruebas, sin fallos ni errores y 46 omitidas, lint y compilaciones. Los tres casos
nuevos del coordinador cubren disponibilidad tardía, cancelación al ocultar el host,
ausencia de mediciones simultáneas y espera máxima entre reintentos. El propietario autorizó después la instalación y ADB confirmó la versión 48 en
el usuario 10. Se conservó la configuración privada de Portón. No se enviaron
órdenes al accesorio; falta validar la proyección y el botón en ejecución.

### Mapa recibido y conflicto con la copia de Amap: observación posterior

En la sesión posterior de la versión 48 se verificó la ruta del arranque 2856 a
`local:131`, display lógico 2. El iPhone negoció el stream 111 a 720×720 y se
registraron fotogramas decodificados a unos 30 fps. Tras reabrir DiPlay se obtuvo
`cluster buffer sample result=SUCCESS content=VARIED`; una captura privada de
la composición Android mostró el mapa de Waze dentro del recuadro esperado. Esto
no contradice el informe del propietario: el cuadro físico seguía en una página
Amap con «Iniciando», sin su opción adicional de proyección. La captura Android
no prueba qué página está mostrando la composición física del cuadro.

La aplicación OEM de navegación se había actualizado de 2611 a 3000 el 26/10.
Además, `com.leapmotor.autonavi` y la copia `com.santiago.amap.oemuy` registraban
ambas `leapmotor.autonavi.ThirdActivity`. La resolución devolvió
`com.android.car.activityresolver/.CarResolverActivity`; los registros muestran
intentos de apertura de esa acción en display 2 desviados a ese selector. Los
manifiestos privados confirman que ambas DashboardActivity son no exportadas.
La apertura directa desde shell fue rechazada; no se intentó eludir ese permiso.

El conflicto del registro es concreto, pero su relación completa con la página
física «Iniciando» necesita contraste. La corrección debe aislar la acción de la
copia y hacer explícitas sus aperturas internas, preservando los componentes
privados. No se desinstaló, deshabilitó ni modificó ninguna de las dos apps OEM,
no se usó SystemUpdate y no se atribuye este conflicto a traducir una etiqueta.
Los manifiestos, la captura y el informe permanecen en `.private`.

### Tema automático del mapa del cuadro: revisión del 01/11/2026

El propietario mostró una fotografía diurna con la navegación central clara y el
mapa del cuadro oscuro. Solicitó que el cuadro siga automáticamente el día/noche
del coche. El modo existente `Sistema` de DiPlay toma ese estado de la configuración
Android del vehículo; no se añade un selector, reloj ni sensor independiente.

Apple documenta que cada stream del cuadro tiene un modo nocturno independiente y
que la apariencia del mapa puede diferir de la interfaz:
[WWDC19](https://developer.apple.com/videos/play/wwdc2019/252/) y
[WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/).
El envío anterior era solamente `setNightMode` sin destino y descartaba el valor
después de escribirlo, aunque el stream secundario todavía no hubiera arrancado.

El candidato 49 conserva el envío general utilizado por la central y añade un
`setNightMode` con el UUID negociado del cuadro y el mismo valor día/noche. Retiene
el último estado para enviarlo después del SETUP del stream 111, al recrearse ese
stream y al restablecerse el canal de eventos. Las escrituras pendientes conservan
solo el valor más reciente. No se reinicia CarPlay ni se altera el vídeo, la
geometría del recuadro o las tarjetas para cambiar el tema. La aceptación de la
orden dirigida por el iPhone y el resultado visual requieren validación física;
una escritura correcta no demuestra que la app de mapas haya cambiado de tema.

El candidato 49 pasó las comprobaciones Android completas ejecutadas localmente
en la Mac del propietario: 3756 pruebas, sin fallos ni errores y 46 omitidas,
lint y compilaciones. Tres pruebas nuevas inspeccionan los comandos cifrados
emitidos para el arranque tardío del cuadro, los cambios día/noche y la recreación
del stream, además de mantener el comando anterior en sesiones con una pantalla.
La versión 49 se instaló después con autorización del propietario. Se verificó el
número de versión y que el ajuste no tiene anulación manual (predeterminado
`Sistema`); el coche informó `notnight` en ese momento. Se abrió DiPlay. El
propietario indicó que realizará personalmente las pruebas del coche, por lo que
se detuvieron las comprobaciones en el vehículo. El cambio visual sigue pendiente
de su validación.

La captura de la capa Android física `local:131` muestra un canvas 1920×720 con
un recuadro OEM de mapas en x=1200, y=0, ancho=720, alto=720. No contiene los
indicadores que el usuario ve en las otras capas del cuadro; la captura por sí
sola no demuestra la composición física completa. Se limita la prueba a ese
recuadro Android y se necesita contraste visual en el coche.

`LeapmotorClusterDisplay` selecciona solo ese perfil en el firmware
`qti/msmnile_gvmq/msmnile_gvmq:12/SQ3A.220705.003.A1/582:user/test-keys`, bajo
el metadato explícito C11. El SDK público API 32 no expone `Display.getUniqueId`
y la primera prueba no obtuvo una identidad utilizable desde la descripción
accesible a la app. Por eso no se adivina el cuadro por nombre, resolución o
posición: la ruta se provisiona explícitamente tras leer `dumpsys display` por
ADB y resolver una única identidad física `local:131`.

La configuración queda en `no_backup/c11-cluster-route.json`, privada del UID de
pruebas, sin formar parte del APK ni de un backup. Contiene schemaVersion 1,
fingerprint, BOOT_COUNT actual, identidad física, display lógico, modeId y tamaño.
La app comprueba el arranque, firmware, modo, ID y tamaño, además de exigir un
display válido, público, sin rotación y distinto del principal. Un archivo
ausente, malformado, mayor de 2 KiB o de otro arranque desactiva esa ruta. Los IDs
lógicos no se consideran permanentes: tras reiniciar el coche hay que repetir
la validación ADB para esta prueba experimental. No usa reflexión oculta,
exenciones de APIs, permisos nuevos o fallback al acompañante, HDMI o BYD.

La primera ventana TYPE_PRESENTATION fue rechazada por Android con
`InvalidDisplayException`, aunque el display estuviera disponible para la app.
La salida C11 se implementa como TYPE_APPLICATION_OVERLAY, con un contexto de
ventana asociado explícitamente al display y al tipo de overlay. Requiere el
permiso de dibujar sobre otras apps que el manifest ya declara; no modifica
SELinux ni necesita root. La implementación sigue la API pública de
[Context.createWindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle)).

La superposición tiene una ventana de exactamente 720×720 en (1200,0), sin
foco, pulsaciones o dimming. No instancia las vistas BYD de velocidad o música.
La ventana permanece invisible sin stream 111 o si se oculta el mapa; el cierre
retira la ventana. El stream solicitado es cuadrado 720×720, 30 fps, sin input,
con márgenes equilibrados y sin atribuir medidas físicas BYD al C11. Respeta la
elección de contenido de mapa/tarjeta existente. El informe incluye identidad
de pantallas y el perfil seleccionado. Preferencias antiguas no pueden habilitar
`AdbClusterRouter` para C11.

`ClusterMapWindow` separa el contrato del decoder de su tipo de ventana: BYD
conserva su Presentation; C11 usa `LeapmotorClusterMapOverlay`. No se cambia el
tipo de una Presentation ya creada ni se intenta reutilizar su window token.

Prueba instalada del 2026-10-17: Android aceptó la superposición en el display
lógico 2, resuelto para ese arranque desde `local:131`. `dumpsys window` confirma
`mFrame=[1200,0][1920,720]`, idéntico al de DashboardActivity de Amap, con los
flags sin foco ni pulsaciones y alpha 0 mientras no hay stream. Se concedió el
permiso de superposición únicamente a `com.shihab.diplay.hudtest`. AutoKit y la
instalación original de DiPlay permanecen intactos. La comprobación de imagen
real y composición física sigue pendiente: tras activar el hotspot, el último
intento se detuvo al conectar el servicio Bluetooth del iPhone.

La prueba requiere reconectar para negociar esas dimensiones. No cambia mediante
comandos la sección OEM seleccionada en el cuadro: el propietario debe elegir
su sección de mapas para observar la salida. Esta implementación sigue siendo
el stream convencional 111 de CarPlay, no una negociación Ultra.

## Secuencia de trabajo pendiente

La [traza de telemetría y lector de propiedades](LEAPMOTOR_C11_TELEMETRY.md)
documenta el avance posterior. La [APK independiente de diagnóstico](LEAPMOTOR_C11_DIAGNOSTICS.md)
ya se instaló y probó con autorización en el C11: conecta a Android Car por la API
pública, pero autonomía devuelve timestamp 0 y las otras tres propiedades EV no
están expuestas. El productor LMOS y las lecturas EV completas siguen sin
verificar por esa vía. La versión 0.4 sí recibió datos OEM por JNI: SOC 53,1 %,
autonomía 227 km contrastada con el usuario y estados brutos de carga. Se acordó
asumir reciente el último dato y usar el tiempo de recepción como referencia,
manteniendo desconocido el timestamp de origen. Esta ruta se probó con SELinux
Permissive. Ya se implementó el backend parcial de SOC/autonomía en el runtime,
con proceso separado y datos opcionales ausentes. Las pruebas posteriores con
iPhone aceptaron identificación/autenticación y solicitaron estado EV por USB
y por Wi-Fi; se transmitieron 226 km y SOC 52,9/52,8 %. Wi-Fi mostró vídeo
principal y recibió un mapa alternativo sin salida física asignada. USB no llegó
a establecer vídeo. La visualización EV en iOS, la validación de carga y Ultra
siguen pendientes. El contrato y sus límites están en
[telemetría](LEAPMOTOR_C11_TELEMETRY.md#integración-runtime-de-soc-y-autonomía).

1. Analizar artefactos locales autorizados del firmware C11 para identificar servicios,
   interfaces, permisos y rutas de lectura. Registrar versión y origen de cada evidencia.
   La coincidencia de un nombre o ID no verifica su semántica.
2. Preparar fixtures sin identificadores personales para SOC, autonomía, energía y
   carga; verificar unidades, señales ausentes y envejecimiento. Conectores y capacidad
   dependen de la variante/mercado, no sólo del nombre C11 EV 2024.
3. Implementar la fuente de sólo lectura y su selección explícita; conectar el proveedor
   al controlador tanto en USB como en inalámbrico, sin inicializar salidas BYD.
4. Preparar un inventario de pantallas y una ruta Leapmotor explícita. Probar primero
   el stream 111 con fixtures/emulador; mantener medidas, márgenes y testigos OEM.
   Un espejo de ese stream en el acompañante seguiría siendo un espejo, no una tercera
   pantalla CarPlay independiente.
5. Registrar la negociación con un iPhone y un receptor de laboratorio autorizado:
   identificar qué activa el aviso, qué solicita el teléfono y dónde falla. No publicar
   secretos de identidad, credenciales, ubicaciones o capturas privadas.
6. Resolver las piezas Ultra de la tabla con especificación/evidencia verificable.
   No tratar el soporte de tres `Surface` Android como soporte de tres displays Ultra.
7. Estudiar HVAC por separado: lecturas primero, luego contrato de acciones con
   límites/unidades/zonas y confirmaciones. La ejecución en el coche requiere una
   tarea posterior autorizada. En esta fase no hay implementación de escrituras.

La validación real exige más adelante una sesión sostenida, cada pantalla con su
contenido e inputs correctos, estado coherente, reconexiones y retorno OEM al fallar.
Las pruebas locales no sustituyen esa validación. Se instaló únicamente la APK
independiente de diagnóstico; DiPlay no se instaló ni se ejecutaron comandos de
control en esta fase. El inventario ADB autorizado
ha localizado ocho APKs y cuatro JARs OEM, las pantallas y barreras de permisos;
véase el [informe de inventario](LEAPMOTOR_C11_INVENTORY.md). No se ha habilitado
un backend EV nuevo. Se verificó acceso al gestor Android Car desde la app de
diagnóstico, sin obtener valores EV utilizables.

## Entorno reproducible

CI pide Java 25, Gradle 9.5.0 (wrapper), plataforma `android-37.0`, build-tools
`36.0.0` y NDK `28.2.13676358`. El launcher local tiene Java 26.0.1; el wrapper usa
los criterios de `gradle/gradle-daemon-jvm.properties` para provisionar Java 25.
`ANDROID_HOME` apunta al SDK local. No se cambian las versiones del proyecto para
adaptarlas al equipo.

Ejecutar la línea completa de checks de [AGENTS.md](../AGENTS.md), además de
`python3 scripts/check_public_tree.py`. El APK de CI carece de identidad de
autenticación de runtime, como explica [BUILD.md](BUILD.md); compilarlo no prueba
una conexión CarPlay ni Ultra.

Resultado local del 2026-10-17: línea completa de AGENTS.md, más los tres targets
de `c11diagnostics` del workflow, finalizada con `BUILD SUCCESSFUL` (2 min 36 s).
Informes JUnit: 3.020 tests, cero fallos/errores y uno omitido. Las pruebas
dirigidas del mapa suman 29, incluidas nueve nuevas del perfil C11, provisión,
geometría, permiso de superposición y aislamiento de las rutas BYD.
Lint y ensamblado debug de `mobile`, `home`, `maphost` y `c11diagnostics` completados.
`check_public_tree.py` y `git diff --check` también pasan.
Tras instalar la APK privada de prueba se regeneró la APK debug ordinaria sin
assets `offline-mfi`; la identidad reutilizada permanece en el directorio privado.
