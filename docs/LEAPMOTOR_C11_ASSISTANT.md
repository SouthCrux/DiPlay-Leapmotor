# C11 steering-wheel assistant button

The requested default mapping is a short press for the connected platform's
default assistant and a double press for ChatGPT. The user subsequently requested
independent configurable actions for both gestures, including Gemini and Claude.
This iteration targets the connected iPhone: the default assistant is Siri. It
does not add Android Auto support or launch Android apps on the head unit.

OpenAI's [CarPlay guide](https://help.openai.com/en/articles/20001153-using-chatgpt-in-carplay)
documents a "Start automatically in CarPlay" option under Voice in the iPhone
app. This starts voice mode when ChatGPT is opened in CarPlay; it does not, by
itself, provide a head-unit command to open ChatGPT. The guide does not document
a remote app-launch API. A button mapping still needs a verified launch route.

## Verified input route

On the tested firmware, the OEM speech service receives SOME/IP notifications
from service 4161, instance 20545, event group 17920, event 4114. A physical-button
test produced one-byte ASCII `1` and `0` payloads. The OEM handler invokes its
speech interaction for `1`. No Android voice-assistant key event was captured in
that test. Selecting a default Android assistant alone is therefore not a verified
solution for this button.

The DiPlay prototype extends its existing read-only subscriber in the disposable
`:c11telemetry` process. It does not publish vehicle commands or modify the OEM
speech service. Button events are kept separate from battery and range freshness.

## Prototype behavior and limits

The host listens only while resumed, on the explicit C11 vehicle profile, and
with the opt-in enabled under Vehicle > Steering-wheel keys > Steering-wheel
assistant (experimental). It defaults to false. Each gesture independently offers
Default assistant, No action, ChatGPT, Gemini and Claude. Changes apply on returning
to CarPlay without reconnecting. Unknown saved actions fail closed to No action.
An active CarPlay session is required; calls suppress the callbacks.

The decoder requires an observed release after startup or a source reset and
ignores the first second. If no initial release is delivered, the first physical
press only arms the decoder when released. It rejects
stale, out-of-order, future and malformed payloads, ignores repeated down states,
and applies an 80 ms bounce guard. A single press waits 350 ms for a possible
second press. A double press cancels the pending Siri request. Leaving the host
or losing eligibility cancels or suppresses a pending request.

Default assistant uses the existing CarPlay Siri request. Other selections send
an experimental iAP2 RequestAppLaunch (0xEA02) using the minimal legacy form with
the app bundle identifier in parameter 0. No URL scheme is invented. The bundle
identifiers were checked against Apple's App Store lookup API:

| App | App Store ID | Bundle identifier |
| --- | --- | --- |
| ChatGPT | 6448311069 | com.openai.chat |
| Gemini | 6477489729 | com.google.gemini |
| Claude | 6473753684 | com.anthropic.claude |

The existing authenticated runtime metadata channel carries the request; wireless
launches use the AirPlay iAP2 tunnel rather than the Bluetooth bootstrap channel.
An enqueued or transmitted frame is not proof of successful app launch. Phone
unlock state, application compatibility and voice startup remain unverified. The
request might open an app only on the phone. UI copy explicitly states this limit.

The legacy message is catalogued in the repository and independently identified
in [JJTech's iAP2 dissector](https://gist.github.com/JJTech0130/78527c600f7b4d0a7aeb294eab06d8ba).
The exact current-iOS behavior of this minimal request requires a live test; it is
not a verified general CarPlay app-launch API.

The decoder, settings persistence, profile gating and translations have automated
coverage. Compact, full and Arabic RTL layouts are rendered at large font scale.
The private authenticated prototype has been installed in the separate test
package and enabled with one click = Default assistant and double click = ChatGPT.
Live invocation still requires the user's physical-button test. Other installed
CarPlay receivers are preserved. Automated checks passed with 3039 tests (one
skipped); a final repeat also covers the corrected RTL screenshot environment.

OEM applications, private captures, decompiled code, authentication material and
personal identifiers are excluded from the public source tree.

## Audit of installed cockpit-ready APK, 2026-10-20

The installed archive with SHA-256
`e75604beeaa5bab97268ba2ab01ef4d8a736d5eba6fbaf3b132b995c6055c670`
contains the C11 input/settings and experimental iPhone app-request route described
above. Source/DEX review does not confirm the current car preference or successful
phone app/voice launch. The last car access was offline; no key was pressed in this
audit. This mapping does not call the new LeapMoto Python backend. Its Android/voice
destination is not implemented. The passive subscriber also cannot prevent the OEM
speech app from responding or taking focus; that remains a physical test item.

Spanish configuration: Ajustes → Vehículo → Botones del volante → Asistente del
volante (experimental). Enable it, choose Un clic → Asistente predeterminado and
Doble clic → ChatGPT, then return to CarPlay. The existing settings text explains
that the default assistant is Siri on the connected iPhone and the other app must
be installed there. No new button mapping was introduced by the transparent-header
variant or the LeapMoto API work.
