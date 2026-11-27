# Privacy-Lane Sync Replay Non-Retention (`AWAI-4154 v1.0.3` / Wire v1 Ed25519 PureEdDSA)

> **Status:** Draft implementation on PR #716 (`feat/resident-kagishi-ram-replay`). Do not enable in production until Gate II–IV blockers (`cloud-murakumo` Worker Wire v1.0.3 Ed25519 alignment, `/legal-privacy` & `/legal-terms` DRAFT resolution, O10 legal sign-off, and owner approval including archival/removal of legacy `receipts.json`) are resolved.

## Process Boundary & OS Prerequisites (`INFER-PRIV-01`)

In `replay-ledger-mode` (`"kagishi-ram"` / `"ephemeral-memory"`), Murakumo `resident` holds all replay tags (`HMAC-SHA256(K_t, "awai/v1/murakumo-replay\0" || u32_be(len(boot_id)) || boot_id || u32_be(len(replay_nonce)) || replay_nonce)`) strictly in volatile RAM across 2 rotating key generations (`K_curr`, `K_prev`).

- **In-process controls (implemented in `resident.cljk`):**
  - `K_curr` and `K_prev` are stored strictly as Node.js `Buffer` instances (never converted to hex/base64 strings) and zeroized via `Buffer.fill(0)` on rotation and process shutdown (`:close`, `SIGINT`, `SIGTERM`).
  - Bucket rotation is driven strictly by a **monotonic clock** (`process.hrtime.bigint()`) so wall-clock jumps never cause premature rotation; if monotonic elapsed is `>= 2 * T_rot`, **both** `curr_set` and `prev_set` are cleared and **both** `K_curr` and `K_prev` are zeroized immediately.
  - `effective_now` is anchored to `max(Date.now(), resident_started_at + monotonic_elapsed_ms)` so wall-clock rollback cannot resurrect expired envelopes.
  - Best-effort `process.setrlimit("core", {soft: 0, hard: 0})` is invoked when exposed by the runtime (Node.js v26 on macOS/Linux does not expose `process.setrlimit`, so the host service wrapper MUST enforce `RLIMIT_CORE=0`).
  - Zero disk writes for replay state or PoI receipts (`receipts*.json*`, `*.tmp`, `*.bak`, `*.lock`, `*.poi-receipts.jsonl*`); startup fails closed via `lstatSync` if any residual receipt/temp/backup/lock/PoI file or symlink exists in the state directory (preserving regular-file `.owner-id`, `.bound-owner`, `.node-key`, `.observation-pins.json`, `.draining`).

- **Required host / OS wrapper prerequisites (must be configured on privacy-lane nodes):**
  - **Disable core dumps (`RLIMIT_CORE=0`):** `ulimit -c 0` / systemd `LimitCORE=0` / launchd `Core = 0`.
  - **Linux dumpability guard (`PR_SET_DUMPABLE=0`):** set via systemd/wrapper (`prctl(PR_SET_DUMPABLE, 0)`) or `fs.suid_dumpable=0` + `kernel.core_pattern=|/bin/false`.
  - **Swap protection:** disable swap (`swapoff -a`) or enforce ephemeral-key encrypted swap (macOS APFS/FileVault encrypted swap or Linux `dm-crypt` random-key swap).
  - **Memory locking / dump exclusion (where supported by host OS):** `mlockall`/`MADV_DONTDUMP` in native wrappers when migrating from Node.js to native binaries.
  - **Explicit Non-Goals:** Live-RAM physical/root compromise, host `ptrace` attachment by privileged root, kernel memory scraping, and live hypervisor/VM memory snapshots are outside the software process boundary threat model.

## Normative Timing Defaults & Startup Invariants (`INFER-PRIV-02`, v1.0.1)

| Parameter | Config Key | Privacy Default | Invariant |
| --- | --- | --- | --- |
| Job execution timeout | `job-timeout-ms` | `180000` ms (3 min) | `job-timeout-ms <= W` |
| Replay freshness window (`W`) | `replay-window-ms` | `300000` ms (5 min) | `W > 0` |
| Clock skew tolerance (`±Δ`) | `replay-clock-skew-ms` | `30000` ms (30 sec) | `Δ >= 0` |
| Key rotation period (`T_rot`) | `replay-rotation-ms` | `360000` ms (6 min) | `W + 2Δ <= T_rot` |
| Periodic sweep tick (`T_tick`) | `replay-sweep-interval-ms` | `10000` ms (10 sec) | `0 < T_tick <= 30000` |
| Rotation jitter | `rotation-jitter-ms` | `0` ms | `2 * T_rot + T_tick + rotation-jitter-ms <= 900000` ms |
| Max RAM entries | `replay-max-entries` (alias `max-receipts`) | `10000` | Overflow returns `503 {"error":"replay_capacity","executed":false}` (no LRU eviction) |

## Wire v1.0.3 Authenticated Admit Envelope (`INFER-PRIV-08`)

Requests to privacy-lane `fleet_gateway` / `resident` carry:
- `x-murakumo-admit-envelope`: **unpadded** Base64url-encoded UTF-8 JSON (`envelope_raw`, `<= 2048` bytes, zero duplicate JSON keys, exact keys only — downstream `"op"` is forbidden):
  ```json
  {"v":1,"alg":"ed25519","kid":"<kid>","route":"POST /v1/chat/completions","owner":"<node>","lane":"<lane>","boot":"<resident_boot_id>","iat":1764240000000,"nonce":"<128-bit+ CSPRNG>"}
  ```
- `x-murakumo-admit-sig`: unpadded `<kid>.<base64url(sig)>` (or `<base64url(sig)>`; when `<kid>.` is present, `header_kid === envelope.kid`), verified using **Ed25519 PureEdDSA** (`crypto.verify(null, signingInput, publicKey, sigBytes)` — NO `Ed25519ph`, NO unsalted body SHA-256) over:
  ```
  "awai/v1/murakumo-admit-envelope\0"
  || u32_be(len(envelope_raw)) || envelope_raw
  || u32_be(len(body_bytes)) || body_bytes
  ```
- **Key pinning (`admit-public-keys`):** Supports `>= 2` pinned Ed25519 public keys with `"active"`, `"staged"`, and `"revoked"` states (`stage -> activate -> revoke`). Unknown/revoked `kid`, unsupported `alg` (including reserved `"ed25519+ml-dsa-65"`), padded Base64url, duplicate HTTP headers, duplicate JSON keys, unknown/forbidden fields (`"op"`), route/owner/lane mismatch, or plaintext-only headers all fail closed with 0 backend calls.
- **Single `(owner, lane)` routing (`INFER-PRIV-08` / `09`):** Privacy-lane `fleet_gateway` routes strictly to the pinned `(owner, lane)` peer (no failover to another peer) and refuses `"durable"` peers fail-closed with `503 {"error":"no_privacy_peer_available","executed":false}`.
- **4-layer non-correlation (`INFER-PRIV-05`):** Privacy-lane `resident` replaces `prefill-heartbeat!` SSE `:id`, streaming chunk `id`, and non-stream completion JSON `id` with `"chatcmpl-private"`, strips `x-murakumo-job-id`, `x-murakumo-node`, `cf-ray`, `traceparent`, `tracestate`, `x-request-id`, `x-correlation-id`, and `access-control-expose-headers`, and never logs `x-murakumo-admit-envelope`, `x-murakumo-admit-sig`, or `replay_nonce`.
