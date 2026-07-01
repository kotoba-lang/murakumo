# murakumo 叢雲

**Control plane for the kotoba WASM lattice/mesh across the Tailscale Mac-mini fleet.**

kotoba ships a single-node mesh runtime (`kotoba-server` with the `p2p,realtime-wasm`
features) and a single-node status command (`kotoba lattice ps`) — but **no
fleet-facing control surface**. `murakumo` is that surface: a thin **babashka/clj**
operator that runs **from your terminal**, reaches every node over **Tailscale SSH**,
installs a **resident** kotoba mesh node on each, and folds the whole fleet into one
view. No agent is installed on the nodes beyond the two kotoba binaries.

> Not to be confused with the *etzhayyim* murakumo (k3s-on-Lima + Ansible control
> plane for the religious-corp **LangGraph/Pregel cells**). This repo is the
> **kotoba WASM mesh** layer — libp2p lattice nodes hosting content-addressed WASM
> components (`run` / `on-http` / `on-tick` / `on-kse`). Different substrate, on the
> same hardware.

## What it manages

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
export MURAKUMO_OPERATOR_SEED=$(openssl rand -hex 32)   # the fleet operator identity (never commit)
export MURAKUMO_KOTOBA_DIR=~/github/com-junkawasaki/orgs/com-junkawasaki/kotoba

bb murakumo nodes                 # who's reachable, who has the mesh installed
bb murakumo provision asher       # rsync binaries + install + load the LaunchAgent (canary first)
bb murakumo status                # fold /health + lattice ps across the fleet
bb murakumo deploy app.edn asher  # compile clj→WASM + publish to a node's lattice
bb murakumo provision all         # roll out to the whole fleet once the canary is green
bb murakumo mesh all              # form ONE gossipsub lattice (fleet-wide auction)
bb reconcile murakumo.app.edn --dry-run   # declarative desired-state plan (wadm); --apply to converge
```

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
| `fleet <datom-log.edn> [now-ms]` | **coordination-plane view** — fold a [kotoba-fleet](https://github.com/kotoba-lang/kotoba-fleet) Datom log into one snapshot (per-work holders · active leases · pending proposals) via `kotoba.fleet.view/snapshot`. The `status` of the 20-agent coordination layer, next to the mesh `status`. |

## Layout

| path | role |
|---|---|
| `fleet.edn` | node inventory (host, roles, labels, port) — the SSoT |
| `connect.edn` | **single connectivity description** (read=HTTP-by-CID / live=libp2p multi-transport); node-class → transports |
| `murakumo.app.edn` | **declarative desired state** (wadm manifest): apps × replicas × placement (incl. `:reach`) |
| `src/murakumo/connect.clj` | connect.edn loader + `serves-reach?` (pure: can a node reach a client class on a plane?) |
| `src/murakumo/ssh.clj` | Tailscale-SSH transport (BatchMode, fast-fail, scp/curl-on-node) |
| `src/murakumo/fleet.clj` | inventory load + `tailscale status` enrichment + node selector |
| `src/murakumo/core.clj` | the command implementations + per-node identity derivation |
| `src/murakumo/reconcile.clj` | the wadm reconciler — PURE desired/observed→plan core + apply/watch/persist shell |
| `src/murakumo/dash.clj` | snapshotter + web UI + Datom-log persistence (observed-state source for reconcile) |
| `test/murakumo/reconcile_test.clj` | offline unit tests for the pure reconcile core (`bb test`) |
| `deploy/com.murakumo.kotoba-mesh.plist.tmpl` | the resident LaunchAgent template |

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

## Identity & no-server-key

The fleet shares one **operator DID** derived from `MURAKUMO_OPERATOR_SEED` (32-byte
hex, supplied via the env, **never committed**). Per-node identities are derived
deterministically (`sha256(operator-seed : node-name)`) so they are stable and
reproducible without storing a secret per node. Autonomous component writes are
attributed to the operator (or, with a member CACAO leash, to the consenting member —
see `com-junkawasaki/kotoba`'s mesh persistence + etzhayyim's `issue-cacao`).

## Status (honest)

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
- **Next**:
  - `reconcile --apply` currently converges *up* (re-publish under-replicated apps);
    **scale-down / eviction** of `:over` placements is reported but not enacted (kotoba
    needs a lattice `Stop`/drain surface murakumo can drive).
  - match-without-rebuild needs the deployed component **CID surfaced back** into
    `murakumo.app.edn` automatically (today you paste `:cid` after a first deploy).
  - reconcile reads observed placement from node logs (`trigger: executed … <cid>`);
    a first-class `lattice ps --json` on `kotoba-server` would replace the log grep.

## License

Apache-2.0.
