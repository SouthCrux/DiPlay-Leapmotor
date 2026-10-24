# Resource negotiation and selected central topology

Development status on 2026-10-19. No car/iPhone connection was made for this work.

## Resource evidence

A fresh request to [Apple's asset catalog](https://gdmf.apple.com/v2/assets), using
LIVI's generic reference request, returned four entries including `44C9FA31EC`
version 985. The query uses no device credentials or personal identifiers. This
is a catalog observation under a generic profile, not independent verification
of the catalog JWS or compatibility with the owner's particular iPhone.

The [Single package](https://updates.cdn-apple.com/MobileAssets2026/mobileassets/140-40687/F88F06C5-4B15-445E-B43D-36412420D116/com_apple_MobileAsset_CarDDPAssets/ec94d2ff7ae8ab2f10f410d06e8e364ad52a415d.zip)
`44C9FA31EC-Single` version 547 was inspected through bounded ZIP byte ranges:
130,624 bytes of network data, metadata only, no scripts executed. It still
declares **Cluster_Display and Center_Display**. Its cluster adds SingleDigiRing,
SingleDigiBandTicks and SingleDigiBand layouts; its center retains Overlay and
Wallpaper at 1920×720. Its name does not establish a center-only topology, and
the selected resource was not switched.

The previously inspected accessory signature/chain and leaf expiry remain
separate evidence. Neither catalog presence nor a current-date chain check
decides the iPhone's asset acceptance policy. No replacement certificates or
guessed transfer channel are introduced.

## Explicit central candidate

`UltraThemeBinding` can now select the center without a cluster. It atomically
sets the selected main geometry and removes an inherited alternate stream.
Metadata contains one center video stream, one matching panel and controls
pointing to that center, with no synthetic panel, cluster plug-in or instrument
capability. Configuration copies cannot silently add a cluster or change canvas.

This is internally coherent, **not an iPhone-accepted topology**. Pinned LIVI
adds a diagnostic plug-in panel when no themed cluster exists; this candidate
deliberately avoids advertising that unimplemented extra panel. Whether the
iPhone permits the reduced set remains open. The existing C11 setting retains
its earlier two-display binding. Neither normal CarPlay nor Activity startup
automatically selects the new candidate.

`check_theme_contract.py --center-only` validates the explicit selection against
package metadata. It distinguishes `metadataMatches` from
`topologyAcceptanceVerified` and `phoneAcceptanceVerified`. The default checker
continues to require both resource displays.

## Evidence needed from a real session

| Observation | Establishes | Does not establish |
| --- | --- | --- |
| iAP2 identification/start has the real resource descriptor | Our resource declaration | Phone acceptance/download/provision |
| `themeAssets` availability | Optional availability state | Acceptance of an ID/version: neither is in that field |
| Selected `uiSync` feature | Negotiated transport feature | A loaded theme or displayed scene |
| Reset/reset-complete | Protocol initialization | Resource acceptance or presentation |
| Sent layout request / receipt ACK | Local request / transport receipt | Applied visual state |
| Incoming start/end-layout matches the request | Peer response for that layout in this channel | Signature policy, cache versus transfer or physical output |
| Latched phone frame + applied scene + actual output | Visual integration for that session | Reconnect/endurance or other layouts |

Diagnostics record configured topology and selected UI Sync with explicit
acceptance-unverified labels. Script-owner traces distinguish sent/received
message kinds, layout ID presence and correlation with the last requested UUID
(`matchesRequest`, `differsFromRequest`, `noRequestObserved`). Identifiers and
message bodies are not exported. Output is limited to 32 distinct observations
per channel; diagnostic callbacks receive copies and cannot change protocol bytes
or terminate ownership. None of these diagnostics automatically marks a package
accepted. A missing download prompt also does not decide acceptance or rejection.

Private catalog, metadata and comparison reports are under
`.private/c11/ultra-20261019/`. Public builds gain neither proprietary resources
nor authentication material. Actual phone acceptance awaits authorized hardware
access with ordinary CarPlay recovery available.
