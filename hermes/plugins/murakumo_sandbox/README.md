# Murakumo Sandbox terminal backend for Hermes

This plugin keeps one Hermes gateway and its profile/session/cron state on the
controller. A terminal or file tool call creates a task-scoped OCI container on
an explicitly allowed Murakumo fleet node. Murakumo's `task plan` chooses the
node; the plugin drives the container over SSH. Every command in the Hermes
session uses the same container. Cleanup removes it; its hard one-hour process
lifetime bounds an orphan after controller failure.

The current execution contract is intentionally narrow: a read-only root image,
no network, no host mounts, no profile credential sync, a writable `/workspace`
and `/tmp` tmpfs, bounded CPU/memory/PIDs, and digest-pinned images. Hermes
command approvals remain active. Container files are ephemeral; callers can
read or fetch them through Hermes' file tools while the session is alive. A
separate artifact transfer must be designed before moving coding jobs that
require durable worktrees.

## Configuration

Install this directory as a Hermes backend plugin, then enable it. In a test
profile's `config.yaml`:

```yaml
terminal:
  backend: murakumo_sandbox
  container_cpu: 1
  container_memory: 512
```

Set these names in that profile's `.env` (values shown contain no secrets):

```text
MURAKUMO_TASK_ROOT=/absolute/path/to/murakumo
MURAKUMO_SANDBOX_NODES=aiueos-6600hs-1
MURAKUMO_SANDBOX_IMAGE=debian@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251
```

Leave `terminal.cwd` at the profile's local default; the sandbox command
directory is `/workspace`. Hermes also checks `terminal.cwd` on the gateway.

`MURAKUMO_SANDBOX_NODES` is an explicit inventory-name allowlist. An optional
`MURAKUMO_SANDBOX_LABELS=tier=...` adds Murakumo placement constraints. A
missing or refused placement stops execution. A failed remote container never
falls back to the gateway host. SSH authentication stays on the controller;
profile `.env`, OAuth tokens, and worktrees are not sent to fleet nodes.

Hermes `cron --script` runs on the controller before the agent's terminal
backend is used. Moving an existing cron script requires a separate bounded
remote-task wrapper and source/artifact contract; selecting this plugin alone
does not relocate it.
