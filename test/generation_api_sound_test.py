import http.client
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import tempfile
import time
import unittest
from http.server import ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"


def load_api(root, sound_runner):
    os.environ["MURAKUMO_GENERATION_DIR"] = str(root)
    os.environ["MURAKUMO_SOUND_RUNNER"] = str(sound_runner)
    os.environ["MURAKUMO_GENERATION_TOKEN"] = ""
    loader = importlib.machinery.SourceFileLoader(
        f"murakumo_generation_sound_test_{time.time_ns()}", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class SoundGenerationTest(unittest.TestCase):
    def test_submit_status_runner_and_artifact_keep_exact_sound_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "sound-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys, wave
args = sys.argv[1:]
out = pathlib.Path(args[args.index('--output') + 1])
duration = int(args[args.index('--duration-ms') + 1])
out.parent.joinpath('argv.json').write_text(json.dumps(args))
with wave.open(str(out), 'wb') as wav:
    wav.setnchannels(2); wav.setsampwidth(2); wav.setframerate(24000)
    wav.writeframes(bytes(round(24000 * duration / 1000) * 4))
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", runner)
            server = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
            thread = __import__("threading").Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                request = {"type": "sound", "model": "ace-step",
                           "input": {"prompt": "orchestral instrumental"},
                           "params": {"sound_kind": "music", "duration_ms": 150,
                                      "seed": 0, "loop": True}}
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                connection.request("POST", "/v1/generation", json.dumps(request),
                                   {"content-type": "application/json"})
                response = connection.getresponse()
                self.assertEqual(response.status, 202)
                queued = json.loads(response.read())
                self.assertEqual({key: queued[key] for key in
                                  ("model", "durationMs", "seed", "soundKind", "loop")},
                                 {"model": "ace-step", "durationMs": 150, "seed": 0,
                                  "soundKind": "music", "loop": True})
                job_id = queued["jobId"]
                deadline = time.monotonic() + 3
                while api.jobs[job_id]["status"] not in ("done", "failed") and time.monotonic() < deadline:
                    time.sleep(0.01)
                job = api.jobs[job_id]
                self.assertEqual(job["status"], "done", job.get("error"))
                argv = json.loads((api.ROOT / job_id / "argv.json").read_text())
                self.assertEqual(argv[argv.index("--model") + 1], "ace-step")
                self.assertEqual(argv[argv.index("--duration-ms") + 1], "150")
                self.assertEqual(argv[argv.index("--seed") + 1], "0")
                self.assertIn("--loop", argv)
                artifact = job["artifacts"][0]
                self.assertEqual(artifact["model"], "ace-step")
                self.assertEqual(artifact["seed"], 0)
                self.assertEqual(artifact["durationMs"], 150.0)
                self.assertIn("model:ace-step", artifact["capabilities"])
            finally:
                server.shutdown(); server.server_close(); thread.join(2)

    def test_sound_model_kind_and_seed_are_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            api = load_api(root / "artifacts", root / "runner")
            params = {"sound_kind": "music", "duration_ms": 30000,
                      "model": "ace-step", "seed": 0, "loop": True}
            self.assertEqual(api.validate_sound_request({"prompt": "theme"}, params), "theme")
            self.assertEqual(params["seed"], 0)
            with self.assertRaisesRegex(ValueError, "does not support"):
                api.validate_sound_request({"prompt": "hit"},
                                           {"sound_kind": "sfx", "model": "ace-step"})
            with self.assertRaisesRegex(ValueError, "seed"):
                api.validate_sound_request({"prompt": "theme"},
                                           {"sound_kind": "music", "seed": -1})


if __name__ == "__main__":
    unittest.main()
