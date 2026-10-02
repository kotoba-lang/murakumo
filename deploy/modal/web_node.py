"""murakumo web node on Modal (ADR-260929): a second, DIFFERENT-EGRESS node.

The same stock-nbb bundle the fleet nodes run (scripts/web-bundle.sh) is baked
into the image and driven through one function. There is NO public endpoint: the
function is invoked through Modal's authenticated API with the operator's Modal
token (scripts/modal_call.py), so nothing on the internet can call it.

State (node.key, store/) lives on a Volume so the node keeps one did:key and its
stored artifacts across containers. One container at a time (max_containers=1)
so volume reload/commit never race.

    scripts/web-bundle.sh build/web-bundle
    MURAKUMO_WEB_BUNDLE=build/web-bundle modal deploy deploy/modal/web_node.py
    # remove it:  modal app stop murakumo-web-node
"""
import os
import subprocess

import modal

BUNDLE = os.environ.get("MURAKUMO_WEB_BUNDLE", "build/web-bundle")

app = modal.App("murakumo-web-node")
state = modal.Volume.from_name("murakumo-web-node-state", create_if_missing=True)

image = (
    modal.Image.from_registry("node:22-slim", add_python="3.12")
    .apt_install("curl", "ca-certificates")
    .run_commands("npm install -g nbb@1.5.211")
    .add_local_dir(BUNDLE, "/opt/web-bundle", copy=True)
)

MAX_REQUEST_BYTES = 1024 * 1024


@app.function(image=image, volumes={"/state": state}, timeout=900, max_containers=1)
def run(request: str) -> str:
    """One EDN request in, one EDN line out — the same contract as web_node.cljs on ssh."""
    if len(request.encode()) > MAX_REQUEST_BYTES:
        return '{:refused :node/request-too-large}'
    state.reload()
    env = {**os.environ, "MURAKUMO_WEB_HOME": "/state/home", "HOME": "/state"}
    p = subprocess.run(
        ["nbb", "-cp", "/opt/web-bundle", "/opt/web-bundle/web_node.cljs"],
        input=request, capture_output=True, text=True, env=env, timeout=850,
    )
    state.commit()
    lines = [l for l in p.stdout.splitlines() if l.strip()]
    if p.returncode != 0 or not lines:
        return '{:refused :node/exec-failed :detail "exit %d"}' % p.returncode
    return lines[-1]
