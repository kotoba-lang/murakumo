#!/usr/bin/env python3
"""Admission-queue checks for hunyuan3d-generation-api.

Run: python3 scripts/test-generation-queue.py

Every check here has to be able to FAIL for the right reason. The bug this
guards against is not "the queue is wrong" — it is "there was no queue and
everything looked fine", so several checks assert the *negative* direction
too: a broken table must refuse to load, and capacity 2 must actually let two
jobs run (otherwise "only one ran" would pass for the wrong reason).
"""
import importlib.machinery
import importlib.util
import os
import pathlib
import sys
import tempfile
import threading
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TABLE = REPO / "resources" / "murakumo" / "resource-classes.edn"

failures = []


def check(name, got, want):
    """`got` may be a value or a thunk. A thunk that raises is a FAILURE, not a
    crash: when this file first broke reconcile on purpose, the assertion threw
    KeyError, the summary never printed, and anything grepping for "FAILED"
    saw silence. A check that cannot report its own failure is not a check."""
    if callable(got):
        try:
            got = got()
        except Exception as exc:
            failures.append("%s\n    raised %s: %s" % (name, type(exc).__name__, exc))
            return
    if got != want:
        failures.append("%s\n    got  %r\n    want %r" % (name, got, want))
    else:
        print("ok   %s" % name)


def check_raises(name, fn):
    try:
        fn()
    except Exception as exc:
        print("ok   %s (refused: %s)" % (name, str(exc)[:60]))
        return
    failures.append("%s — expected a refusal, got none" % name)


def load(table_path=TABLE, node="gad", tmp=None):
    os.environ["MURAKUMO_RESOURCE_CLASSES"] = str(table_path)
    os.environ["MURAKUMO_NODE_NAME"] = node
    os.environ["MURAKUMO_GENERATION_DIR"] = tmp or tempfile.mkdtemp()
    name = "genapi_%d" % time.time_ns()
    spec = importlib.util.spec_from_loader(
        name,
        importlib.machinery.SourceFileLoader(
            name, str(HERE / "hunyuan3d-generation-api")))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# --- the table loads, and a broken one refuses ------------------------------

m = load()
check("table declares :apu/video", ":apu/video" in m.RESOURCE_CLASSES[":classes"], True)

bad = pathlib.Path(tempfile.mkdtemp()) / "broken.edn"
bad.write_text("{:classes {:apu/video {:capacity 1}}")          # unclosed map
check_raises("a truncated table refuses to load (fail closed)", lambda: load(bad))

bad2 = pathlib.Path(tempfile.mkdtemp()) / "missing.edn"
check_raises("a missing table refuses to load (fail closed)", lambda: load(bad2))

# --- which jobs take the exclusive class ------------------------------------

check("minimax-h3 takes :apu/video",
      m.job_class("video", {"model": "minimax-h3"}), ":apu/video")
check("wan2.2 takes the SAME class (that is the point)",
      m.job_class("video", {"model": "wan2.2-ti2v-5b"}), ":apu/video")
check("an image job takes no class",
      m.job_class("image", {"model": "sdxl-turbo"}), None)
check("a hosted video model takes no class on this node",
      m.job_class("video", {"model": "seedance-2.0-fast"}, {"host": "fal"}), None)

# --- capacity ---------------------------------------------------------------

check("gad capacity is 1", m.class_capacity(":apu/video"), 1)
m2 = load(node="some-other-node")
check("a node with no override falls back to :capacity", m2.class_capacity(":apu/video"), 1)

# --- deadline ---------------------------------------------------------------

check("unknown model keeps the floor",
      m.video_deadline({"model": "wan2.2-ti2v-5b"}), 1800)
d124 = m.video_deadline({"model": "minimax-h3", "frames": 124, "steps": 20})
check("minimax at 124f exceeds the old 1800s constant", d124 > 1800, True)
check("minimax at 124f covers the measured 2284s", d124 > 2284, True)
check("minimax at 175f is larger than at 124f",
      m.video_deadline({"model": "minimax-h3", "frames": 175, "steps": 20}) > d124, True)

# --- the dispatcher actually holds the line ---------------------------------

def run_capacity_probe(mod, capacity, n_jobs=3):
    """Start n_jobs against one class and report the maximum number that were
    ever running at the same time."""
    mod.class_capacity = lambda _k: capacity
    peak = {"n": 0}
    live = {"n": 0}
    guard = threading.Lock()
    done = threading.Event()
    finished = {"n": 0}

    def body(_job_id, _source, _params):
        with guard:
            live["n"] += 1
            peak["n"] = max(peak["n"], live["n"])
        time.sleep(0.30)
        with guard:
            live["n"] -= 1
            finished["n"] += 1
            if finished["n"] == n_jobs:
                done.set()

    threading.Thread(target=mod._dispatch_loop, daemon=True).start()
    for i in range(n_jobs):
        jid = "job%d" % i
        mod.jobs[jid] = {"jobId": jid, "status": "queued", "progress": 1, "artifacts": []}
        mod.enqueue_job(jid, body, {}, {}, ":apu/video")
    done.wait(timeout=20)
    return peak["n"], finished["n"]


m3 = load()
peak1, fin1 = run_capacity_probe(m3, capacity=1)
check("3 jobs all finish under capacity 1", fin1, 3)
check("capacity 1 never runs two at once", peak1, 1)

# The negative direction: if the dispatcher ignored capacity, the check above
# would still read 1 whenever the machine happened to serialise them. Prove the
# probe can see concurrency at all.
m4 = load()
peak2, fin2 = run_capacity_probe(m4, capacity=2)
check("3 jobs all finish under capacity 2", fin2, 3)
check("capacity 2 does run two at once (the probe can see concurrency)", peak2, 2)

# --- the class can be held from outside this API -----------------------------

m6 = load()
check("the table asks for the external check",
      m6.RESOURCE_CLASSES[":classes"][":apu/video"].get(":external-busy-check"), ":comfy-any")

# A probe that cannot answer must read as busy: not knowing whether the APU is
# free is not the same as it being free.
m6.comfy_queue_busy = lambda base: (_ for _ in ()).throw(OSError("connection refused"))
m6.class_external_bases = lambda k: ["http://127.0.0.1:9"]
check("an unanswerable probe counts as busy", m6.class_externally_busy(":apu/video"), True)

m6.comfy_queue_busy = lambda base: True
check("a busy sibling counts as busy", m6.class_externally_busy(":apu/video"), True)
m6.comfy_queue_busy = lambda base: False
check("an idle sibling does not", m6.class_externally_busy(":apu/video"), False)

# And the dispatcher must actually hold on it — otherwise the check above is
# true but nothing uses it.
m7 = load()
m7.class_external_bases = lambda k: ["http://x"]
m7.comfy_queue_busy = lambda base: True
started = {"n": 0}
def _never(job_id, source, params):
    started["n"] += 1
threading.Thread(target=m7._dispatch_loop, daemon=True).start()
m7.jobs["held"] = {"jobId": "held", "status": "queued", "progress": 1, "artifacts": []}
m7.enqueue_job("held", _never, {}, {}, ":apu/video")
time.sleep(1.2)
check("a job is held while the class is externally busy", started["n"], 0)
m7.comfy_queue_busy = lambda base: False
time.sleep(2.5)
check("and starts once the class frees up", started["n"], 1)

# --- boot reconcile ---------------------------------------------------------

m5 = load()
m5.jobs.clear()
m5.jobs.update({"a": {"jobId": "a", "status": "running", "progress": 40},
                "b": {"jobId": "b", "status": "queued", "progress": 1},
                "c": {"jobId": "c", "status": "done", "progress": 100}})
check("reconcile strands exactly the two with no execution behind them",
      m5.reconcile_on_boot(), 2)
check("a running record becomes failed", lambda: m5.jobs["a"]["status"], "failed")
check("the reason is distinguishable", lambda: "restart" in m5.jobs["a"]["error"], True)
check("a finished job is left alone", lambda: m5.jobs["c"]["status"], "done")

# ---------------------------------------------------------------------------

print()
if failures:
    print("FAILED %d check(s):" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("all checks passed")
