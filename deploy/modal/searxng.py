"""A private SearXNG for the web plane's Phase 1 comparison (ADR-260929 M3).

Not a public endpoint: `search` is invoked through Modal's authenticated API
(scripts/modal_call.py) with the operator's credentials. SearXNG runs in-process
through its Flask test client, so no port is opened.

    modal deploy deploy/modal/searxng.py
    modal app stop murakumo-searxng          # turn it off

Datacenter addresses are often rate-limited by the big engines; the result count
per engine is returned so that shows up instead of being hidden.
"""
import json
import os

import modal

app = modal.App("murakumo-searxng")

SETTINGS = """
use_default_settings: true
general:
  instance_name: murakumo-private
server:
  secret_key: "%s"
  limiter: false
  public_instance: false
  image_proxy: false
search:
  formats: [html, json]
  safe_search: 0
ui:
  static_use_hash: false
"""

image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("git", "build-essential", "libxslt1-dev", "libxml2-dev", "zlib1g-dev", "libffi-dev", "libssl-dev")
    .run_commands(
        "git clone --depth 1 https://github.com/searxng/searxng /opt/searxng",
        "pip install --upgrade pip setuptools wheel",
        "cd /opt/searxng && pip install -r requirements.txt",
    )
)


@app.function(image=image, timeout=120, max_containers=2)
def search(request: str) -> str:
    """request: JSON {"q": str, "k": int}. Returns one JSON line {"results":[...], "engines":{...}}."""
    req = json.loads(request)
    q = str(req.get("q", ""))[:512]
    k = max(1, min(int(req.get("k", 10)), 50))
    path = "/tmp/searxng-settings.yml"
    with open(path, "w") as f:
        f.write(SETTINGS % os.urandom(16).hex())
    os.environ["SEARXNG_SETTINGS_PATH"] = path
    import sys
    sys.path.insert(0, "/opt/searxng")
    from searx import webapp  # noqa: E402  (import after settings are in place)

    client = webapp.app.test_client()
    resp = client.get("/search", query_string={"q": q, "format": "json"})
    if resp.status_code != 200:
        return json.dumps({"error": "http-%d" % resp.status_code})
    doc = resp.get_json()
    results = [
        {"url": r.get("url"), "title": r.get("title"), "content": r.get("content"), "engine": r.get("engine")}
        for r in doc.get("results", [])[:k]
    ]
    engines = {}
    for r in doc.get("results", []):
        engines[r.get("engine")] = engines.get(r.get("engine"), 0) + 1
    return json.dumps({"results": results, "engines": engines,
                       "unresponsive": [list(x) for x in doc.get("unresponsive_engines", [])]})
