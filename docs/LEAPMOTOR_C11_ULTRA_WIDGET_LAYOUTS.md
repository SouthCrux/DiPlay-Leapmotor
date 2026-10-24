# Destinos visuales para la integración Ultra del C11

Inventario local del 2026-10-19. Perfil de referencia: recurso `44C9FA31EC`,
versión 985, SDK 19.0. Es evidencia de metadatos originales, no de aceptación del
iPhone, servicios del coche disponibles ni presentación física.

## Integración actual en la aplicación

`UltraBatteryCard` ya está integrada en `CarPlayHostActivity`, fuera del rectángulo
de vídeo, con SOC y autonomía de la caché C11 existente. Exige host Ultra visible,
opt-in manual, salida preparada y espacio libre real; excluye barras y controles.
Dato ausente/vencido, pausa, fin de sesión o falta de margen ocultan la tarjeta.
0% sigue siendo un valor válido. No cambia la geometría del vídeo ni los módulos
originales del recurso. Es una tarjeta Android local, no el widget charging del
iPhone ni una zona nueva en Wallpaper. Pasó la validación conjunta local de
3.370 pruebas registradas (cero fallos/errores, una omisión previa), lint y builds.
Cinco capturas con gráficos nativos cubren fuente grande, compacto/completo,
árabe RTL, alta densidad y geometría mdpi medida del C11; verifican texto completo
y píxeles de vídeo intactos con datos simulados. No se ha instalado ni observado
esta integración en el coche. La caché usa el último estado recibido y lo retira
tras 60 segundos sin entrega; no acredita una lectura simultánea del HAL.

El fixture raster genérico descrito más abajo es evidencia histórica separada.
Su verificación de píxeles no demuestra por sí sola esta tarjeta integrada ni
interacción con Siri. La climatización sigue sin broker autorizado/paso real
verificados; no se añade un control que confirme un cambio antes del coche.

## Matriz de destinos

| Destino/layout | Declarado en el recurso | Implementado actualmente | Consecuencia |
| --- | --- | --- | --- |
| Centro `Wallpaper` | Cero zonas | Vídeo principal y composición central | No tiene zona donde solicitar un widget de batería o clima. |
| Centro `Overlay` | Dos zonas: Settings y Camera | No es una superficie de widgets del vehículo | Settings es `punchThroughType=setting`; Camera es `punchThrough=camera`. Contar dos zonas no demuestra soporte de widgets. |
| Cluster `Cobranded` | Cuatro zonas, un slot `widgetPopover` | Sin compositor ni propietario de ese panel en la integración central | No anunciarlo ni mostrar sus indicadores sobre los OEM. |
| Cluster DigiRing/Modular/DigiBandTicks/DigiBand | Seis zonas y un `widgetPopover` por layout | Sin integración física de esos layouts | No activarlos por semejanza de resolución. |
| Cluster `MapsMedia` | Ocho zonas, un `widgetPopover` | Sin integración física de ese layout | Es un destino distinto de la navegación convencional. |
| Cuadro C11, recuadro de navegación | Ruta Android verificada `local:131`; ventana de 720×720 en (1200,0) | Decoder independiente del stream111; ruta condicionada al arranque/firmware/preferencia | Puede recibir navegación compatible del iPhone. No constituye un panel temático Ultra ni un destino de widgets. |

Todos los displays del recurso miden 1920×720. Esa geometría lógica del recurso
no describe la pantalla principal física del C11, que mide 2560×1440. Tampoco
autoriza a ocupar el cuadro completo. Acompañante `local:130` y cuadro
`local:131` son destinos distintos aunque tengan igual resolución.

## Zona de widgets existente en la referencia

En `Cluster_Display`, layout Cobranded
`6C45CC47-EC01-527A-B40D-1316AA9C936F`, Zone1
`D6E7BC4C-744C-5944-BA96-8F578A31ED6D` ocupa (660,120), con 600×480.
Contiene DCA_ADAS, DCA_Map y el overlay RequestContent_Zone1. Su slot O4 es
`widgetPopover`; O2 es un indicador. La zona derecha tiene un indicador, sin
slot `widgetPopover`.

`RequestContentPopover.lua` muestra el contenido cuando el valor recibido es
`inputStream://?identifier=<streamIdentifier>`. Un URL de servicio de Apple
identifica contenido solicitado, pero no entrega por sí solo los píxeles ni
conecta ese input al compositor. El gauge local de batería de los otros slots
es otro mecanismo: consume propiedades CP y se dibuja en el accesorio.

## Dependencias del widget generado por el iPhone

La tarjeta Android local cubre un primer destino visual útil, separado de la
imagen CarPlay. Para contenido producido por el iPhone todavía hace falta un
destino central con panel, región, geometría, ciclo de vida y entrada de vídeo
o renderer reales antes de declarar esa capacidad. No basta cambiar `showsInstruments`, agregar un UUID o
solicitar charging. El contrato gateado de widgets permanece cerrado hasta
que exista ese destino y el esquema requerido esté verificado.

Para un widget generado por el iPhone hacen falta:

1. Negociar una región de contenido aceptada por el iPhone y asociar su input a
   un decoder independiente o a una región del flujo recibido. No reutilizar
   ciegamente el `iPhoneVideo` del centro como si fuera el widget.
2. Implementar la ruta CP `automakerRequestContent.requestContent.*`: valores
   contentURL/on/userDismissible, identidad inmutable de panel/zona, lecturas,
   actualizaciones y el cierre del contenido. El bridge ya ofrece escena y
   batería local, pero no ese contrato de solicitud de contenido; sus escrituras
   de datos del vehículo permanecen rechazadas.
3. Exponer únicamente servicios con esquema verificado y valores reales
   frescos. Para batería, los gauges originales usan
   `highVoltageBattery.batteryLevel.batteryLevel` y
   `highVoltageBattery.batteryRemainingRange`; un widget de charging es una
   interfaz diferente y necesita su propio contrato.
4. Implementar interacción/foco/cierre con confirmación del estado real. Que
   un popover se vea no demuestra que un ajuste de temperatura haya sido
   aplicado. No hay URL de clima confirmado en este inventario.

Para reutilizar gauges originales además faltan las primitivas que sus rutas
ejerciten. La biblioteca incluye texto vectorial mediante
`CP.createAssetFont`, `CP.DPTextFontList`, `CP.DPTextFormat` y `CP.DPTextLayout`;
el bridge actual no las implementa. Esto es un requisito condicional a las
fuentes elegidas: no significa que todos los gauges usen texto vectorial.
Los servicios CP de lectura/suscripción, entradas adicionales, foco y paridad
de render deben verificarse con un fixture del layout elegido. La evidencia
del Wallpaper de cero zonas no cubre esas rutas.

Clima local, widget del iPhone y control mediante Siri requieren verificaciones
distintas. Un panel Android de clima útil puede ser un componente del producto,
pero no prueba que el iPhone reconozca una interfaz Ultra de climatización.

## Reproducción del inventario

`tools/carplay/runtime/widget_layout_inventory.luau` lee exclusivamente
`r/PackageData` y devuelve nombres, geometría y tipos de slots. Ejecutar con el
host local y recursos previamente verificados por `verify_resources` de
`probe_center_layout.py`; no modifica los 193 módulos originales.

El informe completo de esta ejecución está en el área privada de desarrollo:
`.private/c11/ultra-20261019/widget-layout-inventory.json`. La herramienta no
conecta al coche, al iPhone ni a servicios externos.

## Indicador raster local: fixture histórico separado

El recurso 985 configura los slots ChargeFuel como `fuelLevel`,
`fuelLevelState` y `remainingDistance`, no como `chargeLevel` ni
`remainingBatteryDistance` (`r/PackageData.lua`, por ejemplo líneas 237–239,
2178–2180 y 2266). Inyectar batería en CP no convierte esos instrumentos de
combustible en indicadores EV. No se mapea SOC a combustible para forzarlos.

`local_battery_indicator_entry.luau` implementa un destino **local de prueba**
sin activar la UI del producto: wrapper de composición de 320×120, fondo simulado
y un indicador hermano acotado a (20,20), de 280×80. Reutiliza los módulos
originales `MeasurementLabelGauge.newGenericLabel`, `LabelController`,
`ChargeFuelUtils.chargeLevelForUpdate` y la fuente raster central `default`.
El origen continúa siendo `VehicleDataUpdater` original y el servicio CP de
batería. No se modifica ningún módulo original ni el root de un controlador
original; este fixture todavía no se integra con el Wallpaper.

El porcentaje genérico no necesita `chargeLevelState` ni umbrales. Las barras
ChargeFuel de `Layout.lua:2717` sí requieren un StateController: siguen
bloqueadas cuando ese dato OEM no existe. La ruta raster evita las APIs de
texto vectorial condicionales mencionadas arriba.

La redacción original de texto activa `shouldRasterize`
(`APL/TextLayer.lua:291`), que el snapshot rechaza. El fixture mantiene ese
rechazo: usa un adaptador local que devuelve ausencia al recibir
`CP.INVALIDVALUE` y oculta todo el indicador. No muestra 0%, conserva una
lectura visible anterior ni simula el render de la redacción original. Las
capas ocultas también superan la validación completa del snapshot.

`probe_local_battery_indicator.py` verifica los recursos originales antes y
después, ejecuta el fixture con el host nativo y produce seis escenas:
inicial inválido, 75%, 0%, 100%, fuente perdida y 75% restaurado. Comprueba
respectivamente 0/3/2/4/0/3 glyphs visibles y exporta únicamente el PNG de fuente
referenciado. La salida incluye `simulatedValues=true`, `iphoneWidget=false`
y `physicalPresentation=false`; un snapshot correcto todavía no es una
verificación de píxeles. Las escenas se pueden entregar al compositor Android
existente `UltraRenderProbe` para comprobar fondo intacto fuera del recuadro,
texto distinto para 75%/0% y ningún píxel del indicador en ausencia de datos.

La primera prueba Android detectó el signo de porcentaje recortado en el
recuadro inicial. El fixture ampliado usa una escala explícita de 0,5 sobre
la fuente original de 118 píxeles lógicos. La verificación exige que cada
portal completo de glyph tenga al menos 4 píxeles de margen horizontal y
vertical dentro del recuadro; contar glyphs o píxeles modificados no basta.
Las pruebas de regresión rechazan un último glyph recortado y clipping vertical.


### Historical Android label verification, 2026-10-19

The final development APK rendered all six fixture scenes with its actual Android
compositor: no value, 75%, 0%, 100%, source loss and restoration. Pixel checks and
visual review confirm complete characters with four-pixel margins; the missing
states contain only the background and restored 75% is pixel-identical to the
first 75%. This corrected an observed clipped percent sign in the first fixture.
It does not activate the label in the app or establish an Apple widget zone,
phone acceptance, or physical presentation. CI passed with 3,336 registered JUnit
tests (one pre-existing skip), including all seven battery lifecycle/JNI tests.
