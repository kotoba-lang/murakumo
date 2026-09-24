#!/usr/bin/env python3
"""amu_perf_read.py - read-only aggregate of amu compiler perf from amu-bench logs.

Reads ONLY real logs (no LLM, no inference):
  ~/.hermes/profiles/amu-bench/cron/executions.db  (job wall-clock, status)
  ~/.hermes/profiles/amu-bench/state.db sessions    (session_model_usage)
Outputs the last N days of amu-bench run counts, failure rate, wall-clock trend.
"""
import json, os, sqlite3

DAYS = 7
AB = os.path.expanduser("~/.hermes/profiles/amu-bench")
out = {"source": "amu-bench", "days": DAYS}

edb = os.path.join(AB, "cron", "executions.db")
if os.path.exists(edb):
    con = sqlite3.connect("file:%s?mode=ro" % edb, uri=True)
    st = dict(con.execute(
        "SELECT status, COUNT(*) FROM executions"
        " WHERE claimed_at > datetime('now', '-%d days') GROUP BY status" % DAYS).fetchall())
    wall = con.execute(
        "SELECT AVG(julianday(finished_at)-julianday(started_at))*86400,"
        " COUNT(*) FROM executions"
        " WHERE claimed_at > datetime('now', '-%d days')"
        " AND started_at IS NOT NULL AND finished_at IS NOT NULL" % DAYS).fetchone()
    errs = con.execute(
        "SELECT substr(COALESCE(error,''),1,90), COUNT(*) FROM executions"
        " WHERE status='failed' AND claimed_at > datetime('now', '-%d days')"
        " AND error IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 3" % DAYS).fetchall()
    con.close()
    out["exec_status"] = st
    out["avg_wall_s"] = round(wall[0], 1) if wall and wall[0] else None
    out["wall_n"] = wall[1] if wall else 0
    out["top_errors"] = errs
else:
    out["error"] = "executions.db not found"

print(json.dumps(out, ensure_ascii=False))
