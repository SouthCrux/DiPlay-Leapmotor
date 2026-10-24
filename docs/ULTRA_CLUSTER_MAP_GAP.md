# Cuadro C11: indicaciones validadas, plano pendiente

2026-10-20. El propietario confirmó: «funciono lo del las indicaciones en la
pantalla de los mandos aunque chico, falta a demas la proyeccion del plano con
la ruta simplificada en 3d». Es validación física de las indicaciones, no del mapa,
del aspecto 3D ni de estabilidad prolongada del centro.

## Versión y evidencia física

La versión bajo prueba es510df783, SHA completo en
[diagnóstico del negro](ULTRA_BLACK_SCREEN_DIAGNOSIS.md), con ruta renovada a2793.
La fuente usa instrucciones estructuradas iAP2; no contiene polilínea de ruta,
calles, posición/cámara geográfica ni incidentes del proveedor.

Historial filtrado de desarrollo:

* `cluster-map-history-typed.jsonl`: SETUP111 aceptado14:29:59.961 y14:30:51.077,
  activación del receptor111; SETUP110 aceptado14:30:51.398 y Canvas central
  enviado14:30:51.821. Activar el receptor no prueba que haya recibido bytes.
* `cluster-map-video-stats.jsonl`: en ventanas posteriores14:47–14:48 sí aparecen
  datos del decoder primario111, frecuentemente16–18fps recibidos y enviados a
  la superficie, sin recuperaciones. Algunos intervalos bajan a7–10fps.
  Estos contadores prueban procesamiento, no contenido geográfico ni presentación
  física. La ausencia de una línea tampoco prueba cero frames: el logger omite
  ventanas sin recepción.
* `cluster-only-map-gap.png`: captura posterior sólo de la capa Android del display
  físico131. La zona de720×720 aparece plana; no se ve un mapa en esa captura.
  La instrumentación OEM puede estar compuesta fuera de esta capa. La captura
  posterior no reconstruye qué veía el propietario durante la ruta ya terminada.

No se guardaron URLs de destinos, geometría de rutas ni payloads de vídeo en logs.
La confirmación del navegador usado sigue pendiente; la compatibilidad de un
proveedor no se deduce de la de otro.

## Dos correcciones locales concretas

**Legibilidad:** el render de la tarjeta aplicaba los márgenes BYD35/36% dentro de
la ventana C11 ya acotada. Con el tamaño predeterminado55%, terminaba150×58.
El renderer C11 calcula ahora contra la zona completa disponible:396×150.
Conserva las preferencias de posición/tamaño y la geometría de otros perfiles.
Fuente grande, RTL y colocación de totales quedan dentro del área OEM verificada.

**Dimensiones del decoder:** `AndroidMediaSink.newVideoDecoder` pasaba el ancho y
alto principales también al111. Ahora admite dimensiones inmutables por stream;
el host C11 proporciona el tamaño del cluster anunciado,720×720. Espejos y
recreaciones mantienen la dimensión del tipo. La corrección está probada mediante
el `MediaFormat` real recibido por ShadowMediaCodec; falta validación física de
la nueva configuración. El CSD puede haber corregido el tamaño en algunos
decoders anteriores, así que este defecto no demuestra la causa del mapa ausente.

La tarjeta no tiene un fondo opaco de720×720: sólo dibuja su cápsula y totales.
La tarjeta contextual de batería se oculta al recibir stream o guía. Esto reduce
una hipótesis de solapamiento en el código; no descarta problemas de composición
SurfaceView/ventana ni demuestra que el teléfono haya generado cartografía útil.

## Diagnóstico añadido y líneas abiertas

El capturador distingue ahora SETUP110/111, primera recepción por tipo, primer
encolado al decoder con dimensiones, estadísticas por stream y activación del
receptor. Descarta ports, nombres libres, URLs y payloads. `submitted` se usa
como envío al decoder/Surface según el evento, nunca como prueba de visión física.

`MAP_WITH_CUSTOM_CARD` ya anuncia `maps:/car/instrumentcluster/map` y conserva
la ruta111 convencional junto al centro Ultra. En las sesiones capturadas hubo
SETUP111; no se añadió un bootstrap especulativo para resolver una ausencia de
SETUP que esos datos no muestran.

Quedan por discriminar:

1. Qué navegador produjo la guía y qué contenido entrega realmente en111.
2. El formato de salida y destino Surface del decoder111 con la nueva candidata.
3. La composición visible en la zona acotada y la política de showUI/initialURL.
   El estado interno `displayed=initialURL` no es confirmación del teléfono.
4. Si el proveedor permite la perspectiva deseada. Si no entrega mapa, un plano
   propio exige geometría real de ruta, posición y un renderer/proveedor autorizado.
   Una flecha o texto de giro no permite reconstruir calles ni incidentes.

## Candidata preparada

La candidata más reciente es `DiPlay-Ultra-map-hvac-diagnostic-dev-20261020.apk`,
SHA-256 `afc974ec0e16d147585d401e6bd7246fbd4f3d9da6c7256e0683a3293dfbfa62`.
Está **sin instalar**. Reúne las correcciones descritas abajo y una muestra PixelCopy
puntual, sólo debug, para clasificar el buffer111 como uniforme/variado o registrar
un error. No guarda imágenes ni píxeles y no prueba presentación física ni que el
contenido sea cartografía. También identifica exactamente la solicitud `MAP_CLUSTER`
sin cambiar su enrutamiento y añade observación acotada de errores negativos al
diagnóstico HVAC. No se ejecutó SET ni se habilitó Siri. CI completo3.561 JUnit,
cero fallos/errores y una omisión preexistente;36 pruebas Python del capturador,
7 de evidencia TPMS y60 de LeapMoto. El recurso de transparencia está
verificado como false. El coche se retiró y DiPlay fue cerrado por petición del
propietario; no hay una validación física activa.

La candidata previa de muestra de buffer, `bd8eeaed`, se conserva archivada junto
a su verificación de3.547 pruebas; tampoco fue instalada.

La candidata anterior se conserva para trazabilidad:

`DiPlay-Ultra-cluster-legibility-map-dev-20261020.apk`, SHA-256
`2a50aebfeb75c3c95d4f595385e8c87be13477c6bc3cc1ae4589859cb3676539`.
Preparada, **sin instalar**: conserva la versión bajo observación durante la prueba.
CI completo3.540 JUnit sin fallos/errores, una omisión preexistente,35 pruebas Python
del capturador y cuatro capturas nativas de legibilidad inspeccionadas.
Incluye la protección del primer Canvas previamente preparada; no se atribuye
una mejora física hasta instalar y comparar una nueva sesión.

## Referencia visual elegida por el propietario

El 2026-10-20 el propietario adjuntó una captura vertical de navegación AMap y
aclaró: «así quería que luciera». Sustituye la interpretación de una flecha pequeña
como objetivo visual. La imagen es una referencia de composición, no un contrato
de datos ni evidencia de compatibilidad del iPhone o del firmware C11.

El resultado deseado en la zona verificada720×720 es:

* Una vista de carretera/ruta en perspectiva, con la posición del vehículo abajo.
* La ruta próxima destacada, con contraste legible sobre el fondo oscuro.
* Próxima maniobra y distancia grandes en la parte superior, con poco texto.
* Cámara y escala adaptadas al espacio disponible, preservando los instrumentos OEM.
* Carriles recomendados, semáforos y contadores sólo si una fuente real entrega
  esos datos; los valores y objetos de la captura no se reproducen como señales.

La referencia pública inicial fue el [post compartido por el usuario](https://www.instagram.com/p/DdrrQmYGj_6/).
La captura posterior adjunta es el criterio visual más específico. Las imágenes de
trabajo permanecen privadas; no se incorporan a recursos de la APK ni al árbol público.
Una visualización de maniobra calculada sólo desde dirección/distancia debe seguir
identificada como diagrama: no reemplaza el mapa geográfico real que el usuario pide.
