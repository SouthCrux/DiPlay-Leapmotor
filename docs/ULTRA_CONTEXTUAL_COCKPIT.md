# C11 contextual cockpit: requested scope

Date: 2026-10-20. This document records product requirements from the project conversation,
including the coordinating agent's handoff of the latest user additions. It is not a transcript
of the separate Aston Martin guide, a verified specification for Aston Martin or Leapmotor, or
a claim that these features are implemented. The original guide is retained separately by the
coordinating agent.

## Preserve the OEM instrument cluster

The requested result does **not** replace the whole instrument cluster. Preserve the OEM speed,
warnings, safety indicators and other vehicle instruments. Use only the verified navigation
region for contextual content. Do not expand the region merely because a card needs more space.

The user wants an intelligent map area that can present navigation, vehicle battery information
and other useful cards according to context. Navigation should offer an arrow and, when genuine
map data is available, a reduced perspective map of the upcoming road. Upcoming turns, where to
turn, cameras, speed bumps, accidents and highway cards are desired when supported by a real
data source. Their presence in Google Maps or another reference does not establish that the
iPhone exports those fields through the current CarPlay interface.

The initial interpretation should select and format real available information. It must not
invent road geometry, incidents, maneuver sequences or vehicle conditions. A maneuver diagram
and a geographic map must remain distinguishable. Battery information refers to the C11 traction
battery, not the iPhone battery. This is an EV adaptation for the C11, not a direct transplant
of another manufacturer's fuel or instrument model.

## Maintenance sequence at startup

The user requests a startup sequence showing maintenance items such as the pollen/cabin filter,
brakes and coolant, including how much remains before attention is due.

Only show a remaining distance, time or condition when it comes from one of these identified
sources:

- A verified vehicle maintenance/service signal with established units and validity.
- A verified maintenance interval applicable to this C11 variant, combined with a known service
  baseline and current odometer/date.
- A manual maintenance record, explicitly identified as manual, with its entered service date,
  odometer and applicable interval provenance.

An unknown last service cannot support a countdown. An interval for a different model cannot be
silently substituted. Odometer alone cannot establish brake-pad condition; a calendar countdown
cannot be presented as measured coolant or filter condition. If the necessary evidence is
missing, show the item as unknown or awaiting a record rather than manufacture a remaining life.
Manual records must not masquerade as measured OEM data.

This sequence is requested for a normal startup of DiPlay. It does **not** authorize automatic
launch when the iPhone connects over Bluetooth. The user's preference to keep Bluetooth
auto-launch disabled remains in force. The sequence must not obscure OEM warnings or interrupt
an active maneuver; exact duration and dismissal behavior remain design decisions to validate.

## Context and priorities

These priorities translate the user's stated goal into a proposed presentation policy. They
are requirements for future implementation, not a description of a current scheduler:

1. Preserve OEM safety information and the bounded display destination at all times.
2. During active guidance, give the next relevant maneuver priority in the navigation region.
3. Present genuine charging/battery information when relevant to route feasibility, without
   replacing an imminent maneuver with a lower-priority card.
4. Show other supported contextual cards only while their data remains valid and space permits.
5. Present the maintenance sequence at an appropriate startup opportunity; do not let it take
   over ongoing guidance or repeated reconnections.

Core vehicle modules and their data contracts remain prerequisites. The separate LeapMoto
assistant work is not required to decode or display a verified turn, SOC or maintenance record.
The initial contextual policy can be deterministic. A later assistant may explain or recommend
actions based on these same validated inputs, without becoming the source of vehicle facts.

## Inputs required before implementation can be claimed

| Capability | Necessary evidence/source | Current boundary |
| --- | --- | --- |
| Cluster destination | Current-boot display identity, actual mode/geometry, overlay permission and verified navigation tile | Reuse existing bounded destination; no full-cluster replacement |
| Turn card | Current structured navigation session, maneuver, distance and road fields | Existing interpreted-card work is documented separately |
| Reduced perspective map | Actual map video, or licensed route geometry/map rendering with position and camera | Turn text alone is insufficient |
| Incidents, cameras and speed bumps | Typed provider events with position, direction/relevance, freshness and permitted use | No parity assumed from the navigator's own UI |
| Highway cards | Real upcoming exits/services and relationship to the active route | No cards generated from guesses |
| C11 battery/charging context | Verified SOC/range/charging state, units, source lifetime and route context | Phone battery is not a substitute |
| Filter, brakes and coolant maintenance | Applicable verified schedule/vehicle state or identified manual service record; known baseline | No invented service intervals or wear measurements |
| Safe fallback | Expiry, teardown and cancellation rules for each input and the destination | Unavailable data cannot leave an indefinitely current-looking card |

The implemented navigation boundary and its pending physical validation are in
[ULTRA_COCKPIT_NAVIGATION.md](ULTRA_COCKPIT_NAVIGATION.md). Vehicle integration status is tracked
in [LEAPMOTOR_C11_ULTRA_STATUS.md](LEAPMOTOR_C11_ULTRA_STATUS.md). This document does not override
those implementation/evidence records.

## Acceptance evidence for a future version

- With a live route, verify actual turn, distance and road against the navigator; verify that
  cancellation, arrival, source loss and expiry remove stale guidance.
- Verify that a battery or maintenance card never covers the OEM instrument/warning regions
  or takes priority over an imminent maneuver.
- Validate maintenance countdowns with known source records; confirm that missing baselines
  remain unknown and that manual entries are visibly identified.
- Confirm that a Bluetooth connection alone does not launch DiPlay or its maintenance sequence.
- Check actual cluster legibility and card transitions on the car while parked before accepting
  a visual design. A build or simulator screenshot alone does not establish physical fit.

Documentation only: no source code, settings, vehicle configuration or tests were changed by
this scope capture.

## Implemented battery fallback increment (2026-10-20)

The existing C11 navigation tile now has a local traction-battery fallback. It shows the real
cached vehicle SOC and range only when the host supplies a valid reading, the driver selected
map-with-custom-card content, the eligible host/window is visible, and neither an active map
stream nor a semantic navigation instruction occupies the tile. A real map or instruction always
wins immediately. It does not change the OEM instrument canvas, physical destination, route
verification, overlay permission, connection preferences or explicit MAP-only selection.

`LeapmotorClusterMapOverlay.setVehicleSnapshot()` accepts a publication from the host's already
leased telemetry cache. It never acquires a telemetry source. Null/invalid publications remove
the card. Hiding or retiring the window clears the publication and rejects late updates while
hidden; showing the window again needs a fresh host publication. Each accepted publication also
expires after five seconds without a host refresh, independently of the existing telemetry
cache's silence policy. The host's visible configuration poll runs every two seconds. This
publication timeout is a presentation lifetime, not a newly established OEM sensor sampling rate.
The underlying cache still describes last received state, not a guaranteed instantaneous BMS
measurement.

The card uses existing localized battery and kilometre labels. It renders no inferred charging
state, reserve threshold, battery health, charger prediction or maintenance countdown. Maintenance
and the startup service sequence still require actual service records/data contracts; no empty
maintenance panel or guessed interval was added.

`ClusterVehicleCardScreenshotsTest` generates compact/full and Arabic RTL views with font scale
1.5, using synthetic test readings only, in `common/build/reports/ultra-contextual-cockpit/`.
`LeapmotorClusterDisplayTest` covers navigation priority, publication expiry, visibility retirement
and rejection of late hidden-window publications. The coordinating agent owns Host wiring, the
required CI run and inspection of generated images. No installation or physical acceptance is
claimed by this increment: current-boot destination provisioning and an actual parked-car check
remain necessary.
