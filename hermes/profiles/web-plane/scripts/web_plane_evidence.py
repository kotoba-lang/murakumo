#!/usr/bin/env python3
"""Read-only evidence for the web-plane bot (ADR-260929).

Runs scripts/web-fleet-health.cljk against web-nodes.edn and prints MEASURE
lines. Exit codes are load-bearing and three-valued:
  0  every node reachable and healthy
  1  measured, and at least one node is unhealthy or unreachable
  2  could not measure (tool missing, script failed to run)
It performs no writes and no crawl.
"""
import os, subprocess, sys

REPO = os.environ.get("MURAKUMO_REPO", os.path.expanduser("~/github/kotoba-lang/murakumo"))

def main():
    cmd = ["kbb", "--classpath", "src:test", "scripts/web-fleet-health.cljk", "web-nodes.edn"]
    try:
        r = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=300)
    except FileNotFoundError:
        print("MEASURE\tweb_health\tUNMEASURED\tkbb-not-found"); return 2
    except subprocess.TimeoutExpired:
        print("MEASURE\tweb_health\tUNMEASURED\ttimeout"); return 2
    print("=== WEB PLANE NODE HEALTH ===")
    for line in r.stdout.splitlines():
        print("MEASURE\tnode\t" + line)
    if r.returncode not in (0, 1):
        print("MEASURE\tweb_health\tUNMEASURED\texit-%d" % r.returncode)
        sys.stderr.write(r.stderr[-500:]); return 2
    return r.returncode

if __name__ == "__main__":
    sys.exit(main())
