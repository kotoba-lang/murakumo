#!/usr/bin/env python3
"""murakumo-native evidence: measure the num->torch->murakumo native stack.

Read-only. Emits MEASURE<TAB>key<TAB>value lines, appends to
workspace/native-infer-ledger.jsonl. Missing inputs are UNMEASURED.
"""
import json, subprocess, os, sys, time

PROFILE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WS = os.path.join(PROFILE, "workspace")
LEDGER = os.path.join(WS, "native-infer-ledger.jsonl")
ROOT = "~/github/com-junkawasaki"
CHECKOUTS = {
    "num-head": ROOT + "/orgs/kotoba-lang/num",
    "torch-head": ROOT + "/orgs/kotoba-lang/torch",
    "murakumo-head": ROOT + "/orgs/kotoba-lang/murakumo",
    "amu-head": ROOT + "/orgs/kotoba-lang/amu",
}

os.makedirs(WS, exist_ok=True)
out = []

def m(k, v):
    out.append((k, str(v)))
    print("MEASURE\t%s\t%s" % (k, v))

def sh(cmd, timeout=20):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() if r.returncode == 0 else None
    except Exception:
        return None

for k, path in CHECKOUTS.items():
    m(k, sh(["git", "-C", path, "rev-parse", "--short", "HEAD"]) or "UNMEASURED")

m("amu-binary", "present" if os.path.exists(ROOT + "/orgs/kotoba-lang/amu/bin/amu") else "MISSING")
m("smoke-kotoba", "present" if os.path.exists(
    MUR := CHECKOUTS["murakumo-head"] + "/kotoba/num_smoke_core.kotoba") else "MISSING")

last = None
if os.path.exists(LEDGER):
    try:
        with open(LEDGER) as f:
            for line in f:
                if line.strip():
                    last = line.strip()
    except Exception:
        last = None
m("ledger-last-tick", (last[:120] if last else "EMPTY"))

row = {"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
       "measures": dict(out)}
with open(LEDGER, "a") as f:
    f.write(json.dumps(row, ensure_ascii=False) + "\n")
print("LEDGER\t" + LEDGER)
