import base64
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
import urllib.error
import urllib.parse

from refoss_auth import AuthorizationError, NoRedirect, authorize, make_request, save_key, valid_submission


class Opener:
    def __init__(self, data):
        self.data = data
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return io.BytesIO(self.data)


class RefossAuthorizationTest(unittest.TestCase):
    def test_safari_null_or_missing_origin_still_requires_the_secret_form_token(self):
        expected = "http://127.0.0.1:54346"
        for origin in (None, "null", expected):
            for fetch in (None, "same-origin"):
                headers = {"Host": "127.0.0.1:54346"}
                if origin is not None:
                    headers["Origin"] = origin
                if fetch is not None:
                    headers["Sec-Fetch-Site"] = fetch
                self.assertTrue(valid_submission(expected, headers, {"csrf": "secret-form-token"}, "secret-form-token"))
                for fields in ({}, {"csrf": "old-token"}, {"csrf": ""}):
                    self.assertFalse(valid_submission(expected, headers, fields, "secret-form-token"))

    def test_foreign_origin_rebinding_host_and_cross_site_fetch_are_rejected_even_with_token(self):
        expected = "http://127.0.0.1:54346"
        for extra in ({"Origin": "https://other.invalid"}, {"Origin": "http://127.0.0.1:54347"},
                      {"Host": "attacker.invalid:54346"}, {"Sec-Fetch-Site": "cross-site"},
                      {"Sec-Fetch-Site": "same-site"}):
            headers = {"Host": "127.0.0.1:54346", **extra}
            self.assertFalse(valid_submission(expected, headers, {"csrf": "secret"}, "secret"))

    def test_request_is_only_sign_in_and_does_not_transmit_plaintext_password(self):
        request = make_request("iotx.refoss.net", "test@example.invalid", "private-test-password",
                               "UY", "123456", "synthetic-app-value", timestamp=1700000000, nonce="fixed-test-nonce")
        self.assertEqual("https://iotx.refoss.net/v1/Auth/signIn", request.full_url)
        self.assertEqual("POST", request.method)
        fields = urllib.parse.parse_qs(request.data.decode())
        params = json.loads(base64.b64decode(fields["params"][0]))
        self.assertNotIn(b"private-test-password", request.data)
        self.assertEqual("test@example.invalid", params["email"])
        self.assertEqual(32, len(params["password"]))
        self.assertEqual(1, params["encryption"])
        self.assertEqual("123456", params["mfaCode"])
        self.assertEqual({"params", "sign", "timestamp", "nonce"}, set(fields))

    def test_only_exact_vendor_hosts_are_allowed_before_network_access(self):
        for host in ("iotx.refoss.net.attacker.invalid", "http://iotx.refoss.net", "iotx.refoss.net:80", "localhost"):
            opener = Opener(b"{}")
            with self.assertRaises(AuthorizationError):
                authorize(host, "test@example.invalid", "test", "UY", "", "synthetic", opener=opener)
            self.assertEqual([], opener.requests)

    def test_redirects_cannot_forward_credentials(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 307, "", {}, "https://other.invalid"))

    def test_success_returns_only_key_instead_of_account_token_or_email(self):
        key = "a1" * 16
        opener = Opener(json.dumps({"apiStatus": 0, "data": {"key": key, "token": "private-token",
                                                                "email": "private@example.invalid"}}).encode())
        self.assertEqual(key, authorize("iotx-eu.refoss.net", "test@example.invalid", "test", "UY", "",
                                        "synthetic", opener=opener))
        self.assertEqual(1, len(opener.requests))

    def test_cloud_errors_never_echo_arbitrary_messages_or_credentials(self):
        for data in ({"apiStatus": 1001, "info": "private-password"},
                     {"apiStatus": 0, "data": {"key": "private-password"}},
                     {"apiStatus": True, "data": {}}, ["private-password"]):
            with self.assertRaises(AuthorizationError) as raised:
                authorize("iotx.refoss.net", "test@example.invalid", "test", "UY", "", "synthetic",
                          opener=Opener(json.dumps(data).encode()))
            self.assertNotIn("private-password", str(raised.exception))

    def test_network_exception_is_sanitized_and_never_retried(self):
        class Failing:
            calls = 0
            def open(self, request, timeout):
                self.calls += 1
                raise RuntimeError("private-response")
        opener = Failing()
        with self.assertRaises(AuthorizationError) as raised:
            authorize("iotx.refoss.net", "test@example.invalid", "test", "UY", "", "synthetic", opener=opener)
        self.assertNotIn("private-response", str(raised.exception))
        self.assertEqual(1, opener.calls)

    def test_storage_keeps_only_key_and_owner_permissions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "key.txt"
            save_key(path, "a1" * 16)
            self.assertEqual("a1" * 16 + "\n", path.read_text())
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual([path], list(Path(folder).iterdir()))


if __name__ == "__main__":
    unittest.main()
