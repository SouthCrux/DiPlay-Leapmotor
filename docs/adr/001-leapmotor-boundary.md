# ADR-001: Límite independiente de integración Leapmotor

**Status:** Accepted (límite inicial; transporte C11 pendiente de evidencia)
**Date:** 2026-10-16
**Deciders:** Trabajo del fork C11 solicitado por su propietario

## Context

DiPlay está orientado a BYD. Sus salidas OEM y lectura de batería usan interfaces
propietarias BYD. El objetivo C11 incluye tres pantallas, estado y HVAC, pero esta
tarea excluye acceso/modificaciones al vehículo. El aviso Ultra no demuestra soporte.
La [investigación](../LEAPMOTOR_C11.md) detalla las fuentes y brechas.

## Decision

Crear un proveedor Leapmotor de datos normalizados que implemente
`VehicleStatusProvider`, aislado de I/O y sin activación automática. Reutilizar la
codificación iAP2 existente. Mantener desconocido como ausencia de lectura y exigir
caducidad explícita. Posponer selección runtime, displays y comandos hasta verificar
las interfaces C11. La selección runtime inicial es una salida OEM vacía para el
perfil C11 explícito del manifest de `mobile`; la fuente EV sigue sin conectarse.
HVAC necesitará otro contrato; no cabe en el modelo EV actual.

## Options Considered

| Opción | Complejidad | Coste | Escalabilidad | Familiaridad |
| --- | --- | --- | --- | --- |
| Reutilizar bridges/IDs BYD | Baja al principio, comportamiento sin verificar | Riesgo de actuar sobre el servicio equivocado | Mala separación OEM | Código conocido, semántica C11 desconocida |
| Proveedor Leapmotor normalizado | Pequeña base; transporte aún pendiente | Tests y validación de fuente | Aísla OEM del protocolo iAP2 | Contrato existente en el repositorio |
| Implementar todo Ultra ahora | Alta, protocolo incompleto | Sin estimación defendible | Exige rediseño de pantallas y composición | Faltan especificación y evidencia |

La primera opción reutiliza código, pero no tiene evidencia de compatibilidad.
La segunda permite probar el límite sin inventar direcciones de servicios.
La tercera persigue el objetivo final, pero no ofrece hoy una implementación verificable.

## Trade-off Analysis

El proveedor separado hace verificables frescura, unidades y ausencia de datos.
Introduce código aún no conectado y no produce funcionalidad C11 visible por sí solo.
Es preferible a anunciar una capacidad que no puede servir. No cambia la conducta
BYD existente; el aislamiento completo del runtime sigue pendiente.

## Consequences

- Se pueden reproducir lecturas EV y mensajes iAP2 sin coche ni secretos.
- Debe verificarse y conectarse una fuente C11 antes de habilitar estado EV.
- Múltiples pantallas, estado Ultra y HVAC necesitan trabajos separados.
- La configuración de conectores no se deduce de la marca ni del idioma del sistema.

## Action Items

1. [x] Proveedor sin I/O y pruebas de integración con codificación/identificación iAP2.
2. [x] Salida OEM inyectable en el controlador; perfil C11 sin workers BYD y tarjeta
   de navegación independiente del transporte OEM.
3. [ ] Fuente C11 verificada e inyección sin fallback BYD.
4. [ ] Retirar/proteger diagnósticos BYD manuales y rutas DiLink del perfil C11.
5. [ ] Ruta de mapas Leapmotor con geometría y permisos comprobados.
6. [ ] Negociación y arquitectura Ultra completas, incluida tercera pantalla.
7. [ ] Lectura HVAC y contrato de acciones respaldados por evidencia C11.
