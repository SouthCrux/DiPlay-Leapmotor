# APK de diagnóstico C11

Paquete independiente: `com.southcrux.c11diagnostics`, versión 0.4. Se construye
con firma debug propia; no sustituye Calendar, Gallery o DiPlay ni utiliza una
firma OEM. Prepararla y compilarla no autoriza instalarla en el coche.

## Qué comprueba

La pantalla inicial no inicia consultas ni solicita permisos. «Consultar ahora»
genera un informe JSON con:

- SDK Android, identidad de esta app y presencia de la feature automotive.
- Estado de permisos de energía, velocidad, climatización y broker OEM. El informe
  distingue los permisos declarados por esta app: únicamente solicita CAR_ENERGY.
- Pantallas lógicas visibles para esta app: ID, resolución del modo, frecuencia,
  flags, estado, rotación y pertenencia a la categoría presentation. No presume
  que los IDs sean permanentes ni asigna roles por posición en la lista.
- Resultado de conexión y lectura puntual de las cuatro propiedades EV del
  [lector de diagnóstico](LEAPMOTOR_C11_TELEMETRY.md), con estado y timestamp de
  origen. Autonomía se informa en metros, energía en Wh y puerto como booleano.

No recoge VIN, número de serie, ubicación, cuentas, nombres de pantallas ni
identificadores de red. La consulta Android Car no guarda archivos. El sondeo
OEM opcional guarda su último informe en el almacenamiento privado de esta app;
no envía informes automáticamente.
El texto es seleccionable y «Compartir informe» abre el selector de Android solo
tras pulsarlo. El usuario elige el destino; compartir un informe no es automático.

## Conexión y límites

Usa la API pública `Car.createCar(Context, Handler, CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
CarServiceLifecycleListener)`, presente tanto en el SDK oficial como en el JAR
extraído del C11. Solo tras un callback `ready=true` obtiene
`Car.getCarManager(Car.PROPERTY_SERVICE)` y lee las propiedades en un worker.
La biblioteca `android.car` del SDK se utiliza para compilar y se declara opcional
en el manifest; no se empaqueta el JAR del SDK ni el extraído del coche.
La app no accede a `ICar`, constructores internos, ServiceManager ni transacciones
Binder numéricas. No añade exenciones non-SDK ni modifica políticas de Android.
El adaptador de propiedades sigue usando reflexión sobre getters públicos.

A diferencia de la versión 0.1, esta API pública gestiona su propio binding.
En el firmware inspeccionado usa AUTO_CREATE y puede reintentar el enlace antes
de `disconnect()`. Por tanto puede crear el servicio si no está ejecutándose.
La app no llama directamente a startService ni implementa reintentos. Cada
consulta abre una conexión y la cierra al terminar, al perder el servicio,
al caducar o al abandonar la pantalla. No se solicita otra consulta al reconectar
el servicio; hace falta volver a pulsar «Consultar ahora».

Referencias: [Car](https://developer.android.com/reference/android/car/Car) y
[CarServiceLifecycleListener](https://developer.android.com/reference/android/car/Car.CarServiceLifecycleListener).

Las lecturas se ejecutan en un worker; la conexión espera hasta 5 s y la lectura
hasta 8 s para mostrar un timeout. Binder no garantiza cancelar una llamada ya
bloqueada: no se encolan nuevas consultas mientras ese worker siga ocupado.
Al abandonar la Activity, perder el servicio o caducar el intento se invalidan
los resultados pendientes y se solicita `disconnect()` en un worker de limpieza:
el bloqueo interno de Car durante una lectura no debe bloquear el hilo de UI.
Se ignoran callbacks tardíos o de intentos anteriores, incluidos los callbacks
síncronos de createCar. En esta consulta no hay suscripciones de propiedades, sondeo periódico,
servicio de fondo de la app ni controles del vehículo.

El botón separado «Autorizar lectura de energía» solicita CAR_ENERGY mediante el
diálogo normal de Android, solo tras pulsarlo. Es opcional y modifica únicamente
el permiso de esta app con consentimiento del usuario. No concede permisos por
ADB, no solicita privilegios de climatización ni usa el broker OEM. Tras responder
hay que generar una muestra nueva; no se reutiliza el informe anterior.

El módulo incorpora mediante una tarea Sync únicamente los dos archivos fuente
del lector EV y su adaptador de reflexión. No depende de shared/common como
bibliotecas: no incluye los conectores BYD, servicios CarPlay, credenciales,
bibliotecas nativas o receptores de arranque de DiPlay.

## Compilación y revisión local

```sh
./gradlew :c11diagnostics:testDebugUnitTest :c11diagnostics:lintDebug :c11diagnostics:assembleDebug
```

APK: `tools/c11diagnostics/build/outputs/apk/debug/c11diagnostics-debug.apk`.
El workflow Android ejecuta esos tres targets además de los checks existentes.
Las pruebas comprueban callback síncrono, denegación, timeout, callback tardío,
desconexión, cierre sin bloquear UI y ausencia de consultas o permisos automáticos
al abrir la pantalla.
Las validaciones de valores/frescura del lector siguen cubiertas en shared.

La versión 0.1 pasó los checks completos: 2.991 pruebas, cero fallos/errores y una
omitida. SHA-256 histórico:
`22cc9d67ced09b24e69027b541587a47029b513bb7e936056fc084a3a7aff099`.

## Primera prueba real: 2026-10-16

Se autorizó e instaló la versión 0.1 en el usuario activo 10. La instalación
ordinaria fue rechazada por el filtro OEM de paquetes, después de pasar la
comprobación de integridad. El análisis local de services.jar identificó la
excepción `installReason == 64` en LeapApplicationInfoManager.filterPackage;
la instalación autorizada con ese parámetro tuvo éxito. No se cambió la lista
de paquetes admitidos ni la identidad o firma de la app.

Se generaron informes antes y después de conceder CAR_ENERGY mediante el diálogo
normal de Android. El permiso pasó a concedido, pero ambos informes mostraron
`API_NOT_AVAILABLE_OR_BLOCKED`. Logcat confirmó el bloqueo por reflexión de
`android.car.ICar$Stub.asInterface`. Esa es la causa del cambio a la API pública
en la versión 0.2; conceder el permiso no resuelve un acceso non-SDK.

La app vio displays lógicos 0 (2560×1440), 2 y 3 (1920×720), y 4 (1920×1080).
Solo el 4 figuraba en la categoría presentation. Esto no demuestra que la app
pueda dibujar en ellos ni identifica por sí solo su función física.
Los informes originales y el análisis permanecen en `.private/c11/`.

## Versión 0.2: API pública verificada en el coche

La actualización autorizada conserva paquete, firma y permiso CAR_ENERGY. Dos
consultas consecutivas, cada una con una conexión nueva, dieron
`CONNECTED_PUBLIC_API`. El informe muestra la versión instalada, no una cadena
de versión fija. Los resultados de ambas muestras fueron:

| Propiedad | Resultado observado |
| --- | --- |
| Energía actual | NOT_EXPOSED |
| Capacidad nominal | NOT_EXPOSED |
| Autonomía | STALE, timestamp de origen 0; valor descartado |
| Puerto de carga conectado | NOT_EXPOSED |

Esto verifica acceso a Android Car y a su gestor de propiedades desde nuestra
app ordinaria, pero no una fuente EV utilizable. La configuración de autonomía
se recibe y su lectura llega al adaptador; el timestamp 0 impide tratar el valor
como una muestra actual. No se interpreta como autonomía cero, ni se infiere SOC
o carga. Los demás IDs no están expuestos a esta app en esas consultas.
El proveedor EV de DiPlay sigue desconectado.

Logcat del nuevo proceso no mostró el bloqueo de ICar observado con la versión
0.1. Al acabar se cerró la pantalla; la APK permanece instalada y CAR_ENERGY
concedido. Informes privados: `.private/c11/public-api-probe-20261016/`.

Verificación local: línea completa de CI (incluidos tests, lint y assemble del
diagnóstico) con BUILD SUCCESSFUL; 2.993 pruebas, cero fallos/errores y una omitida.
Las seis pruebas del diagnóstico pasan. Lint del módulo: cero errores y cuatro
advertencias anteriores ajenas a APIs ocultas. `check_public_tree.py` y
`git diff --check` pasan. Se verificaron firma debug, paquete/versión, único permiso
CAR_ENERGY y ausencia de JARs, bibliotecas nativas, assets, clases de plataforma/OEM
y conectores BYD empaquetados.

SHA-256 del APK 0.2 probado:
`f71a7fa6273e93f9ec2f9f599066e1efeba4546e6cb9fba2d16af6aa3d27ae5e`.

## Versiones 0.3–0.4: lectura OEM en proceso separado

La biblioteca `libsomeip.leapmotor.so` está declarada pública en el firmware.
El diagnóstico declara su uso opcional y contiene únicamente declaraciones Java
para su ABI JNI; no empaqueta bibliotecas ni código compilado OEM. Conserva su
paquete, UID y firma propios. Su transporte observado es un socket UNIX local.
El acceso se comprobó con SELinux en Permissive; no se modificó esa política ni
se comprobó el funcionamiento en Enforcing.

Abrir la pantalla OEM no inicia la escucha. Dos botones permiten una ventana de
5 o 30 segundos para cinco eventos fijos de SOC, autonomía y carga. El informe
registra primera y última entrega, número de entregas y cambios de valor, con
hasta 16 transiciones recientes por señal. Cada entrega lleva tiempo monotónico
de recepción; el timestamp de origen permanece nulo porque la API no lo entrega.

El `stop()` nativo abortó por uso de JNIEnv desde otro hilo. Por eso la escucha
se aísla en `:oemprobe`: primero congela y guarda el informe, después termina
exclusivamente ese proceso para liberar sus conexiones. Un watchdog limita la
duración si el código nativo se bloquea. No hay publicación de eventos ni setters.

Prueba de 30 segundos, versión 0.4, 2026-10-16:

| Señal | Último valor | Entregas | Cambios durante la ventana |
| --- | --- | --- | --- |
| SOC mostrado | 53,1 % | 1 | 0 |
| Autonomía | 227 km | 14 | 0 |
| Estado de carga | 0, enumeración pendiente | 1 | 0 |
| Conexión AC | 0, enumeración pendiente | 1 | 0 |
| Conexión DC | 0, enumeración pendiente | 1 | 0 |

El usuario confirmó que la autonomía coincide con el valor real del coche y
autorizó asumir reciente el último dato recibido. La política acordada para la
integración es usar el tiempo de recepción como referencia de edad local. Es una
suposición operativa explícita, no un timestamp de medición proporcionado por el
OEM. El informe bruto conserva `originFreshness=UNVERIFIED`: describe la evidencia
del protocolo, no impide aplicar la política acordada. El SOC del sondeo anterior
era 53,2 %. No se ha contrastado todavía la carga durante una sesión real.

El proceso de escucha ya había terminado al comprobarlo tras el sondeo. Evidencia
privada: `.private/c11/oem-source-probe-20261016/report-v04-30s.json`.
En el momento de esta prueba el proveedor EV de DiPlay seguía desconectado.
La implementación posterior añadió un backend parcial con ciclo de vida propio;
véase [telemetría](LEAPMOTOR_C11_TELEMETRY.md#integración-runtime-de-soc-y-autonomía).

Verificación 0.4: CI completa, incluidos los tres targets de diagnóstico,
BUILD SUCCESSFUL; 3.000 pruebas, cero fallos/errores y una omitida. El diagnóstico
tiene 13 pruebas. Lint del módulo: cero errores y cuatro advertencias anteriores.
`scripts/check_public_tree.py` y `git diff --check` pasan. Firma debug verificada,
sin bibliotecas nativas, assets o JARs empaquetados.

SHA-256 del APK 0.4 probado:
`c42dc9f91d511183e2de3110eda6fb18c76ed9b384dfafc33766cd06b3ccdc75`.
