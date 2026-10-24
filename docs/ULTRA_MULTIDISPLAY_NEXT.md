# C11: navegación y acompañante, dependencias actuales

Revisión local del 2026-10-20. No se conectó al coche ni se alteraron sus ventanas.
Esta matriz distingue destinos implementados de capacidades declaradas al iPhone.

| Destino | Identidad física medida | Entrada / propietario | Estado |
| --- | --- | --- | --- |
| Principal | local:129, display lógico 0, 2560×1440 físico | Stream 110, propietario central Ultra y superficie de Activity sustituible | Composición central implementada; sus layouts no implican un cluster temático. |
| Cuadro, navegación Android | local:131, display lógico 2, 1920×720 | Stream 111 convencional, decoder y Surface propios; ventana720×720 en (1200,0) | Ruta condicionada a prueba del arranque/firmware y preferencia. Compatible en código con centro Ultra. |
| Acompañante | local:130, display lógico 3, 1920×720 | Actividad/reproductor independiente con lease de destino por sesión | Selección y revocación implementadas; la integración C11 no anuncia vídeo al iPhone. |
| HDMI lógico | Identidad distinta de las anteriores | Ninguno seleccionado | No es un fallback autorizado ni prueba de conector físico. |

Los IDs lógicos de esta tabla son mediciones históricas, no constantes de
selección. Cuadro y acompañante tienen igual tamaño, pero cumplen funciones
distintas. La app no los elige por orden, nombre genérico o resolución.

## Navegación real junto al centro Ultra

`UltraThemeBinding.conventionalNavigation` conserva la configuración ordinaria:
ALT_UUID, initialURL, 720×720, sin input. No tiene plugin ni propietario UI Sync
de instrumentos. Es excluyente con `cluster` temático. El owner del centro
continúa gestionando solo Center_Display y su vídeo 110; el 111 llega al sink
ordinario cuando `suppressesConventionalCluster` es falso.

`CarPlayHostActivity.clusterDisplayConfig()` exige la preferencia de mapa y
selección de display válida. `LeapmotorClusterDisplay` verifica metadata C11,
firmware, BOOT_COUNT, modo, tamaño, orientación, privacidad e identidad
provisionada en `no_backup/c11-cluster-route.json`. El permiso overlay también
es necesario. No abre ni modifica DashboardActivity OEM. La sección física de
mapas tiene que estar disponible por el mecanismo propio del coche.

El contenido procede del iPhone mediante el stream 111 y su URL de cluster.
No es un recorte del vídeo 110. No se garantiza que cualquier navegador de
terceros exponga esa interfaz ni que el destino siga automáticamente a la app
activa. Tampoco prueba instrumentación completa, widgets Ultra o salida física
en el C11 actual: esa comprobación necesita una prueba coordinada posterior.

## Retirada y recuperación de la ventana del cuadro

`LeapmotorClusterMapOverlay` mantiene una ventana sin foco ni interacción,
acotada al recuadro medido. Cada apertura tiene un propietario distinto:
callbacks de una ventana retirada no pueden adoptar una Surface, ni una
destrucción tardía de otra Surface puede desconectar la actual.

El cierre invalida el propietario y elimina referencias antes de retirar la
ventana. La desconexión se notifica una sola vez si había Surface. El código
no libera Surfaces prestadas por Android. Una apertura parcialmente fallida
retira su intento sin publicarlo como ventana mostrada y permite un reintento.
Si Android ya retiró la ventana, el cierre sigue siendo idempotente.

Las pruebas cubren reemplazo de Surface, callbacks de otra apertura,
invalidación del propietario y desconexión del decoder antes de retirar la ventana y fallo después de una entrega parcial.
Estos cambios no lanzan DiPlay al frente ni permiten competir con cámaras
OEM. El problema histórico de foco de la pantalla principal tras la cámara
requiere identificar la transición/propietario real; este cambio no lo declara
resuelto mediante una orden periódica de foreground.

## Acompañante independiente

`CarPlayPassengerDisplay` requiere prueba local:130 del mismo arranque y
firmware, display válido/público/no principal, modo y geometría correctos.
`CarPlayVideoRouteState` conserva selección y un único lease de lanzamiento
por sesión. Una sesión nueva no hereda el opt-in del acompañante; una pérdida
de ese destino no redirige contenido silenciosamente al principal.

El reproductor comprueba destino y permiso de vídeo al adoptar el lease,
durante reproducción y al reanudar. `CarPlayVideoDestinationMonitor` observa
la desaparición/cambio de display y cierra una sola vez. Los callbacks de
actores retirados no pueden adoptar el reproductor de otra sesión. El cierre
de este reproductor no contiene una orden para lanzar CarPlay principal.

Esto proporciona separación local de ventana/reproductor y ciclo de vida, pero
no crea un rol de pantalla Apple, catálogo de aplicaciones, proveedor DRM o
permiso para reproducir mientras se conduce. La integración C11 conserva
`videoInCar=false`: faltan una señal física de P confirmada y un contrato de
destino acordado antes de habilitarlo. No basta cambiar el flag de laboratorio
`VideoInCar.allowed` usado por pruebas.

## Siguiente secuencia verificable

1. Con prueba de ruta de ese arranque y preferencia explícita, comprobar que el
   iPhone entrega 111 y que el cuadro recibe navegación mientras 110 sigue
   independiente. Registrar recepción, callback de Surface y observación
   física por separado.
2. Comprobar retirada/reaparición de la superficie de navegación y cambio de
   cámara sin forzar ventanas OEM; distinguir el problema del foco principal.
3. Para acompañante, verificar por separado señal física de P y destino de
   presentación independiente. Solo después decidir qué capacidad de vídeo
   puede declararse. No inventar aplicaciones compatibles ni soporte DRM.

No se anuncia un nuevo panel temático ni se ocupan cuadros completos con el
recurso de referencia por coincidir su resolución.
