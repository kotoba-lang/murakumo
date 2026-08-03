"""Hosted video (Seedance 2.0 on fal.ai) for the murakumo generation API —
admission, the exact wire the provider requires, job lifecycle against a stub
fal queue, and the catalog. Mirrors test/generation_api_image_test.py's
importlib harness.

The wire assertions here are not decoration. This path existed as unexercised
code for two weeks with `Authorization: Bearer` and a numeric duration, both of
which fal rejects — the kind of break no amount of local reasoning catches and
one stub server does.
"""
import http.client
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"
KEY = "stub-key-id:stub-key-secret"
MP4 = b"\x00\x00\x00\x18ftypmp42" + b"murakumo-stub-video" * 100


class StubFal(BaseHTTPRequestHandler):
    base = ""
    submissions = []
    auth_headers = []
    cdn_auth_headers = []
    polls = 0
    polls_before_completion = 1
    fail_mode = None

    def json(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        StubFal.auth_headers.append(self.headers.get("authorization"))
        body = json.loads(self.rfile.read(int(self.headers.get("content-length", "0"))))
        StubFal.submissions.append({"path": self.path, "body": body})
        if StubFal.fail_mode == "reject":
            return self.json(422, {"detail": [{"msg": "duration must be a string"}]})
        self.json(200, {"request_id": "stub-req-1",
                        "status_url": StubFal.base + "/requests/stub-req-1/status",
                        "response_url": StubFal.base + "/requests/stub-req-1"})

    def do_GET(self):
        if self.path.endswith("/status"):
            StubFal.auth_headers.append(self.headers.get("authorization"))
            StubFal.polls += 1
            if StubFal.fail_mode == "failed":
                return self.json(200, {"status": "FAILED", "error": "partner_validation_failed"})
            if StubFal.polls <= StubFal.polls_before_completion:
                return self.json(200, {"status": "IN_QUEUE", "queue_position": 3})
            return self.json(200, {"status": "COMPLETED"})
        if self.path.endswith("/requests/stub-req-1"):
            StubFal.auth_headers.append(self.headers.get("authorization"))
            return self.json(200, {"video": {"url": StubFal.base + "/cdn/out.mp4"},
                                   "seed": 4242})
        if self.path == "/cdn/out.mp4":
            StubFal.cdn_auth_headers.append(self.headers.get("authorization"))
            self.send_response(200)
            self.send_header("content-type", "video/mp4")
            self.send_header("content-length", str(len(MP4)))
            self.end_headers()
            return self.wfile.write(MP4)
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt, *args):
        pass


def load_api(root, fal_url, key=KEY, comfy_url=""):
    os.environ["MURAKUMO_GENERATION_DIR"] = str(root)
    for runner in ("TTS", "MOTION", "EFFECT", "SOUND", "VISEME"):
        os.environ["MURAKUMO_%s_RUNNER" % runner] = ""
    os.environ["MURAKUMO_COMFY_URL"] = comfy_url
    os.environ["MURAKUMO_FAL_QUEUE_URL"] = fal_url
    os.environ["SEEDANCE_API_KEY"] = key
    loader = importlib.machinery.SourceFileLoader("murakumo_generation_api_seedance_test", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class SeedanceGenerationTest(unittest.TestCase):
    def setUp(self):
        StubFal.submissions = []
        StubFal.auth_headers = []
        StubFal.cdn_auth_headers = []
        StubFal.polls = 0
        StubFal.polls_before_completion = 1
        StubFal.fail_mode = None
        self.fal = ThreadingHTTPServer(("127.0.0.1", 0), StubFal)
        threading.Thread(target=self.fal.serve_forever, daemon=True).start()
        self.fal_url = f"http://127.0.0.1:{self.fal.server_port}"
        StubFal.base = self.fal_url

    def tearDown(self):
        self.fal.shutdown()

    def request_api(self, api, method, path, body=None, headers=None):
        server = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
            connection.request(method, path, body=json.dumps(body) if body is not None else None,
                               headers=headers or {})
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            server.shutdown()

    def await_job(self, api, job_id, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            job = api.jobs[job_id]
            if job["status"] in ("done", "failed"):
                return job
            time.sleep(0.05)
        self.fail("job did not settle: " + json.dumps(api.jobs[job_id]))

    # ---- admission ---------------------------------------------------------

    def test_validation_rejects_requests_the_provider_would_reject(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            for inputs, params, message in [
                    ({"prompt": "a chase"}, {"model": "seedance-3.0"}, "params.model"),
                    ({"prompt": "a chase"},
                     {"model": "seedance-2.0-fast", "resolution": "4k"}, "params.resolution"),
                    ({"prompt": "a chase"},
                     {"model": "seedance-2.0", "aspect_ratio": "5:4"}, "params.aspect_ratio"),
                    ({"prompt": "a chase"},
                     {"model": "seedance-2.0", "duration": 42}, "params.duration"),
                    ({"prompt": "a chase"},
                     {"model": "seedance-2.0", "generate_audio": "yes"}, "params.generate_audio"),
                    ({"prompt": "a chase", "images": ["https://x/a.png"] * 10},
                     {"model": "seedance-2.0"}, "at most 9"),
                    ({"prompt": "a chase", "images": ["data:image/png;base64,AAAA"]},
                     {"model": "seedance-2.0"}, "http(s)")]:
                with self.assertRaises(ValueError, msg=message) as caught:
                    api.validate_video_request(inputs, params)
                self.assertIn(message, str(caught.exception))

    def test_fleet_video_admission_is_unchanged_by_the_hosted_branch(self):
        """The hosted branch forks admission before the ComfyUI geometry rules,
        so the fleet path has to be re-proved: its constraints must still apply
        to fleet models and must NOT have leaked onto hosted ones."""
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url,
                           comfy_url="http://127.0.0.1:1")
            source = api.validate_video_request(
                {"prompt": "she looks up", "image": "https://x/nei.png"},
                {"model": "wan2.2-ti2v-5b", "width": 768, "height": 448, "duration_ms": 2000})
            self.assertEqual(source, {"prompt": "she looks up", "image": "https://x/nei.png"})
            for params, message in [({"width": 100}, "params.width"),
                                    ({"frames": 50}, "params.frames"),
                                    ({"seed": -1}, "params.seed")]:
                with self.assertRaises(ValueError, msg=message) as caught:
                    api.validate_video_request({"prompt": "x"}, dict(params))
                self.assertIn(message, str(caught.exception))

        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url, comfy_url="")
            with self.assertRaises(ValueError) as caught:
                api.validate_video_request({"prompt": "x"}, {"model": "ltx-2.3"})
            self.assertIn("ComfyUI", str(caught.exception))

    def test_hosted_model_is_refused_when_no_provider_key_is_configured(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url, key="")
            status, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0-fast",
                 "input": {"prompt": "a chase"}})
            self.assertEqual(status, 400)
            self.assertIn("not configured", json.loads(data)["message"])

    def test_catalog_only_advertises_models_this_node_can_run(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            status, data = self.request_api(api, "GET", "/healthz")
            body = json.loads(data)
            self.assertEqual(status, 200)
            # no ComfyUI configured here: the fleet models must NOT be offered,
            # the hosted ones must be, and video must still be a capability.
            self.assertEqual(body["videoModels"], ["seedance-2.0", "seedance-2.0-fast"])
            self.assertIn("video", body["capabilities"])
            self.assertNotIn("image", body["capabilities"])

        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url, key="")
            body = json.loads(self.request_api(api, "GET", "/healthz")[1])
            self.assertEqual(body["videoModels"], [])
            self.assertNotIn("video", body["capabilities"])

    # ---- the wire ----------------------------------------------------------

    def test_submitted_request_matches_fals_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            status, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0-fast",
                 "input": {"prompt": "a rooftop chase",
                           "images": ["https://x/shiro.png", "https://x/pico.png"]},
                 "params": {"duration_ms": 10000, "resolution": "480p",
                            "aspect_ratio": "16:9"}})
            self.assertEqual(status, 202)
            self.await_job(api, json.loads(data)["jobId"])

            submission = StubFal.submissions[0]
            self.assertEqual(submission["path"],
                             "/bytedance/seedance-2.0/fast/reference-to-video")
            self.assertEqual(submission["body"], {
                "prompt": "a rooftop chase",
                "duration": "10",          # string enum — a number is a 422
                "generate_audio": False,   # provider default is True; ours is not
                "image_urls": ["https://x/shiro.png", "https://x/pico.png"],
                "resolution": "480p",
                "aspect_ratio": "16:9"})

    def test_provider_authorization_uses_the_key_scheme_and_never_reaches_the_cdn(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            _, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0",
                 "input": {"prompt": "a rooftop chase"}})
            self.await_job(api, json.loads(data)["jobId"])
            self.assertTrue(StubFal.auth_headers)
            for header in StubFal.auth_headers:
                self.assertEqual(header, "Key " + KEY)
            self.assertEqual(StubFal.cdn_auth_headers, [None])

    # ---- lifecycle ---------------------------------------------------------

    def test_job_completes_and_stores_the_artifact_we_serve(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "artifacts"
            api = load_api(root, self.fal_url)
            StubFal.polls_before_completion = 2
            _, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0-fast",
                 "input": {"prompt": "a chase", "image": "https://x/shiro.png"}})
            job_id = json.loads(data)["jobId"]
            job = self.await_job(api, job_id)

            self.assertEqual(job["status"], "done")
            self.assertEqual(job["outputKind"], "mp4")
            artifact = job["artifacts"][0]
            self.assertEqual(artifact["bytes"], len(MP4))
            self.assertTrue(artifact["contentHash"].startswith("sha256:"))
            self.assertEqual((root / job_id / "video.mp4").read_bytes(), MP4)
            # the model is the product; the provider is a routing decision
            self.assertEqual(job["capabilities"],
                             ["video", "mp4", "model:seedance-2.0-fast", "reference-to-video"])
            self.assertNotIn("fal", json.dumps(job))

    def test_provider_failure_fails_the_job_instead_of_pretending(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            StubFal.fail_mode = "failed"
            _, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0",
                 "input": {"prompt": "a chase"}})
            job = self.await_job(api, json.loads(data)["jobId"])
            self.assertEqual(job["status"], "failed")
            self.assertIn("partner_validation_failed", job["error"])

    def test_rejected_submission_surfaces_the_providers_own_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.fal_url)
            StubFal.fail_mode = "reject"
            _, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "video", "model": "seedance-2.0",
                 "input": {"prompt": "a chase"}})
            job = self.await_job(api, json.loads(data)["jobId"])
            self.assertEqual(job["status"], "failed")
            self.assertIn("HTTP 422", job["error"])
            self.assertNotIn(KEY, job["error"])


if __name__ == "__main__":
    unittest.main()
