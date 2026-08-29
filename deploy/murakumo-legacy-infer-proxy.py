#!/usr/bin/env python3
"""Stream legacy local origins through the authenticated Murakumo head.

Older Cloudflare Tunnel hostnames connect to localhost:11434 without an origin
token. Do not keep a second 27B model resident for them: this proxy injects the
configured fleet-origin token and streams the 8090 response.
"""

from __future__ import annotations

import http.client
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


LISTEN_HOST = os.environ.get("MURAKUMO_PROXY_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("MURAKUMO_PROXY_PORT", "11434"))
UPSTREAM_HOST = os.environ.get("MURAKUMO_UPSTREAM_HOST", "127.0.0.1")
UPSTREAM_PORT = int(os.environ.get("MURAKUMO_UPSTREAM_PORT", "8090"))
KEY_FILE = os.environ.get(
    "MURAKUMO_ORIGIN_KEY_FILE", "/etc/murakumo/fleet-origin.keys"
)

HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade",
}


def origin_token() -> str:
    with open(KEY_FILE, "r", encoding="utf-8") as source:
        for line in source:
            token = line.strip()
            if token:
                return token
    raise RuntimeError(f"no origin token in {KEY_FILE}")


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "murakumo-legacy-proxy/1"

    def _body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def _proxy(self) -> None:
        upstream = None
        response_started = False
        try:
            headers = {
                key: value
                for key, value in self.headers.items()
                if key.lower() not in HOP_BY_HOP
                and key.lower() not in {"host", "authorization", "content-length"}
            }
            body = self._body()
            headers["Host"] = f"{UPSTREAM_HOST}:{UPSTREAM_PORT}"
            headers["Authorization"] = f"Bearer {origin_token()}"
            if body:
                headers["Content-Length"] = str(len(body))

            upstream = http.client.HTTPConnection(
                UPSTREAM_HOST, UPSTREAM_PORT, timeout=3600
            )
            upstream.request(self.command, self.path, body=body, headers=headers)
            response = upstream.getresponse()

            self.send_response(response.status, response.reason)
            response_started = True
            has_length = response.getheader("Content-Length") is not None
            for key, value in response.getheaders():
                if key.lower() not in HOP_BY_HOP:
                    self.send_header(key, value)
            if not has_length:
                self.send_header("Connection", "close")
                self.close_connection = True
            self.end_headers()

            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (OSError, http.client.HTTPException, RuntimeError, ValueError) as exc:
            if not response_started and not self.wfile.closed:
                payload = json.dumps(
                    {"error": {"type": "proxy_error", "message": str(exc)}}
                ).encode("utf-8")
                try:
                    self.send_response(502)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(payload)
                except OSError:
                    pass
                self.close_connection = True
        finally:
            if upstream is not None:
                upstream.close()

    do_GET = _proxy
    do_HEAD = _proxy
    do_POST = _proxy
    do_PUT = _proxy
    do_PATCH = _proxy
    do_DELETE = _proxy
    do_OPTIONS = _proxy

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.client_address[0]} {fmt % args}", flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), ProxyHandler)
    server.daemon_threads = True
    server.serve_forever()
