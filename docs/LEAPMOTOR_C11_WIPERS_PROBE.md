# C11: investigación de estados de limpiaparabrisas y lluvia

Fecha: 2026-10-23. Herramienta preparada en local; no se conectó al coche.

## Estado

DiPlay conserva los seis avisos de mantenimiento/activación ya preparados.
Los estados normales, selectores, velocidad, AUTO y lluvia requieren evidencia
de recepción y correspondencia física. C11 Diagnóstico **0.6** añade una escucha
manual para obtener esa evidencia en una sola ventana de 5 o 30 segundos.
Estos candidatos no se incorporan como nuevas lecturas verificadas de DiPlay.

Se revisaron los consumidores disponibles y se hizo una búsqueda de constantes
en métodos DEX del APK CarControl. Los resultados fueron candidatos, no pruebas:
hay coincidencias en tablas de fallos y también valores ajenos al protocolo.
La búsqueda quedó en `wiper-dex-candidates.json` de la evidencia privada.

## Candidatos de la escucha

Nombres e IDs proceden del inventario `CanEvent.java` del APK OEM:

| ID | Candidato de inventario |
| --- | --- |
| 3826 | Estado de limpiaparabrisas delantero |
| 3843 | Selector delantero |
| 3844 | Selector trasero |
| 2603 | Retorno de parada delantero |
| 2604 | Estado del motor delantero |
| 2725 | Retorno de control trasero |
| 2726 | Retorno de parada trasero |
| 3100 | Solicitud de velocidad desde lluvia |
| 3101 | Nivel de lluvia |
| 3104 | Fallo de lluvia |
| 10614 | Ajuste de intervalo |
| 3849 | Modo de mantenimiento |

La prueba solicita estos eventos en `0x9900 / 0x9901 / 0x9903`, como ruta
candidata. No da por verificados tipo, ruta efectiva o interpretación por la
mera existencia del nombre en el inventario. El evento 3104 tiene además
registro INT32 en `LeapCenterManager.leapCenterCanEvents` y una entrada de fallo;
no demuestra un nivel de lluvia ni el modo AUTO.

El cliente usa sólo la API de suscripción JNI que ya utiliza la herramienta,
en el proceso desechable `:oemprobe`. No declara publicadores ni envía órdenes
de barrido, lavado o mantenimiento. El perfil anterior de cinco lecturas OEM
continúa separado; la elección se hace desde un botón distinto.

## Uso previsto cuando vuelva el coche

Abrir C11 Diagnóstico → «Investigar estados de limpiaparabrisas» → ventana de
5 o 30 segundos. En la prueba física se contrastarán cambios del mando original
y el funcionamiento observado, con el coche estacionado. La herramienta no
acciona mecanismos por sí sola.

El resultado se guarda antes de cerrar el cliente nativo en
`files/wiper-probe-last.json`, separado del informe OEM de energía. El cierre
usa la terminación de su propio proceso, como la prueba OEM existente, por el
fallo de ABI documentado en el `stop` del JNI instalado.

Para recuperar el resultado en el futuro ensayo, sin iniciar otra escucha:

```sh
adb -s SERIAL shell run-as com.southcrux.c11diagnostics cat files/wiper-probe-last.json
```

Este comando no se ejecutó en el trabajo local. El informe incluye la marca
`capturedAtElapsedNanos` de la prueba; un archivo anterior no acredita un intento
nuevo si el guardado falla. No se instalan permisos adicionales ni se modifica
la configuración OEM para realizar esta prueba prevista.

## Qué conserva el informe

Sólo se admiten los doce IDs y el endpoint solicitado. Para cada uno se guardan
contador, primera/última recepción y hasta ocho cambios de payload. Se retienen
de 1 a 8 bytes en hexadecimal; muestras nulas, vacías o mayores sólo registran
su tamaño. Se ignoran otros IDs/endpoints. No hay descodificación numérica,
unidades inventadas ni interpretación automática de cero como OFF.

Los campos sin recepción permanecen sin muestra. El informe marca tipo, ruta
y semántica como pendientes y reconoce que la primera entrega puede ser cacheada.
Los tiempos son de recepción local, no del sensor. Al cerrar la ventana se
rechazan callbacks tardíos antes de guardar y liberar el cliente.

Las pruebas locales cubren límites, filtros, cambios repetidos, cierre, guardado
anterior a liberación y apertura de pantalla sin escucha automática. La captura
real sigue pendiente; una ventana vacía no prueba ausencia de sensores.

## Después de la prueba física

Identificar qué evento cambia con cada acción, tamaño/representación del payload
y valores reproducibles. Contrastar con el consumidor OEM cuando sea posible y
recién entonces añadir el decoder de estados normales al módulo de DiPlay.
Los doce candidatos de esta escucha no aumentan el catálogo de 91 lecturas ya
preparadas ni se anuncian como funciones de control o capacidades Siri.

Validación local final: CI completo PASS (3.727 pruebas, 0 fallos/errores,
46 omitidas), incluidos siete tests nuevos. APK diagnóstica 0.6 archivada sin
instalar; hash y fuentes en `wiper-candidate-probe-verification.json` de la
carpeta privada de artefactos. No se recogieron muestras físicas.
