# C11 B11: inventario de solo lectura y componentes OEM

Captura: 2026-10-16, 00:59 UTC. Trabajo local, sin publicar cambios.

## Búsqueda y procedencia

Se buscó en `~/Downloads` y en este proyecto. `~/Downloads/Leapmotor/` contiene una
guía histórica y bibliotecas VNDK genéricas, sin APKs OEM ni volcados útiles.
`~/Downloads/RUNBOOK_TECNICO_SYSTEMUPDATE.md` identifica SystemUpdate B11 y su hash.
Sus instrucciones de modificación no se ejecutaron. `Auto_9.1.0.600087_release_signed.apk`
es `com.autonavi.amapauto`, no un componente OEM. AutoKit y DiPlay se excluyeron.
La inspección del índice de 94 ZIPs/contenedores de APK no encontró una imagen de
firmware ni un paquete OEM útil. Los falsos positivos de nombres se descartaron.

Sin APKs/volcados aprovechables, se consultó `adb devices -l`. Había un dispositivo
autorizado con Android 12/API 32, usuario activo 10 y componentes B11. Fingerprint:
`qti/msmnile_gvmq/msmnile_gvmq:12/SQ3A.220705.003.A1/582:user/test-keys`.
La identidad Qualcomm es genérica; la atribución B11 se apoya en los componentes
OEM y la coincidencia de SystemUpdate con el hash histórico. No se consultó el VIN.

Todos los originales y datos completos están **solo en local**, bajo:

```text
.private/c11/20261016T005916Z/
  manifest.json                  consultas del inventario y resultados
  packages-user0.txt
  packages-user10.txt
  binder-services.txt
  dumpsys-services.txt
  processes.txt
  displays.txt
  window-displays.txt
  surfaceflinger-displays.txt
  car-service.txt
  framework-files.txt
  packages/                      dumps por paquete y manifests decodificados
  original/                      ocho APKs y cuatro JARs
  extraction-manifest.json       rutas, tamaños y SHA-256 de cada original
  decompiled/                    fuentes de análisis
  decompilation-manifest.json    resultados de JADX
```

`.private/` está excluido de Git. Los dumps pueden incluir información personal;
no añadirlos al árbol público. Las fuentes descompiladas son material de análisis,
no código para copiar a nuestra aplicación.

## Pantallas observadas

| Papel | Display lógico actual | Identidad física | Resolución | Evidencia |
| --- | --- | --- | --- | --- |
| Central | 0 | `local:129`, puerto 129 | 2560×1440 | Ventana OEM `CarControlWindow` y Activity de CarControl en display 0 |
| Cuadro, área Android de navegación | 2 | `local:131`, puerto 131 | 1920×720 | `com.leapmotor.autonavi/.ui.dashboard.DashboardActivity` en display 2 |
| Acompañante | 3 | `local:130`, puerto 130 | 1920×720 | CopilotShow, `p3/a.java` (ActivityUtils), elige 3 con API 31+ y usa `setLaunchDisplayId` |
| Salida adicional | 4 | `local:4`, puerto 4 | 1920×1080 | HDMI externo; no es un cuarto panel integrante de las tres pantallas del coche |

Los tres internos anuncian aproximadamente 60 Hz y no tienen `FLAG_PRESENTATION`;
el HDMI sí. Elegir la primera pantalla de `DISPLAY_CATEGORY_PRESENTATION` no
identifica los paneles del C11. Los internos tienen nombres iguales y localizados.
Los IDs físicos SurfaceFlinger, IDs lógicos Android, índices HWC e IDs de CombinePlay
son numeraciones diferentes. Los valores anteriores son de esta sesión, no una
garantía de estabilidad entre firmwares o reinicios.

La ventana Android de navegación no demuestra propiedad de todo el cuadro:
faltan sus capas OEM, márgenes y reserva de testigos. No se lanzó ninguna Activity,
Presentation o superficie para probar acceso. Que ADB vea un display no demuestra
que nuestra app pueda usarlo.

## Componentes copiados

| Paquete | Ruta del dispositivo | Función identificada |
| --- | --- | --- |
| `com.leapmotor.carcontrol` | `/vendor/app/CarControl/CarControl.apk` | UI Unity, HVAC y broker de eventos/datos |
| `com.android.systemui` | `/system_ext/priv-app/CarSystemUI/CarSystemUI.apk` | Barras OEM, accesos Unity y controlador HVAC Android Car |
| `com.android.car` | `/system/priv-app/CarService/CarService.apk` | Android Car, propiedades y permisos |
| `com.android.car.settings` | `/system_ext/priv-app/CarSettings/CarSettings.apk` | Ajustes Automotive OEM; reservado para análisis posterior |
| `com.leapmotor.castscreen` | `/vendor/app/CastScreen/CastScreen.apk` | Composición/reproducción combinada OEM |
| `com.leapmotor.copilotshowapp` | `/vendor/app/CopilotShow/CopilotShow.apk` | Lanzamiento y contenido del acompañante |
| `com.leapmotor.driving` | `/system/priv-app/DrivingEntertainmentApp/DrivingEntertainmentApp.apk` | Candidato de entretenimiento/conducción; falta rastrear dependencias |
| `com.leapmotor.systemupdate` | `/vendor/app/SystemUpdate_B11/SystemUpdate_B11.apk` | Actualización/diagnóstico B11; no se abrió ni ejecutó |

También se copiaron de `/system/framework/`: `android.car.jar`, `lmos-framework.jar`,
`services.lmos.jar` y `car-frameworks-service.jar`. Las doce copias terminaron bien.
El manifest de extracción contiene hashes completos. SystemUpdate tiene SHA-256
`25d455a34e91c8ec25bd839853447427e5ba90a09d47071ebd975fe31f6fe8c2`,
idéntico al documento histórico; esto no verifica todos los demás componentes de
aquella sesión.

## Climatización: rutas y barreras

- **CarControl/Unity:** versión 1.12.1.20251224 (código 140), con Unity, IL2CPP y
  `liblpInterCommFw.so`. `carctrl/unity/utils/UnityPageId.java` identifica páginas
  HVAC. SystemUI contiene `UnityAirConditionerDisplayAction` y estados del aire
  acondicionado. Esos IDs de páginas no son comandos CAN.
- **Broker OEM:** `carcontrol/binder/CarControlImpl.java` implementa `getCanData(int)`
  como lectura de una caché de cadenas y registra callbacks de datos. `onTransact`
  exige el permiso `com.leapmotor.carcontrol.CAR_CONTROL_SERVICE` y comprueba que
  el UID pertenezca a un paquete `com.leapmotor…` o `com.android.systemui…`.
  Aunque el permiso se declara `normal` y MainService está exportado, eso no
  habilita a DiPlay. No se intentó eludir esa comprobación. El cliente ICarControl
  de SystemUI y el servidor no exponen exactamente el mismo conjunto de métodos:
  no copiar transacciones ni generar AIDL sin cotejar ambos DEX.
- **Android Car:** `systemui/.../car/hvac/HvacController.java` obtiene el manager
  `property`, registra callbacks y contiene setters de temperatura, ventilación y
  encendido. El dump actual muestra suscripciones a varias propiedades HVAC.
  `CarService/.../hal/PropertyHalServiceIds.java` asigna lectura y escritura HVAC
  a `android.car.permission.CONTROL_CAR_CLIMATE`, instalado como
  `signature|privileged`. No es acceso ordinario para nuestra app; tampoco prueba
  que estén implementadas todas las propiedades estándar.

No se invocaron setters, `control`, `longControl`, transacciones Binder manuales
ni registros de callbacks. Solo se leyeron archivos y dumps.

## Datos del vehículo

Se observan `car_service` (`android.car.ICar`), `lmos_carext_service`
(`lmos.framework.car.ICarExtService`), los procesos `lp_habGw`, `lp_carconfig` y
el HAL de vehículo Qualcomm.

`lmos/.../car/ICarCallback.java` y `services/carext/CarExtImpl.java` contienen
callbacks de SOC (`onSocPrencentChange`), velocidad, marcha, alimentación y pantalla.
Es una ruta candidata independiente de BYD. Los métodos inspeccionados de
CarExtImpl despachan valores al callback, pero falta rastrear quién los alimenta,
unidades, lectura inicial, frescura y permisos SELinux. No se registró un cliente.

`android.car.jar` define `EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY` y
`RANGE_REMAINING`; el servicio asigna `CAR_ENERGY` a batería/autonomía. La existencia
de constantes no demuestra que el C11 publique esos datos. El dump no dio un
inventario completo utilizable: `VehicleHal.dump` terminó con una excepción en
`TimeHalService.dump`. No implica que todas las propiedades fallen ni que el
servicio se haya caído.

Actualización posterior: el dump dirigido de PropertyHalService sí devolvió 30
configuraciones, entre ellas autonomía y HVAC, pero sin energía/capacidad de
batería ni conexión del cargador. La consulta explícita de valores por `cmd`
fue rechazada por requerir una build no-user. Véanse el catálogo resumido,
la excepción y sus límites en [telemetría C11](LEAPMOTOR_C11_TELEMETRY.md#comprobación-adb-del-firmware-instalado).

Faltan muestras verificadas de autonomía, SOC, energía/capacidad, advertencia de
autonomía y estado de carga, con unidades y edad. El nuevo proveedor Leapmotor
sigue sin conectarse; no se rellenan sus campos con valores supuestos.

## Gestión OEM de pantallas

Existe `lp_combine_screen_play` (`lmos.framework.combineplay.ICombinePlay`) y un
proceso homónimo. `CombinePlayManager` ofrece creación, retirada, ocultación y
latido de render. CastScreen/SubActivity usa `CombinePlayView` con ese servicio.
No se llamó a esas operaciones ni se inyectaron eventos táctiles. Esta ruta de
composición OEM no es evidencia de Ultra ni de tres streams independientes.

El HAL de cuadro declara `mIsCoreSupported:false` y
`mIsNavigationStateSupported:false`; no asumir que Android Car Cluster sustituye
la ruta OEM de mapas.

## Calidad y límites

JADX 1.5.6 procede de su repositorio oficial, verificado contra el digest SHA-256
de la release. Android Car, LMOS, Driving y framework de servicios terminaron sin
errores de JADX. CarService tuvo diez errores; CarControl (43), SystemUI (15),
CastScreen (8) y Copilot (50) terminaron con código 3 y fuentes parciales.
Se conservan logs. Los métodos
citados se pudieron leer; clases ausentes o métodos marcados como incorrectos
requieren cotejo con DEX/smali y, para Unity, análisis IL2CPP. CarSettings y
SystemUpdate tienen aquí copia original, manifest y metadatos.

Ya hay APKs e inventario; siguen pendientes acceso de una app ordinaria, contratos
HVAC, fuente de datos, rutas a paneles y composición segura del cuadro. Las brechas
de negociación, renderer local y sincronización Ultra de
[LEAPMOTOR_C11.md](LEAPMOTOR_C11.md) siguen abiertas.

Se usaron `getprop`, consultas `pm`, `service list`, `ps`, `dumpsys`, `ls` y `adb pull`.
No se usaron `push`, instalación, grants, cambios de Settings, `service call`,
comandos UDS/QNX, lanzamiento de Activities ni escrituras en el coche.

Verificación local: doce archivos ZIP/APK/JAR íntegros según CRC y con tamaños
coincidentes con el manifest de extracción. La línea completa de checks de
AGENTS.md terminó con `BUILD SUCCESSFUL`; `git diff --check` y
`scripts/check_public_tree.py` también pasan. Esta fase solo añade documentación
y material local de análisis; no modifica el runtime de DiPlay.
