"""Image (ComfyUI) job type for the murakumo generation API — validation,
job lifecycle against a stub ComfyUI, artifact serving, healthz capability.
Mirrors test/generation_api_voice_test.py's importlib harness."""
import base64
import http.client
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import struct
import tempfile
import threading
import time
import unittest
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"


def tiny_png():
    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))
    raw = b"".join(b"\x00" + bytes([200, 80, 60] * 4) for _ in range(4))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class StubComfy(BaseHTTPRequestHandler):
    prompts = {}
    png = tiny_png()
    fail_mode = None
    # What this stub's /object_info advertises. Tests that need the face graph
    # add the IP-Adapter classes; the default is a bare txt2img node.
    checkpoints = ["animagine-xl-4.0.safetensors"]
    node_classes = ["CheckpointLoaderSimple", "KSampler", "LoadImage"]

    def do_POST(self):
        if self.path == "/upload/image":
            raw = self.rfile.read(int(self.headers.get("content-length", "0")))
            StubComfy.prompts["last-upload-bytes"] = len(raw)
            payload = json.dumps({"name": "uploaded-face.png", "subfolder": ""}).encode()
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            return self.wfile.write(payload)
        body = json.loads(self.rfile.read(int(self.headers.get("content-length", "0"))))
        graph = body.get("prompt") or {}
        StubComfy.prompts["last-graph"] = graph
        payload = json.dumps({"prompt_id": "stub-1"}).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path.startswith("/queue"):
            # image jobs take the APU class since 2026-09-11, so the dispatcher
            # probes ComfyUI's queue before starting one; an idle stub says so.
            payload = json.dumps({"queue_running": [], "queue_pending": []}).encode()
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            return self.wfile.write(payload)
        if self.path == "/object_info/CheckpointLoaderSimple":
            payload = json.dumps({"CheckpointLoaderSimple": {"input": {"required": {
                "ckpt_name": [list(StubComfy.checkpoints)]}}}}).encode()
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            return self.wfile.write(payload)
        if self.path == "/object_info":
            payload = json.dumps({name: {} for name in StubComfy.node_classes}).encode()
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            return self.wfile.write(payload)
        if self.path.startswith("/history/"):
            if StubComfy.fail_mode == "error":
                entry = {"status": {"status_str": "error"}}
            else:
                entry = {"status": {"status_str": "success", "completed": True},
                         "outputs": {"9": {"images": [{"filename": "gen_00001_.png",
                                                       "subfolder": "", "type": "output"}]}}}
            payload = json.dumps({"stub-1": entry}).encode()
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            return self.wfile.write(payload)
        if self.path.startswith("/view"):
            self.send_response(200)
            self.send_header("content-type", "image/png")
            self.send_header("content-length", str(len(StubComfy.png)))
            self.end_headers()
            return self.wfile.write(StubComfy.png)
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt, *args):
        pass


def load_api(root, comfy_url):
    os.environ["MURAKUMO_GENERATION_DIR"] = str(root)
    os.environ["MURAKUMO_TTS_RUNNER"] = ""
    os.environ["MURAKUMO_MOTION_RUNNER"] = ""
    os.environ["MURAKUMO_EFFECT_RUNNER"] = ""
    os.environ["MURAKUMO_SOUND_RUNNER"] = ""
    os.environ["MURAKUMO_VISEME_RUNNER"] = ""
    os.environ["MURAKUMO_COMFY_URL"] = comfy_url
    # The service reads the resource-class table from next to its own file,
    # which is where provisioning puts it; in the repo it lives under
    # resources/. Without this every test here fails at import, on main too.
    os.environ["MURAKUMO_RESOURCE_CLASSES"] = str(
        pathlib.Path(__file__).parents[1] / "resources" / "murakumo" / "resource-classes.edn")
    loader = importlib.machinery.SourceFileLoader("murakumo_generation_api_image_test", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    # The service starts its dispatcher in __main__; classed jobs (image since
    # 2026-09-11) wait in the admission queue until it runs.
    threading.Thread(target=module._dispatch_loop, daemon=True).start()
    return module


class ImageGenerationTest(unittest.TestCase):
    def setUp(self):
        StubComfy.fail_mode = None
        StubComfy.prompts.clear()
        StubComfy.checkpoints = ["animagine-xl-4.0.safetensors"]
        StubComfy.node_classes = ["CheckpointLoaderSimple", "KSampler", "LoadImage"]
        self.comfy = ThreadingHTTPServer(("127.0.0.1", 0), StubComfy)
        threading.Thread(target=self.comfy.serve_forever, daemon=True).start()
        self.comfy_url = f"http://127.0.0.1:{self.comfy.server_port}"

    def tearDown(self):
        self.comfy.shutdown()

    def request_api(self, api, method, path, body=None, headers=None):
        server = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
            connection.request(method, path, body=json.dumps(body) if body is not None else None,
                               headers=headers or {})
            response = connection.getresponse()
            data = response.read()
            return response.status, data
        finally:
            server.shutdown()

    def test_validation_rejects_bad_image_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            for inputs, params, message in [
                    ({}, {}, "input.prompt"),
                    ({"prompt": "x" * 2001}, {}, "input.prompt"),
                    ({"prompt": "a knight"}, {"model": "midjourney"}, "params.model"),
                    ({"prompt": "a knight"}, {"width": 100}, "params.width"),
                    ({"prompt": "a knight"}, {"steps": 0}, "params.steps"),
                    ({"prompt": "a knight"}, {"steps": True}, "params.steps"),
                    ({"prompt": "a knight"}, {"negative_prompt": "y" * 2001}, "params.negative_prompt"),
                    ({"prompt": "a knight"}, {"seed": -1}, "params.seed")]:
                with self.assertRaises(ValueError, msg=message) as caught:
                    api.validate_image_request(inputs, params)
                self.assertIn(message, str(caught.exception))

    def test_image_type_rejected_without_comfy_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", "")
            status, data = self.request_api(api, "POST", "/v1/generation",
                                            {"type": "image", "input": {"prompt": "a knight"}})
            self.assertEqual(status, 400)
            self.assertIn("ComfyUI", json.loads(data)["message"])

    def test_image_job_completes_and_serves_png_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            status, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "image", "input": {"prompt": "a red cube on a desk"},
                 "params": {"model": "animagine-xl-4.0", "width": 832, "height": 1216,
                            "steps": 12, "seed": 7, "license": "cc0"}})
            self.assertEqual(status, 202)
            job = json.loads(data)
            self.assertEqual(job["outputKind"], "png")
            job_id = job["jobId"]
            deadline = time.monotonic() + 10
            while api.jobs[job_id]["status"] not in ("done", "failed") \
                    and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertEqual(api.jobs[job_id]["status"], "done", api.jobs[job_id].get("error"))
            artifact = api.jobs[job_id]["artifacts"][0]
            self.assertEqual(artifact["kind"], "png")
            self.assertEqual(artifact["license"], "cc0")
            self.assertEqual(artifact["width"], 832)
            self.assertTrue(artifact["contentHash"].startswith("sha256:"))
            graph = StubComfy.prompts["last-graph"]
            self.assertEqual(graph["9"]["inputs"]["filename_prefix"], "murakumo-gen-" + job_id[:12],
                             "unique per job, so a repeated graph is not served from cache with no outputs")
            self.assertEqual(graph["4"]["inputs"]["ckpt_name"], "animagine-xl-4.0.safetensors")
            self.assertEqual(graph["5"]["inputs"]["width"], 832)
            self.assertEqual(graph["3"]["inputs"]["seed"], 7)
            self.assertEqual(graph["3"]["inputs"]["steps"], 12)
            status, data = self.request_api(
                api, "GET", f"/v1/generation/jobs/{job_id}/artifact")
            self.assertEqual(status, 200)
            self.assertTrue(data.startswith(b"\x89PNG"))

    def test_comfy_error_fails_the_job_honestly(self):
        StubComfy.fail_mode = "error"
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            status, data = self.request_api(api, "POST", "/v1/generation",
                                            {"type": "image", "input": {"prompt": "a knight"}})
            self.assertEqual(status, 202)
            job_id = json.loads(data)["jobId"]
            deadline = time.monotonic() + 10
            while api.jobs[job_id]["status"] not in ("done", "failed") \
                    and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertEqual(api.jobs[job_id]["status"], "failed")
            self.assertIn("ComfyUI", api.jobs[job_id]["error"])

    def test_healthz_advertises_image_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            status, data = self.request_api(api, "GET", "/healthz")
            self.assertEqual(status, 200)
            self.assertIn("image", json.loads(data)["capabilities"])
            api_off = load_api(pathlib.Path(directory) / "artifacts2", "")
            status, data = self.request_api(api_off, "GET", "/healthz")
            self.assertNotIn("image", json.loads(data)["capabilities"])

    def _wait(self, api, job_id):
        deadline = time.monotonic() + 10
        while api.jobs[job_id]["status"] not in ("done", "failed") \
                and time.monotonic() < deadline:
            time.sleep(0.05)
        return api.jobs[job_id]

    def test_image_models_are_asked_of_comfyui_not_the_static_table(self):
        StubComfy.checkpoints = ["animagine-xl-4.0.safetensors", "waiREALMIX_v11.safetensors",
                                 "not-a-checkpoint.txt"]
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            self.assertEqual(api.image_models(),
                             {"animagine-xl-4.0": "animagine-xl-4.0.safetensors",
                              "waiREALMIX_v11": "waiREALMIX_v11.safetensors"})
            self.assertEqual(api.image_models_source(), "comfyui")
            # a checkpoint that is on THIS node validates without a release
            api.validate_image_request({"prompt": "x"}, {"model": "waiREALMIX_v11"})
            status, data = self.request_api(api, "GET", "/v1/generation/image-models")
            self.assertEqual(status, 200)
            listed = json.loads(data)
            self.assertEqual([m["id"] for m in listed["models"]],
                             ["animagine-xl-4.0", "waiREALMIX_v11"])
            self.assertEqual(listed["source"], "comfyui")
            self.assertEqual(listed["faceModes"], [])

    def test_image_models_fall_back_when_comfyui_cannot_be_asked(self):
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", "http://127.0.0.1:9")
            self.assertEqual(api.image_models(), api.IMAGE_MODELS)
            self.assertEqual(api.image_models_source(), "static-fallback")

    def test_face_reference_is_refused_where_the_node_has_no_ipadapter(self):
        face = "data:image/png;base64," + base64.b64encode(tiny_png()).decode()
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            self.assertEqual(api.image_face_modes(), [])
            with self.assertRaises(ValueError) as caught:
                api.validate_image_request({"prompt": "portrait", "face": face}, {})
            self.assertIn("not available on this node", str(caught.exception))

    def test_face_reference_validation(self):
        StubComfy.node_classes += ["IPAdapterUnifiedLoader", "IPAdapter"]
        face = "data:image/png;base64," + base64.b64encode(tiny_png()).decode()
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            self.assertEqual(api.image_face_modes(), ["plus-face"])
            for inputs, params, message in [
                    ({"prompt": "p", "face": "https://example.com/a.png"}, {}, "input.face must be"),
                    ({"prompt": "p", "face": "data:image/png;base64,@@@"}, {}, "invalid base64"),
                    ({"prompt": "p", "face": face}, {"face_mode": "reactor"}, "params.face_mode"),
                    ({"prompt": "p", "face": face}, {"face_mode": "faceid"}, "not available"),
                    ({"prompt": "p", "face": face}, {"face_weight": "1"}, "params.face_weight"),
                    ({"prompt": "p", "face": face}, {"face_weight": 1.51}, "params.face_weight"),
                    ({"prompt": "p", "face": face}, {"face_weight": True}, "params.face_weight")]:
                with self.assertRaises(ValueError, msg=message) as caught:
                    api.validate_image_request(inputs, params)
                self.assertIn(message, str(caught.exception))
            # the boundary itself is inside the range
            accepted = api.validate_image_request({"prompt": "p", "face": face}, {"face_weight": 1.5})
            self.assertEqual(accepted, {"prompt": "p", "face": face})
            # no face -> the pre-face contract, a bare prompt string
            self.assertEqual(api.validate_image_request({"prompt": "p"}, {}), "p")

    def test_face_job_uploads_the_portrait_and_reroutes_the_model_edge(self):
        StubComfy.node_classes += ["IPAdapterUnifiedLoader", "IPAdapter"]
        png = tiny_png()
        face = "data:image/png;base64," + base64.b64encode(png).decode()
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            status, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "image", "input": {"prompt": "portrait, soft light", "face": face},
                 "params": {"seed": 3, "face_weight": 0.7}})
            self.assertEqual(status, 202)
            job = self._wait(api, json.loads(data)["jobId"])
            self.assertEqual(job["status"], "done", job.get("error"))
            self.assertIn("face-reference:plus-face", job["capabilities"])
            self.assertGreaterEqual(StubComfy.prompts["last-upload-bytes"], len(png))
            graph = StubComfy.prompts["last-graph"]
            self.assertEqual(graph["10"], {"class_type": "LoadImage",
                                           "inputs": {"image": "uploaded-face.png"}})
            self.assertEqual(graph["11"]["inputs"]["preset"], "PLUS FACE (portraits)")
            self.assertEqual(graph["12"]["inputs"]["weight"], 0.7)
            self.assertEqual(graph["12"]["inputs"]["image"], ["10", 0])
            self.assertEqual(graph["3"]["inputs"]["model"], ["12", 0])
            # the plain job still samples straight from the checkpoint
            status, data = self.request_api(
                api, "POST", "/v1/generation",
                {"type": "image", "input": {"prompt": "portrait"}, "params": {"seed": 3}})
            job = self._wait(api, json.loads(data)["jobId"])
            self.assertEqual(job["status"], "done", job.get("error"))
            self.assertNotIn("10", StubComfy.prompts["last-graph"])
            self.assertEqual(StubComfy.prompts["last-graph"]["3"]["inputs"]["model"], ["4", 0])
            self.assertFalse(any(c.startswith("face-reference") for c in job["capabilities"]))

    def test_healthz_advertises_face_modes(self):
        StubComfy.node_classes += ["IPAdapterUnifiedLoader", "IPAdapter"]
        with tempfile.TemporaryDirectory() as directory:
            api = load_api(pathlib.Path(directory) / "artifacts", self.comfy_url)
            status, data = self.request_api(api, "GET", "/healthz")
            body = json.loads(data)
            self.assertIn("image-face", body["capabilities"])
            self.assertEqual(body["imageFaceModes"], ["plus-face"])
            self.assertEqual(body["imageModels"], ["animagine-xl-4.0"])


if __name__ == "__main__":
    unittest.main()
