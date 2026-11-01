"""Signed MSG100 LAN operations. A pulse is not evidence of physical gate position.

Protocol references: the owner's Refoss APK and the Meross LAN garage implementation.
No pairing, cloud login, configuration writes, automatic command retry or key replay.
"""
import hashlib
import hmac
import ipaddress
import json
import re
import secrets
import time
import urllib.request

from refoss_auth import NoRedirect


class GarageError(Exception):
    pass


class RefossLocal:
    def __init__(self, address, key, expected_id=None, *, opener=None):
        ip = ipaddress.ip_address(address)
        if not any(ip in ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
            raise GarageError("Not a private LAN target")
        if not re.fullmatch(r"[a-fA-F0-9]{32}", key):
            raise GarageError("Invalid key format")
        if expected_id is not None and not re.fullmatch(r"[a-fA-F0-9]{32}", expected_id):
            raise GarageError("Invalid target identity")
        self.address, self._key, self.expected_id = address, key, expected_id
        self.control_request_attempted = False
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def _signature(self, identifier, stamp):
        return hashlib.md5((identifier + self._key + str(stamp)).encode()).hexdigest()

    def _exchange(self, namespace, method, payload):
        identifier, stamp = secrets.token_hex(16), int(time.time())
        header = {"messageId": identifier, "namespace": namespace, "method": method, "payloadVersion": 1,
                  "timestamp": stamp, "timestampMs": 0, "from": "/app/subscribe",
                  "sign": self._signature(identifier, stamp)}
        request = urllib.request.Request("http://" + self.address + "/config",
            data=json.dumps({"header": header, "payload": payload}, separators=(",", ":")).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        # Exactly one HTTP attempt, including for a command whose acknowledgement is lost.
        try:
            with self.opener.open(request, timeout=4) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise GarageError("Response too large")
            body = json.loads(raw)
            reply = body["header"]
            if reply.get("messageId") != identifier or reply.get("namespace") != namespace:
                raise GarageError("Reply correlation failed")
            if type(reply.get("timestamp")) is not int:
                raise GarageError("Invalid reply time")
            signature = reply.get("sign")
            if not isinstance(signature, str) or not signature.isascii() or not hmac.compare_digest(
                    signature, self._signature(identifier, reply["timestamp"])):
                raise GarageError("Reply authentication failed")
            if reply.get("method") != method + "ACK":
                raise GarageError("Device rejected request")
            return reply, body["payload"]
        except GarageError:
            raise
        except Exception:
            raise GarageError("No verified device response") from None

    def identify(self):
        header, payload = self._exchange("Appliance.System.All", "GET", {})
        try:
            hardware = payload["all"]["system"]["hardware"]
            if str(hardware["type"]).lower() != "msg100":
                raise GarageError("Unexpected hardware model")
            identity = hardware.get("uuid")
            if identity is None:
                match = re.fullmatch(r"/appliance/([a-fA-F0-9]{32})/publish", header.get("from", ""))
                identity = match[1] if match else None
            if not isinstance(identity, str) or not re.fullmatch(r"[a-fA-F0-9]{32}", identity):
                raise GarageError("No stable target identity")
            if self.expected_id is not None and identity != self.expected_id:
                raise GarageError("Wrong target device")
            return identity
        except (KeyError, TypeError):
            raise GarageError("Invalid device identity reply") from None

    @staticmethod
    def _single_channel(payload):
        states = payload.get("state")
        if isinstance(states, dict):
            states = [states]
        if not isinstance(states, list):
            raise GarageError("Invalid contact reply")
        matches = [s for s in states if isinstance(s, dict) and type(s.get("channel")) is int and s["channel"] == 0]
        if len(matches) != 1:
            raise GarageError("Missing or ambiguous contact channel")
        return matches[0]

    def read_contact(self):
        _, payload = self._exchange("Appliance.GarageDoor.State", "GET", {"state": [{"channel": 0}]})
        value = self._single_channel(payload).get("open")
        if type(value) is not int or value not in (0, 1):
            raise GarageError("Unknown contact value")
        return value

    def pulse_once(self):
        if self.expected_id is None:
            raise GarageError("No pinned device; control disabled")
        self.identify()
        contact = self.read_contact()
        # MSG100 exposes desired state, not a raw relay API. Asking for the opposite
        # of its contact reading requests an activation, even when that reading does
        # not represent the physical gate position. It is never labelled open/close success.
        self.control_request_attempted = True
        _, payload = self._exchange("Appliance.GarageDoor.State", "SET",
                                    {"state": {"channel": 0, "open": 1 - contact}})
        execution = self._single_channel(payload).get("execute")
        if type(execution) is not int or execution not in (0, 1):
            raise GarageError("Activation acknowledgement unknown; do not retry")
        return {"commandAcknowledged": True, "deviceExecutionReported": bool(execution),
                "physicalMovementVerified": False}


class OneShotDoubleClick:
    """Consume one fresh live event; all timestamps use the calibrated device epoch."""
    def __init__(self, armed_after, expires_at):
        self.armed_after, self.expires_at = armed_after, expires_at
        self.consumed = False

    def accept(self, event_time, device_now, classification, same_processes):
        if self.consumed or not same_processes or classification != "DOUBLE":
            return False
        if not self.armed_after <= event_time <= self.expires_at or device_now > self.expires_at:
            return False
        if not 0 <= device_now - event_time <= 1.0:
            return False
        # Mark consumed before any network activity, so timeout or lost ACK cannot retry.
        self.consumed = True
        return True
