#!/usr/bin/env python3
"""murakumo_infer_bench.py - no_agent pre-run script for murakumo-tok.

stdout is injected into the agent's prompt as this tick's measurement.
Measures ONLY real things:
  1. registry declaration (GET /v1/models, token via MURAKUMO_API_TOKEN env)
  2. live tok/s at conc 1 and 2 (conc>=4 known to 502 as of 2026-09-05;
     probes conc 4 once with n=2 only when FORCE_CONC4=1)
  3. writes one append-only line to
     ~/.hermes/profiles/murakumo-tok/workspace/tok-ledger.jsonl
     and one to workspace/case-log.jsonl when registry notes changed.

Env: MURAKUMO_API_TOKEN (required), MAX_TOKENS (default 96), FORCE_CONC4.
Propose-only: never edits jobs, never calls murakumo admin endpoints.
"""
import json, os, sys, time, urllib.request, urllib.error, concurrent.futures

HOME = os.path.expanduser("~")
LEDGER = os.path.join(HOME, ".hermes/profiles/murakumo-tok/workspace/tok-ledger.jsonl")
CASELOG = os.path.join(HOME, ".hermes/profiles/murakumo-tok/workspace/case-log.jsonl")
PREV = os.path.join(HOME, ".hermes/profiles/murakumo-tok/workspace/.prev-registry.json")

TOKEN = os.environ.get("MURAKUMO_API_TOKEN", "")
MAXTOK = int(os.environ.get("MAX_TOKENS", "96"))
BASE = "https://api.murakumo.cloud/v1"
UA = "Mozilla/5.0 (Macintosh) murakumo-tok-bench/1.0"


def req(url, body=None):
    headers = {"User-Agent": UA, "Accept": "application/json"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    data = json.dumps(body).encode() if body is not None else None
    if data:
        headers["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return json.loads(resp.read()), None
    except urllib.error.HTTPError as e:
        return None, "HTTP %d: %s" % (e.code, e.read().decode()[:160])
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e)


def registry():
    d, err = req(BASE + "/models")
    if err:
        return None, err
    out = []
    for m in d.get("data", []):
        mu = m.get("murakumo") or {}
        cap = m.get("capacity") or {}
        out.append({
            "id": m.get("id"),
            "declared_status": m.get("declared-status") or m.get("status"),
            "max_concurrency": cap.get("max_concurrency"),
            "capacity_tok_s": mu.get("capacity-measured-aggregate-tok-s"),
            "capacity_at": mu.get("capacity-measured-at"),
            "note": (m.get("note") or "")[:400],
        })
    return out, None


def one(max_tokens=MAXTOK):
    body = {"model": "murakumo-main",
            "messages": [{"role": "user",
                          "content": "Write continuously about rain for %d tokens." % max_tokens}],
            "max_tokens": max_tokens, "temperature": 0.7}
    t0 = time.time()
    d, err = req(BASE + "/chat/completions", body)
    dt = time.time() - t0
    if err:
        return dt, 0, err
    return dt, d.get("usage", {}).get("completion_tokens", 0), None


def bench(conc, n):
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(conc) as ex:
        res = list(ex.map(lambda _: one(), range(n)))
    wall = time.time() - t0
    errs = [e for _, _, e in res if e]
    tot = sum(c for _, c, _ in res)
    return {"conc": conc, "n": n, "wall_s": round(wall, 2),
            "total_completion_tokens": tot,
            "aggregate_tok_s": round(tot / wall, 2) if wall > 0 else None,
            "avg_req_s": round(sum(dt for dt, _, _ in res) / n, 2),
            "err_count": len(errs), "err_sample": errs[:1]}


def main():
    if not TOKEN:
        print("MEASUREMENT FAILED: MURAKUMO_API_TOKEN not set in env (secrets.command)")
        sys.exit(1)
    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    report = {"date": now, "registry": None, "benches": [], "drift": None}

    reg, rerr = registry()
    if rerr:
        print("REGISTRY READ FAILED:", rerr)
    else:
        report["registry"] = reg
        prev = None
        if os.path.exists(PREV):
            try:
                prev = json.load(open(PREV))
            except Exception:
                prev = None
        if prev is not None:
            pmap = {m["id"]: m for m in prev}
            drift = []
            for m in reg:
                p = pmap.get(m["id"])
                if p and (p["note"] != m["note"] or p["declared_status"] != m["declared_status"]
                          or p["capacity_tok_s"] != m["capacity_tok_s"]):
                    drift.append({"id": m["id"],
                                  "note_changed": p["note"] != m["note"],
                                  "status": [p["declared_status"], m["declared_status"]],
                                  "capacity": [p["capacity_tok_s"], m["capacity_tok_s"]]})
            ids_gone = [i for i in pmap if i not in {m["id"] for m in reg}]
            ids_new = [m["id"] for m in reg if m["id"] not in pmap]
            report["drift"] = {"changed": drift, "removed": ids_gone, "added": ids_new}
            if drift or ids_gone or ids_new:
                with open(CASELOG, "a") as f:
                    f.write(json.dumps({"ts": now, "kind": "registry-drift",
                                        "drift": report["drift"]}) + "\n")
        json.dump(reg, open(PREV, "w"))

    report["benches"].append(bench(1, 2))
    report["benches"].append(bench(2, 4))
    if os.environ.get("FORCE_CONC4"):
        report["benches"].append(bench(4, 2))

    best = max((b for b in report["benches"] if b["err_count"] == 0),
               key=lambda b: b["aggregate_tok_s"], default=None)
    report["practical_conc"] = best["conc"] if best else None
    report["practical_tok_s"] = best["aggregate_tok_s"] if best else None

    prev_line = None
    if os.path.exists(LEDGER):
        with open(LEDGER) as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        if lines:
            prev_line = json.loads(lines[-1])
    report["delta_vs_prev"] = None
    if prev_line and prev_line.get("practical_tok_s") and report["practical_tok_s"]:
        report["delta_vs_prev"] = round(
            (report["practical_tok_s"] - prev_line["practical_tok_s"])
            / prev_line["practical_tok_s"] * 100, 1)

    with open(LEDGER, "a") as f:
        f.write(json.dumps(report) + "\n")

    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
