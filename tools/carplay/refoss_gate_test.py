"""Manual, single-activation local test. No action is sent until the user submits the form."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import time
import urllib.parse

from refoss_auth import valid_submission
from refoss_local import RefossLocal


class ActivationOnce:
    def __init__(self, client):
        self.client = client
        self.consumed = False

    def activate(self):
        if self.consumed:
            return None
        self.consumed = True
        result = {"physicalMovementVerified": False}
        try:
            result.update(self.client.pulse_once())
        except Exception as error:
            result["failure"] = type(error).__name__
        result["controlRequestAttempted"] = self.client.control_request_attempted
        result["outcomeUnconfirmed"] = self.client.control_request_attempted and "failure" in result
        return result


def serve(client, receipt, port=0):
    activation = ActivationOnce(client)
    csrf = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass

        def page(self, code, text):
            body = ("<!doctype html><html lang=es><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    "<title>Probar Portón · DiPlay</title><style>body{font:20px system-ui;max-width:520px;"
                    "margin:50px auto;padding:24px;background:#f5f7fa;color:#172332}button{font:inherit;"
                    "background:#166b58;color:white;border:0;border-radius:12px;padding:20px;width:100%}"
                    "p{line-height:1.5}</style>" + text + "</html>").encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "same-origin")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.headers.get("Host") != "127.0.0.1:%d" % self.server.server_port or self.path != "/":
                self.page(404, "No disponible.")
                return
            self.page(200, "<h1>Probar Portón</h1><p>Esta prueba temporal funciona desde la Mac. "
                "Pulsar el botón enviará una activación al abridor Refoss y puede poner el portón en movimiento.</p>"
                "<p>El sensor no se considera una indicación fiable de su posición. Se enviará una sola orden, "
                "sin reintentos automáticos.</p><form method=post action=/activate>"
                "<input type=hidden name=csrf value='" + csrf + "'>"
                "<button>Activar Portón una vez</button></form>")

        def do_POST(self):
            origin = "http://127.0.0.1:%d" % self.server.server_port
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if self.path != "/activate" or not 0 < length <= 1024:
                    raise ValueError()
                data = urllib.parse.parse_qs(self.rfile.read(length).decode(), keep_blank_values=True)
                if set(data) != {"csrf"} or len(data["csrf"]) != 1:
                    raise ValueError()
                fields = {"csrf": data["csrf"][0]}
                if not valid_submission(origin, self.headers, fields, csrf):
                    raise ValueError()
            except (ValueError, UnicodeError):
                self.page(403, "Formulario inválido o vencido. Recargá la página.")
                return
            result = activation.activate()
            if result is None:
                self.page(409, "La única activación de esta prueba ya fue consumida.")
                return
            receipt.write_text(json.dumps(result, indent=2) + "\n")
            receipt.chmod(0o600)
            if result.get("deviceExecutionReported"):
                text = "<h1>Solicitud aceptada</h1><p>Refoss informó la ejecución. Comprobá si el portón se movió. "
            else:
                text = "<h1>Resultado sin confirmar</h1><p>No se repetirá la orden automáticamente. "
            self.page(200, text + "Esta prueba terminó y no enviará más órdenes.</p>")
            print(json.dumps(result), flush=True)

    server = HTTPServer(("127.0.0.1", port), Handler)
    server.timeout = 0.5
    print(json.dumps({"url": "http://127.0.0.1:%d/" % server.server_port,
                      "maximumActivations": 1, "expiresInMinutes": 10}), flush=True)
    deadline = time.monotonic() + 600
    try:
        while not activation.consumed and time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-file", type=Path, required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    target = json.loads(args.target_file.read_text())
    client = RefossLocal(socket.gethostbyname(target["hostname"]), args.key_file.read_text().strip(), target["deviceId"])
    serve(client, args.receipt)
