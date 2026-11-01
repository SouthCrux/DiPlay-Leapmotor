# C11: pantalla negra, bloqueos y fallback a CarPlay común

## Retorno de cámara 360: candidata45, 2026-10-24

El propietario vuelve a confirmar: cámara360 toma el primer plano, DiPlay pierde
foco y al regresar queda negro. Los ocho logs de sesión se copiaron con la app
cerrada, sin usar SystemUpdate. `diplay.log:332` registra a las13:49:52.817
`Ultra reference failed reason=FIRST_FRAME_TIMEOUT; rebuilding ordinary CarPlay`.
Esto acredita el motivo del fallback, no la causa completa de la falta de imagen.
La captura de logcat disponible no contenía eventos filtrables posteriores.

Se identificó una brecha de progreso en `UltraVideoPipeline`: al reactivar el host
se solicita un redibujado, pero los polls siguientes solo despertaban composición
cuando generaban mensajes salientes. Si el primer intento encuentra una escena
todavía no disponible y no llega otro frame o mensaje, no hay un nuevo intento.
Las pruebas anteriores de escena estática pedían el segundo redibujado de forma
explícita y no cubrían esa ausencia de mensajes.

La candidata45 añade `UltraOutputRecovery`: los polls existentes solicitan
redibujado mientras el host tenga superficie visible y falte una publicación
Canvas aceptada en esa activación. Reutiliza la última imagen y su timestamp;
no añade temporizadores, no conserva superficies propias, no cambia foco,
configuración de red ni negociación. Una aceptación detiene estos reintentos;
pausa o superficie ausente los suspende. Se mantienen los tokens de activación
y el watchdog que acota fallos persistentes. La prueba con el trabajador real
reproduce pausa, primer dibujo no disponible y recuperación sin un segundo frame
ni mensajes salientes. Falta verificarlo físicamente con cámara360: no se declara
resuelta la regresión del coche solo con esta corrección local.

Evidencia privada: `closed-session-logs-20261024T173350Z/manifest.json` y
`session-markers.jsonl`, dentro de `.private/c11/ultra-20261022/`.

## Prueba posterior: recuperación insuficiente, 2026-10-22

La APK completa `c469c29a` se instaló después por autorización del propietario,
conservando datos y verificando el hash instalado. El propietario informa mayor
velocidad y batería del coche visible, pero el bloqueo volvió. La captura registra
Canvas aceptado, pérdida de disponibilidad, otra publicación aceptada y una nueva
pérdida seguida de `FIRST_FRAME_TIMEOUT`. La corrección de imagen retenida no
resuelve todavía la recuperación física de Ultra.

El propietario también percibe que se cierra el AP. El registro disponible muestra
asociaciones/desasociaciones entre las interfaces de las dos bandas, pero no
`AP-DISABLED` ni el timeout de instancia previamente observado. Esos eventos no
identifican qué cliente cambió. La inspección puntual muestra SoftAP `StartedState`,
apagado general por inactividad desactivado y la opción guardada de apagado
oportunista de banda también desactivada. Esto no descarta un corte intermitente.
No se cambió la configuración de red ni se reinició el AP. Evidencia filtrada:
`.private/c11/ultra-20261022/static-recovery-owner-failure-summary.json`.

## Recuperación de una imagen estática, 2026-10-22

La prueba física de la APK completa `02ad2563` confirmó conexión inalámbrica,
pero al abrir DiPlay y regresar a la proyección registró `FIRST_FRAME_TIMEOUT`.
El fallback reconectó con UI Sync desactivado y mostró CarPlay convencional.
Eso no valida Ultra ni el panel Vehículo. La captura filtrada y la secuencia
están en `.private/c11/ultra-20261020/vehicle-panel-physical-check-20261022.json`.

Se encontró una dependencia local que puede producir ese resultado: el trabajador
de composición sólo despertaba al recibir una imagen nueva del decodificador.
Una reactivación o una respuesta UI Sync podía dejar una escena sin publicar
hasta que el iPhone enviara otro fotograma. El registro físico identifica el
timeout, pero no demuestra por sí solo que ésta sea su única causa.

La corrección local conserva una referencia a la última imagen decodificada y
vuelve a componer al reactivarse el host o avanzar el protocolo. Las entregas
usan tokens nuevos y el timestamp original del teléfono; no se inventa tiempo
ni se copia otra vez el bitmap. Se conservan la invalidación de entrada al
pausar, los tokens de activación, el plazo de primer Canvas y el rechazo de
publicaciones antiguas. Las peticiones de redibujo se agrupan y se ejecutan en
el trabajador de composición. Los píxeles se liberan al cerrar su última
referencia, después de terminar cualquier composición en curso.

Dos regresiones con JNI real verifican la recuperación sin otra imagen del
decodificador: tras pausa y flush de la entrada nativa, y después de cancelar
la primera oferta porque la escena aún no estaba disponible. Otras pruebas
cubren la liberación de referencias, errores y orden de timestamps. El informe
local se guarda en `.private/c11/ultra-20261022/`. Esta iteración no accede al
coche, por instrucción del propietario mientras otra sesión usa SystemUpdate.
La corrección necesita aún repetir la secuencia física de salir y volver.

Validación local completada: CI completo más diagnóstico y compilación standalone,
3.625 pruebas JUnit, cero fallos/errores y 46 omisiones por sus condiciones de
entorno. La ejecución específica con JNI real pasó nueve pruebas sin omisiones,
incluidas las dos de recuperación. APK completa preparada, **sin instalar**:
`DiPlay-Ultra-static-frame-recovery-complete-dev-20261022.apk`, SHA-256
`c469c29a3f5423e7e31eb89220e062aae5fa67c94fbc614ebc4cbc4fd1c2deb9`.
Firma, autenticación local y coincidencia de recursos/biblioteca Ultra comprobadas.
Informe: `.private/c11/ultra-20261022/static-recovery-verification.json`.

## Estado actual, 2026-10-21

Actualización de instalación: el propietario autorizó después explícitamente
borrar la instalación anterior e instalar la candidata nueva. Se completó la
reinstalación limpia de `14450bee`, con hash del APK instalado verificado, sin
restaurar configuración ni ruta del cuadro. La app quedó cerrada. La validación
física de cámaras/foco y reconexión sigue pendiente. Esta actualización sustituye
la restricción histórica de instalación descrita a continuación.

La prioridad es estabilidad. El propietario confirma pérdida de conexión al pasar
a cámaras o perder foco y pantalla negra al reconectar, hasta cerrar completamente
la app. También confirma indicaciones y mapa simplificado en el cuadro; Apple Maps
funciona mejor que Google Maps. Las opciones repetidas siguen pendientes de ubicar
en el menú exacto. No se declara resuelto el fallo físico.

El propietario solicitó una reinstalación con todos los valores por omisión. Se
desinstaló `com.shihab.diplay.hudtest` sin conservar datos y se reinstaló el APK
e6ced9da mediante el procedimiento del firmware (`--install-reason 64`). Terminó
mientras llegaba su instrucción posterior de hacerlo sólo después de resolver
la estabilidad. La app quedó cerrada; no se restauraron ajustes, ruta del cuadro
ni opt-ins. No instalar otra candidata antes de cumplir esa instrucción.

La candidata local incorpora recuperación de escena aún no construida y watchdog
para cada nueva activación visible, además de separar estado y Canvas. El CI
completo de ese incremento pasó. La corrección posterior de Surface también pasó
el CI completo: 3.581 JUnit, cero fallos/errores, una omisión preexistente, y 126
pruebas Python. La candidata `DiPlay-Ultra-focus-recovery-dev-20261021.apk`, SHA-256
`14450bee4fb031f6fff1a33dc9ddce0235d60861f96ccbfb5d1726851472f267`,
se instaló posteriormente por instrucción expresa del propietario, como indica
la actualización al comienzo de esta sección.

`UltraSurfaceOutput` convertía un tamaño transitorio distinto en excepción fatal.
Además, `Surface.isValid` no evita que Android abandone el buffer antes de
`lockCanvas` o `unlockCanvasAndPost`. Esas excepciones alcanzaban el cierre del
pipeline. Ahora el tamaño incorrecto y las excepciones de superficie en lock/post
producen una publicación fallida; el pipeline puede reintentar y el watchdog sigue
acotando una ausencia persistente de salida. Los demás errores siguen propagándose.
Cinco regresiones locales verifican recuperación y que no se anuncie disponibilidad
sin publicación. Esto prueba el camino de código; no identifica por sí solo la causa
del fallo observado en el coche.

Las secciones siguientes conservan evidencia histórica de versiones anteriores.

2026-10-20. Prioridad actual del propietario: resolver la versión inusable antes
de ampliar funciones. Reportó cuadro sin contenido, transiciones lentas, negro,
cierres y un fallback a CarPlay común. Estos síntomas físicos siguen sin solución
validada; las pruebas locales no los sustituyen.

## Evidencia del coche

* APK instalada identificada por SHA-256:
  `e75604beeaa5bab97268ba2ab01ef4d8a736d5eba6fbaf3b132b995c6055c670`.
  Las candidatas posteriores de transparencia, fan/TPMS y contexto no estaban
  instaladas durante el reporte. No atribuirles el fallo observado.
* Arranque actual2793; la ruta `no_backup/c11-cluster-route.json` seguía ligada
  a2791. Esto invalida `LeapmotorClusterDisplay.findDisplay` y bloquea la salida
  al cuadro antes de pedir el stream. La pantalla real volvió a medirse como
  `local:131`, display2, mode2, 1920×720, rotación0, firmware esperado.
* Ruta renovada a2793 con copia previa, comprobación del arranque y readback.
  Sólo se cambió ese JSON técnico. La renovación no demuestra contenido visible
  ni resuelve automáticamente el próximo arranque: el autoprovisionado sigue pendiente.
* Al inspeccionar, DiPlay no estaba en primer plano. Los buffers disponibles no
  conservaron eventos filtrados del negro/fallback reportados. No se registró
  una nueva sesión CarPlay durante la reproducción posterior de180s abriendo
  explícitamente la candidata instalada. No hubo una nueva negociación UI Sync
  ni un motivo de fallback capturado; no se puede asignar causa física todavía.
* La reproducción sí capturó target izquierdo20.0 y derecho20.0, ambos por el
  observador pasivo. También interior raw36.5 y exterior corregido raw17.5, aún
  con unidades/sensor validity sin verificar. No se cambió ninguna consigna.

Evidencia filtrada de desarrollo:
`.private/c11/ultra-20261020/cluster-route-renewal-2793.json`,
`black-screen-reproduce-old-candidate.jsonl`, `black-screen-exit-info.json`.
No se guardaron mensajes de log arbitrarios, fotos del usuario ni trazas personales.

## Defecto de concurrencia corregido localmente

El callback de render de `UltraReferenceCenterHost` ejecutaba `output.publish()`
mientras poseía el mismo monitor que utiliza el estado del host. `publish` llama
a `Surface.lockCanvas`, dibuja el bitmap y publica el Canvas. Una publicación
lenta podía impedir pausar, consultar disponibilidad o leer el motivo de fallo.
Esta cadena es demostrable en código; falta demostrar que causó el bloqueo físico.

Ahora el dibujo y el estado tienen monitores separados. La publicación captura
conexión, attachment y activación bajo un bloqueo breve y los vuelve a validar
al terminar. Un frame antiguo no establece disponibilidad para una sesión,
superficie o activación nueva. Las operaciones de salida permanecen serializadas;
no se añade una cola de bitmaps ni se libera una Surface prestada por Android.

Pausa, lectura de disponibilidad y motivo de fallo no esperan el Canvas.
Cambio/destrucción de superficie y cierre todavía pueden esperar el dibujo
en curso para preservar su vida útil: no se afirma haber eliminado todo bloqueo
posible ni haber hecho la composición fluida en el coche.

Cuatro pruebas concurrentes reproducen un `publish` bloqueado y comprueban
respuesta de estado, invalidación temprana, reemplazo de conexión y retiro seguro.
El CI completo pasó3.523 JUnit, cero fallos/errores y una omisión preexistente.

## Captura que permite discriminar la siguiente reproducción

El capturador público ya registra el motivo del fallback:

| Evento | Qué discrimina |
| --- | --- |
| `UI_SYNC_NOT_NEGOTIATED` | El teléfono no negoció ese canal |
| `UI_SYNC_CHANNEL_TIMEOUT` | No abrió el panel en el plazo útil del watchdog |
| `UI_SYNC_CHANNEL_CLOSED` | Se cerró después de abrir |
| `BINDING_MISMATCH` | Configuración del centro incompatible |
| `RENDERER_FAILURE` | Fallo de la ruta de composición/render; todavía requiere aislar su etapa |
| `canvas_submission` | Sólo envío a Canvas; no prueba visibilidad física |

El watchdog actual cubre apertura UI Sync, no una garantía de primer frame tras
abrir el canal. No confundir «negociado» con «interfaz útil». Próxima prueba:
captura antes de conectar el iPhone, observar negociación, primer envío y fallback
si ocurre; repetir con la candidata corregida y verificar pausa/regreso y cuadro.
Mantener la barra original en la candidata de diagnóstico para conservar una base
visual conocida; la variante transparente se conserva como artefacto aparte.

No ejecutar la herramienta SET HVAC durante esta investigación. Sigue pendiente
su calibración física separada; no ayuda a explicar una pantalla negra.

## Corrección instalada y primera conexión breve

La candidata con barra original `510df7839c18136d191b7ac221da62520f0492fd6d8ab993d7b11b4b8fdd6571`
se instaló y verificó por hash. Ruta2793 y permiso overlay sobrevivieron la instalación.
En `render-lock-installed-startup.jsonl` se observó negociación Ultra14:29:53.968,
SETUP14:30:00.110, owner14:30:00.359 y primer cuadro decodificado, seguido de
OWNER_CLOSED14:30:03.046. El propietario dijo estar lejos y que quizá se desconectó:
no atribuir ese cierre a la corrección ni usarlo como prueba de estabilidad.
Fan1008 se recibió físicamente como nivel0, sin inferir apagado del HVAC.
La prueba cercana quedó iniciada en `owner-parked-render-lock-validation.jsonl`.

## Protección adicional preparada, sin instalar

La apertura del canal no garantiza un primer Canvas. La candidata
`275c099543f9d466202d171fd71b03f6346fb9337294594b4dabc5527c458f4d`
añade un presupuesto independiente de10s útiles para el primer envío aceptado,
con fallo `FIRST_FRAME_TIMEOUT`. Se suspende al pausar/perder superficie y descarta
tickets de fases, conexiones y activaciones antiguas. No crea timers por frame
ni promete fluidez. CI3.531 JUnit sin fallos/errores, una omisión preexistente;
parser32Python y extractor TPMS7Python aprobados. No se instaló durante la prueba
presencial: la versión bajo observación sigue siendo510df783.
