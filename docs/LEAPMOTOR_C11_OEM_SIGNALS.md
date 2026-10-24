# C11: ruta OEM de SOC, autonomía y carga

Fecha: 2026-10-16. Resultado: se identificó el procesamiento de SOC y carga en
CarControl y se comprobó acceso de lectura desde el diagnóstico independiente.
La autonomía se contrastó con el usuario; se implementó un backend parcial para DiPlay. El
catálogo Android Car incompleto no justifica reutilizar comandos BYD ni convertir
una caché sin edad en una medición con timestamp de origen.

## Evidencia y alcance

Original: `com.leapmotor.carcontrol`, versión `1.12.1.20251224`, código 140,
del [inventario del coche](LEAPMOTOR_C11_INVENTORY.md).
SHA-256 del APK: `31c83cb9468df8a69bfdc1e750e55b64cf4af0210dd3474c92af1179eb4a12dc`.
Los originales, fuentes descompiladas y hashes permanecen en `.private/c11/`;
no se incorporan componentes OEM al código del fork.

Se recuperaron con JADX 1.5.6 las clases ExtendControlManager, SomeIpService,
DispatchCenterImpl, ControlManager y CanService. Los archivos `*-fallback.java`
representan instrucciones para análisis, no Java compilable. La salida `simple`
de ExtendControlManager terminó con un error; se contrastaron los bloques
relevantes con el fallback. No se considera toda la descompilación correcta.
Manifiesto local: `20261016T005916Z/oem-signal-analysis-manifest.json`.

## Cadena estática identificada

```text
VSomeipSubNative (JNI, biblioteca someip.leapmotor)
  → SomeIpSubManageImpl.handleMessage / classifyMessage
  → caché SomeIpPool + callback SomeIpService.MsgCb
  → callbacks registrados en DispatchCenterImpl
  → ExtendControlManager
  → CarControlImpl / CarControlExternalImpl
```

SomeIpService configura servicio 39168 (`0x9900`), instancia 39169 (`0x9901`)
y grupo 39171 (`0x9903`), agregando los eventos de ExtendControlManager y otros
módulos. ExtendControlManager.initSub registra sus callbacks en DispatchCenterImpl.
El clasificador interpreta INT como cuatro bytes y DOUBLE como ocho, en orden
little-endian según ByteUtils. Son contratos observados en este APK; no son IDs
de CAN ni IDs de propiedades Android Car.

La cadena muestra quién procesa y redistribuye los datos dentro de CarControl.
Falta verificar el productor anterior al límite JNI, el tráfico real y la
disponibilidad efectiva en esta variante del C11. En esta fase estática inicial
no se cargó la biblioteca. La prueba posterior sí abrió una suscripción local,
como se documenta más abajo.

## Correspondencias observadas

| Señal | Entrada de ExtendControlManager | Tratamiento OEM y salida |
| --- | --- | --- |
| Porcentaje mostrado | Servicio 39168, evento 6000, DOUBLE | Acepta 0–100, formatea a una decimal y guarda `batterySoe`; redondea al entero para el broker interno 6000 y externo 12 (`EVENT_REMAIN_SOC`) y la UI de porcentaje |
| Estado de carga | Servicio 39168, evento 5702, INT | Guarda `charge_status`, actualiza UI y la clave global `Car_batteryChangeStatus`; el código de escenas trata el valor 1 como carga activa |
| Conexión AC | Servicio 39168, evento 6207, INT | Guarda `slowChargeStatus` y `strCarCCConectSts`; no equivale a carga activa |
| Conexión DC | Servicio 39168, evento 5905, INT | Guarda `fastChargeStatus`; tampoco equivale por sí sola a carga activa |
| Autonomía candidata | Servicio 4161, evento 301, INT | Reenvía la cadena al broker externo 10 (`EVENT_REMAIN_MILE`); faltan unidades y muestras |

Para SOC, `AudioEvent.OPCODE_BMS_BattSOC_Display` coincide con 6000. La ruta de
UI respalda que representa un porcentaje mostrado; no prueba que mida energía
en Wh ni capacidad útil. Para carga, el valor 1 tiene una interpretación interna
clara, pero no se ha validado en vivo la enumeración completa ni los estados
de fallo, espera o finalización. No se implementa una conversión booleana que
trate todos los demás valores como una lectura válida de «no cargando».

Hay varios espacios de identificadores: CanHeadUtils también define SOC 3160 y
estado de cargador 3102, distintos de los eventos procesados aquí. La semejanza
de sus nombres no autoriza a intercambiarlos.

Dos nombres especialmente engañosos:

- `EVENT_ALL_SOC` / 13 se inicializa desde `persist.sys.lp.config.SOCValue` con
  un valor de configuración por defecto. No se toma como SOC actual.
- `EVENT_REMAIN_SOC_ELE` / 20 recibe el mismo valor que el evento de autonomía
  301 en la rama EV extranjera. En la rama de autonomía extendida extranjera,
  `handleUpdateCurrEvKm` lo alimenta con `mCltcEvKm` o `mWltcEvKm`. Su nombre no
  acredita un porcentaje; no debe mapearse a batería.

`EVENT_CHARGING_TYPE` / 17 y `EVENT_DISCHARGE_STATUS` / 18 existen en ThirdAppEvent,
pero su productor no quedó identificado en las clases recuperadas. No se les
atribuye una semántica completa solo por esas constantes.

## Acceso y frescura

El broker interno exige `CAR_CONTROL_SERVICE` y comprueba paquetes del UID.
El externo exige el mismo permiso y una lista explícita de paquetes admitidos.
Aunque el permiso está declarado normal, DiPlay no está en esas listas; tampoco
Calendar o Gallery. La firma AOSP test-key no cambia esas comprobaciones.
No se invocaron sus transacciones ni se intentó suplantar un paquete.

SomeIpPool conserva objetos por servicio/evento sin timestamp ni estado de
disponibilidad en el valor de caché. Su callback diferido tampoco añade fecha de
origen. CarControlExternalImpl expone cadenas, sin edad ni secuencia de muestra.
Leer repetidamente `getCanData` no demostraría que se hayan recibido datos nuevos.
Un timestamp de recepción local no permite fechar un valor antiguo obtenido de
la caché inicial. La política operativa posterior acepta expresamente esa
incertidumbre y usa la recepción como referencia de edad local.

Como contraste de solo lectura se consultó la clave `Car_batteryChangeStatus` y
el buffer existente de logcat para el tag CarControlExternalImpl. La clave devolvió
`0` sin timestamp; no hubo líneas del tag en la consulta. No demuestra el estado
actual de carga ni que el broker nunca emita datos. Evidencia privada:
`readonly-probe-20261016T101023Z/oem-broker-log-and-charge-cache.txt`.
No se cambiaron ajustes ni se iniciaron servicios, controles o callbacks.

## Consecuencia para el fork

Se mantiene Android Car como diagnóstico parcial. La integración posterior usa
la suscripción JNI verificada, sin depender de logs, ajustes persistidos ni
suplantación de paquetes. Véase el contrato runtime en
[telemetría](LEAPMOTOR_C11_TELEMETRY.md#integración-runtime-de-soc-y-autonomía).

Para completar la validación y ampliar los campos disponibles falta:

1. Probar el ciclo completo del servicio de DiPlay y la aceptación de su declaración
   parcial por el iPhone; la lectura desde la identidad del diagnóstico ya funciona.
2. Contrastar estados de carga activa, espera, desconexión y fallo, y su enumeración.
3. Comprobar en vivo desconexión y silencio con la política de recepción acordada;
   esa política no proporciona un timestamp de origen ni acredita Enforcing.
4. Identificar advertencia de autonomía, autonomía máxima y conectores de esta
   variante si se desea anunciarlos. Su ausencia no bloquea el backend parcial.

La primera [prueba de la APK de diagnóstico](LEAPMOTOR_C11_DIAGNOSTICS.md) usó
Android Car; las versiones 0.3–0.4 verificaron después la lectura JNI descrita
a continuación. Ninguna invoca este broker. La integración Ultra sigue teniendo las brechas de protocolo descritas
en [la investigación principal](LEAPMOTOR_C11.md), además de estos requisitos OEM.

## Lectura JNI verificada y política acordada

`/system/etc/public.libraries.txt` declara `libsomeip.leapmotor.so`. Su backend
observado en `lib.corecomm.so` utiliza sockets UNIX `/mnt/uds/<servicio>_<instancia>`.
La app de diagnóstico recibió los cinco eventos con su identidad real, sin
root ni suplantación, con SELinux ya en Permissive. No se verificó Enforcing.

El análisis nativo muestra que `UdsPublish::HandleReceivedMessage`
(0x21998–0x21a08) reproduce la caché al registrar un cliente usando PacketCmd 2,
también usado para datos ordinarios. `Packet::serialize` (0x3156c–0x31660)
no añade fecha de origen al payload. `ProcessQueue` puede suprimir valores sin
cambios. Por tanto, ni el primer callback ni el silencio posterior acreditan
por sí solos la edad de la medición.

La configuración del gateway incluye `entimestamp=1` en el tópico DDS
`/leap/ivi/public/p/lpGwIviCan`; no demuestra que ese timestamp atraviese el
camino activo hasta esta API. No se inició ningún cliente DDS ni emisor SOME/IP.

En 30 segundos se recibieron SOC 53,1 %, autonomía 227 (14 entregas) y estado de
carga/AC/DC 0 (una entrega cada uno), sin transiciones. El usuario contrastó la
autonomía con el coche y pidió asumir reciente el último dato. Se acepta esa
política para la integración: usar el timestamp local de recepción para la edad
y mantener separado el timestamp de origen desconocido. El backend parcial
posterior aplica esa política y descarta el estado al perder el servicio o
tras 60 segundos sin eventos; no equivale a una validación completa del estado EV.
Las enumeraciones de carga, autonomía máxima y advertencia siguen pendientes.
