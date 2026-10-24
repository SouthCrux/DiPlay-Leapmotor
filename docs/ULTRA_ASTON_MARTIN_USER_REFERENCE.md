# Referencia del usuario: experiencia CarPlay Ultra Aston Martin

Guardada el 2026-10-20 a petición del propietario. El bloque siguiente reproduce la referencia facilitada en la conversación: **sus cifras, requisitos y funciones no se han verificado como especificación oficial**. Sirve como objetivo de experiencia, no como evidencia de compatibilidad del C11 ni como estado de implementación.

Para el proyecto se conservan los instrumentos OEM y se busca una zona contextual en el espacio disponible del mapa; no se presupone reemplazo completo del cuadro. Combustible/RPM y modos específicos Aston Martin se adaptarán sólo cuando exista una función C11 equivalente real. La preferencia del usuario sigue siendo **no iniciar DiPlay al conectar Bluetooth**. La secuencia de mantenimiento y las tarjetas contextuales añadidas después están en [ULTRA_CONTEXTUAL_COCKPIT.md](ULTRA_CONTEXTUAL_COCKPIT.md).

## Texto aportado

```text
Carplay Ultra Aston Martin Integration:
================================================================================
GUÍA COMPLETA Y DETALLADA DE FUNCIONALIDADES: APPLE CARPLAY ULTRA (ASTON MARTIN)
================================================================================

1. ARQUITECTURA DE PANTALLAS E INTEGRACIÓN INTEGRAL (DIGITAL COCKPIT)
--------------------------------------------------------------------------------
CarPlay Ultra unifica la interfaz mezclando tres entornos: "Local UI" (datos en
tiempo real procesados por el auto), "Mirrored" (apps desde el iPhone) y
"Punch-through UI" (menús complejos nativos integrados visualmente).

* Pantalla Central Infotainment (10.25” Pure Black Touchscreen):
  - Matriz táctil con soporte gestual multitáctil (capacitiva de varios dedos).
  - Barra lateral persistente con accesos rápidos fijos a: Home (Apple),
    Climatización (Climate), Radio y Mapas.
* Cuadro de Instrumentos Principal (Detrás del Volante):
  - Control absoluto del panel digital por parte del ecosistema Apple.
  - Lectura en tiempo real integrada: Velocímetro digital, Tacómetro (RPM),
    Indicador de marcha seleccionada, Nivel de combustible y Autonomía.
  - Alertas del sistema de asistencia ADAS integradas con tipografía de Apple.
  - Pop-ups dinámicos sobre el estado del auto (ej. Presión de neumáticos baja).

2. PANEL DE INSTRUMENTOS PERSONALIZABLE (ESTILOS Y TEMAS VARIADOS)
--------------------------------------------------------------------------------
Cuenta con más de 150 variaciones de diseño gestionables desde la app de Ajustes
de CarPlay:

* Estilos de Esferas y Diales Principales:
  - Diseño "Twin-Roundel" (Doble esfera tradicional): Velocímetro y tacómetro
    clásicos con estética Aston Martin Racing Green.
  - Diseño "Power Bar": Gráficos horizontales modernos y minimalistas de barras
    de color al estilo Apple.
  - Vista Extendida "Full Map": Expande Apple Maps (o el navegador por defecto)
    a pantalla completa por detrás del volante, encogiendo la velocidad y las
    revoluciones al borde inferior de la pantalla.
* Paletas de Colores y Fondos (9 Variantes Oficiales):
  - Azul, Rojo, Amarillo, Verde, Midnight, Starlight, Rose, entre otros.
  - Fondos de pantalla adaptativos coordinados con el color de la carrocería o
    el diseño interior.
  - Fondos de reproducción multimedia difuminados en tiempo real según el álbum.

3. CONTROL INTEGRADO DE CLIMATIZACIÓN Y BIENESTAR
--------------------------------------------------------------------------------
No se requiere salir de la interfaz de CarPlay para cambiar los valores térmicos:

* Ajustes digitales en pantalla central:
  - Selector digital de temperatura independiente (Conductor / Acompañante).
  - Control de velocidad del ventilador e iconos de dirección del flujo de aire
    (parabrisas, torso, pies).
  - Encendido/Apagado de aire acondicionado (A/C) y recirculación.
* Accesos Directos Inteligentes en pantalla: Shortcuts para activar calefacción
  máxima o desempañado.
* Climatización de asientos: Activación de calefacción y ventilación de butacas
  desde submenús rápidos de CarPlay.

4. CONTROL MECÁNICO, MODOS DE CONDUCCIÓN Y ASISTENCIA
--------------------------------------------------------------------------------
* Configuración Dinámica del Vehículo:
  - Visualización y selección de Modos de Conducción (Sport, Sport+, GT,
    Individual) desde el entorno digital Apple.
  - Menú de personalización del Modo "Individual" (Ajuste de respuesta del
    motor y dureza de la dirección).
* Seguridad y ADAS (Sistemas Avanzados de Asistencia):
  - Configuración y switch de preferencias del control de crucero adaptativo y
    asistente de carril.
  - Estado gráfico de los sensores de punto ciego.
* Cámaras y Ayuda de Estacionamiento:
  - Despliegue de la cámara de entorno 3D directamente dentro del visor Apple.
  - Control táctil para rotar la cámara periférica alrededor del modelo 3D del
    vehículo en pantalla central.
  - Modo específico "Towbar View" (Vista de remolque/enganche) habilitado en el
    SUV Aston Martin DBX707.

5. CONTROL MULTIMEDIA Y AUDIO NATIVO
--------------------------------------------------------------------------------
* Radio Integrada:
  - Sintonizador AM/FM y Radio Digital integrado de manera interna (deja de ser
    una app externa del fabricante).
  - Gestión y almacenamiento de emisoras favoritas directamente en CarPlay.
* Ecualización Avanzada de Sonido:
  - Ajustes de ecualizador gráfico (Agudos, Medios, Graves).
  - Soporte exclusivo y perfiles de audio dedicados para los sistemas premium
    Bowers & Wilkins (Ajustes de escenario de audio y sonido envolvente).

6. INTERACCIÓN, BOTONES FÍSICOS Y MÉTODOS DE CONTROL
--------------------------------------------------------------------------------
El sistema opera en un balance entre pantalla, voz y botones físicos del coche:

* Mandos Físicos en la Consola Central (Aston Martin Touchpoints):
  - Interruptores/Selectores mecánicos para regular la climatización e info de
    audio (al moverlos, CarPlay Ultra muestra un overlay/notificación gráfica
    flotante informando el cambio).
  - Switches mecánicos de anulación (override) para Chasis, ESP (Control de
    estabilidad), Válvula de Escape, Lane Assist y Park Distance Control.
* Controles en el Volante (Mandos integrados):
  - Almohadilla táctil/Thumb pad del lado izquierdo del volante:
    * Deslizar Izquierda / Derecha: Cambia los diales del cuadro de
      instrumentos (como cambiar carátulas de un Apple Watch).
    * Deslizar Arriba / Abajo: Cambia el widget del cuadrado central del panel
      (itera entre Apple Maps, Reproductor de audio actual, Presión de
      neumáticos y Datos de viaje/Consumos).
* Control por Voz Avanzado:
  - Siri e integración con Apple Intelligence: Permite usar comandos naturales
    en lenguaje común para cambiar la temperatura del auto, abrir aplicaciones de
    terceros, dictar mensajes o pedir indicaciones específicas.

7. REQUISITOS TÉCNICOS Y CONECTIVIDAD
--------------------------------------------------------------------------------
* Dispositivo compatible: Requiere un iPhone 12 o modelo posterior.
* Sistema Operativo mínimo: iOS 18.5 o superior.
* Vinculación inicial: Requiere un emparejamiento único inicial a través de
  cable o wireless. Posterior a esto, la activación de CarPlay Ultra en todas las
  pantallas es automática en milisegundos tras accionar el botón de encendido del
  motor del auto.
================================================================================
```
