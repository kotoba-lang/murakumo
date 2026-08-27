# murakumo 叢雲

**Control plane for the kotoba WASM lattice/mesh across the Mac-mini fleet.**

kotoba ships a single-node mesh runtime (`kotoba-server` with the `p2p,realtime-wasm`
features) and a single-node status command (`kotoba lattice ps`) — but **no
fleet-facing control surface**. `murakumo` is that surface: a thin **babashka/clj**
operator that runs **from your terminal**, reaches every node over **Tailscale SSH**
today, installs a **resident** kotoba mesh node on each, and folds the whole fleet
into one view. `murakumo.cloud` is the Murakumo-native overlay being built to replace
the Tailscale/WireGuard dependency with DID/CID identity addressing, policy records,
direct QUIC/WebRTC/WebTransport paths, and relay fallback. No agent is installed on
the nodes beyond the two kotoba binaries.

GitHub merge governance is also treated as desired state. The exact Actions
permissions and app-bound, strict `main` check are versioned in
[`ADR-260811-version-github-merge-governance.edn`](docs/adr/ADR-260811-version-github-merge-governance.edn)
and audited against the live API with an administrator-authenticated `gh`:

```bash
npx nbb scripts/check-github-governance.cljs
```

The EDN is not proof of current enforcement; only a successful live readback is.
The decision and blocked-canary evidence are in
[`ADR-260811`](docs/adr/ADR-260811-version-github-merge-governance.md).

> Not to be confused with the *etzhayyim* murakumo (k3s-on-Lima + Ansible control
> plane for the religious-corp **LangGraph/Pregel cells**). This repo is the
> **kotoba WASM mesh** layer — libp2p lattice nodes hosting content-addressed WASM
> components (`run` / `on-http` / `on-tick` / `on-kse`). Different substrate, on the
> same hardware.

## Public API boundary

Clients and applications use `https://api.murakumo.cloud` exclusively.
`infer.murakumo.cloud` is the authenticated Cloudflare Tunnel origin behind
that Worker, not a supported client endpoint. The Worker supplies
`MURAKUMO_FLEET_ORIGIN_TOKEN`; llama.cpp reads the same value from
`/etc/murakumo/fleet-origin.keys`. This keeps model routing, protocol
translation, metering, and access policy at one public boundary.


## API keys (`mk1` capability tokens)

`api.murakumo.cloud` gates `/api/v1/*` on a capability token presented as
`x-api-key` or `Authorization: Bearer`. Two ways to issue one — same
implementation (`murakumo.apikey`), so they cannot drift.

Both need `$MURAKUMO_TOKEN_SECRET`: the operator's signing secret, the same value
the gateway verifies with. Unset ⇒ both refuse, rather than emitting a token that
would fail at the gateway with an indistinguishable 401.

### CLI

```bash
export MURAKUMO_TOKEN_SECRET=…                      # same value as the gateway
nbb scripts/run-task.cljs token issue --sub laptop --scope chat --ttl 604800
nbb scripts/run-task.cljs token verify mk1.…
```

The token alone goes to stdout, so it pipes (`… token issue | pbcopy`);
guidance goes to stderr.

### MCP

```bash
claude mcp add murakumo -- nbb \
  --classpath "src:../org-anthropic-mcp/src" \
  scripts/mcp-server.cljs
```

Tools: `murakumo.issue_api_key` (sub / scope / ttl_seconds) and
`murakumo.verify_api_key` (token). Running it grants the client the ability to
mint keys within the bounds below, for as long as the secret is in the
environment.

### Bounds, and why

- **scope** must be one the gateway understands (`chat` | `image` | `all`). An
  unknown scope is refused at issue time instead of surfacing later as a
  confusing 401.
- **ttl** is capped at 90 days (default 30). `mk1` is stateless by design — the
  gateway verifies with no KV or DB round-trip, which also means there is no
  revocation list. Expiry is the entire revocation story, so an unbounded key
  would be a permanent one. Re-issue instead of minting long-lived keys.

Compatibility with the verifying gateway is a test, not a claim:
`nbb scripts/run-task.cljs test-apikey` asserts that keys minted here are
byte-identical to, and accepted by, `cloud-murakumo.token`.

> Historical note: the gateway's 401 used to say `bb murakumo token issue`. That
> command has not been runnable since babashka was retired (ADR-2607173000) —
> `murakumo.core` is `.clj` on `babashka.process` and `bb.edn` is gone. The
> `token` task above is its nbb port.
>
> The 401 was corrected on 2026-08-13 (ADR-2608133200). It had been changed once
> already, from `bb murakumo token issue` to `clojure -M:token issue`, but that
> second command raised `ArityException` — `cloud-murakumo.cli/cmd-token` was
> fixed-arity while the alias passes only `issue`. Both the arity and the message
> are fixed now; the message names this repo's `token` task as the alternative.


## What it manages

Operator policy is defined in [`RULES.md`](RULES.md). The liveness/model-placement
decision is recorded in
[`docs/adr/ADR-260712-fleet-liveness-model-provisioning.md`](docs/adr/ADR-260712-fleet-liveness-model-provisioning.md),
Component authority epochs are defined in
[`docs/adr/ADR-260725-component-authority-epochs.md`](docs/adr/ADR-260725-component-authority-epochs.md),
with recovery commands in [`docs/FLEET-RECOVERY.md`](docs/FLEET-RECOVERY.md).

Each fleet node runs `kotoba-server` as a **macOS LaunchAgent** (`RunAtLoad` +
`KeepAlive` ⇒ starts at login, self-heals on crash). The node:

- forms a libp2p **gossipsub lattice** and advertises a **Heartbeat** (roles, labels,
  free-gas, hosted components);
- **hosts WASM components** placed by the lattice auction and fires their cron
  (`on-tick`), HTTP (`on-http`), and KSE (`on-kse`) triggers;
- **persists** the components' `kqe-assert!` output to its kotoba Datom log — i.e. the
  node is a real PDS/graph writer, not a sandbox.

## Quickstart

```bash
# Generate the shared operator identity once (kotoba writes the XDG store, mode 0600).
# murakumo reads that same file when MURAKUMO_OPERATOR_SEED is unset. Env still wins if set.
kotoba identity new
export MURAKUMO_KOTOBA_DIR=~/github/com-junkawasaki/orgs/com-junkawasaki/kotoba

nbb scripts/run-task.cljs identity      # print the operator DID (never the seed)
nbb scripts/run-task.cljs ops status    # fold /health + lattice ps across the fleet
nbb scripts/run-task.cljs task run --n 22 --cmd 'hostname'   # fan a batch over the fleet
nbb scripts/run-task.cljs token issue --scope chat           # mint a gateway API key
```

> ### The rest of the control plane is unavailable
>
> This Quickstart used to list fifteen `bb …` commands. babashka was retired as
> this workspace's script host by ADR-2607173000; `murakumo.core`, `cloud.clj`
> and `overlay.clj` are `.clj` on `babashka.process`, and `bb.edn` is gone. The
> Wave-3 conversion carried over only the shell-out tasks, so **sixteen named
> entrypoints were dropped and have no runnable path today** (ADR-2608131600) —
> `nodes`, `status`, `provision`, `mesh`, `deploy`, `reconcile`, `infer`,
> `model`, `dash`, `cloud`, `overlay` among them. `scripts/tasks.edn` records
> which.
>
> Three have been ported and are the commands above: `ops` (the nbb port of
> `nodes` + `status`), `task` (the fleet task plane), and `token`. Everything
> else needs a port — the bodies require `babashka.process`/`cheshire` under
> SCI, so re-registering them is a decision about which of these commands should
> still exist, not a mechanical conversion.
>
> Every `bb …` line remaining below in this README is in that dropped set. They
> are left in place, rather than deleted, because they describe what this repo
> is *for*; treat them as a specification of the missing surface, not as
> instructions. `nbb scripts/run-task.cljs` with no argument lists what actually
> resolves.

## Command surface

| command | what it does |
|---|---|
| `nodes` | Tailscale reachability + SSH + whether the mesh binary/agent is present (read-only fleet map) |
| `provision [node\|all]` | `rsync` the `kotoba`/`kotoba-server` binaries, render + load the per-node LaunchAgent (idempotent) |
| `up` / `down [node\|all]` | `launchctl kickstart` / `bootout` the resident mesh node |
| `status [node\|all]` | per-node `/health` (wasm_executor, peer_count) + `lattice ps`, folded into one table |
| `mesh [node\|all]` | 2-pass: provision with a fixed P2P port + stable PeerId, collect PeerIds, re-provision with `KOTOBA_BOOTSTRAP_PEERS` = the others ⇒ ONE gossipsub lattice (fleet-wide auction) |
| `deploy <app.edn> [node]` | port-forward the node's kotoba port, then `kotoba app deploy --publish` (clj→WASM → lattice) |
| `reconcile <murakumo.app.edn> [--dry-run\|--apply\|--watch[=secs]]` | **declarative desired-state (wadm)** — fold a fleet manifest vs live placement, report/converge the drift (see below) |
| `cloud [plan\|records\|routes\|dial\|connect <node>\|relay <name>\|bootstrap] [--cloud=cloud.edn] [--fleet=fleet.edn]` | plan the `murakumo.cloud` identity overlay, route hints, driver argv, relay argv, bootstrap order, and control-plane records that replace an external VPN control plane |
| `overlay dial\|relay --overlay ...` | native overlay driver shell: validate canonical dial/relay argv and emit the session record a real stream/packet driver will open |
| `fleet <datom-log.edn> [now-ms]` | **coordination-plane view** — fold a [kotoba-fleet](https://github.com/kotoba-lang/kotoba-fleet) Datom log into one snapshot (per-work holders · active leases · pending proposals) via `kotoba.fleet.view/snapshot`. The `status` of the 20-agent coordination layer, next to the mesh `status`. |
| `infer probe\|plan <model>\|provision\|up\|down\|ps\|serve\|generate` | **distributed inference across the fleet, exo-style** — memory-weighted shard plan + pipeline-parallel ring (see below) |
| `task probe\|plan\|run\|report` | **fleet task plane** — fan a BATCH of short-lived tasks over the fleet and gather the results (k8s-Job / Ray-tasks shape, next to `reconcile`'s k8s-Deployment shape). `nbb scripts/run-task.cljs task run --n 22 --cmd 'hostname'` (see below) |
| `ops status [all\|a,b]` | **fleet read surface, restored on nbb** — one ssh round trip per node for `/health`, wasm_executor, peer links, hosted component CIDs, mesh binary presence and LaunchAgent state. This is the nbb port of the bb-era `nodes` + `status`, which have had no runnable entrypoint since the bb.edn removal. `nbb scripts/run-task.cljs ops status` |

## Layout

| path | role |
|---|---|
| `fleet.edn` | node inventory (host, roles, labels, port) — the SSoT |
| `connect.edn` | **single connectivity description** (read=HTTP-by-CID / live=libp2p multi-transport); node-class → transports |
| `cloud.edn` | **murakumo.cloud overlay declaration**: domain, relay set, direct transports, and policy |
| `murakumo.app.edn` | **declarative desired state** (wadm manifest): apps × replicas × placement (incl. `:reach`) |
| `src/murakumo/config.cljc` | portable config/path/runtime resolution helpers |
| `src/murakumo/component_authority.cljc` | Component placement/revocation authority: monotonic epochs and exact events consumed by Kototama hosts |
| `src/murakumo/component_authority_store.clj` | fsync-backed signed authority outbox: enqueue-before-state, ordered retry, acknowledge-after-delivery |
| `src/murakumo/component_authority_http.clj` | HTTPS publisher connecting the durable authority outbox to Kototama’s bounded receiver |
| `src/murakumo/component_authority_deploy.clj` | secret-free per-node TLS receiver rollout plan, node audience binding, and overlap-first trusted-key rotation |
| `src/murakumo/connect.cljc` | connect.edn loader + portable `serves-reach?` (pure: can a node reach a client class on a plane?) |
| `src/murakumo/cloud.clj` | `bb cloud` CLI shell: load fleet/cloud declarations and print plans or records |
| `src/murakumo/cloud/plan.cljc` | portable murakumo.cloud overlay planner: stable IDs, relay choice, node/relay/route/policy records |
| `src/murakumo/overlay.clj` | `bb overlay` CLI shell for the native overlay driver boundary |
| `src/murakumo/overlay/forward.clj` | local TCP forwarder over the sealed relay stream contract |
| `src/murakumo/overlay/dial.clj` | host-side dial reachability plus relay hello/frame checks |
| `src/murakumo/overlay/driver.cljc` | portable overlay driver core: parse/validate canonical dial argv and emit session records |
| `src/murakumo/overlay/relay.clj` | host-side relay listener process with identity-aware ack and frame handling |
| `src/murakumo/overlay/runtime.cljc` | portable overlay runtime adapter registry and execution-report placeholder contract |
| `src/murakumo/deploy/plan.cljc` | portable deploy/pin helpers: app manifest parsing, kotoba command argv shapes, placement observation, pinned binary copy plans |
| `src/murakumo/identity.cljc` | portable identity formatting helpers: SHA-256 hex, graph CID, operator token |
| `src/murakumo/persist.cljc` | portable Datom/atproto repo.write envelope helpers |
| `src/murakumo/ssh.clj` | Tailscale-SSH transport (BatchMode, fast-fail, scp/curl-on-node) |
| `src/murakumo/fleet/inventory.cljc` | portable fleet inventory helpers: node port defaults + selector semantics |
| `src/murakumo/fleet.clj` | inventory load + `tailscale status` enrichment around the `.cljc` inventory helpers |
| `src/murakumo/provision/plan.cljc` | portable provision/mesh helpers: p2p ports, bootstrap peers, plist rendering, rsync argv, launch commands |
| `src/murakumo/report.cljc` | portable CLI report formatting for nodes/status/deploy/reconcile/help |
| `src/murakumo/tunnel.cljc` | **THE transport contract** (portable): ssh connection options, the in-band exit-status sentinel every remote command is wrapped in, optional ControlMaster multiplexing, local-forward command shapes |
| `src/murakumo/core.clj` | the command implementations + per-node identity derivation |
| `src/murakumo/reconcile/plan.cljc` | portable wadm planner — PURE desired/observed→plan core |
| `src/murakumo/reconcile.clj` | the wadm CLI shell — collect/apply/watch/persist around the `.cljc` planner |
| `src/murakumo/dash/state.cljc` | portable dashboard state helpers: snapshot record shape + liveness alert diffs |
| `src/murakumo/dash.clj` | snapshotter + web UI + Datom-log persistence around the `.cljc` state helpers |
| `test/murakumo/cloud_plan_test.clj` | offline unit tests for the murakumo.cloud overlay planner |
| `test/murakumo/overlay_driver_test.clj` | offline unit tests for the native overlay driver shell core |
| `test/murakumo/reconcile_test.clj` | offline unit tests for the pure reconcile core (`bb test`) |
| `test/murakumo/smoke_test.clj` | namespace-load smoke tests for CLI shell entrypoints |
| `deploy/com.murakumo.kotoba-mesh.plist.tmpl` | the resident LaunchAgent template |
| `infer.edn` | distributed-inference config: model registry + head/worker memory policy — the SSoT |
| `src/murakumo/infer/plan.cljc` | **PURE exo-style planner**: memory-weighted contiguous layer partition + fits-gate (bb/JVM/cljs/WASM portable) |
| `src/murakumo/infer/moe.cljc` | **PURE mlx-moe planner**: single best-memory-node pick, README capacity tiers, expert-ratio verdict heuristic |
| `src/murakumo/infer/engine.cljc` | **PURE engine adapters**: plan → llama.cpp `--rpc/--tensor-split` cmds / `mlx.launch` ring cmds / `mlx-moe serve` cmd |
| `src/murakumo/infer/topology.cljc` | **PURE interconnect evidence**: folds per-boundary link facts, and gates the `:link-gbps` `choose-strategy` is allowed to see (ADR-260815) |
| `src/murakumo/infer/topology_probe.cljs` | the nbb feed: `discover` (tailscale + Bonjour vs `fleet.edn`) / `nominal` / `thunderbolt` / `measure` (receiver-verified transfers) |

The authority receiver rollout is deliberately secret-free. Its default
artifact source is the pinned sibling checkout at `../kototama`; callers may
instead pass an explicit release checkout to `deployment-plan`. Before changing
the remote configuration, the rollout verifies that the Kototama runtime is
installed at `/opt/kototama`, and that the node already has a readable PKCS#12
certificate plus a root-managed
`/etc/kototama/component-authority.secret`. Neither TLS material nor passwords
are copied by Murakumo.
| `src/murakumo/infer.clj` | the inference operator: SSH probe → plan → provision → ring up/down → serve/generate (mlx-moe models take the single-node path) |
| `test/murakumo/infer_test.clj` | offline unit tests for the pure planner/engine (`bb test`) |
| `src/murakumo/task/plan.cljc` | **PURE task scheduler**: eligibility (labels/roles/memory/exclusions) → slot-aware least-filled placement → retry-elsewhere → honest speedup summary |
| `src/murakumo/task/exec.cljs` | nbb execution shell: bounded-concurrency SSH fan-out, per-task timeout, in-band exit-code sentinel, node probing |
| `src/murakumo/task/worker.cljs` | resident remote shells (`ssh host bash -s` per slot) with per-task framing, timeout-kill + respawn — the transport that removes the per-task ssh cost |
| `src/murakumo/task.cljs` | the `task` CLI (nbb): `probe` / `plan` / `run` / `report` + run ledger |
| `src/murakumo/ops.cljs` | the `ops status` CLI (nbb): the restored fleet read surface, built on the portable `dash/state.cljc` probe core |
| `test/murakumo/task_plan_test.cljs` | offline unit tests for the pure scheduler (`nbb scripts/run-task.cljs test-task`) |
| `test/murakumo/task_exec_test.cljs` | offline unit tests for the exec shell's pure helpers |
| `test/murakumo/infer_moe_test.cljc` | offline unit tests for the pure mlx-moe planner (`bb test`) |

## Dashboard + Datom persistence

```bash
bb dash [port=8899] [interval-s=15]   # → http://localhost:8899
```

`dash` runs a background snapshotter + a web UI (babashka's built-in http-kit):

- every `interval` seconds it polls the fleet (each node's `/health`, live libp2p
  **LINKS**, and the component CIDs it **HOSTS** = where the lattice placed things);
- it **persists** each snapshot to the kotoba Datom log — one `atproto.repo.write`
  tx into the `murakumo-fleet` graph, so the fleet's heartbeat + placement state
  becomes an append-only **as-of history** (tamper-evident, queryable);
- it serves that snapshot as an auto-refreshing page (`/`) + JSON (`/api`).

The page shows, per node: HEALTH · WASM-EXEC · LINKS · P2P-PORT · HOSTED (the
content-addressed components the lattice placed there) — `murakumo status`, live,
in a browser, with history on the Datom log behind it.

## Declarative reconcile — murakumo's wadm

`deploy` is **imperative** (compile → distribute → publish, once). `reconcile` is the
**declarative** half: you write a fleet manifest (`murakumo.app.edn`) that states
*what should be running and with what spread*, and murakumo continuously folds the
live placement against it.

```edn
;; murakumo.app.edn
{:murakumo/version 1
 :apps [{:name "kotodama-bot"
         :manifest "../kotoba/examples/kotoba-mesh-app/kotoba.app.edn"  ; clj→wasm
         :cid "bafy…"                          ; once known ⇒ match observed without rebuild
         :replicas 2                            ; desired # of eligible nodes hosting it
         :placement {:labels {:tier "edge" :zone "jp"} :roles ["compute"]}}]}
```

```bash
bb reconcile murakumo.app.edn --dry-run     # print desired-vs-observed plan, do nothing
bb reconcile murakumo.app.edn --apply       # re-publish under-replicated apps → auction converges
bb reconcile murakumo.app.edn --watch=30    # keep it converged; record each plan to the Datom log
bb reconcile murakumo.app.edn --dry-run --snapshot=snap.edn   # offline, against a recorded snapshot
```

A plan is per-app: `eligible` (label/role-matched nodes the auction may place on),
`running` (eligible nodes actually hosting the CID, from `dash` observation),
`misplaced` (running where *not* eligible — drift), and an action:

| action | meaning |
|---|---|
| `:satisfied` | running == desired, no drift |
| `:place` | under-replicated → `:targets` = least-loaded eligible nodes to deploy onto |
| `:over` | running > desired (reported; murakumo does **not** auto-evict) |
| `:blocked` | under-replicated but no eligible node free |
| `:needs-build` | app has no `:cid` yet — compile `:manifest` first |

This is the kotoba-mesh ADR's **L5** (`docs/ADR-kotoba-mesh-wasm-hosting.md`): desired
state AND observed state are both **datoms**, so the reconciler is just their diff.
The pure core (`desired/observed → plan`) is unit-tested offline — `bb test`, no fleet
or SSH needed. `--watch` writes each plan as a `com.murakumo.fleet.reconcile` record
into the `murakumo-fleet` graph, so the fleet's desired-vs-observed history is itself a
queryable as-of Datom chain (alongside `dash`'s heartbeat snapshots).

> **Layering vs kotoba's own manifest**: a `kotoba.app.edn` already has per-component
> `:scale` + `:placement`, and `kotoba app deploy --publish` places them **once** via
> the auction. `murakumo.app.edn` sits **above** that with the fleet-level desired
> replica count and the **convergence loop** (drift detection + self-heal + history)
> that the one-shot deploy doesn't have. `reconcile` ≈ wadm; `deploy` ≈ `wash app`.

## Task plane — `task` (the fleet as one batch executor)

`reconcile` converges **long-lived apps** (k8s Deployment / wadm shape).
`task` fans a **batch of short-lived units of work** across the same fleet and
gathers the results — the k8s-Job / Ray-`.remote` shape that ADR-2607071400's
equivalence table had no entry for (ADR-2607256000).

```bash
nbb scripts/run-task.cljs task probe                       # cores / RAM / load1 / reachability, per node
nbb scripts/run-task.cljs task plan  --n 22 --cmd 'hostname'   # pure placement preview, nothing executed
nbb scripts/run-task.cljs task run   --n 22 --cmd 'hostname'   # place → execute over SSH → gather → retry
nbb scripts/run-task.cljs task run   --tasks batch.edn         # heterogeneous batch from a file
nbb scripts/run-task.cljs task run   --n 8 --labels tier=gpu --cmd './render.sh'
nbb scripts/run-task.cljs task report --last 5                 # replay recorded runs from the ledger
```

- **Placement** reuses `reconcile`'s vocabulary — `--labels` (all must match),
  `--roles` (all must be present) — plus `--min-mem-gb` and `--nodes`.
  Unschedulable tasks are **reported, never silently dropped**.
- **Slots**: each node runs at most `min(cores, --max-slots)` tasks at once
  (`--slots N` to override). Placement is least-*filled*-first (assigned/slots),
  so a 32-core box takes proportionally more than a 10-core mini.
- **Admission**: `--max-load` / `--max-load-per-core` hold back nodes that are
  already saturated by other resident work, and `--max-inflight` caps fleet-wide
  concurrency by *shrinking slot budgets* rather than dropping tasks. Nodes that
  fail their probe are dropped before the budget is divided, so an unreachable
  node cannot consume capacity. Every held-back node is listed with its reason.
- **Transport**: by default each slot keeps a **resident remote shell**
  (`ssh host bash -s`) and tasks are framed onto its stdin, over a multiplexed
  connection. Measured on the fleet (110 tasks × 11 nodes):

  | transport | per-task p50 | wall |
  |---|---|---|
  | one ssh per task | 241ms | 2572ms |
  | + connection multiplexing | 90ms | 1577ms |
  | + resident workers (default) | **15ms** | **403ms** |

  `--no-worker` falls back to one ssh per task (which keeps stderr separate);
  `--no-multiplex` also drops connection reuse. In worker mode stderr is merged
  into stdout (`:stderr-merged? true`) and a hung task is stopped by killing its
  worker — the task is reported `TIMEOUT`, the worker is respawned, and the rest
  of that node's queue continues. (The multiplex socket path is length-checked:
  macOS caps a unix socket at 104 bytes and `os.tmpdir()` alone eats half of it.)
- **`probe` also reports mesh health** — the same round trip curls the node's own
  `kotoba-server /health`, so `SSH up / MESH down` is visible per node.
- **`--format edn`** emits the whole probe/plan/run/report payload as EDN for
  piping into another tool; `p50`/`p95` per-task latency are reported next to
  `speedup` (speedup only compares runs of the *same* work — latency is the
  figure that survives a transport change).
- **Retry**: a failed task is re-placed on a **different** node (`--attempts`,
  default 2). A task that succeeds on retry is a **succeeded task** — the run's
  verdict is the final attempt, with `attempts`/`retried` reported separately.
  This is Ray's task retry, *not* lineage re-execution.
- **Exit codes are read in band.** Measured 2026-07-25: Tailscale SSH on the
  macOS nodes does **not** propagate the remote exit status (`ssh asher 'exit 7'`
  → 0), while the Linux node returns it correctly. Every command therefore runs
  in a subshell that echoes a `__murakumo_rc=` sentinel, which wins over ssh's
  own code — otherwise 10 of 11 nodes would report silent false successes.
  This lives in `murakumo.tunnel` (`.cljc`), so the bb/JVM control plane
  (`murakumo.ssh` → provision / status / deploy / infer / model GC) inherits the
  same fix instead of each caller re-inventing an `&& echo ok || echo FAILED`
  workaround. `sh-result` keeps ssh's own code as `:ssh-exit` so a transport
  failure is still distinguishable from a remote non-zero exit.
- **Ledger**: every run appends one EDN map to `.murakumo-task-ledger.edn`
  (`--ledger` to move it), the same append-only shape as the relay ledger.

Deliberately NOT provided (see ADR-2607256000 for the honest gap list): a
distributed object store / futures, lineage-based re-execution, gang scheduling
or placement groups, and an autoscaler. Results come back inline.

## Connectivity — `connect.edn` (one description, every transport)

The mesh has **two planes** (ADR `90-docs/adr/2606271700-kotoba-transport-planes.md`):

- **read** — CID-over-HTTP. Transport-*untrusted*: `CID == sha256(dag-cbor(bytes))`
  is the trust, so any gateway / peer / CDN is equivalent. Browser + edge are
  first-class here with **zero p2p transport**.
- **live** — libp2p multi-transport (`quic` native · `webrtc` browser · `webtransport`
  · `wss` edge). An availability/liveness layer (placement gossip, realtime, 持ち合い).

`connect.edn` is the **single declarative description** of this — like a Connect
schema, one source of truth spanning every transport. It maps each node *class* to
the transports it speaks:

```edn
{:classes {:native  {:read [:http] :live [:quic]                :dialable true}
           :edge    {:read [:http] :live [:wss]                 :dialable true}
           :browser {:read [:http] :live [:webrtc :webtransport] :dialable false}}}
```

`reconcile` reads it to honour an app's `:placement {:reach […]}` — place a component
only on nodes that can actually **reach** its client class:

- `:reach [:browser/read]` → any `:http` node (universal CID pull) → all servers eligible.
- `:reach [:browser/live]` → needs a shared *live* transport with browsers (`webrtc`/
  `webtransport`). Native nodes speak only `:quic` today, so such an app reconciles to
  **`:blocked`** — honestly, instead of being placed where browsers can't reach it.

**Wiring knob**: to make the native fleet serve browser-live peers, add `:webrtc` to
`:native :live` in `connect.edn` (one edit) — and wire that transport into the
`kotoba-net` swarm. That single change flips eligibility for every `:reach
:browser/live` app (proven by `reach-after-wiring-webrtc-into-native` in the tests).
QUIC stays the native↔native default; browser/edge are **not** raw-QUIC peers.

## murakumo.cloud — replacing Tailscale/WireGuard

Tailscale and WireGuard are IP/subnet VPN substrates. `murakumo.cloud` is designed as
Murakumo's own identity-addressed overlay instead: nodes are addressed by stable
overlay/node CIDs, the control plane is a Datom/atproto graph (`murakumo-cloud`), and
policy is expressed as records rather than ACLs in an external VPN product.

```bash
bb cloud plan        # human summary: overlay CID, nodes, chosen relays, policy count
bb cloud routes      # route table: direct transport candidates + relay fallback
bb cloud dial asher  # policy-checked identity-overlay dial hints for one node
bb cloud connect asher  # canonical murakumo-overlay driver argv for that dial
bb cloud relay jp-tyo-1 # canonical murakumo-overlay relay argv for one relay
bb cloud bootstrap      # relays first, then policy-authorized node connects
bb cloud bootstrap --format=edn # cloud.murakumo.bootstrap manifest for runners
bb cloud dial asher --from=browser --capability=ssh  # denied unless policy allows it
bb overlay bootstrap --manifest-file bootstrap.edn   # validate every bootstrap step
bb overlay run --manifest-file bootstrap.edn         # dry-run ordered overlay runner plan
bb overlay dispatch --manifest-file bootstrap.edn    # attach runtime adapters to every step
bb overlay execute --manifest-file bootstrap.edn     # execution-report contract through runtime adapters
bb overlay adapters                                  # list runtime adapters and implementation status
bb overlay transports                                # list transport adapters: native relay + external QUIC/WebRTC boundaries
bb overlay transport-probe --overlay ...             # probe the selected direct transport socket boundary
bb overlay adapter-plan --overlay ...                # build external QUIC/WebRTC adapter argv + EDN request
bb overlay adapter-check --overlay ...               # run the configured adapter check command
bb overlay adapter-supervisor --overlay ...          # plan restart policy for a long-running adapter process
MURAKUMO_QUIC_DRIVER="bb overlay-adapter" bb overlay adapter-check --overlay ... # use the bundled reference adapter
bb quic-driver check --request-edn '{...}'        # JVM Clojure/Kwik QUIC driver
bb quic-cert ensure --overlay=bafyOverlay --node=bafyNode --host=localhost # issue/ensure stored QUIC cert/key
bb quic-cert list                              # show active QUIC material generations/fingerprints
bb quic-cert rotate --overlay=bafyOverlay --node=bafyNode --host=localhost # rotate active QUIC cert/key
bb quic-cert verify                            # verify files, fingerprints, and audit hash chain
bb quic-cert prune --keep=1                    # remove old non-active generations
bb quic-driver serve --request-edn '{...}'       # QUIC listener; auto-issues cert/key if env is absent
MURAKUMO_QUIC_CERT=cert.pem MURAKUMO_QUIC_KEY=key.pem bb quic-driver serve --request-edn '{...}' # explicit cert/key override
MURAKUMO_QUIC_DRIVER="clojure -M:quic-driver" bb overlay adapter-check --overlay ... # real Clojure QUIC driver
bb overlay-adapter check --request-edn '{...}'       # reference external adapter driver entrypoint
bb overlay dial-check --overlay ...                  # probe direct endpoint reachability
bb overlay dial-check --via=relay --overlay ...      # connect to relay and exchange overlay hello/frame/ack
bb overlay dial-check --via=relay --frames=a,b,c ... # stream ordered frames through the relay contract
bb overlay dial-check --auth-key ... --via=relay ... # stream frames with keyed MAC validation
bb overlay relay-check --overlay ...                 # prove a relay listener can bind locally
bb overlay serve-relay --auth-key ... --require-auth true --max-frame-bytes 65536 --overlay ... # hardened relay listener
bb overlay service-plan --listen 127.0.0.1:18022 --service ssh --auth-key ... --via=relay ... # persistent service proxy plan
bb overlay service-proxy --listen 127.0.0.1:18022 --service ssh --auth-key ... --via=relay ... # persistent byte proxy
bb overlay local-forward --listen 127.0.0.1:18022 --auth-key ... --via=relay ... # local TCP lines over sealed relay stream
bb overlay local-forward-bytes --listen 127.0.0.1:18023 --auth-key ... --via=relay ... # local TCP byte chunks over sealed relay stream
MURAKUMO_OVERLAY_AUTH_KEY=... bb cloud bootstrap --format=edn # inject auth-key into driver argv
MURAKUMO_OPERATOR_SEED=... bb cloud bootstrap --format=edn     # derive overlay auth-key if no explicit key is set
bb cloud records     # EDN records ready to persist/publish into the cloud graph
```

The current implementation is the deterministic control-plane layer:

- `cloud.edn` declares the domain, overlay, direct transports, relay regions, and
  default-deny capability policy.
- `murakumo.cloud.plan` folds `fleet.edn + cloud.edn` into `cloud.murakumo.node`,
  `cloud.murakumo.relay`, `cloud.murakumo.route`, and `cloud.murakumo.policy`
  records.
- Relay fallback is deterministic and region-aware (`:labels {:zone ...}` /
  `:region`), while direct paths prefer QUIC/WebRTC/WebTransport.
- `bb cloud dial <node>` is policy-aware: it defaults to
  `from=operator to=fleet capability=ssh`, emits direct/relay candidates only when
  `cloud.edn` allows that capability, and otherwise returns a policy denial instead
  of a route.
- `bb cloud connect <node>` turns the authorized dial plan into the canonical
  `murakumo-overlay dial ...` argv that a native stream/packet driver can execute.
- `bb cloud relay <name>` turns a relay control record into the canonical
  `murakumo-overlay relay ...` argv for starting a relay process.
- `bb overlay transports` exposes the adapter boundary: relay is native today;
  QUIC/WebRTC/WebTransport are executable external-adapter slots
  (`MURAKUMO_QUIC_DRIVER`, `MURAKUMO_WEBRTC_DRIVER`,
  `MURAKUMO_WEBTRANSPORT_DRIVER`) until a JVM/babashka-safe transport is linked.
- `bb overlay adapter-plan` and `bb overlay adapter-check` implement the external
  driver protocol: the configured command receives
  `<action> --request-edn '<murakumo.overlay.adapter-request>'`, and murakumo records
  exit code, stdout, stderr, timeout, and missing-adapter failures as EDN.
- `bb overlay adapter-supervisor` produces the long-running process supervision
  plan for QUIC/WebRTC/WebTransport drivers, including restart policy and max
  restart count.
- `bb overlay-adapter` is a bundled reference external driver. It implements
  `check`, `dial`, `serve`, and `serve-once` against the same EDN request contract,
  so real `murakumo-quic-driver` / `murakumo-webrtc-driver` binaries can be tested
  against a known protocol shape.
- `murakumo.overlay.quic-driver` is the first real external transport driver. It is
  JVM Clojure using the pure-Java Kwik QUIC stack. It performs QUIC `check`,
  `dial`, `serve`, and `serve-once`, opens a QUIC stream, and exchanges the same
  `adapter-hello` / `adapter-ack` records used by the reference adapter.
- `bb quic-cert` issues, lists, and rotates QUIC certificate material under
  `.murakumo/kagi/quic` by default (`MURAKUMO_KAGI_DIR` overrides the path). Files
  are written owner-only (`0600`), indexed in `index.edn`, and tracked by
  overlay/node/host generation, active generation, fingerprint, and expiry.
  Issue/rotate/prune operations append to a hash-chained audit log, and
  `bb quic-cert verify` checks both material fingerprints and that audit chain.
  `MURAKUMO_QUIC_CERT` and `MURAKUMO_QUIC_KEY` still override the stored material
  when supplied.
- `murakumo.overlay.stream` models ordered logical streams, so multiple service
  sessions can share one overlay transport contract.
- `murakumo.overlay.peer` keeps deterministic peer discovery/route-selection state
  from `cloud.murakumo.route` records.
- `murakumo.overlay.keyring` derives per-overlay, per-epoch key material for key
  rotation while accepting previous/current/next key ids during rollover.
- `bb overlay service-proxy` is the persistent service-proxy entrypoint over the
  relay byte stream; `local-forward*` remains the lower-level debug surface.
- Relay hardening now includes optional auth-required mode and max frame byte
  limits; rejected frames are not counted as successful dial checks.
- `bb cloud bootstrap` prints the fleet-wide overlay boot sequence: relay processes
  first, then policy-authorized node dial argv.
- `bb cloud bootstrap --format=edn` emits the same sequence as a
  `cloud.murakumo.bootstrap` manifest with explicit phases and executable argv.
- `bb overlay bootstrap --manifest-file <file>` reads that manifest and validates
  every phase step through the same `dial` / `relay` driver contracts before a
  future runtime opens sockets.
- `bb overlay run --manifest-file <file>` turns a validated bootstrap manifest into
  a dry-run `murakumo.overlay.run-plan`, preserving phase order and marking every
  step as `:run` or `:blocked`.
- `bb overlay dispatch --manifest-file <file>` attaches runtime adapter names
  (`murakumo.runtime.quic`, `murakumo.runtime.relay`, etc.) to each runnable step.
- `bb overlay execute --manifest-file <file>` preserves the same ordering and emits
  a `murakumo.overlay.execution-report`. `murakumo.overlay.runtime` owns the adapter
  registry (`relay`, `quic`, `webrtc`, `webtransport`, relay-client) and currently
  returns explicit `:would-run` execution records; the real socket/relay runtime
  plugs into this boundary.
- `bb overlay adapters` lists those runtime adapters and their current
  implementation status.
- `bb overlay dial-check ...` opens a host socket to the planned direct endpoint,
  proving the dial target is reachable before full QUIC/WebRTC framing exists.
- `bb overlay dial-check --via=relay ...` connects to the relay endpoint and
  exchanges minimal EDN `relay-hello`, `relay-ack`, `relay-frame`, and
  `relay-frame-ack` records carrying overlay, node, principal identity, target, and
  a small payload.
- `bb overlay dial-check --via=relay --frames=a,b,c ...` streams multiple ordered
  frames over the same relay connection and verifies one digest-checked ack per
  frame.
- `--auth-key <secret>` on both `serve-relay` and `dial-check` adds a keyed frame
  MAC; relay acks expose `:mac-ok?` and reject frames with a bad MAC.
- With `--auth-key`, relay frame payloads are sealed with AES-GCM on the wire;
  acks expose `:open-ok?` / `:sealed?` after successful decrypt-and-verify.
- `bb overlay local-forward ...` opens a local TCP listener and forwards client
  input lines as sealed relay stream frames, returning acknowledged payload lines
  to the local client. This is the first host-side tunnel boundary; byte-stream
  framing and service proxying can replace the line codec next.
- `bb overlay local-forward-bytes ...` uses the same sealed relay stream but frames
  raw local TCP bytes as base64url chunks, then decodes acknowledged chunks back to
  bytes for the local client.
- `cloud.edn` declares `:overlay/auth-key-env` so `bb cloud connect`, `relay`, and
  `bootstrap` can inject `--auth-key` into executable driver argv from the local
  environment without storing the secret in control-plane records.
- If no explicit overlay auth key is set, `:overlay/auth-key-source :operator-seed`
  derives the driver MAC key from `MURAKUMO_OPERATOR_SEED` and the overlay CID.
  The derived key is still only placed in executable argv, not records.
- `bb overlay relay-check ...` opens and closes the relay listener, proving the
  host can bind the requested port.
- `bb overlay serve-relay ...` starts the minimal host relay process. It accepts
  TCP connections and returns an identity-aware ack while transport framing is
  still being implemented.
- `bb overlay dial ...` / `bb overlay relay ...` are the repo-local driver shell for
  those canonical argv. They do not open sockets yet; they validate requests and emit
  `murakumo.overlay.session` / `murakumo.overlay.relay` records so the next
  implementation layer has a stable executable contract.

The live packet/stream driver is intentionally separate: SSH and provisioning still
use Tailscale today, but the CLI now owns the node, relay, policy, and route records
needed for a Murakumo-native overlay control plane. The next layer is to bind these
route hints to relay processes and host networking so `murakumo cli` can open the
planned identity dials without depending on Tailscale/WireGuard.

## Binary pinning (raced-checkout safety)

The shared `kotoba` checkout is raced by concurrent agents — a sibling rebuild can
swap the cli/server protocol out from under a live fleet. So murakumo deploys a
**pinned binary set it owns** under `./bin` (the binaries themselves are gitignored;
never commit machine-built blobs). What IS tracked is **`bin/BUILD.edn`** — the
fleet's *expected* version:

```edn
{:source "…/release" :git-sha "d3595506" :version "kotoba 0.1.0" :features "p2p,realtime-wasm"}
```

So "which kotoba the fleet runs" is auditable in git, while the binary is distributed
out-of-band. To bump the fleet version:

```bash
# build the new kotoba (cli+server, p2p,realtime-wasm), then:
bb murakumo pin <its release dir>     # copies binaries → ./bin, rewrites bin/BUILD.edn
git commit bin/BUILD.edn -m "bump fleet kotoba → <sha>"   # the auditable version pin
bb murakumo provision all             # roll it out
```

`provision`/`mesh` print the pinned version on rollout and refuse if `bin/BUILD.edn`
declares a version but `./bin` is empty (clone murakumo → `pin` the declared sha first).

## Distributed inference — `infer` (the fleet as one model host, exo-style)

The same fleet that hosts WASM components can serve **one LLM too large for any
single node**, [exo](https://github.com/exo-explore/exo)-style: probe every node's
live memory, cut a **memory-weighted contiguous layer partition** (each node's slice
∝ its usable RAM), and run a pipeline-parallel ring over it. Pipeline parallel is
the only scheme that survives the fleet's 1 GbE interconnect (one activation handoff
per shard boundary per token — ADR-2605300000); tensor/expert all-to-all stay the
Thunderbolt upgrade axis.

The planner + engine adapters are **pure cljc** (`murakumo.infer.plan` / `.engine`) —
the same code cuts plans in bb on the operator's terminal, in JVM tests, in
cloud-murakumo's CF Worker, and (eventually) inside a kotoba WASM component. Engines:

- **`:llamacpp-rpc`** — every worker runs a small `rpc-server` (ggml RPC: Metal /
  CUDA / CPU alike, so macOS minis and linux boxes mix freely); the head runs
  `llama-server --rpc … --tensor-split …`, holds the GGUF on ITS disk (weights
  stream to workers at load, cacheable node-side with `-c`), and serves the
  **OpenAI-compatible `/v1` API**. No model download on any worker.
- **`:mlx-ring`** — `mlx.launch --backend ring` + mlx_lm pipeline sharding for
  MLX-format checkpoints (all-Apple fleets).
- **`:mlx-moe`** — [mu-hashmi/mlx-moe](https://github.com/mu-hashmi/mlx-moe)
  single-node MoE serving: **no ring at all**. A model registry entry with
  `:model/engine :mlx-moe` skips the fleet-wide layer partition — a MoE
  checkpoint's router only ever activates a handful of experts per token, so
  mlx-moe loads the model WITHOUT its expert weights, discovers which experts
  a prompt routes to, and pages only those in from the node's own SSD. One
  Mac with ≥32 GiB usable memory then serves a checkpoint whose full weights
  exceed it (mlx-moe's own number: a 46 GB Qwen3-Coder-Next model on a 32 GB
  Mac). `murakumo.infer.moe` is the pure single-node planner (best-memory-node
  pick, README hardware-table capacity tiers, an honest "does this model
  benefit" verdict from the expert-ratio/shared-expert heuristic); it shapes
  its plan exactly like `murakumo.infer.plan`'s (one `:head?` assignment
  spanning every layer), so `infer.engine/commands`, `infer.credits/settle`,
  and `plan/report` all work on it unmodified.

For the M4 mini fleet, the efficient GLM-5.2 path is still
**GGUF + `:llamacpp-rpc`**: 16 GiB minis contribute small contiguous layer
shards and cache them with `rpc-server -c`. `mlx-moe` is a per-node hot-expert
cache for Apple nodes that can hold the non-expert base plus the measured
resident expert capacity. The local GLM-5.2 mxfp4 Clojure/Datomic profile is
registered as `glm-5.2-mxfp4-mlx-moe`; it requires a single Apple node at the
measured `capacity=4/8` tiers, so 16 GiB minis are rejected honestly instead of
being counted together as if `mlx-moe` were distributed.

```bash
npm run task -- infer probe                    # live mem/disk/GPU map of the fleet
npm run task -- infer plan glm-5.2-reap50-q2k  # shard plan + go/no-go gate (infer.edn registry)
npm run task -- infer provision                # push rpc-server + raise iogpu.wired_limit_mb
npm run task -- infer ha-provision             # adopt existing binaries into LaunchDaemon + HA key
npm run task -- infer up                       # start the worker ring
npm run task -- infer serve glm-5.2-reap50-q2k ~/models/GLM-5.2-…-00001-of-00004.gguf
npm run task -- infer generate "叢雲とは何ですか"   # OpenAI API → the whole fleet answers

# Hugging Face model cache setup over Tailscale SSH
bb murakumo model plan trellis-image-large asher
bb murakumo model setup trellis-image-large        # auto: live non-canary node; asher is fallback
bb murakumo model status trellis-image-large all
bb murakumo revive all                             # Wake-on-LAN offline Macs via a live peer

# :model/engine :mlx-moe — same verbs, single-node path (no ring/up/down):
npm run task -- infer plan qwen3-coder-next-mlx-moe    # picks the best-memory node + capacity + verdict
npm run task -- infer provision                        # pip install -U mlx-moe on that node
npm run task -- infer serve qwen3-coder-next-mlx-moe   # nohup `mlx-moe serve` there, OpenAI-compatible /v1
npm run task -- infer generate "叢雲とは何ですか"        # targets whichever host the last plan chose

# Experimental GLM-5.2 mxfp4 hot-expert cache on one 32 GiB+ Apple node:
npm run task -- infer plan glm-5.2-mxfp4-mlx-moe
npm run task -- infer serve glm-5.2-mxfp4-mlx-moe      # capacity/pin-top-k/profile come from infer.edn
```

The distributed head takes `:infer/parallel` from `infer.edn` (currently 2).
llama.cpp continuous batching therefore admits two concurrent request slots;
the configured 524288-token total context gives each slot the model's full
262144-token training window. Extra requests wait at the head rather than
creating unbounded worker processes.

`infer provision` also installs each macOS RPC worker as the system
LaunchDaemon `com.murakumo.rpc-worker` (`RunAtLoad` + `KeepAlive`). On a remote
head it creates a dedicated Ed25519 recovery key whose worker-side
`authorized_keys` entry is restricted to exactly one forced command: kickstart
that LaunchDaemon. The 30-second head watchdog probes `/slots`; after the
420-second cold-load grace it first checks for established client connections,
because llama.cpp can serialize `/slots` behind a healthy long decode. A busy
head is left alone. An idle head must fail two probes five seconds apart before
the watchdog stops it, restarts every RPC worker through that constrained
capability, waits until every planned endpoint accepts TCP, and only then starts
the head again. `ExecStartPre` repeats the all-endpoint check, so llama.cpp
cannot silently come up with a partial rank set; it also gives the RPC protocol
five seconds to settle after TCP starts listening. The watchdog appends
machine-readable health/busy/recovery events to
`/var/lib/murakumo/ring-watchdog-events.log`; logrotate retains twelve weekly
files. Inspect with `systemctl status murakumo-ring-watchdog.timer` on the head,
`tail /var/lib/murakumo/ring-watchdog-events.log`, and
`sudo launchctl print system/com.murakumo.rpc-worker` on a Mac worker.

### Is the link fast enough for tensor parallel? — `topology_probe`

`choose-strategy` returns `"tensor"` at 20 Gbps and above, and until
2026-08-15 **nothing produced the number it reads** — every caller was a test
passing a literal, and the production path spelled it `(or link-gbps 0)`, so
an unmeasured fleet and a slow one were the same input (ADR-260815).

```bash
nbb src/murakumo/infer/topology_probe.cljs discover      # nodes from tailscale + Bonjour vs fleet.edn
nbb src/murakumo/infer/topology_probe.cljs nominal       # what each NIC claims — cannot lift the gate
nbb src/murakumo/infer/topology_probe.cljs thunderbolt   # bridge0 state: the cable-arrived detector
nbb src/murakumo/infer/topology_probe.cljs measure --bytes-mib 128   # real transfers, counted at the receiver
```

The link number is **zero unless every rank boundary carries a verified
transfer**, so no plan the fleet makes today changes; what changes is that
`pipeline` now arrives with `:evidence :measured | :partial | :none |
:unverified` instead of a zero meaning four different things. Measured
2026-08-15: **933 Mbps** between minis (a saturated 1 GbE wire — pipeline
parallel remains correct), and **27 idle Thunderbolt ports** already enrolled
in `bridge0` on the nine reachable nodes, waiting on cables rather than on
software.

### Standalone (no RPC ring) — when the model fits on the head alone

The head (this fleet's: an AMD Ryzen AI MAX+ 395 "Strix Halo" APU, Radeon
8060S iGPU) is a real GPU-capable machine, but its `:infer/head :bin-dir`
RPC-ring binary is a CPU-only build — `-ngl 999` was a no-op there, so the
head's ~40% ring-share ran on CPU alone. For any model whose full weights
fit in the head's own memory, skip the ring entirely and run a GPU-backend
build standalone instead:

```bash
npm run task -- infer down                                              # free the RPC workers, not needed
npm run task -- infer serve-standalone qwen-agentworld-35b-a3b \
  /home/gad/models/Qwen-AgentWorld-35B-A3B-GGUF/Qwen-AgentWorld-35B-A3B-UD-Q4_K_M.gguf
```

Verified 2026-07-05: **61.5 tok/s** vs. **12.7 tok/s** for the same model
spread across the 7-node CPU RPC ring — real GPU beats network-distributed
CPU by ~5x on this hardware. The binary is the official llama.cpp Vulkan
release (`*-ubuntu-vulkan-x64.tar.gz` — Mesa/RADV detects the iGPU cleanly);
the equivalent ROCm 7.2 release build detects **no device** at all
(gfx1151/Strix Halo isn't in ROCm's supported list yet). `:infer/head
:standalone-bin-dir` in infer.edn points at the Vulkan build.

### Claude Code on the fleet

`gftdcojp/local-murakumo`'s `/v1/messages` translates the Anthropic Messages
API to/from whatever's actually serving here — the same shape of bridge z.ai
runs for GLM. `tools/claude-murakumo` sets `ANTHROPIC_BASE_URL`/
`ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_MODEL` and launches the real `claude` binary:

```bash
bb claude                 # or ./tools/claude-murakumo/claude-murakumo
```

The go/no-go gate is honest about the memory math: `plan` exits non-zero when the
fleet cannot hold the weights (e.g. GLM-5.2-REAP50 **MLX-4bit = 214 GiB ✗** on
today's 11×16 GiB + head, while **GGUF Q2_K = 129.5 GiB ✓**) — or, for an
`:mlx-moe` model, when no single node clears mlx-moe's smallest measured
hardware tier (32 GiB usable memory). Plans, model registry, and run results
(tok/s) are published to cloud-murakumo's `/infer/*` API.

## Identity & no-server-key

kotoba and murakumo share one local operator identity so both CLIs work on one
machine without two env seeds.

Generate once with kotoba (`kotoba identity new` / shared store). That writes

```
${XDG_DATA_HOME:-$HOME/.local/share}/kotoba/operator.seed
```

(mode **0600**, never committed, never echoed) — the exact kotoba-lang/kotoba#493
contract. When `XDG_DATA_HOME` is unset that is `$HOME/.local/share/kotoba/operator.seed`.
One path only; murakumo does not also look under `~/.kotoba/`.

Resolution: `MURAKUMO_OPERATOR_SEED` (32-byte hex) if set, else that shared
file. Missing both is the existing missing-seed error; murakumo does not invent
a seed. `identity` / report commands print the **operator DID** only.

```bash
kotoba identity new                         # write the shared 0600 seed file
nbb scripts/run-task.cljs identity          # same DID kotoba prints
# optional override:
# export MURAKUMO_OPERATOR_SEED=<32-byte hex>
```

The fleet shares that one **operator DID**. Per-node identities are derived
deterministically (`sha256(operator-seed : node-name)`) so they are stable and
reproducible without storing a secret per node. Autonomous component writes are
attributed to the operator (or, with a member CACAO leash, to the consenting member —
see `com-junkawasaki/kotoba`'s mesh persistence + etzhayyim's `issue-cacao`).

## Fleet admission — kekkai (zero-trust gate, opt-in)

`fleet.edn` is the **desired** inventory; it is not, by itself, an admission
record — anyone who can edit `fleet.edn` and reach a node over Tailscale gets
treated as fleet today. `murakumo.kekkai` closes that gap by gating
`murakumo.fleet/select` — the single choke point every command (`nodes`,
`provision`, `status`, `mesh`, `deploy`, `up`/`down`) resolves its node set
through — against [`kotoba-lang/kekkai`](https://github.com/kotoba-lang/kekkai),
a zero-trust Tailscale-equivalent control plane (coord-LLM proposal ⊣
TailnetGovernor, admission always routed to a human, append-only ledger).

```bash
cp kekkai-tailnet.edn.example kekkai-tailnet.edn   # opt in
# edit :status per node ("authorized" | "pending" | "expired" | "revoked")
bb murakumo nodes    # nodes without :status "authorized" are now excluded,
                      # reported to stderr: "[kekkai] <name>: not authorized (<status>) — excluded from fleet ops"
```

- **Opt-in, not a breaking default.** Absent `./kekkai-tailnet.edn` (or
  `$MURAKUMO_KEKKAI_LEDGER`), `select` behaves exactly as before — every
  command against every fleet.edn node. The gate only activates once that
  ledger file exists.
- **Deny-by-default.** A node not in the ledger at all is treated as
  `"unknown"`, same as `"pending"`/`"expired"`/`"revoked"` — only an explicit
  `"authorized"` entry passes. Being listed in `fleet.edn` is never, on its
  own, sufficient (that would make the governor a no-op).
- **Process boundary, not an in-process dep.** kekkai rides langgraph/JVM;
  murakumo's own CLI runs on babashka. Status lookups shell out to
  `clojure -M -m kekkai.cli <ledger> <node-id>` in the sibling kekkai checkout
  (`$MURAKUMO_KEKKAI_DIR`, default
  `~/github/com-junkawasaki/orgs/kotoba-lang/kekkai`) — the same
  process-boundary shape murakumo already uses for the kotoba/tailscale/ssh/
  quic-driver binaries, rather than requiring kekkai's StateGraph stack
  in-process (a sci/babashka compatibility risk).
- **What this does NOT replace.** `cloud.edn`'s default-deny capability
  policy (`bb cloud dial ... capability=ssh`) still governs what a given
  *admitted* node may reach; kekkai gates fleet **membership** (is this node
  operable at all), a layer below that. Real admission (`"pending"` →
  `"authorized"`) happens through kekkai's own CoordinationActor elsewhere —
  this ledger file is the ground-fact snapshot murakumo reads, not the
  admission flow itself.
- Pure logic (`murakumo.kekkai.gate`, env resolution + node partitioning) is
  unit-tested offline in `bb test`; the subprocess shell (`murakumo.kekkai`)
  is exercised manually against a real sibling kekkai checkout.
- **Known cost (not yet optimized): one JVM spawn per node.** `apply-gate`
  shells out to `kekkai.cli` once per node in the selection (no batching), so
  enabling the gate on a full fleet adds one `clojure -M` cold-start per node
  to every command. Fine for occasional `provision`/`mesh`, noticeable on a
  larger fleet or a tight loop. A batched `kekkai.cli <ledger> <node-id>...`
  (one JVM, N statuses) would remove this — not done yet. A launch failure
  (missing `clojure` on PATH, broken checkout) degrades that node to
  `"unknown"` (denied) rather than crashing the command.

## Status (honest)

> **Measured fleet state, 2026-07-25** (`nbb scripts/run-task.cljs ops status`, cross-checked
> with a `task run` over all 11 reachable nodes). The claims in this section describe what the
> CODE does; here is what the FLEET currently looks like:
>
> - 11 of 12 nodes reachable over ssh (xavier has no `~/.ssh/config` entry — see `fleet.edn`).
> - `~/.murakumo/bin/kotoba-server` is installed on **5** of them (simeon, levi, joseph, dan,
>   benjamin) and those 5 answer `/health` with `wasm_executor: ready`. The other 6 have no
>   binary — they are un-provisioned, not crashed.
> - **Residency IS in place on those 5**, and it is a **system LaunchDaemon**, not a user
>   LaunchAgent: `/Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist`, `RunAtLoad=true`,
>   `KeepAlive=true`, with `com.murakumo.kotoba-mesh-watchdog` alongside it on dan / simeon /
>   joseph. Every live server reports `XPC_SERVICE_NAME=com.murakumo.kotoba-mesh` and the full
>   provisioned env (`KOTOBA_AGENT_*`, `KOTOBA_P2P_*`, roles/labels/ports, bootstrap peers), so
>   these are launchd-managed services that self-heal as designed.
>   **Look in `/Library/LaunchDaemons`, not `~/Library/LaunchAgents`**, and note that
>   `launchctl list` run over SSH shows the *user* domain only — it will not list this job.
>   (Two earlier passes of this note got that wrong in both directions; the prose above is what
>   was actually measured.) The `ai.gftd.murakumo-*` agents several nodes carry are a different
>   system — `/usr/local/bin/gftd-murakumo … tunnel` against `murakumo.gftd.ai` — and are
>   unrelated to the kotoba mesh.
> - So the gap is **provisioning, not residency**: the 5 dark Macs (asher, judah, zebulun,
>   naphtali, issachar) have neither the binary nor the daemon. All 5 accept passwordless
>   `sudo`, so installing the daemon is possible; disk headroom is thin on issachar (1.3G) and
>   naphtali (2.8G). What is still missing is `MURAKUMO_OPERATOR_SEED`, from which each node's
>   `KOTOBA_AGENT_*` / `KOTOBA_P2P_*` identity is deterministically derived — it is not in the
>   environment, not in kagi under that name, and not in the login Keychain under that service.
> - The `kotoba-server` binaries that ARE installed are byte-identical across all 5 nodes
>   (sha256 `f1c813cf…`), so redistributing that artifact needs no rebuild.
> - The `LINKS` column counts peers seen in `~/.murakumo/mesh.log`, which survives the binary
>   being removed — read it as history, not as current peering.
>
> Re-provisioning needs the pinned `kotoba` / `kotoba-server` binaries (`bin/BUILD.edn`:
> kotoba `4f38b74a`, features `p2p,realtime-wasm,webrtc`), which are distributed out of band
> and are not in this checkout.

- **Works now**:
  - `wash` layer (imperative): provision / resident LaunchDaemon / status-aggregation /
    per-node + fleet-wide component deploy.
  - **cross-node peering** (`mesh`): the 2-pass PeerId-collect + `KOTOBA_BOOTSTRAP_PEERS`
    re-provision is implemented — nodes dial each other over Tailscale and form ONE
    gossipsub lattice, so a component placed anywhere can run anywhere.
  - **durable mesh-state projection + dashboard + liveness alerts** (`dash`): heartbeat
    snapshots persisted to the Datom log, web UI, drift alerts.
  - **`wadm` layer (declarative)**: `reconcile` desired-vs-observed plan + `--apply`
    convergence + `--watch` with as-of history. Pure core unit-tested offline (`bb test`).
  - **kekkai fleet-admission gate** (opt-in): `select` filters to
    kekkai-`"authorized"` nodes once `kekkai-tailnet.edn` is configured.
- **Next**:
  - `reconcile --apply` currently converges *up* (re-publish under-replicated apps);
    **scale-down / eviction** of `:over` placements is reported but not enacted (kotoba
    needs a lattice `Stop`/drain surface murakumo can drive).
  - match-without-rebuild needs the deployed component **CID surfaced back** into
    `murakumo.app.edn` automatically (today you paste `:cid` after a first deploy).
  - reconcile reads observed placement from node logs (`trigger: executed … <cid>`);
    a first-class `lattice ps --json` on `kotoba-server` would replace the log grep.
  - `:mlx-moe`: the planner/engine/CLI path is implemented and unit-tested
    (`bb test`), but **not yet run against the live fleet** — today's minis are
    16 GiB each, below mlx-moe's smallest measured 32 GiB tier, so `infer plan
    qwen3-coder-next-mlx-moe` currently reports `DOES NOT FIT` honestly on this
    exact hardware until a ≥32 GiB node (fleet or `:infer/extra-nodes`) joins.

## License

Apache-2.0.
