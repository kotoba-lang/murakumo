# Privacy-Lane Sync Replay Non-Retention (`AWAI-4154 v1.0`)

> **Status:** Draft implementation on PR #716 (`feat/resident-kagishi-ram-replay`). Do not enable in production until Gate II–IV blockers (`cloud-murakumo` Worker alignment, `/legal-privacy` & `/legal-terms` DRAFT resolution, O10 legal sign-off, and owner approval including archival/removal of legacy `receipts.json`) are resolved.

## Process Boundary & OS Prerequisites (`INFER-PRIV-01`)

In `replay-ledger-mode` (`"kagishi-ram"` / `"ephemeral-memory"`), Murakumo `resident` holds all replay tags (`HMAC-SHA256(K_t, "awai/v1/murakumo-replay\0" || u32_be(len(boot_id)) || boot_id || u32_be(len(replay_nonce)) || replay_nonce)`) strictly in volatile RAM across 2 rotating key generations (`K_curr`, `K_prev`).

- **In-process controls (implemented in `resident.cljk`):**
  - `K_curr`, `K_prev`, and the replay-bundle auth key buffer are stored strictly as Node.js `Buffer` instances (never converted to hex/base64 strings).
  - Keys are zeroized via `Buffer.fill(0)` on every generation rotation and on process shutdown (`:close`, `SIGINT`, `SIGTERM`).
  - Best-effort `process.setrlimit("core", {soft: 0, hard: 0})` is invoked when exposed by the runtime (note: Node.js v26 on macOS/Linux does not expose `process.setrlimit`, so the host service wrapper MUST enforce `RLIMIT_CORE=0`).
  - Zero disk writes for replay state or PoI receipts (`receipts*.json*`, `*.tmp`, `*.bak`, `*.lock`, `*.poi-receipts.jsonl*`); startup fails closed via `lstatSync` if any residual receipt/temp/backup/lock/PoI file or symlink exists in the state directory (preserving `.owner-id`, `.bound-owner`, `.node-key`, `.observation-pins.json`, `.draining` unless symlinked).

- **Required host / OS wrapper prerequisites (must be configured on privacy-lane nodes):**
  - **Disable core dumps (`RLIMIT_CORE=0`):** `ulimit -c 0` / systemd `LimitCORE=0` / launchd `SoftResourceLimits`/`HardResourceLimits` `Core = 0`.
  - **Linux dumpability guard (`PR_SET_DUMPABLE=0`):** set via systemd `RestrictNamespaces`/wrapper (`prctl(PR_SET_DUMPABLE, 0)`) or `fs.suid_dumpable=0` + `kernel.core_pattern=|/bin/false`.
  - **Swap protection:** disable swap (`swapoff -a`) or enforce ephemeral-key encrypted swap (macOS APFS/FileVault encrypted swap or Linux `dm-crypt` random-key swap).
  - **Memory locking / dump exclusion (where supported by host OS):** `mlockall`/`MADV_DONTDUMP` in native wrappers when migrating from Node.js to native binaries.
  - **Explicit Non-Goals:** Live-RAM physical/root compromise, host `ptrace` attachment by privileged root, kernel memory scraping, and live hypervisor/VM memory snapshots are outside the software process boundary threat model.

## Normative Timing Defaults & Startup Invariants (`INFER-PRIV-02`)

| Parameter | Config Key | Privacy Default | Invariant |
| --- | --- | --- | --- |
| Job execution timeout | `job-timeout-ms` | `180000` ms (3 min) | `job-timeout-ms <= W` |
| Replay freshness window (`W`) | `replay-window-ms` | `300000` ms (5 min) | `W > 0` |
| Clock skew tolerance (`±Δ`) | `replay-clock-skew-ms` | `30000` ms (30 sec) | `Δ >= 0` |
| Key rotation period (`T_rot`) | `replay-rotation-ms` | `360000` ms (6 min) | `W + 2Δ <= T_rot` |
| Rotation jitter | `rotation-jitter-ms` | `0` ms | `2 * T_rot + rotation-jitter-ms <= 900000` ms (15 min) |
| Max RAM entries | `replay-max-entries` (alias `max-receipts`) | `10000` | Overflow returns `503 {"error":"replay_capacity","executed":false}` (no LRU eviction) |

## Authenticated Replay Bundle (`INFER-PRIV-08`)

Requests to privacy-lane `resident` carry:
- `x-murakumo-boot-id` (128-bit hex from peer `/health`, unless `allow-provisional-no-boot-id?` is enabled)
- `x-murakumo-replay-nonce` (32–256 chars, `[A-Za-z0-9_-]`)
- `x-murakumo-issued-at` (Unix epoch milliseconds)
- `x-murakumo-replay-auth` (64-char hex HMAC-SHA256 keyed by `replay-auth-token-file` or `token-file` fallback):
  ```
  HMAC-SHA256(key,
    "awai/v1/murakumo-replay-bundle\0"
    || u32_be(len(boot_id)) || boot_id
    || u32_be(len(replay_nonce)) || replay_nonce
    || u32_be(len(issued_at)) || issued_at
    || raw_body_bytes)
  ```
The raw body is streamed transiently into `crypto.createHmac` solely to authenticate bundle integrity; zero unsalted `SHA-256(body)` is ever computed, stored, or emitted.
