# Botón programable del C11 y Portón de HomeKit

## Implementación actual

La versión de desarrollo 46 añade una suscripción pasiva al evento 4115 del servicio
4161, instancia 20545 y grupo 17920. Se mantiene separado del botón de Siri (4114).
El callback OEM inspeccionado interpreta un único byte ASCII `1` como clic simple y
ASCII `3` como doble clic; los bytes numéricos 1/3 y ASCII `2` no tienen esa interpretación.
La correspondencia procede del código OEM y se contrastó físicamente el 24/10/2026:
el propietario confirmó un clic simple y DiPlay registró `SINGLE`; después confirmó
un doble clic y DiPlay registró `DOUBLE`. Ambos fueron seguidos por una notificación
`UNRECOGNIZED`, a los 37 y 56 ms respectivamente. Su significado no se ha establecido;
no se interpreta como otra pulsación ni se ejecuta ninguna acción.

La versión 46 se compiló con las comprobaciones completas de Android y se instaló
en el coche. Al reabrir el host, el suscriptor recibió una notificación clasificada
como `UNRECOGNIZED`: confirma la entrega de ese canal al proceso de DiPlay, pero no
la correspondencia de un clic físico. La captura de esa sesión muestra la tarjeta
Portón con el texto «Se detectó una obstrucción»; no se pulsó la tarjeta.

DiPlay registra únicamente la clasificación en `DiPlay-C11-Custom` y en el informe
de diagnóstico existente. `observation=NONE` indica que no se recibió ninguna
notificación desde el inicio de la fuente; `UNRECOGNIZED` indica un payload no admitido.
El informe conserva la última observación de esa conexión y la borra al perder la
fuente. Una notificación puede ser un estado inicial o repetido: no demuestra por sí
sola una nueva pulsación física. No se usa para ejecutar acciones.

No cambia la asignación OEM, no consume el evento, no pulsa CarPlay y no abre el portón.
El clic simple sigue asignado por el propietario a descongelación delantera y el
doble clic a retrovisor.

## Lo que falta para activar Portón

1. Clasificación de clic simple/doble: contrastada con el propietario. Queda determinar
   el significado de la notificación posterior y establecer el rechazo de estados iniciales
   o repetidos antes de usar este canal para acciones.
2. Encontrar un mecanismo para evitar la acción OEM cuando DiPlay asuma el botón.
   Una suscripción SomeIP observa mensajes; no cancela lo que hace el consumidor OEM.
3. Obtener una señal de presencia e identidad de la tarjeta de HomeKit y una acción
   dirigida a esa tarjeta. La ruta CarPlay inspeccionada ofrece vídeo, entrada táctil,
   mando y selección de áreas de pantalla; no se identificó una acción semántica de
   HomeKit ni una notificación de visibilidad de la tarjeta.
4. Antes de accionar, validar conexión, contexto, pulsación nueva y que el destino
   sigue siendo la misma tarjeta; descartar eventos iniciales, repetidos o retrasados.

No se implementa un toque en coordenadas fijas ni una secuencia de navegación a ciegas:
la tarjeta sugerida cambia con el contexto. Tampoco se confunde este portón doméstico
con el portón del vehículo. No se ha añadido una aplicación al iPhone.

## Menú original

En el código inspeccionado, las opciones se enumeran en `wheelValueList` y se ejecutan
en `handleWheelEvent`; no se encontró un registro de acciones de aplicaciones externas.
Una opción en Ajustes de DiPlay no añadiría por sí misma una fila a ese menú OEM.

La huella SHA-256 del APK instalado se contrastó por ADB con la copia inspeccionada:
`31c83cb9468df8a69bfdc1e750e55b64cf4af0210dd3474c92af1179eb4a12dc`.
La tabla `assets/UnityTable/Button_331.csv` tiene texto vacío para estas entradas:

| ID de la fila | Nombre OEM | Función descrita en la tabla |
| --- | --- | --- |
| 331009 | ZCSS | Bloqueo/desbloqueo del vehículo |
| 331012 | AR | Navegación AR |
| 331013 | HUD | AR-HUD |

Por tanto, una etiqueta vacía no demuestra que la opción esté libre. AR y HUD ocupan
los índices 6 y 7 de `wheelValueList`, para los que `handleWheelEvent` no contiene una
acción Java. Esto no descarta un consumidor en Unity o QNX; tampoco identifica por
sí solo la posición visual de esas filas en el menú. No se cambió ninguna asignación
para probar esa hipótesis ni se usó SystemUpdate.

Las dos fotografías originales aportadas por el propietario muestran las mismas
opciones para una y dos pulsaciones, con varias filas sin etiqueta y una fila «K».
Esto confirma el problema de presentación, pero no permite asignar inequívocamente
cada fila visual a un ID de la tabla. No se consideran ranuras disponibles ni se
seleccionan para anular una acción. Las capturas ADB del menú solo mostraron el
fondo y las barras del sistema; la lectura por UIAutomator tampoco devolvió su
estructura. Ninguna de estas limitaciones demuestra que las filas no tengan función.

## Vía directa Refoss investigada

El propietario confirmó que Portón funciona tanto en Apple Casa como en la app
Refoss del iPhone. El anuncio HomeKit de la red local identifica un MSG100.
Una única consulta `GET` de `Appliance.System.All` por HTTP `/config`, sin clave,
recibió un `ERROR` correlacionado con código 5001. Esto demuestra respuesta del
protocolo, no acceso autenticado ni control. No se envió ningún `SET`.

El XAPK 1.17.1 proporcionado se inspeccionó sin instalarlo ni ejecutar código de
la aplicación. Las clases de autenticación muestran `/v1/Auth/signIn`, el envoltorio
firmado y una respuesta `ResultLogin` con la clave de cuenta. Se preparó
`tools/carplay/refoss_auth.py` para una autorización manual desde un formulario
que escucha solamente en `127.0.0.1` y vence a los 15 minutos. Envía el inicio de
sesión por HTTPS a una lista cerrada de servidores Refoss; no sigue redirecciones
HTTP, no registra credenciales y guarda únicamente la clave en un archivo privado
con permisos 0600. No envía comandos de movimiento y aún no está integrado en Android.

La autorización manual se completó y se contrastaron respuestas autenticadas de
`Appliance.System.All` y `Appliance.GarageDoor.State`. El dispositivo es MSG100 y
su identidad queda fijada en la configuración privada. Se validan correlación de
petición y respuesta, firma e identidad antes de cualquier solicitud de control.

El propietario confirmó que el contacto informa abierto aunque el portón está
cerrado, y que suele informar mal. Por tanto, este dato no se utiliza como posición
física confirmada. El objetivo solicitado es una activación como pulsador, no una
decisión automática de abrir/cerrar según ese contacto. El protocolo inspeccionado
expone un estado solicitado; `RefossLocal.pulse_once()` pide el opuesto del contacto
reportado para solicitar una activación por la vía normal del dispositivo. No es
una API de relé crudo: un ACK tampoco demuestra movimiento. No se modifican
sensores, tiempos de cierre ni protecciones del abridor.

`tools/carplay/refoss_local.py` implementa el cliente local. La prueba temporal
`.private/c11/refoss-20261024/wheel-gate-once.py` usa la Mac para escuchar un solo
doble clic nuevo registrado por DiPlay. Descarta el registro inicial, eventos
caducados, procesos cambiados y repeticiones. Consume el evento antes de la
petición; no reintenta una orden aunque su ACK se pierda. Solo se permite activar
el dispositivo previamente identificado y autenticado. La captura vence en 90 s.
Esta prueba aún no se ejecutó: ADB quedó offline antes de armarla. No se envió
ningún comando de movimiento.

Pasaron 105 pruebas Python de las herramientas, incluidas 15 de autorización,
cliente y captura única, y las comprobaciones Android completas. La conexión
local autenticada funciona en la Mac. La activación física, la asignación OEM sin
efecto secundario y el traslado de esta integración a Android siguen pendientes.
El APK instalado sigue siendo el 46, que observa el botón y no controla Portón.
Los APK, firmas de cliente, claves y capturas permanecen fuera del árbol público.

Al no recuperarse ADB, se preparó también `tools/carplay/refoss_gate_test.py`:
un formulario temporal de la Mac con un botón explícito «Activar Portón una vez».
No ejecuta ninguna acción al abrir la página. Un envío válido consume la única
activación de esa instancia antes de contactar al dispositivo, incluso si se pierde
el ACK. El formulario vence a los diez minutos, está limitado a loopback y utiliza
la protección de origen/token del flujo de autorización. Es una prueba manual del
actuador; no constituye integración del volante ni del APK. Pasaron 106 pruebas
Python y las comprobaciones Android completas tras añadirla. Queda pendiente
que el propietario pulse el botón y confirme el movimiento físico. Esta era la
situación previa a la prueba confirmada que se describe a continuación.

## Estado del 25/10/2026: candidato Android 47

El propietario activó el formulario manual, confirmó que Portón abrió y después
lo cerró por su cuenta. La prueba se consumió y no quedó un servidor de activación
armado. También se verificaron lecturas autenticadas desde el coche hacia Refoss
por la red local, sin enviar órdenes de movimiento. La coexistencia de esa ruta
con el hotspot de CarPlay todavía necesita una prueba física.

La versión 47 (`0.2.20-c11.20261025.1`) incorpora el cliente local Android y el
indicador «Portón · Doble clic». La integración está desactivada salvo que exista
una configuración privada con dirección local, identidad y clave del accesorio;
la clave no se incluye en el APK. El indicador aparece únicamente cuando una
comprobación autenticada reciente informa que el accesorio es accesible.

El guardia del botón acepta únicamente un doble clic nuevo, después del estado
inactivo esperado, y permite una sola petición sin reintento. Conserva la función
original de un clic. La pantalla de ajuste de retrovisores puede aparecer con la
asignación OEM actual: no se ha eliminado esa función. El retorno opcional a
CarPlay exige identificar esa pantalla; no debe recuperar foco sobre la cámara ni
sobre SystemUpdate. El byte inactivo real y este recorrido completo siguen sin
validarse con el candidato instalado.

Pasaron las comprobaciones Android completas: 3745 pruebas, sin fallos ni errores
y 46 omitidas, además de lint y las compilaciones. Las herramientas Python pasaron
106 pruebas. El candidato también incluye controles manuales de zoom del cuadro.
Tras la autorización posterior del propietario, la versión 47 se instaló por ADB
en el usuario 10 y se verificaron su código y nombre de versión. La configuración
Refoss se guardó en `no_backup` con permisos 600 e integridad verificada, sin
activar Portón ni usar la pantalla o SystemUpdate. La prueba física del botón
nativo sigue pendiente; la configuración se carga al reanudar el host de CarPlay.

La evidencia estática privada está referenciada en
`.private/c11/ultra-20261022/configurable-wheel-source-findings-20261024.json`.
El código OEM y las capturas privadas no se distribuyen con este documento.

## Recuperación y disponibilidad: revisión del 27/10/2026

El propietario informó fallos repetidos después del reinicio y que la función no
respondió en la prueba del día anterior. La instalación y una lectura desde la
Mac o desde el shell del coche no prueban el funcionamiento del cliente Android.
No se considera una integración físicamente estable.

La revisión encontró que la versión 47 usa `Network.openConnection` con HTTP
local, mientras el manifiesto final mantiene la política predeterminada que
rechaza tráfico HTTP sin TLS para este nivel objetivo de Android. Los errores se
descartaban y ocultaban la tarjeta. La política está documentada por
[Android](https://developer.android.com/privacy-and-security/security-config#CleartextTrafficPermitted).
Sin registros del intento físico fallido no se puede afirmar que esa fuera su
única causa.

El candidato 48 sustituye ese transporte por un socket de la red Wi-Fi local
seleccionada, dirigido exclusivamente al IPv4 privado configurado y al puerto 80,
con HTTP acotado y las mismas verificaciones de firma e identidad del accesorio.
No modifica la política HTTP global de la aplicación. No sigue redirecciones ni
reintenta órdenes. La dirección configurada sigue siendo fija; no implementa
descubrimiento después de un cambio de dirección del accesorio.

Una integración configurada conserva su bloque en la barra y distingue espera de
CarPlay, comprobación, espera de inicialización del botón, ausencia de ruta Wi-Fi,
accesorio sin respuesta verificada y envío en curso. La disponibilidad se vuelve
a comprobar automáticamente mientras el host está visible y conectado. La
recuperación solo hace lecturas: nunca ejecuta un gesto anterior. El registro
privado `no_backup/c11-gate-diagnostics.txt` conserva hasta 32 cambios de estado,
con hora y etiquetas fijas, sin claves, direcciones, identificadores ni respuestas.

Siguen siendo necesarias una ruta local al accesorio y una sesión visible de
CarPlay. La coexistencia del hotspot y esa ruta, la señal inactiva del botón y el
recorrido físico completo todavía necesitan validación en el coche.

El candidato 48 pasó las comprobaciones Android completas: 3750 pruebas, sin
fallos ni errores y 46 omitidas, lint y las compilaciones requeridas. Una primera
ejecución falló en `SettingsBackupServerTest.aWrongCodeSaysHowManyTriesAreLeft`;
la repetición completa sin cambios de código pasó. Las pruebas nuevas cubren
el transporte HTTP local acotado, la recuperación después de un fallo sin emitir
SET y la permanencia del bloque de Portón en los distintos estados. Se revisaron
capturas compactas, amplias y RTL con letra grande. La clave privada no está en
el APK. El candidato combinado con la recuperación del cuadro pasó posteriormente
3753 pruebas (46 omitidas), lint y compilaciones. Tras la autorización del
propietario, ADB confirmó su instalación y la versión 48 en el usuario 10. La
configuración de Portón conservó permisos e integridad. No se enviaron órdenes de
activación; el funcionamiento físico sigue pendiente de validar.
