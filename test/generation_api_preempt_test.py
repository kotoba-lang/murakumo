"""Preemption around exclusive jobs (resource-classes.edn :preempts) and image
jobs taking a class. Mirrors generation_api_image_test's importlib harness."""
import importlib.machinery
import importlib.util
import os
import pathlib
import tempfile
import threading
import unittest

SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-generation-api"


def load_api(root):
    os.environ["MURAKUMO_GENERATION_DIR"] = str(root)
    for k in ("MURAKUMO_TTS_RUNNER", "MURAKUMO_MOTION_RUNNER", "MURAKUMO_EFFECT_RUNNER",
              "MURAKUMO_SOUND_RUNNER", "MURAKUMO_VISEME_RUNNER", "MURAKUMO_COMFY_URL"):
        os.environ[k] = ""
    os.environ["MURAKUMO_RESOURCE_CLASSES"] = str(
        pathlib.Path(__file__).parents[1] / "resources" / "murakumo" / "resource-classes.edn")
    os.environ["MURAKUMO_PREEMPT_LOG"] = str(root / "preempt.log")
    loader = importlib.machinery.SourceFileLoader("murakumo_generation_api_preempt_test", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class FakeRun:
    def __init__(self, rc=0, stderr=""):
        self.returncode = rc
        self.stderr = stderr


class PreemptTest(unittest.TestCase):
    def test_the_table_decides_what_is_preempted(self):
        with tempfile.TemporaryDirectory() as d:
            api = load_api(pathlib.Path(d))
            self.assertEqual(["murakumo-ring.service"], api.preempt_units({"model": "minimax-h3"}))
            self.assertEqual([], api.preempt_units({"model": "ltx-2.3"}))
            self.assertEqual([], api.preempt_units({}))

    def test_image_checkpoints_take_the_apu_class_video_takes(self):
        with tempfile.TemporaryDirectory() as d:
            api = load_api(pathlib.Path(d))
            self.assertEqual(":apu/video", api.job_class("video", {"model": "minimax-h3"}))
            self.assertEqual(":apu/video", api.job_class("image", {"model": "waiREALMIX_v11"}))
            self.assertEqual(":apu/video", api.job_class("image", {}), "the default image model is listed")
            self.assertIsNone(api.job_class("image", {"model": "some-unlisted-checkpoint"}))
            self.assertIsNone(api.job_class("voice", {"model": "minimax-h3"}))

    def test_run_stops_before_and_starts_after_even_when_the_job_fails(self):
        with tempfile.TemporaryDirectory() as d:
            api = load_api(pathlib.Path(d))
            calls = []
            api.PREEMPT_RUNNER = lambda action, unit: (calls.append((action, unit)), FakeRun())[1]
            api.jobs["j1"] = {"status": "queued"}
            order = []

            def target(job_id, source, params):
                # job runners catch their own exceptions and mark the job;
                # this one fails the job the way run_image_job would
                order.append(("job", list(calls)))
                api.jobs[job_id].update(status="failed", error="render died")

            api.running_in_class[":apu/video"] = 1
            api._run_and_release("j1", target, "p", {"model": "minimax-h3"}, ":apu/video")
            self.assertEqual([("stop", "murakumo-ring.service"), ("start", "murakumo-ring.service")], calls)
            self.assertEqual([("job", [("stop", "murakumo-ring.service")])], order, "the ring was down while the job ran")
            self.assertEqual(0, api.running_in_class[":apu/video"], "the class is released")
            log = (pathlib.Path(d) / "preempt.log").read_text()
            self.assertIn("event=stopped unit=murakumo-ring.service job=j1", log)
            self.assertIn("event=started unit=murakumo-ring.service job=j1", log)

    def test_a_unit_that_will_not_stop_fails_the_job_honestly(self):
        with tempfile.TemporaryDirectory() as d:
            api = load_api(pathlib.Path(d))
            api.PREEMPT_RUNNER = lambda action, unit: FakeRun(1, "Job for murakumo-ring.service canceled")
            api.jobs["j2"] = {"status": "queued"}
            ran = []
            api.running_in_class[":apu/video"] = 1
            api._run_and_release("j2", lambda *a: ran.append(1), "p", {"model": "minimax-h3"}, ":apu/video")
            self.assertEqual([], ran, "the job never ran on memory it did not have")
            self.assertEqual("failed", api.jobs["j2"]["status"])
            self.assertIn("could not preempt murakumo-ring.service", api.jobs["j2"]["error"])
            self.assertEqual(0, api.running_in_class[":apu/video"])

    def test_a_job_with_nothing_to_preempt_touches_no_unit(self):
        with tempfile.TemporaryDirectory() as d:
            api = load_api(pathlib.Path(d))
            calls = []
            api.PREEMPT_RUNNER = lambda action, unit: (calls.append((action, unit)), FakeRun())[1]
            api.jobs["j3"] = {"status": "queued"}
            api._run_and_release("j3", lambda *a: None, "p", {"model": "waiREALMIX_v11"}, ":apu/video")
            self.assertEqual([], calls)


class ExternalBusyProbeTest(unittest.TestCase):
    """A stopped sibling ComfyUI (connection refused) holds nothing; a probe that
    cannot answer (timeout, garbage) is still busy."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.api = load_api(pathlib.Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def _with_bases(self, bases):
        self.api.COMFY_BASES[:] = bases
        self.api._external_busy_cache.clear()
        return self.api.class_externally_busy(":apu/video")

    def _closed_port(self):
        import socket
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        return "http://127.0.0.1:%d" % port

    def test_a_refused_sibling_is_not_busy(self):
        self.assertFalse(self._with_bases([self._closed_port()]))

    def test_an_unanswerable_probe_is_still_busy(self):
        import socket
        srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(1)
        base = "http://127.0.0.1:%d" % srv.getsockname()[1]
        orig = self.api.comfy_json
        self.api.comfy_json = lambda *a, **k: orig(*a, **{**k, "timeout": 0.3})
        try:
            self.assertTrue(self._with_bases([base]), "accepted but never answered = cannot tell")
        finally:
            self.api.comfy_json = orig
            srv.close()

    def test_refused_plus_a_busy_sibling_is_busy(self):
        self.api.comfy_queue_busy = lambda base: base == "http://busy"
        self.assertTrue(self._with_bases([self._closed_port(), "http://busy"]))


if __name__ == "__main__":
    unittest.main()
