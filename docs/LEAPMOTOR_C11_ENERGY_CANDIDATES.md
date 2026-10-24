# C11: candidatos de corriente, voltaje y potencia

Revisión local, 2026-10-23. Inventario de investigación, sin conexión al coche.
Estos identificadores no se incorporan todavía a las suscripciones de DiPlay.

## Evidencia disponible

Fuente: `CanEvent.java` del CarControl OEM extraído, en
`.private/c11/20261016T005916Z/decompiled/carcontrol/sources/com/leapmotor/can/external/`.
Los nombres y números siguientes están presentes como constantes. Su existencia
no demuestra que sean eventos SOME/IP ni que el firmware los publique.

| ID | Constante OEM, sin prefijo `OPCODE_` | Lectura candidata |
| --- | --- | --- |
| 5800 | BMS_BattVolt | Voltaje de batería |
| 5801 | BMS_BattCurr | Corriente de batería |
| 6108 | OBC_ACVolt | Voltaje AC del cargador |
| 6109 | OBC_ACCurr | Corriente AC del cargador |
| 6105 | OBC_DCVolt | Voltaje DC del cargador |
| 6107 | OBC_DCCurr | Corriente DC del cargador |
| 5806 | BMS_BattMaxChargePower | Límite de potencia de carga |
| 5802 | BMS_BattMaxDischargePower | Límite de potencia de descarga |
| 5900 | BMS_MaxChargeVolt | Límite de voltaje de carga |
| 5901 | BMS_MaxChargeCurr | Límite de corriente de carga |
| 5903 | BMS_MaxPower | Potencia máxima, significado pendiente |
| 6208 | OBC_ACMaxPower | Potencia AC máxima |

La búsqueda por los cuatro nombres BMS_BattVolt, BMS_BattCurr, OBC_ACVolt y
OBC_ACCurr en los fuentes Java decompilados de CarControl sólo encontró las
declaraciones. Tampoco se identificaron los seis IDs de voltaje/corriente en
los fragmentos fallback revisados ni en `LeapCenterManager-tpms.java`.
Esta búsqueda es parcial: no prueba ausencia en otros servicios o código nativo.

## Pendiente antes de mostrar valores

1. Localizar un registro y consumidor de recepción: servicio, instancia, grupo,
   identificador, tipo y longitud del payload. No reutilizar automáticamente
   la ruta CAN SOME/IP de otros módulos ni interpretar bytes como INT32.
2. Confirmar unidad, escala, offset, valores inválidos y signo de corriente.
   Diferenciar magnitudes de batería, entrada AC y salida DC del cargador.
3. Contrastar muestras fechadas con la pantalla OEM durante una carga real.
   Las constantes de potencia máxima no equivalen a potencia instantánea.
4. Sólo derivar potencia de batería con voltaje y corriente validados y
   temporalmente compatibles. Esa magnitud no equivale al consumo de la red;
   el producto de voltaje y corriente AC tampoco demuestra potencia activa.

No se calcula potencia desde cambios de porcentaje, no se inventan muestras
de cero y no se añaden comandos de carga. Los 91 canales ya preparados se
mantienen; este inventario no cuenta como nuevas lecturas implementadas.
