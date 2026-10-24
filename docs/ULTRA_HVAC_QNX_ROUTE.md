# HVAC: BinderPool 5 / QnxUnity route

2026-10-20. Static local review only. No vehicle connection, Binder invocation, setter,
Gradle run, permission change or source integration was performed. This follows
[the independent findings](ULTRA_HVAC_XHIGH_FINDINGS.md).

**Result:** Binder 5 is a guarded Unity-page transport, not a demonstrated admitted HVAC
endpoint. It can reach the existing OEM publisher, but always through command1061 for
its ordinary page messages. Its guard excludes the current real DiPlay/shell identities.
Sending target1409 as a page ID would produce command1061 with text `1409=value`, not
an HVAC command1409. No writer or admission helper was added.

## Sources and identity checks

New targeted source extractions, from the captured CarControl APK classes4.dex:

* `.private/c11/ultra-20261020/hvac-module-review/QnxUnityTransImpl-fallback.java`
* `.private/c11/ultra-20261020/hvac-module-review/IQnxUnityTrans-fallback.java`

`BinderPoolImpl-fallback.java:21–24` delegates the pool transaction to its generated
stub. `queryBinder(5)` returns a new QnxUnityTransImpl (`:40–47`). That does not bypass
the returned Binder's own `onTransact` checks.

`QnxUnityTransImpl:148–207` performs both checks before generated dispatch:

1. `checkCallingOrSelfPermission("com.leapmotor.carcontrol.CAR_CONTROL_SERVICE")`
   must not equal PERMISSION_DENIED. Its manifest declares this permission normal.
2. `getPackagesForUid(getCallingUid())` must contain a nonempty name starting with
   `com.leapmotor` or `com.android.systemui`. It uses the actual caller UID, not a
   supplied package string. There is no shell/root exception in this method.

This is the same captured name-based guard as Binder0. It does not establish an OEM
signature/licensing rule, but it is a concrete server check current DiPlay and shell
cannot satisfy merely by requesting the normal permission. Owner authorization for
vehicle development does not change these package identities. Renaming/impersonating
an OEM client is not proposed.

## Interface and exact wire dispatch

Descriptor: `com.leapmotor.carcontrol.IQnxUnityTrans`.
Generated `IQnxUnityTrans:46–50,262–310` defines synchronous transactions:

| Code | Arguments, in Parcel order after interface token | Operation |
| --- | --- | --- |
|1|String, String|sendData2QnxUnity|
|2|String, String, String|sendData2QnxUnityMsg|
|3|String|sendPic2QnxUnity|
|4|strong callback Binder|setQnxUnityListener|

Methods1–3 are sends; method4 registers a listener and changes server state. There is
no get/status/query/unregister method in this interface. The inherited descriptor
transaction1598968902 has no actuator effect, but it is not a readback API or proof of
HVAC schema. The implementation's guard runs before delegating even descriptor dispatch.
No null-listener experiment is warranted: server registration has no demonstrated
removal contract, and current identity is statically rejected first.

## Destination and message constraints

`QnxUnityTransImpl:210–271`:

* Ordinary `sendData2QnxUnity(pageId,value)` requires combined Java String lengths
  strictly below2048, then calls `MainService.sendSpecialPageMessage2QnxUnity`.
  No null/schema validation or climate range/side check appears here.
* Special page IDs `903` and `1008` concatenate `pageId=value`; encoded bytes must
  be strictly below102400. They send DDS topic `/leap/ivi/public/b/carcontrol/android_pic`,
  type3. The string1008 is a UI transport key, not the passive fan event1008 contract.

`QnxUnityTransImpl:274–327`:

* Ordinary `sendData2QnxUnityMsg(pageId,id,value)` requires combined lengths below2048,
  joins `id+value`, then uses the same special-page helper.
* Special page1007 concatenates `pageId=id=value` and sends the same DDS topic, type4.
  This branch has no corresponding2048 check in the captured method.

`sendPic2QnxUnity` (`:330–340`) sends the supplied string bytes to the DDS topic, type2;
no bound is checked in that method. `setQnxUnityListener` (`:343–348`) only registers
IQnxUnityCallBack in RemoteCallbackList. Static notify methods emit `onUnityClick` and
`onUnityPageChange` UI callbacks and populate notification maps; they do not expose a
confirmed HVAC target or a transaction-specific actuator acknowledgement.

`MainService-fallback.java:6038–6047` constructs `pageId=value` and calls
`sendCarControlEventToQnx(1061,text)`. That helper (`:5803–5840`) builds CarControlEvent
with `appName="0"`, nested Event `event="1061"`, `value=text`, and publishes event15001
through `SomeIpService.mPub`. The existing publisher owner/route is the one identified
in the independent review:0x7101/0x7102/group0x7100, not a new service offered by DiPlay.

This establishes a route to the publisher for UI pages, not an HVAC parser on the
receiving QNX side. No captured1061 page grammar maps to temperature1409/1410, and
there is no evidence that appName0 page transport equals appName1 audio command handling.
Thus both admission and an HVAC-specific schema remain absent for Binder5.

## Captured client behavior

The already captured SpeechAdapter SDK `CarControlManager` source
(`/private/tmp/diplay-SpeechCarControlSDK-local.java:404–425`) queries pool index5 and
calls IQnxUnityTrans.Stub.asInterface. Wrappers at495–592 forward all four methods,
checking only that the pool is connected and swallowing/logging exceptions. They do
not add permission, choose an HVAC page, translate1409/1410, or check vehicle readback.
An SDK class in the voice APK is not proof the voice domain invokes these methods for
HVAC. Targeted rg of already decompiled captured clients found no concrete HVAC caller
of sendData2QnxUnity/sendData2QnxUnityMsg. No claim is made about every undecompiled APK.

## Next concrete work and stop criterion

Binder5 is now resolved for the captured implementation: no useful HVAC admission probe
or writer should be built from it. Merely binding/querying5 or displaying a descriptor
would not remove either identified obstacle. Revisit only with an authorized real caller
and a captured QNX page-to-HVAC contract; otherwise stop this branch.

The next discriminating implementation is a **separate bounded shell VHAL SET experiment**,
coordinated by root after passive11205/11206 side/readback calibration. The invalid GET
result disproves valid GET targets on this build; it does not prove SET has no effect.
The Java SET path is independent of GET, as established by the independent review.
Do not loosen the production controller's validation or advertise Siri to run that test.

A helper, if separately approved by root, would reuse the existing shell UID/permission/
config ownership validation, whitelist only358614275 + areas49/68, require an explicit
integer value already tested manually in the relevant physical zone, perform at most
one SET with a deadline and final permission check, and report only submission outcome.
Only the independent passive observer plus visible OEM control change can establish
actuation. No exception is not success; a new matching callback alone can still be a
replay/echo. A return-to-baseline operation is a separate explicit action, not an
unbounded retry. Current task did not authorize or implement that writer.

For product integration, a positive shell experiment would prove a firmware path under
UID2000, not permission/admission for the DiPlay UID. An authorized OEM broker/build or
supported integration still has to solve that separately. Unknown client APIs in other
SDKs/builds remain unexplored, not disproven. No second OEM SomeIP offer, invented request
method, UI click automation or package masquerade is proposed.
