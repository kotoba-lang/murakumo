import base64
import hashlib
import hmac
import http.client
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import threading
import time
import unittest
from http.server import ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"


def load_api():
    os.environ.setdefault("MURAKUMO_RESOURCE_CLASSES", str(
        SCRIPT.parents[1] / "resources" / "murakumo" / "resource-classes.edn"))
    loader = importlib.machinery.SourceFileLoader("murakumo_generation_auth_test", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    module.TOKEN = "legacy-static-key"
    module.CALLER_SECRET = "scoped-generation-key"
    return module


def capability(claims, secret="scoped-generation-key"):
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    signing_input = f"mk1.{payload}"
    signature = base64.urlsafe_b64encode(hmac.new(
        secret.encode(), signing_input.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return f"{signing_input}.{signature}"


class GenerationAuthTest(unittest.TestCase):
    def test_one_minute_isekai_generation_capability_and_legacy_key(self):
        api = load_api()
        now = 1800000000
        claims = {"sub": "isekai-pages", "scope": "generation", "iat": now, "exp": now + 60}
        good = capability(claims)
        self.assertTrue(api.authorized_generation_capability(good, now))
        self.assertFalse(api.authorized_generation_capability(good, now + 60))
        self.assertFalse(api.authorized_generation_capability(good + "x", now))
        for change in ({"scope": "chat"}, {"sub": "another-site"}, {"exp": now + 3600},
                       {"iat": now + 31}, {"iat": now - 31}, {"exp": now},
                       {"iat": True}):
            self.assertFalse(api.authorized_generation_capability(
                capability({**claims, **change}), now))
        self.assertFalse(api.authorized_generation_capability(capability(claims, "wrong-key"), now))

        server = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            def status(token):
                conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                conn.request("GET", "/v1/generation/jobs/0123456789abcdef",
                             headers={"authorization": f"Bearer {token}"})
                response = conn.getresponse()
                response.read()
                conn.close()
                return response.status

            live = capability({**claims, "iat": int(time.time()), "exp": int(time.time()) + 60})
            self.assertEqual(status("legacy-static-key"), 404)
            self.assertEqual(status(live), 404)
            self.assertEqual(status("invalid"), 401)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == "__main__":
    unittest.main()
