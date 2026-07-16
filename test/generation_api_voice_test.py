import importlib.machinery
import importlib.util
import http.client
import os
import pathlib
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"


def load_api(root, runner, motion_runner="", effect_runner="", sound_runner="", viseme_runner=""):
    os.environ["MURAKUMO_GENERATION_DIR"] = str(root)
    os.environ["MURAKUMO_TTS_RUNNER"] = str(runner)
    os.environ["MURAKUMO_MOTION_RUNNER"] = str(motion_runner)
    os.environ["MURAKUMO_EFFECT_RUNNER"] = str(effect_runner)
    os.environ["MURAKUMO_SOUND_RUNNER"] = str(sound_runner)
    os.environ["MURAKUMO_VISEME_RUNNER"] = str(viseme_runner)
    os.environ["MURAKUMO_TTS_SPEAKER_VERSION"] = "test-speaker-v2"
    loader = importlib.machinery.SourceFileLoader("murakumo_generation_api_test", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class VoiceGenerationTest(unittest.TestCase):
    def test_running_job_cancellation_terminates_runner_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "slow-tts-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, subprocess, sys, time
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
(out.parent / 'started').write_text('yes')
subprocess.Popen([sys.executable, '-c',
  "import pathlib,time; time.sleep(0.5); pathlib.Path(r'%s').write_text('survived')" % (out.parent / 'descendant-survived')])
time.sleep(30)
out.write_bytes(b'unreachable')
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", runner)
            job_id = "cancel-running"
            api.jobs[job_id] = {"jobId": job_id, "status": "queued", "progress": 1,
                                "outputKind": "wav", "artifacts": []}
            worker = threading.Thread(target=api.run_voice_job,
                                      args=(job_id, "hello", {"locale": "en-US"}))
            worker.start()
            deadline = time.monotonic() + 3
            while job_id not in api.processes and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertIn(job_id, api.processes)
            self.assertEqual(api.cancel_job(job_id)["status"], "cancelled")
            worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertEqual(api.jobs[job_id]["status"], "cancelled")
            self.assertEqual(api.jobs[job_id]["error"], "cancelled-by-owner")
            self.assertNotIn(job_id, api.processes)
            self.assertFalse((api.ROOT / job_id / "voice.wav").exists())
            time.sleep(0.7)
            self.assertFalse((api.ROOT / job_id / "descendant-survived").exists(),
                             "cancellation must terminate runner descendants")
            self.assertEqual(api.cancel_job(job_id)["status"], "cancelled")
            self.assertIsNone(api.cancel_job("missing"))

    def test_delete_endpoint_requires_auth_and_cancels_exact_job_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            api = load_api(root / "artifacts", root / "unused-tts")
            api.TOKEN = "secret"
            api.jobs["queued"] = {"jobId": "queued", "status": "queued", "progress": 1,
                                  "outputKind": "wav", "artifacts": []}
            server = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                connection.request("DELETE", "/v1/generation/jobs/queued")
                self.assertEqual(connection.getresponse().status, 401)
                connection.request("DELETE", "/v1/generation/jobs/queued/artifact",
                                   headers={"authorization": "Bearer secret"})
                self.assertEqual(connection.getresponse().status, 404)
                connection.request("DELETE", "/v1/generation/jobs/queued",
                                   headers={"authorization": "Bearer secret"})
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(__import__("json").loads(response.read())["status"], "cancelled")
            finally:
                server.shutdown()
                server.server_close()
                thread.join(2)

    def test_all_product_locales_create_verified_wav_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "tts-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_bytes(b'RIFF' + (36).to_bytes(4, 'little') + b'WAVEfmt ' + (16).to_bytes(4, 'little') + b'\\x01\\x00\\x01\\x00' + (24000).to_bytes(4, 'little') + (48000).to_bytes(4, 'little') + b'\\x02\\x00\\x10\\x00data' + (4).to_bytes(4, 'little') + b'\\x00\\x00\\x00\\x00')
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", runner)
            self.assertEqual(api.validate_voice_request({"text": " hello "}, {"locale": "ja-JP"}),
                             " hello ")
            with self.assertRaisesRegex(ValueError, "unsupported"):
                api.validate_voice_request({"text": "hello"}, {"locale": "de-DE"})
            with self.assertRaisesRegex(ValueError, "1-2000"):
                api.validate_voice_request({"text": " "}, {"locale": "en-US"})
            with self.assertRaisesRegex(ValueError, "emotion"):
                api.validate_voice_request({"text": "hello"},
                                           {"locale": "en-US", "emotion": "surprised"})
            with self.assertRaisesRegex(ValueError, "speed"):
                api.validate_voice_request({"text": "hello"},
                                           {"locale": "en-US", "speed": 2.1})
            self.assertEqual(api.VOICE_LOCALES,
                             {"ja-JP", "en-US", "ar-SA", "hi-IN", "zh-CN", "es-ES", "fr-FR"})
            for index, locale in enumerate(sorted(api.VOICE_LOCALES)):
                job_id = f"job{index}"
                api.jobs[job_id] = {"jobId": job_id, "status": "queued", "progress": 1,
                                    "outputKind": "wav", "artifacts": []}
                api.run_voice_job(job_id, "hello", {"locale": locale, "license": "cc-by"})
                job = api.jobs[job_id]
                self.assertEqual(job["status"], "done")
                self.assertEqual(job["outputKind"], "wav")
                self.assertEqual(job["locale"], locale)
                self.assertIn(f"locale:{locale}", job["capabilities"])
                self.assertEqual(job["speaker"], {"id": "default", "version": "test-speaker-v2"})
                self.assertIn("speaker:default@test-speaker-v2", job["capabilities"])
                self.assertEqual(job["artifacts"][0]["license"], "cc-by")
                self.assertGreater(job["artifacts"][0]["durationMs"], 0)
                self.assertRegex(job["artifacts"][0]["contentHash"], r"^sha256:[0-9a-f]{64}$")
                self.assertTrue((api.ROOT / job_id / "voice.wav").exists())

    def test_bounded_provider_viseme_sidecar_is_validated_and_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "tts-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_bytes(b'RIFF' + (36).to_bytes(4, 'little') + b'WAVEfmt ' + (16).to_bytes(4, 'little') + b'\\x01\\x00\\x01\\x00' + (24000).to_bytes(4, 'little') + (48000).to_bytes(4, 'little') + b'\\x02\\x00\\x10\\x00data' + (4).to_bytes(4, 'little') + b'\\x00\\x00\\x00\\x00')
""")
            viseme = root / "viseme-runner"
            viseme.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_text(json.dumps({'schema':'kotoba.viseme-timeline/v1','cues':[{'timeMs':0,'viseme':'aa','weight':0.8}]}))
""")
            runner.chmod(0o755); viseme.chmod(0o755)
            api = load_api(root / "artifacts", runner, viseme_runner=viseme)
            api.jobs["voice-viseme"] = {"jobId": "voice-viseme", "status": "queued",
                                         "progress": 1, "outputKind": "wav", "artifacts": []}
            api.run_voice_job("voice-viseme", "hello", {"locale": "en-US"})
            job = api.jobs["voice-viseme"]
            self.assertEqual(job["status"], "done")
            self.assertEqual(job["visemes"]["cues"][0]["viseme"], "aa")
            self.assertEqual(job["visemeTimingSource"], "duration-derived/v1")
            self.assertIn("visemes:kotoba.viseme-timeline/v1", job["capabilities"])
            with self.assertRaisesRegex(RuntimeError, "invalid cue"):
                api.validate_viseme_timeline(
                    {"schema": "kotoba.viseme-timeline/v1",
                     "cues": [{"timeMs": 101, "viseme": "unknown", "weight": 2}]}, 100)

    def test_provider_alignment_visemes_take_precedence_over_generic_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "aligned-tts-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_bytes(b'RIFF' + (36).to_bytes(4, 'little') + b'WAVEfmt ' + (16).to_bytes(4, 'little') + b'\\x01\\x00\\x01\\x00' + (24000).to_bytes(4, 'little') + (48000).to_bytes(4, 'little') + b'\\x02\\x00\\x10\\x00data' + (4).to_bytes(4, 'little') + b'\\x00\\x00\\x00\\x00')
pathlib.Path(str(out) + '.metadata.json').write_text(json.dumps({'schema':'kotoba.voice-provider/v1','engine':'piper','speaker':'aligned-test','version':'test-v1','modelLicense':'cc0','timingSource':'piper-phoneme-alignment/v1','prosodySource':'piper-synthesis-config/v1','prosodyPreset':'happy','speakingRate':1.2}))
pathlib.Path(str(out) + '.visemes.json').write_text(json.dumps({'schema':'kotoba.viseme-timeline/v1','timingSource':'piper-phoneme-alignment/v1','cues':[{'timeMs':0,'viseme':'ee','weight':0.86}]}))
""")
            generic = root / "must-not-run-viseme"
            generic.write_text("""#!/usr/bin/env python3
raise SystemExit('provider timing must take precedence')
""")
            runner.chmod(0o755); generic.chmod(0o755)
            api = load_api(root / "artifacts", runner, viseme_runner=generic)
            api.jobs["aligned"] = {"jobId": "aligned", "status": "queued", "progress": 1,
                                     "outputKind": "wav", "artifacts": []}
            api.run_voice_job("aligned", "hello", {"locale": "en-US", "emotion": "happy", "speed": 1.2})
            job = api.jobs["aligned"]
            self.assertEqual(job["status"], "done")
            self.assertEqual(job["visemes"]["cues"],
                             [{"timeMs": 0.0, "viseme": "ee", "weight": 0.86}])
            self.assertEqual(job["voiceProvider"]["timingSource"],
                             "piper-phoneme-alignment/v1")
            self.assertEqual(job["visemeTimingSource"], "piper-phoneme-alignment/v1")
            self.assertIn("viseme-timing:piper-phoneme-alignment/v1", job["capabilities"])
            self.assertEqual(job["voiceProvider"]["prosodyPreset"], "happy")
            self.assertEqual(job["voiceProvider"]["speakingRate"], 1.2)

    def test_bounded_voice_provider_sidecar_is_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "tts-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_bytes(b'RIFF' + (36).to_bytes(4, 'little') + b'WAVEfmt ' + (16).to_bytes(4, 'little') + b'\\x01\\x00\\x01\\x00' + (24000).to_bytes(4, 'little') + (48000).to_bytes(4, 'little') + b'\\x02\\x00\\x10\\x00data' + (4).to_bytes(4, 'little') + b'\\x00\\x00\\x00\\x00')
pathlib.Path(str(out) + '.metadata.json').write_text(json.dumps({'schema':'kotoba.voice-provider/v1','engine':'piper','speaker':'en_US-kristin-medium','version':'piper-voices-main','modelLicense':'public-domain'}))
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", runner)
            api.jobs["provider"] = {"jobId": "provider", "status": "queued", "progress": 1,
                                      "outputKind": "wav", "artifacts": []}
            api.run_voice_job("provider", "hello", {"locale": "en-US"})
            job = api.jobs["provider"]
            self.assertEqual(job["speaker"], {"id": "en_US-kristin-medium", "version": "piper-voices-main"})
            self.assertEqual(job["voiceProvider"]["engine"], "piper")
            self.assertIn("tts-engine:piper", job["capabilities"])
            self.assertIn("model-license:public-domain", job["capabilities"])

    def test_invalid_runner_output_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "bad-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, sys
pathlib.Path(sys.argv[sys.argv.index('--output') + 1]).write_bytes(b'not-wav')
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", runner)
            api.jobs["bad"] = {"jobId": "bad", "status": "queued", "progress": 1,
                               "outputKind": "wav", "artifacts": []}
            api.run_voice_job("bad", "hello", {"locale": "en-US"})
            self.assertEqual(api.jobs["bad"]["status"], "failed")
            self.assertIn("invalid WAV", api.jobs["bad"]["error"])
            self.assertEqual(api.jobs["bad"]["artifacts"], [])


class MotionGenerationTest(unittest.TestCase):
    def test_vrm_humanoid_motion_has_real_changed_tracks_and_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "motion-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
duration = int(sys.argv[sys.argv.index('--duration-ms') + 1])
out.write_text(json.dumps({'schema':'kotoba.motion-clip/v1','skeletonProfile':'vrm-humanoid-1.0','durationMs':duration,'tracks':[{'bone':'hips','keyframes':[{'timeMs':0,'rotation':[0,0,0,1]},{'timeMs':duration,'rotation':[0,0.3826834,0,0.9238795]}]}]}))
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", runner)
            params = {"skeleton_profile": "vrm-humanoid-1.0", "duration_ms": 1800,
                      "license": "cc-by"}
            self.assertEqual(api.validate_motion_request({"prompt": "gentle wave"}, params), "gentle wave")
            api.jobs["motion"] = {"jobId": "motion", "status": "queued", "progress": 1,
                                  "outputKind": "motion-json", "artifacts": []}
            api.run_motion_job("motion", "gentle wave", params)
            job = api.jobs["motion"]
            self.assertEqual(job["status"], "done")
            self.assertEqual(job["outputKind"], "motion-json")
            self.assertEqual(job["durationMs"], 1800.0)
            self.assertEqual(job["skeletonProfile"], "vrm-humanoid-1.0")
            self.assertRegex(job["artifacts"][0]["contentHash"], r"^sha256:[0-9a-f]{64}$")
            self.assertIn("motion", job["capabilities"])

    def test_static_or_wrong_profile_motion_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "static-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_text(json.dumps({'schema':'kotoba.motion-clip/v1','skeletonProfile':'vrm-humanoid-1.0','durationMs':1000,'tracks':[{'bone':'hips','keyframes':[{'timeMs':0,'rotation':[0,0,0,1]},{'timeMs':1000,'rotation':[0,0,0,1]}]}]}))
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", runner)
            with self.assertRaisesRegex(ValueError, "skeleton_profile"):
                api.validate_motion_request({"prompt": "walk"},
                                            {"skeleton_profile": "mixamo", "duration_ms": 1000})
            params = {"skeleton_profile": "vrm-humanoid-1.0", "duration_ms": 1000}
            api.jobs["static"] = {"jobId": "static", "status": "queued", "progress": 1,
                                  "outputKind": "motion-json", "artifacts": []}
            api.run_motion_job("static", "walk", params)
            self.assertEqual(api.jobs["static"]["status"], "failed")
            self.assertIn("static clip", api.jobs["static"]["error"])
            self.assertEqual(api.jobs["static"]["artifacts"], [])


class EffectGenerationTest(unittest.TestCase):
    def test_bounded_effect_preset_has_verified_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "effect-runner"
            runner.write_text("""#!/usr/bin/env python3
import json, pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
duration = int(sys.argv[sys.argv.index('--duration-ms') + 1])
out.write_text(json.dumps({'schema':'kotoba.effect-preset/v1','preset':'sparkle','durationMs':duration,'intensity':0.7,'particles':12,'color':'#ff44aa'}))
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", "", runner)
            params = {"duration_ms": 1800, "license": "cc-by"}
            self.assertEqual(api.validate_effect_request({"prompt": "pink sparkle"}, params),
                             "pink sparkle")
            api.jobs["effect"] = {"jobId": "effect", "status": "queued", "progress": 1,
                                  "outputKind": "effect-json", "artifacts": []}
            api.run_effect_job("effect", "pink sparkle", params)
            job = api.jobs["effect"]
            self.assertEqual(job["status"], "done")
            self.assertEqual(job["outputKind"], "effect-json")
            self.assertEqual(job["durationMs"], 1800.0)
            self.assertIn("bounded-preset", job["capabilities"])
            self.assertRegex(job["artifacts"][0]["contentHash"], r"^sha256:[0-9a-f]{64}$")
            self.assertEqual(job["artifacts"][0]["license"], "cc-by")

    def test_effect_rejects_code_unknown_preset_and_excess_budget(self):
        valid = {"schema": "kotoba.effect-preset/v1", "preset": "sparkle",
                 "durationMs": 1000, "intensity": 0.5, "particles": 8, "color": "#ffffff"}
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "effect-runner"
            runner.write_text("#!/bin/sh\nexit 0\n")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", "", runner)
            with self.assertRaisesRegex(RuntimeError, "unknown preset"):
                api.validate_effect_document({**valid, "preset": "arbitrary-shader"}, 1000)
            with self.assertRaisesRegex(RuntimeError, "particle count"):
                api.validate_effect_document({**valid, "particles": 25}, 1000)
            with self.assertRaisesRegex(RuntimeError, "unsupported executable"):
                api.validate_effect_document({**valid, "shader": "while(true){}"}, 1000)


class SoundGenerationTest(unittest.TestCase):
    def test_looped_ambience_wav_has_verified_audio_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "sound-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, sys, wave
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
with wave.open(str(out), 'wb') as wav:
  wav.setnchannels(2); wav.setsampwidth(2); wav.setframerate(24000)
  wav.writeframes(b'\\x00\\x00' * 2 * 2400)
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", "", "", runner)
            params = {"sound_kind": "ambience", "duration_ms": 1000,
                      "loop": True, "license": "cc-by"}
            self.assertEqual(api.validate_sound_request({"prompt": "soft rain"}, params),
                             "soft rain")
            api.jobs["sound"] = {"jobId": "sound", "status": "queued", "progress": 1,
                                 "outputKind": "sound-wav", "artifacts": []}
            api.run_sound_job("sound", "soft rain", params)
            job, artifact = api.jobs["sound"], api.jobs["sound"]["artifacts"][0]
            self.assertEqual(job["status"], "done")
            self.assertEqual(job["outputKind"], "sound-wav")
            self.assertEqual(job["soundKind"], "ambience")
            self.assertTrue(job["loop"])
            self.assertEqual(artifact["channels"], 2)
            self.assertEqual(artifact["bitDepth"], 16)
            self.assertEqual(artifact["sampleRate"], 24000)
            self.assertEqual(artifact["loopStartMs"], 0)
            self.assertEqual(artifact["loopEndMs"], artifact["durationMs"])
            self.assertRegex(artifact["contentHash"], r"^sha256:[0-9a-f]{64}$")

    def test_sound_kind_loop_and_invalid_wav_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            runner = root / "bad-sound-runner"
            runner.write_text("""#!/usr/bin/env python3
import pathlib, sys
pathlib.Path(sys.argv[sys.argv.index('--output') + 1]).write_bytes(b'not-wav')
""")
            runner.chmod(0o755)
            api = load_api(root / "artifacts", root / "unused-tts", "", "", runner)
            with self.assertRaisesRegex(ValueError, "sfx, ambience or music"):
                api.validate_sound_request({"prompt": "noise"},
                                           {"sound_kind": "voice", "duration_ms": 1000})
            with self.assertRaisesRegex(ValueError, "loop is allowed"):
                api.validate_sound_request({"prompt": "click"},
                                           {"sound_kind": "sfx", "duration_ms": 1000, "loop": True})
            api.jobs["bad-sound"] = {"jobId": "bad-sound", "status": "queued",
                                     "progress": 1, "outputKind": "sound-wav", "artifacts": []}
            api.run_sound_job("bad-sound", "noise",
                              {"sound_kind": "sfx", "duration_ms": 1000})
            self.assertEqual(api.jobs["bad-sound"]["status"], "failed")
            self.assertIn("invalid WAV", api.jobs["bad-sound"]["error"])


if __name__ == "__main__":
    unittest.main()
