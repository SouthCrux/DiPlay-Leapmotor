import hashlib
import io
import json
import unittest

from refoss_local import GarageError, OneShotDoubleClick, RefossLocal
from refoss_gate_test import ActivationOnce


KEY = "a1" * 16
IDENTITY = "b2" * 16


class Device:
    def __init__(self, contact=1, identity=IDENTITY, fault=None):
        self.contact, self.identity, self.fault = contact, identity, fault
        self.requests = []

    def open(self, request, timeout):
        body = json.loads(request.data)
        self.requests.append(body)
        header = body["header"]
        self.last_method = header["method"]
        if header["namespace"] == "Appliance.System.All":
            payload = {"all": {"system": {"hardware": {"type": "msg100", "uuid": self.identity}}}}
        elif header["method"] == "GET":
            payload = {"state": [{"channel": 0, "open": self.contact}]}
        else:
            if self.fault == "lost_ack":
                raise TimeoutError()
            # A SETACK can still contain the old contact value; it does not prove movement.
            payload = {"state": {"channel": 0, "open": self.contact, "execute": 1}}
        reply = {**header, "method": header["method"] + "ACK"}
        if self.fault == "wrong_id":
            reply["messageId"] = "c3" * 16
        reply["sign"] = hashlib.md5((reply["messageId"] + KEY + str(reply["timestamp"])).encode()).hexdigest()
        if self.fault == "bad_signature":
            reply["sign"] = "0" * 32
        return io.BytesIO(json.dumps({"header": reply, "payload": payload}).encode())


class RefossLocalTest(unittest.TestCase):
    def test_manual_test_consumes_the_action_even_if_the_ack_is_lost(self):
        for fault in (None, "lost_ack"):
            device = Device(fault=fault)
            activation = ActivationOnce(RefossLocal("192.168.50.2", KEY, IDENTITY, opener=device))
            result = activation.activate()
            self.assertTrue(result["controlRequestAttempted"])
            self.assertFalse(result["physicalMovementVerified"])
            self.assertEqual(fault is not None, result["outcomeUnconfirmed"])
            self.assertIsNone(activation.activate())
            self.assertEqual(1, sum(r["header"]["method"] == "SET" for r in device.requests))

    def test_faulty_contact_is_only_used_to_request_one_activation_not_claim_position(self):
        for contact in (0, 1):
            device = Device(contact)
            client = RefossLocal("192.168.50.2", KEY, IDENTITY, opener=device)
            result = client.pulse_once()
            writes = [r for r in device.requests if r["header"]["method"] == "SET"]
            self.assertEqual(1, len(writes))
            self.assertEqual({"state": {"channel": 0, "open": 1 - contact}}, writes[0]["payload"])
            self.assertTrue(result["deviceExecutionReported"])
            self.assertFalse(result["physicalMovementVerified"])

    def test_wrong_target_invalid_auth_and_unknown_contact_cannot_send_a_command(self):
        for device in (Device(identity="c3" * 16), Device(fault="wrong_id"),
                       Device(fault="bad_signature"), Device(contact=2), Device(contact=True)):
            with self.assertRaises(GarageError):
                RefossLocal("192.168.50.2", KEY, IDENTITY, opener=device).pulse_once()
            self.assertFalse(any(r["header"]["method"] == "SET" for r in device.requests))

    def test_unpinned_device_cannot_control_even_with_valid_key(self):
        device = Device()
        with self.assertRaises(GarageError):
            RefossLocal("192.168.50.2", KEY, opener=device).pulse_once()
        self.assertEqual([], device.requests)

    def test_lost_command_acknowledgement_never_retries_the_pulse(self):
        device = Device(fault="lost_ack")
        with self.assertRaises(GarageError):
            RefossLocal("192.168.50.2", KEY, IDENTITY, opener=device).pulse_once()
        self.assertEqual(1, sum(r["header"]["method"] == "SET" for r in device.requests))

    def test_only_one_fresh_double_click_from_unchanged_processes_is_consumed(self):
        guard = OneShotDoubleClick(102, 190)
        for stamp, now, click, same in ((100, 102, "DOUBLE", True), (104, 106, "DOUBLE", True),
                                      (107, 106, "DOUBLE", True), (105, 105, "SINGLE", True),
                                      (105, 105, "DOUBLE", False)):
            self.assertFalse(guard.accept(stamp, now, click, same))
        self.assertTrue(guard.accept(110, 110.5, "DOUBLE", True))
        self.assertFalse(guard.accept(111, 111, "DOUBLE", True))

    def test_expired_capture_cannot_trigger(self):
        guard = OneShotDoubleClick(102, 190)
        self.assertFalse(guard.accept(189.9, 190.1, "DOUBLE", True))


if __name__ == "__main__":
    unittest.main()
