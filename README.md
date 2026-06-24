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
```

## Command surface

| command | what it does |
|---|---|
| `nodes` | Tailscale reachability + SSH + whether the mesh binary/agent is present (read-only fleet map) |
| `provision [node\|all]` | `rsync` the `kotoba`/`kotoba-server` binaries, render + load the per-node LaunchAgent (idempotent) |
| `up` / `down [node\|all]` | `launchctl kickstart` / `bootout` the resident mesh node |
| `status [node\|all]` | per-node `/health` (wasm_executor, peer_count) + `lattice ps`, folded into one table |
| `deploy <app.edn> [node]` | port-forward the node's kotoba port, then `kotoba app deploy --publish` (clj→WASM → lattice) |

## Layout

| path | role |
|---|---|
| `fleet.edn` | node inventory (host, roles, labels, port) — the SSoT |
| `src/murakumo/ssh.clj` | Tailscale-SSH transport (BatchMode, fast-fail, scp/curl-on-node) |
| `src/murakumo/fleet.clj` | inventory load + `tailscale status` enrichment + node selector |
| `src/murakumo/core.clj` | the command implementations + per-node identity derivation |
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

- **Works now**: provision/resident/status-aggregation/component-deploy per node. Each
  node is a self-contained single-node lattice that hosts components + fires cron, and
  murakumo gives one terminal to drive all of them.
- **Next**: cross-node lattice peering (one auction spanning the fleet) needs gossipsub
  **bootstrap multiaddrs** wired from `fleet.edn` into each node's swarm dial list — a
  kotoba-side env (`KOTOBA_BOOTSTRAP_PEERS`) + a murakumo `--peers` render. Until then,
  placement is per-node, not fleet-wide.
- **Later**: durable mesh-state projection (Heartbeats → Datom log), a web dashboard,
  liveness alerting. See the design notes in `core.clj`.

## License

Apache-2.0.
