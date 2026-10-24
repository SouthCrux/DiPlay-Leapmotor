# Revisión independiente xhigh: climatización C11 / DiPlay Ultra

Fecha: 2026-10-20. Destinatario: instancia de razonamiento xhigh con acceso al repositorio o a este documento. «ACC» en esta solicitud significa aire acondicionado, no control de crucero adaptativo.

## Resultado que buscamos

Desde CarPlay, leer el estado real y permitir que Siri cambie una temperatura o encienda el aire, confirmando el efecto en el coche. Hoy **no existe un recorrido de escritura funcional validado**. Que el iPhone diga Ultra, que llegue un paquete o que un panel cambie de manera optimista no demuestra actuación física.

El usuario autoriza trabajo de desarrollo y pruebas del coche, excluyendo archivos personales y contraseñas. Esta revisión es local, sin cambios de firmware, identidades OEM, permisos ni actuadores. Propón experimentos concretos antes de ejecutarlos. La autorización del propietario y la admisión por el firmware son condiciones diferentes.

## Evidencia disponible sin depender de una conversación previa

### 1. CarProperty estándar: configuración plausible, lectura inutilizable

Sonda física con UID real 2000 (`com.android.shell`), permiso `CONTROL_CAR_CLIMATE` concedido antes y después. No se fingió una identidad OEM. Propiedad `358614275` (`HVAC_TEMPERATURE_SET`): Float, acceso 3 (RW), modo 1 (ON_CHANGE), áreas 49 y 68, rango 16–32; `configArray` vacío, paso desconocido.

Para las dos áreas solicitadas devolvió propiedad correcta, **área 0, Float 16.0, estado 0 y timestamp 0**. No coincide con el área pedida ni aporta una observación fechada. Ningún setter se ejecutó. La app DiPlay de usuario carece de `CONTROL_CAR_CLIMATE`, que es signature/privileged; shell sí lo tiene y aun así obtiene estas lecturas.

Conclusión limitada: este GET no proporciona confirmación de temperatura por zona. **No demuestra por sí solo que SET sea inoperante**. El controlador actual exige una lectura válida y paso verificado antes de escribir; ese requisito es una decisión de ingeniería, no una limitación del firmware ya demostrada. Examina si un transporte admitido con confirmación SomeIP independiente permitiría evaluar una alternativa sin confundir caché local con efecto real.

Evidencia: `.private/c11/ultra-20261020/climate-shell-read-physical.json`.

### 2. SomeIP pasivo y consumidor OEM

Canal: servicio `0x9900` (39168), instancia `0x9901` (39169), grupo configurado `0x9903` (39171). El callback nativo no entrega el grupo por paquete; la agrupación procede de la configuración del suscriptor.

| Dato | Evento / formato | Evidencia y límite |
| --- | --- | --- |
| Consigna izquierda física | 11205, double LE, 8 bytes | Consumidor OEM 18–32 °C; recepción física izquierda aún por correlacionar |
| Consigna derecha física | 11206, double LE, 8 bytes | Se observó 20.0 °C; no establece paso ni equivalencia conductor/acompañante |
| Ventilador | 1008, int32 LE, 4 bytes | Consumidor admite 0–7; decoder/cache nuevos, recepción física pendiente |
| Temperatura interior medida | 1221, double LE, 8 bytes | Raw 25.0 y posteriormente 27.5; no sustituir consigna |
| Temperatura exterior medida | 1016, double LE, 8 bytes | Raw 14.5; grados en consumidor de voz, unidad Celsius y estados inválidos finitos todavía no demostrados |
| Encendido | 1001 | OEM trata 1 como encendido; dominio completo todavía no caracterizado |
| Distribución | 1011 | OEM guarda raw+1; outlets completos desconocidos |
| Desempañadores | 1012 / 1013 | Semántica parcial, sin enum completo confirmado |

Temperaturas medidas permanecen `OBSERVED_UNVERIFIED`; una lectura plausible no confirma unidad, salud del sensor ni alcance físico. Una antigua limitación de logs omitía campos posteriores recibidos dentro de un segundo; se corrigió con emisión final diferida. Por eso una ausencia histórica de logs no prueba que el coche no publicara el evento.

Las caducidades de 5 segundos para CarProperty y 60 segundos de silencio en cachés SomeIP son políticas nuestras. ON_CHANGE puede conservar un valor estable durante más tiempo. Hay que distinguir valor observado antiguo, estado actual confirmado y nueva confirmación posterior a un comando; no refrescar un timestamp al reproducir una caché.

### 3. Escrituras OEM: IDs correctos y ruta incompleta

Contratos estáticos capturados: **1409 izquierda / 1410 derecha** para temperatura, **1402** encendido, **1407** ventilador. Los IDs 110x citados en investigaciones anteriores pertenecen a otras funciones; no utilizarlos para climatización.

AirManager recibe enteros 18–32 y multiplica por 2 antes de llamar a syncMainTemp/syncViceTemp. AirControlManager divide ese valor UI por 2 y manda Celsius en texto decimal mediante `MainService.sendAudioEventToQnx`, 1409/1410. No deducir que el circuito admite medios grados. Escritura de ventilador permite 1–7, mientras lectura 1008 admite 0–7: **escribir 0 no está probado como apagado**.

El manager actualiza parte del estado local antes de enviar. Su callback local no acredita que el coche aplicó nada. No tenemos una petición física, ACK aplicado o readback independiente de escritura que cierre este recorrido.

### 4. Admisión de clientes y rutas revisadas

* `CarControlImpl.onTransact`: permiso **normal** `com.leapmotor.carcontrol.CAR_CONTROL_SERVICE`, y luego comprobación real de `getPackagesForUid`: prefijo `com.leapmotor` o `com.android.systemui`. Conceder el permiso normal no elimina el filtro de paquetes. Shell y el DiPlay actual no cumplen el filtro.
* `CarControlExternalImpl`: lista explícita diferente; menciona `com.leapmotor.autosdk.server`. Un `pm path --user 10` sin ruta no demuestra ausencia en todos los usuarios/firmwares. No se capturó contrato utilizable de ese broker.
* `CustomSceneService`: servicio exportado y Binder real, pero `onTransact` exige que el primer paquete del UID sea exactamente `com.leapmotor.scene` antes del dispatch. `handlerTask` no es un endpoint abierto.
* CarShellDumpsysProvider sólo selecciona tres dumps hardcodeados de ventanas/actividades/SurfaceFlinger. No es ejecución arbitraria de comandos HVAC.
* Broadcast `strcontrol` lleva a STR 7009, no climatización. `AiSpeechService.onBind` es null, extras de arranque sin comando HVAC; receptor SPEECH gestiona TTS/micrófono; CustomSceneReceiver actualiza vocabulario/configuración.
* Wrappers SomeIP capturados muestran suscripción, oferta de servicio y publicación/notify. No se identificó todavía el contrato de un cliente de peticiones independiente. Evitar colisión con una oferta OEM es nuestro alcance de ingeniería; no demuestra imposibilidad universal del protocolo.

No se afirma haber agotado todos los endpoints del firmware. Se necesitan rutas admitidas y esquemas reales, no sólo nombres sugerentes.

## Componentes nuestros y límites

En `shared/src/main/java/com/shilapi/xcertplay/vehicle/leapmotor/`:

* `LeapmotorClimateController.kt`: identidad exacta de propiedad/área, Float finito, rango, estado, timestamp y antigüedad; `enableCommands=false` por defecto. Requiere paso verificado para writable. Un envío queda `SUBMITTED_UNCONFIRMED`; sólo una observación nueva posterior y coincidente puede confirmarlo.
* `LeapmotorClimateBackendFactory.kt`: inyección de manager ya autorizado, comprobación de permiso, capacidad y lectura; **sin broker de producción registrado**.
* `ReflectiveLeapmotorClimateAccess.kt`, `LeapmotorClimateShellProbe.kt`, `LeapmotorClimateShellReadMain.kt`: acceso/sonda acotados, sin setter en la sonda física usada.
* `LeapmotorSomeIpClimateDecoder.kt`, `LeapmotorSomeIpFan.kt`: lectura pasiva con epoch y retiro por dueño; no transportes de control.

En Apple se ha identificado localmente servicio térmico `0x11000002`, accesorio `0x01000001`, target `0x31000017`, current `0x31000019`, Float9/unidad Celsius 1082. Eso no demuestra todavía la aceptación de Siri ni el servicio completo encendido/ventilador. Un sensor global de cabina no debe duplicarse como dos sensores de zona.

## Preguntas para la instancia xhigh

1. ¿Estamos descartando indebidamente SET porque GET es placeholder? ¿Qué lectura independiente y correlación posterior permitirían comprobarlo sin falso éxito ni reiterar la misma sonda?
2. ¿Qué ruta OEM real falta por inspeccionar? Especifica archivo, interfaz, usuario, permiso, UID y esquema que deben verificarse; distingue hipótesis de hallazgo.
3. ¿Existe evidencia suficiente para un cliente SomeIP de peticiones, o sólo para suscripción/notify? ¿Qué método o captura demostraría el contrato sin ofrecer el servicio OEM?
4. ¿Cómo demostrar paso, zona física y causalidad de un cambio, evitando los caches optimistas y callbacks repetidos? ¿Hay que cambiar nuestra política de caducidad ON_CHANGE?
5. ¿Qué contrato adicional pide Siri para encender A/C, separado de cambiar consigna? Identifica el primer hito mínimo útil de extremo a extremo.

Entrega esperada: tabla de hallazgos con evidencia, conclusiones que exceden la evidencia, hasta tres experimentos ordenados por ganancia de información, condiciones de parada y observación que falsaría cada hipótesis. No modificar código ni acceder al coche en esta revisión. No recomendar repetir el mismo GET sin una condición nueva que cambie su resultado esperado.

## Índice para inspección local

* [Estado del módulo HVAC](ULTRA_HVAC_MODULE_STATUS.md): contratos, líneas de consumidores y pasos de cableado.
* [Temperaturas medidas](ULTRA_AMBIENT_TEMPERATURES.md).
* `.private/c11/ultra-20261020/hvac-module-review/AirControlManager-fallback.java`.
* `.private/c11/ultra-20261020/AirManager-climate-readonly-fallback.java`.
* `.private/c11/ultra-20261020/climate-exported-route-review.json` y `climate-write-route-audit.json` (histórico; contrastar IDs con la corrección de este documento).
* `.private/c11/ultra-20261020/CustomSceneService-caller-guard-review.java`.

Los archivos privados contienen evidencia de desarrollo y no se requieren para entender los resultados resumidos arriba. No incluir APKs OEM ni registros sin filtrar en un paquete para otra instancia.

## Prompt para reutilizar

> Revisa este documento con razonamiento xhigh como investigador independiente. Buscamos control real HVAC desde Siri en DiPlay/C11. Cuestiona tanto inferencias técnicas como restricciones que sean sólo decisiones nuestras. Separa hechos, hipótesis y experimentos; exige admisión del transporte y confirmación física independiente antes de declarar éxito. No implementes ni ejecutes comandos en el coche. Devuelve evidencia, máximo tres experimentos falsables y el primer hito útil de extremo a extremo.

## Revisión xhigh realizada

La [revisión independiente](ULTRA_HVAC_XHIGH_FINDINGS.md) ya está disponible.
Aporta una cadena estática adicional: `sendAudioEventToQnx` encola JSON, el consumidor
construye `CarControlEvent` con `appName="1"` y un evento/valor y publica en **15001**;
el publicador OEM usa **0x7101/0x7102/grupo0x7100**. Los SDK IDs1409/1410 van dentro
del mensaje, no son métodos SomeIP. El esquema exterior completo y la admisión de
un emisor siguen sin verificarse; no se emitió ningún paquete.

La revisión propone primero calibrar lados/paso mediante control OEM y observador
independiente, después discriminar un SET VHAL acotado del GET inválido, y revisar
`QnxUnityTransImpl`/BinderPool5 como hipótesis pendiente. Ninguno de esos experimentos
se ejecutó. Para detalle y fuentes, usar el informe de hallazgos.
