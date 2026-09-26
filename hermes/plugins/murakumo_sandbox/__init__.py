"""Hermes terminal backend backed by Murakumo placement and a remote OCI sandbox.

The gateway owns the profile/session. This backend owns only the disposable tool
filesystem and command process. It deliberately does not copy profile secrets or
arbitrary host worktrees to fleet nodes.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shlex
import shutil
import subprocess
import uuid
from pathlib import Path

from agent.secret_scope import get_secret
from agent.terminal_env_provider import TerminalEnvironmentProvider
from tools.environments.base import BaseEnvironment, EnvironmentConnectionError
from tools.environments.base_output import _popen_bash

LOG = logging.getLogger(__name__)
DEFAULT_IMAGE = "debian@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251"
HOST_RE = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_.-]*@)?[A-Za-z0-9][A-Za-z0-9.-]*$")
IMAGE_RE = re.compile(r"^[a-z0-9][a-z0-9./_-]*@sha256:[a-f0-9]{64}$")


def setting(name: str, default: str = "") -> str:
    """Read under Hermes' active profile scope; never fall through to another profile."""
    return str(get_secret(name, default) or default).strip()


def ssh_command(host: str, args: list[str]) -> list[str]:
    if not HOST_RE.fullmatch(host) or host.startswith("-"):
        raise ValueError("Murakumo placement returned an invalid SSH host")
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", host, shlex.join(args)]


def remote(host: str, args: list[str], *, timeout: int = 20) -> str:
    result = subprocess.run(ssh_command(host, args), text=True, capture_output=True,
                            stdin=subprocess.DEVNULL, timeout=timeout, check=False)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()[:400]
        raise EnvironmentConnectionError(f"Murakumo node command failed (exit {result.returncode}): {detail}")
    return result.stdout.strip()


def select_host(task_root: Path, nodes: str, labels: str = "", max_per_node: int = 2) -> tuple[str, str]:
    allowed_nodes = nodes.split(",")
    if not nodes or not all(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9-]*", n) for n in allowed_nodes):
        raise ValueError("MURAKUMO_SANDBOX_NODES must be a comma-separated inventory node allowlist")
    args = ["kbb", "--backend", "sci", "scripts/run-task.cljk", "task", "plan",
            "--n", str(len(allowed_nodes)), "--cmd", "true", "--nodes", nodes,
            "--max-inflight", str(len(allowed_nodes)), "--format", "json"]
    if labels:
        args += ["--labels", labels]
    try:
        result = subprocess.run(args, cwd=task_root, text=True, capture_output=True,
                                stdin=subprocess.DEVNULL, timeout=90, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise EnvironmentConnectionError(f"Murakumo placement could not run: {exc}") from exc
    if result.returncode != 0:
        raise EnvironmentConnectionError(f"Murakumo placement refused (exit {result.returncode}): {result.stderr[-400:]}")
    try:
        plan = json.loads(result.stdout.strip().splitlines()[-1])
        placements = plan["placements"]
        if plan["assigned"] < 1 or not placements:
            raise ValueError("no eligible sandbox node")
        candidates = {}
        for placement in placements:
            node, host = placement["node"], placement["host"]
            if node not in allowed_nodes or not HOST_RE.fullmatch(host) or host.startswith("-"):
                raise ValueError("placement escaped the node allowlist")
            candidates[node] = host
        available = []
        for node, host in candidates.items():
            try:
                running = remote(host, ["docker", "ps", "--filter", "label=hermes.murakumo.sandbox=1",
                                        "--format", "{{.ID}}"], timeout=15)
                count = len(running.splitlines()) if running else 0
                if count < max_per_node:
                    available.append((count, uuid.uuid4().hex, node, host))
            except (EnvironmentConnectionError, subprocess.TimeoutExpired) as exc:
                LOG.warning("Murakumo sandbox candidate %s unavailable: %s", node, exc)
        if not available:
            raise ValueError("no Docker-capable sandbox node below its concurrency limit")
        _, _, node, host = min(available)
        return node, host
    except (IndexError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise EnvironmentConnectionError(f"Murakumo placement returned no usable node: {exc}") from exc


class MurakumoEnvironment(BaseEnvironment):
    """One session-bound remote Docker sandbox; commands share its filesystem."""

    def __init__(self, *, host: str, image: str, task_id: str, timeout: int,
                 cpu: float = 1, memory_mb: int = 512):
        if not IMAGE_RE.fullmatch(image):
            raise ValueError("MURAKUMO_SANDBOX_IMAGE must be pinned by sha256 digest")
        self.host = host
        self.image = image
        self.task_id = task_id
        self.name = "hermes-murakumo-" + uuid.uuid4().hex[:16]
        self._closed = False
        super().__init__(cwd="/workspace", timeout=timeout)
        remote(host, ["docker", "version", "--format", "{{.Server.Version}}"], timeout=15)
        args = ["docker", "run", "--rm", "-d", "--name", self.name,
                "--label", "hermes.murakumo.sandbox=1",
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--pids-limit", "128",
                "--cpus", str(max(0.25, min(float(cpu), 4.0))),
                "--memory", f"{max(128, min(int(memory_mb), 4096))}m",
                "--user", "65534:65534",
                "--tmpfs", "/workspace:rw,exec,nosuid,size=512m,mode=1777",
                "--tmpfs", "/tmp:rw,nosuid,noexec,size=128m,mode=1777",
                "--workdir", "/workspace", image, "sleep", "3600"]
        try:
            remote(host, args, timeout=120)
            self.init_session()
        except BaseException:
            self.cleanup()
            raise

    def _run_bash(self, cmd_string: str, *, login: bool = False, timeout: int = 120,
                  stdin_data: str | None = None):
        args = ["docker", "exec"]
        if stdin_data is not None:
            args.append("-i")
        args += ["--workdir", "/workspace", self.name, "timeout", "-k", "2",
                 str(max(1, int(timeout))), "bash", "-lc" if login else "-c", cmd_string]
        return _popen_bash(ssh_command(self.host, args), stdin_data)

    def cleanup(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            remote(self.host, ["docker", "rm", "-f", self.name], timeout=20)
        except Exception as exc:
            LOG.warning("Murakumo sandbox cleanup failed for %s: %s", self.name, exc)


class MurakumoSandboxProvider(TerminalEnvironmentProvider):
    name = "murakumo_sandbox"
    display_name = "Murakumo Sandbox"
    is_remote = True
    is_container = True
    session_isolated_when_nonpersistent = True

    @property
    def description(self) -> str:
        return "Run terminal tools in a session-bound OCI sandbox placed on the Murakumo fleet."

    @property
    def skip_container_guards(self) -> bool:
        # Keep Hermes approvals until the fleet boundary is qualified for every allowed node.
        return False

    def is_available(self) -> bool:
        configured_root = setting("MURAKUMO_TASK_ROOT")
        root = Path(configured_root) if configured_root else Path("/")
        return bool(configured_root and shutil.which("kbb") and setting("MURAKUMO_SANDBOX_NODES")
                    and (root / "scripts/run-task.cljk").is_file())

    def create_environment(self, *, cwd: str, timeout: int, task_id: str = "default",
                           image: str | None = None, container_config: dict | None = None, **kwargs):
        root = Path(setting("MURAKUMO_TASK_ROOT"))
        nodes = setting("MURAKUMO_SANDBOX_NODES")
        labels = setting("MURAKUMO_SANDBOX_LABELS")
        selected_image = setting("MURAKUMO_SANDBOX_IMAGE", DEFAULT_IMAGE)
        if not (root / "scripts/run-task.cljk").is_file():
            raise EnvironmentConnectionError("MURAKUMO_TASK_ROOT is not a Murakumo checkout")
        max_per_node = int(setting("MURAKUMO_SANDBOX_MAX_PER_NODE", "2"))
        if not 1 <= max_per_node <= 16:
            raise EnvironmentConnectionError("MURAKUMO_SANDBOX_MAX_PER_NODE must be 1..16")
        node, host = select_host(root, nodes, labels, max_per_node)
        LOG.info("Murakumo sandbox placement: task=%s node=%s", task_id, node)
        resources = container_config or {}
        return MurakumoEnvironment(host=host, image=selected_image, task_id=task_id,
                                   timeout=timeout, cpu=resources.get("cpu") or 1,
                                   memory_mb=resources.get("memory") or 512)


def register(ctx):
    ctx.register_terminal_environment_provider(MurakumoSandboxProvider())
