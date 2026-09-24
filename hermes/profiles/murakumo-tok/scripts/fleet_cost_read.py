#!/usr/bin/env python3
"""fleet_cost_read.py - read-only view of murakumo fleet CI cost EMA.

Reads ONLY ~/.gftd/fleet-ci-cost.edn (append-only EMA ledger, batch-unit
upper bounds; NOT absolute durations). Outputs recent entries so the agent
can spot cost drift. Never edits the file.
"""
import os, re, json

P = os.path.expanduser("~/.gftd/fleet-ci-cost.edn")
if not os.path.exists(P):
    print(json.dumps({"source": "fleet-ci-cost.edn", "error": "not found"}))
    raise SystemExit
txt = open(P).read()
entries = re.findall(r"[:{]\s*([\w#./-]+)\s*\{:ema-s\s+([\d.]+)", txt)
out = {"source": "fleet-ci-cost.edn", "total_entries": len(entries),
       "note": "EMA batch-unit upper bounds; relative weights only",
       "last_5": entries[-5:]}
print(json.dumps(out, ensure_ascii=False))
