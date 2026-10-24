# Experimento diagnóstico: separar SET de GET en HVAC C11

2026-10-20. Herramienta local preparada; **no ejecutada en el coche**. No habilita
controles DiPlay/Siri ni registra un backend de producción.

La [revisión xhigh](ULTRA_HVAC_XHIGH_FINDINGS.md) mostró que el GET con área0 y
timestamp0 no demuestra que SET sea inoperante. El nuevo entrypoint
`com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateShellWriteMain` permite
discriminar esa hipótesis sin exigir que GET deje de ser placeholder.

## Antes de una ejecución física

Primero, con coche estacionado y control OEM, correlacionar izquierda/derecha y
los eventos11205/11206, con SYNC desactivado. Observar una transición manual de
un grado entero dentro de18–32 y volver al baseline. Mantener el observador
pasivo independiente, misma época y sin resuscribir durante la prueba.
La herramienta recibe el baseline declarado por el operador; **no verifica ni
certifica esa calibración por sí misma**. No ejecutarla para descubrir al azar
áreas o valores, ni en ausencia de un retorno de referencia utilizable.

## Contrato de la herramienta

Argumentos: `check <area> <baseline> <target>` o
`set-once <area> <baseline> <target>`. Área49 o68, ambos valores enteros18–32,
diferencia exactamente un grado. `check` nunca llama al setter. `set-once` llama
como máximo una vez; sin bucle, reintento ni restauración automática.

Sólo UID real2000, asociado realmente a `com.android.shell`, con permiso
`CONTROL_CAR_CLIMATE` antes del acceso y después de consultar configuración.
Se exige una única configuración Float/RW/ON_CHANGE para propiedad358614275,
una única área coincidente y baseline/target dentro del rango HAL. No se concede
ningún permiso ni se cambia una identidad. No se invoca GET ni se modifica la
política del controlador de producción.

Se carga `/system/framework/android.car.jar` del propio firmware. La firma
capturada es `ICarProperty.setProperty(CarPropertyValue, ICarPropertyEventListener)`.
El callback Binder es real y no nulo: el servicio OEM aplica SET antes de llamar
a `listener.asBinder()`, por lo que un listener nulo podría actuar y después
fallar. Los callbacks de cambio de estado se descartan; no son readback.

El diagnóstico conserva únicamente el prefijo escalar de un error asíncrono para
la propiedad y el área solicitadas. La fuente capturada
`CarPropertyService.onPropertySetError` (`:485–508`) envía una lista de un único
`CarPropertyEvent` de tipo1, estadoERROR y timestamp0;
`CarPropertyEvent.createErrorEventWithErrorCode` documenta esa forma. No se
invocan creadores Parcelable ni se deserializan valores/clases arbitrarios.
Sólo se acepta esa lista singleton, con cuerpo máximo1024bytes, nombre de clase
exacto y cabecera completa. Se registran categorías de error permitidas
`TRY_AGAIN`, `INVALID_ARG`, `PROPERTY_NOT_AVAILABLE`, `ACCESS_DENIED` o `UNKNOWN`;
no se imprimen valores ni mensajes arbitrarios.

El observador se arma inmediatamente antes del único intento SET y mantiene una
ventana de espera de como máximo500ms después de que ese intento termine,
también si hubo una excepción síncrona. Si detecta un error coincidente imprime
`VHAL_ERROR_OBSERVED` y el diagnóstico sale con2. Si no lo detecta imprime
`ASYNC_ERROR_WINDOW|state=NO_MATCH_SEEN`: el envío continúa
`SUBMITTED_UNCONFIRMED`, sin declarar aceptación. Una interrupción sale con2.
Después de cerrar la ventana se ignoran callbacks tardíos.

Este acuse negativo tiene correlación limitada: no lleva transactionID, y el
servicio lo dirige al último escritor registrado para propiedad/área. Además,
registra ese escritor después de llamar al HAL, por lo que un error muy temprano
puede perderse o dirigirse a un dueño anterior. La ausencia de callback, un error
que llegue fuera de la ventana o un callback de cambio no demuestran efecto ni
aceptación. No se añade retry ni restauración automática, ni siquiera ante
`TRY_AGAIN`.

Antes de cruzar Binder se imprime y vacía stdout con `DISPATCH_BEGIN`, área,
target y tiempo monotónico. Un watchdog de25s termina el proceso bloqueado.
Un timeout o excepción después del despacho deja el efecto **desconocido**:
no se debe repetir el envío por ese motivo. La salida normal tras SET sólo dice
`SUBMITTED_UNCONFIRMED`; nunca `CONFIRMED` ni éxito físico.

Código de salida0 significa que el check o envío retornó sin fallo síncrono y sin
error asíncrono observado en la ventana;
no acredita efecto en vehículo. Rechazos, errores y watchdog salen con2.
Los errores no imprimen mensajes arbitrarios ni datos de excepciones.

## Uso previsto, sin ejecutar todavía

Después de compilar y verificar la candidata, copiar exclusivamente esa APK de
desarrollo a `/data/local/tmp/diplay-ultra-hvac-diag.apk`. Ejecutar con `app_process`
en shell legítimo, sin instalar como aplicación privilegiada:

```sh
CLASSPATH=/data/local/tmp/diplay-ultra-hvac-diag.apk app_process / \
  com.shilapi.xcertplay.vehicle.leapmotor.LeapmotorClimateShellWriteMain \
  check 49 20 21
```

Los números son un ejemplo de sintaxis, **no una selección calibrada de este coche**.
Sólo tras reunir la evidencia previa y fijar baseline real puede sustituirse
`check` por `set-once` con los parámetros establecidos. No encadenar un envío
automático a un check: éste sólo comprueba identidad/permiso/configuración y
argumentos, no la calibración física.

Aceptar como efecto únicamente una transición independiente nueva del lado
esperado al valor solicitado, coherente con la pantalla OEM y sin cambio
inesperado de la otra zona. Registrar baseline, acción, época y tiempos; fijar
el plazo a partir de la latencia manual observada. Restaurar manualmente el
baseline al terminar. Si no hay transición, detenerse sin barrer valores ni áreas.

Una prueba positiva demostraría el puente VHAL bajo shell en ese firmware.
Seguirían pendientes la admisión del UID de DiPlay, el contrato Siri y su respuesta
tras confirmación real. Una prueba negativa sólo descartaría ese caso concreto.

## Verificación local

Las pruebas cubren rechazo de identidades, argumentos y configuraciones; revocación
de permiso; ausencia de escrituras en check; un único intento sin retry; marcador
anterior al envío; sanitización de errores y la firma Binder exacta con listener
no nulo. Root ejecuta el CI completo antes de declarar comprobada la candidata.
El incremento de acuses negativos añade pruebas de forma Parcel exacta y cabeceras
rechazadas, propiedad/área incorrectas, cambio de estado ignorado, error recibido
durante el setter o durante la espera, retiro/duplicados, interrupción y códigos
no reconocidos. Sus pruebas y esta preparación no validan ningún SET físico.

CI coordinado del incremento:3.561 pruebas JUnit en los módulos requeridos y
diagnóstico, sin fallos/errores y una omisión preexistente;13 pruebas nuevas cubren
esta observación de errores. Candidata `DiPlay-Ultra-map-hvac-diagnostic-dev-20261020.apk`
archivada y **sin instalar**, con SHA y estado en
[el registro de integración](LEAPMOTOR_C11_ULTRA_STATUS.md). La ejecución física
del diagnóstico sigue pendiente de calibración, independientemente de CI.
