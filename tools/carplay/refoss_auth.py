"""One-time Refoss authorization on loopback. Never sends device-control commands.

Protocol independently described from the owner's Refoss 1.17.1 APK:
MS2FARemote.u, MS2FARemoteApi.c, HttpUtils.h and SafeUtils.c/g/m.
The app request-signing value is a private input, not an account credential in source.
"""
import argparse
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import platform
import re
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request


HOSTS = {"iotx.refoss.net", "iotx-us.refoss.net", "iotx-eu.refoss.net", "iotx-ap.refoss.net"}
MAX_RESPONSE = 256 * 1024


class AuthorizationError(Exception):
    """Only fixed, non-sensitive messages may be shown to the user."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward account credentials to an HTTP redirect destination.
        return None


def make_request(host, email, password, country, mfa, signing_value, *, timestamp=None, nonce=None):
    if host not in HOSTS:
        raise AuthorizationError("Servidor Refoss no permitido.")
    if not email or not password or not re.fullmatch(r"[A-Z]{2}", country):
        raise AuthorizationError("Revisá correo, contraseña y código de país de dos letras.")
    if mfa and not re.fullmatch(r"[0-9]{6}", mfa):
        raise AuthorizationError("El código de verificación debe tener seis dígitos.")
    params = {"email": email, "password": hashlib.md5(password.encode("utf-8")).hexdigest(),
              "encryption": 1, "accountCountryCode": country, "agree": 1,
              "mobileInfo": {"deviceModel": "DiPlay authorization", "mobileOs": platform.system(),
                             "mobileOsVersion": platform.release(), "uuid": nonce or secrets.token_hex(16),
                             "resolution": "", "carrier": ""}}
    if mfa:
        params["mfaCode"] = mfa
    encoded = base64.b64encode(json.dumps(params, separators=(",", ":"), ensure_ascii=False).encode()).decode()
    stamp = str(int(time.time()) if timestamp is None else timestamp)
    nonce = secrets.token_hex(8) if nonce is None else nonce
    signature = hashlib.md5((signing_value + stamp + nonce + encoded).encode()).hexdigest()
    body = urllib.parse.urlencode({"params": encoded, "timestamp": stamp, "nonce": nonce,
                                  "sign": signature}).encode()
    return urllib.request.Request("https://" + host + "/v1/Auth/signIn", data=body, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded", "Vendor": "refoss",
                 "AppVersion": "1.17.1", "AppLanguage": "es", "AppType": "Android"})


def authorize(host, email, password, country, mfa, signing_value, *, opener=None):
    request = make_request(host, email, password, country, mfa, signing_value)
    if opener is None:
        # Default TLS certificate verification; no HTTP proxy or certificate installation.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=15) as response:
            raw = response.read(MAX_RESPONSE + 1)
    except Exception:
        raise AuthorizationError("No se pudo completar la conexión HTTPS con Refoss.") from None
    if len(raw) > MAX_RESPONSE:
        raise AuthorizationError("Refoss devolvió una respuesta demasiado grande.")
    try:
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ValueError()
        status = result.get("apiStatus")
        if type(status) is not int:
            raise ValueError()
        if status != 0:
            # Arbitrary cloud messages may contain personal data: show only the numeric code.
            raise AuthorizationError("Refoss rechazó el acceso (código %d). Revisá región y verificación en dos pasos." % status)
        data = result.get("data")
        if isinstance(data, str):
            data = json.loads(data)
        if not isinstance(data, dict):
            raise ValueError()
        key = data.get("key")
        if not isinstance(key, str) or not re.fullmatch(r"[a-fA-F0-9]{32}", key):
            raise ValueError()
        return key
    except (ValueError, TypeError):
        raise AuthorizationError("La respuesta de autorización no contiene una clave válida.") from None


def save_key(destination, key):
    """Save only the device/account key, never email, password, token or raw cloud data."""
    temporary = destination.with_name(destination.name + "." + secrets.token_hex(8))
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(key + "\n")
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def valid_submission(expected_origin, headers, values, csrf):
    """Safari may omit Origin or send 'null'; the secret form token is always required."""
    if headers.get("Host") != urllib.parse.urlsplit(expected_origin).netloc:
        return False
    if headers.get("Origin") not in (None, "null", expected_origin):
        return False
    if headers.get("Sec-Fetch-Site") not in (None, "same-origin"):
        return False
    token = values.get("csrf", "")
    return isinstance(token, str) and hmac.compare_digest(token, csrf)


def serve(signing_value, destination, port=0):
    csrf = secrets.token_urlsafe(32)
    state = {"completed": False}

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass

        def page(self, status, content):
            data = ("<!doctype html><html lang=es><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    "<title>Autorizar Refoss · DiPlay</title><style>body{font:18px system-ui;max-width:520px;"
                    "margin:40px auto;padding:20px;background:#f5f7fa;color:#172332}input,select,button{font:inherit;"
                    "box-sizing:border-box;width:100%;padding:12px;margin:7px 0 18px}button{background:#166b58;"
                    "color:white;border:0;border-radius:10px}small{display:block;line-height:1.5}</style>" + content + "</html>").encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "same-origin")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def valid_host(self):
            return self.headers.get("Host") == "127.0.0.1:%d" % self.server.server_port

        def do_GET(self):
            if not self.valid_host() or self.path != "/":
                self.page(404, "No disponible.")
                return
            self.page(200, "<h1>Autorizar Refoss</h1><p>Usá la cuenta de la app Refoss.</p>"
                "<small>Las credenciales se envían a Refoss por HTTPS. No se guarda la contraseña. "
                "La clave de acceso local se guarda en la carpeta privada del proyecto. "
                "Esta autorización no abre ni cierra Portón.</small>"
                "<form method=post action=/authorize><input type=hidden name=csrf value='" + csrf + "'>"
                "<label>Correo de Refoss<input name=email type=email autocomplete=username required></label>"
                "<label>Contraseña de Refoss<input name=password type=password autocomplete=current-password required></label>"
                "<label>País de la cuenta (dos letras)<input name=country value=UY maxlength=2 required></label>"
                "<label>Servidor<select name=host><option value=iotx.refoss.net>Global</option>"
                "<option value=iotx-us.refoss.net>América</option><option value=iotx-eu.refoss.net>Europa</option>"
                "<option value=iotx-ap.refoss.net>Asia / Pacífico</option></select></label>"
                "<label>Código de dos pasos, si lo usás<input name=mfa inputmode=numeric maxlength=6 autocomplete=one-time-code></label>"
                "<button>Autorizar conexión local</button></form>")

        def do_POST(self):
            origin = "http://127.0.0.1:%d" % self.server.server_port
            if not self.valid_host() or self.path != "/authorize":
                self.page(403, "Origen no permitido.")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError()
                fields = urllib.parse.parse_qs(self.rfile.read(length).decode(), keep_blank_values=True)
                if any(len(values) != 1 for values in fields.values()):
                    raise ValueError()
                values = {k: v[0] for k, v in fields.items()}
            except (ValueError, UnicodeError):
                self.page(400, "Formulario inválido. Recargá la página.")
                return
            if not valid_submission(origin, self.headers, values, csrf):
                self.page(403, "Formulario no válido o vencido. <a href=/>Recargar acceso</a>")
                return
            try:
                key = authorize(values.get("host", ""), values.get("email", "").strip(),
                                values.get("password", ""), values.get("country", "").strip().upper(),
                                values.get("mfa", "").strip(), signing_value)
                save_key(destination, key)
            except AuthorizationError as error:
                import html
                self.page(400, "<h1>Acceso pendiente</h1><p>" + html.escape(str(error)) + "</p><a href=/>Volver a intentar</a>")
                return
            except Exception:
                self.page(500, "No se pudo guardar la autorización local.")
                return
            state["completed"] = True
            self.page(200, "<h1>Autorización guardada</h1><p>Ya podés cerrar esta pestaña. "
                      "El siguiente paso es comprobar una lectura del abridor; no se envió ninguna orden de movimiento.</p>")
            print(json.dumps({"authorized": True, "keySaved": True, "controlCommandsSent": False}), flush=True)

    server = HTTPServer(("127.0.0.1", port), Handler)
    server.timeout = 0.5
    print(json.dumps({"url": "http://127.0.0.1:%d/" % server.server_port, "expiresInMinutes": 15}), flush=True)
    deadline = time.monotonic() + 900
    try:
        while not state["completed"] and time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-signing-file", type=Path, required=True)
    parser.add_argument("--key-output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    os.umask(0o077)
    serve(args.app_signing_file.read_text().strip(), args.key_output, port=args.port)
