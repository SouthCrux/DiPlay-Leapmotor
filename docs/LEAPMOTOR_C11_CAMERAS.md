# C11: captura puntual de cámaras exteriores

Fecha: 2026-10-23. Preparación local, sin conectar al coche ni instalar esta versión.

## Qué está implementado

`LeapmotorCameraSnapshots` implementa una captura puntual mediante reflexión sobre
el SDK OEM. La app **C11 Diagnóstico 0.6** conserva «Captura de cámaras exteriores»
y cuatro botones de selección. Abrir la pantalla no inicializa el SDK ni abre una
cámara; se necesita una selección manual. No hay llamadas desde el arranque de
DiPlay, CarPlay o el servicio de telemetría.

La captura corre en el proceso separado `:cameraprobe`. Solicita conversión YUV420,
1920×1536 y 5 fps como parámetros, espera una muestra hasta 1,5 segundos y libera
las etapas que abrió. No fuerza una apertura cuando `openCamera` devuelve false.
Un límite externo de 6,5 segundos termina sólo ese proceso si se bloquea el JNI.
La coexistencia real con AVM/retroceso todavía necesita ensayo en el coche;
estos límites no demuestran que el SDK OEM permita dos clientes simultáneos.

El callback se copia una sola vez, con límite de 16 MiB. Se rechazan muestras
vacías/nulas/excesivas, callbacks anteriores al inicio, posteriores al cierre y
peticiones simultáneas del capturador. Se conserva el resultado de limpieza;
un fallo al liberar recursos queda indicado como `cleanupFailed`.

## Vía OEM identificada

Fuentes locales del framework archivado:

- `lmos.framework.core.cam.CamManager`: `init(boolean isToYuv420)`, `setParams`,
  `setCallback`, `openCamera`, `start`, `stop`, `closeCamera`, `uninit`.
- `ICamCallback.onDataCallback(byte[])`: entrega bytes sin geometría, strides,
  timestamp de sensor ni formato por callback.
- `CameraJni`: carga `lp.cam_jni`; la app declara opcionalmente esa biblioteca,
  sin empaquetar bibliotecas nativas ni JAR OEM.
- `CameraInfo` y `CamDefines`: IDs y tamaños nominales del SDK.

| Selección | ID solicitado al SDK |
| --- | --- |
| AVM delantera | 2 |
| AVM trasera | 3 |
| AVM izquierda | 4 |
| AVM derecha | 5 |

Sólo se exponen esos cuatro IDs. No se capturan cámaras de cabina, reconocimiento
facial o ADAS. La identidad física efectiva de cada flujo sigue pendiente de
contraste. La captura tampoco es una vista 360 compuesta: `ILpCameraRender` y
`IAvmControl` contienen operaciones de presentación y selección de ángulo, no
una API de devolución de fotografías en las interfaces inspeccionadas. No se
invocan esas operaciones ni se abre la app OEM para obtener una captura de pantalla.

La resolución de clases/métodos usa `Class.forName(..., false, loader)`, sin
inicializar JNI. Si el SDK no es visible al classloader de la aplicación,
la prueba informa `SDK_UNAVAILABLE`. Clases presentes tampoco prueban permisos,
biblioteca accesible o capacidad de apertura en este firmware.

## Resultado guardado

Cada éxito conserva un archivo privado `camera-<selección>-<uuid>.raw` y actualiza
`files/camera-probe-last.json`. El manifiesto contiene selección solicitada,
parámetros solicitados, estado, tamaño, hash SHA-256 y nombre de archivo.
Cada intento tiene un `captureId`; se compara con el resultado en pantalla para
identificar el manifiesto correcto. Un error de escritura se indica en pantalla
como `metadataSaveFailed`: el manifiesto anterior no acredita el intento nuevo.
La copia de seguridad Android está desactivada; no se sube ni comparte contenido
automáticamente. Una prueba fallida informa su fallo y no muestra una muestra
anterior como si fuera nueva.

**El archivo bruto aún no es una fotografía JPEG/PNG validada.** El callback
no identifica NV12/NV21/I420, orden de planos, padding o dimensiones efectivas.
Por eso el manifiesto marca:

```json
{
  "pixelLayoutVerified": false,
  "actualGeometryVerified": false,
  "actualChannelVerified": false,
  "encodedImageAvailable": false,
  "requestedConversion": "YUV420"
}
```

No se adivina un formato por tamaño ni se reetiquetan bytes como imagen.
El conversor local descrito abajo permite generar vistas PNG bajo parámetros
explícitos. Falta una muestra real para confirmar disposición, geometría y colores;
no se afirma que la captura funcione físicamente porque pase el SDK simulado.

## Conversor local de muestras brutas

`tools/carplay/camera_frame.py` genera una vista PNG para NV12, NV21, I420 o
YV12 de 8 bits, contiguos y sin padding/cabeceras. Exige dimensiones, formato,
matriz BT.601/BT.709 y rango limitado/completo. No detecta automáticamente esos
parámetros. El orden de planos sigue la [documentación V4L2 de Linux](https://kernel.org/doc/html/latest/userspace-api/media/v4l/pixfmt-yuv-planar.html);
el PNG sigue la [especificación W3C](https://www.w3.org/TR/png-3/). El conversor usa
sólo la biblioteca estándar de Python, sin instalar paquetes ni conectar al coche.

La conversión reconstruye crominancia por bloques 2×2 y usa la inversa Kr/Kb con
redondeo fijo de 16 bits. No realiza calibración de color, rotación o recorte, y
no agrega un perfil sRGB supuesto. Strides, buffers con padding, formatos tiled,
HDR/10 bits, tamaños incorrectos y datos truncados quedan fuera de esta versión.

Antes de convertir una muestra SDK comprueba estado, origen, selección, identidad
de intento, longitud y SHA-256 del manifiesto. La ruta `rawFile` del JSON no se
abre: se usa únicamente el archivo `--raw` indicado, que puede haberse renombrado
al recuperarlo. Sólo se copian campos de procedencia acotados al informe nuevo.

Ejemplo para un futuro ensayo: **NV12, BT.601, rango limitado y 1920×1536 son
hipótesis de este comando, no parámetros confirmados del coche**:

```sh
python3 tools/carplay/camera_frame.py \
  --raw muestra.raw --manifest camera-probe-last.json \
  --width 1920 --height 1536 --pixel-format NV12 \
  --matrix BT601 --range limited --output-dir vista-nv12-nueva
```

El directorio debe ser nuevo y su padre debe existir. Se guardan
`preview-unverified.png` y `decode.json`, con hash de entrada/salida y todos los
parámetros asumidos. No se sobrescriben entradas ni vistas anteriores. Una imagen
PNG estructuralmente válida no confirma el formato físico del buffer: geometría,
canal y colorimetría permanecen marcados como no verificados. La app diagnóstica
sigue guardando bruto; este paso de conversión se ejecuta aparte en el Mac.

`--synthetic`, excluyente con `--manifest`, permite probar datos inventados y los
marca como `DECLARED_SYNTHETIC`, tanto en el informe como en el PNG. El patrón
local de barras de color es sólo una prueba del decoder, no una imagen del coche.

## Primera prueba en el coche

Con el coche disponible y estacionado: instalar la APK diagnóstica preparada,
abrir «Captura de cámaras exteriores» y seleccionar una cámara. Revisar el
resultado, confirmar el acceso y recuperar la muestra. Repetir por lado sin
asumir que los cuatro flujos estén disponibles o puedan abrirse simultáneamente.
Comprobar además que la vista OEM de aparcamiento conserve su funcionamiento.

Comandos de recuperación para el futuro ensayo; **no ejecutados durante este trabajo**:

```sh
adb -s SERIAL shell run-as com.southcrux.c11diagnostics cat files/camera-probe-last.json
adb -s SERIAL exec-out run-as com.southcrux.c11diagnostics cat files/ARCHIVO.raw > muestra.raw
```

Usar el nombre exacto del manifiesto; no sobrescribir una muestra anterior.
El `run-as` corresponde a esta compilación debug. No se requiere modificar los
permisos del sistema ni detener aplicaciones OEM para ejecutar la prueba prevista.

## Comprobación local

Las pruebas del capturador usan un SDK falso y callbacks sintéticos: validan
firmas de reflexión, selección de canal, copia del buffer, timeout, rechazo de
reentrada y limpieza por etapas. Las pruebas de la pantalla comprueban que no
haya captura al abrirla, que SDK ausente informe indisponibilidad y que una
selección manual archive los bytes exactos sin afirmar un formato de imagen.

La compilación y resultados finales se registran junto a las APK archivadas en
la evidencia privada de esta iteración. La validación física sigue pendiente.

Validación final local: CI completo PASS (3.720 pruebas, 0 fallos/errores,
46 omitidas), ocho pruebas de capturador y dos de pantalla. APK diagnóstica
`C11-Diagnostico-camera-0.5-dev-20261023.apk` y APK DiPlay archivadas sin instalar;
hashes en `wipers-camera-preparation-verification.json` de la carpeta privada
de artefactos. No se capturó ninguna imagen real.

Conversor local añadido el 2026-10-23: ocho pruebas Python PASS y CI Android
completo PASS, con reutilización de tareas sin cambios. Patrón de barras
sintético 600×120 convertido y reconocido por el lector PNG de macOS, con
comprobación visual. Evidencia en `camera-frame-decoder-verification.json` de
la carpeta privada. Ninguna muestra del coche fue utilizada.
