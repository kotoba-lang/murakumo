# Fleet intent (`murakumo spec`) — ADR-2610071730 P1

The fleet is managed in three layers, by rate of change (root ADR-2610071730):

| layer | what | written by | travels by |
|---|---|---|---|
| L1 intent | models, classes (template + model + replicas + eligibility), trusted node DIDs, pinned versions | the operator, signed | the signed topology's gossip (`:8796`), schema 1 + `spec-version 2` keys |
| L2 observed | each node's own health, workloads, versions, with a timestamp | each node, its own entry only | HTTP self-report (today: the itonami peer `:7420`; P2: resident `/health`) |
| L3 placement | which node runs which class | every node, with the same function | computed, settled by quorum leases (P3) |

Git is an audit mirror of L1 at most. Fast-changing state never goes through git or an operator signature.

## Files

- `deploy/fleet-topology.json` — the reviewed **source payload** (bare), the input of `murakumo topology sign`.
- `deploy/fleet-topology.signed.json` — a mirror of the live **signed** document (audit copy; revision 2026100604).
- `deploy/fleet-intent.draft.json` — the spec-version 2 intent draft, unsigned.

## Commands (read-only)

```
murakumo spec draft [--override node:class:key=value] [--out F]   intent draft from topology + fleet.edn + live L2
murakumo spec lint  F                                              validate the spec-version 2 keys
murakumo spec plan  F                                              placement over live observations
murakumo spec drift F                                              declared vs declared vs observed
murakumo spec mesh  F --node N                                     itonami peers.edn for N from L1 trust
```

Signing and publishing stay `murakumo topology sign` / `POST /topology` by the operator.

## Rules

- **Wire compatibility.** Running residents accept only `schema 1` and ignore unknown keys
  (`replicated_topology/validate!`), so the intent is extra keys of the schema-1 payload.
  `test/murakumo/fleet/spec_test.cljk` asserts the resident's validator accepts a spec-version 2 payload.
- **No argv in the spec.** A class names a template from the closed set in `murakumo.fleet.spec/templates`;
  strings that look like commands or absolute paths are rejected.
- **Stale is unknown.** An observation older than the TTL (5 min) makes a node ineligible, never healthy.
- **Pins** carry today's per-node roles through the migration; placement keeps a pinned node first while it is eligible.
- **Members.** A trusted host without a resident (e.g. the operator's agent host) is a `trust` entry with `"role": "member"`.

## L2: resident observations (P2)

Each resident signs its own observation with a per-node ed25519 key (`<state-file>.node-key`, 0600, did:key):
lanes, host memory pressure / free % / swap / TCP TIME_WAIT / ephemeral floor, launchd state of the workload units
(`com.murakumo.mishima`, `com.murakumo.comfy`, `cloud.itonami.agent.peer`, `com.murakumo.resident`,
`com.murakumo.kotoba-mesh`), the model files the intent names (present / size), its release hash, the topology
revision it holds. It refreshes every probe interval and gossips with 3 peers every 10 s along the topology's node list,
so **any resident answers `GET /observations` for the whole fleet**. That endpoint (and `GET /observation`) is served
without the fleet token — signed, health only, on the tailnet bind; everything else still needs the token.

Merge rule (`observation/accept`): signature must verify against the did it names; the first did seen for a node is
pinned (`<state-file>.observation-pins.json`) and a different key is refused; newer `at` wins; entries older than 15
min are dropped and ones dated more than 2 min ahead are refused.

`murakumo spec plan|drift` read L2 from the first resident that answers and fall back to the itonami peers for hosts
without a resident (`L2 sources: …` is printed).

## Convergence (P3a)

Every resident computes, from the signed intent and the fleet's L2, the placement every node computes and the plan for
its own share (`murakumo.fleet.spec/converge-plan`): `ok`, `start`, `needs-lease`, `blocked`. The plan is published in
the node's signed observation (`payload.converge`); `murakumo spec converge F` lists every node's plan.

A resident **acts** only when the signed intent says so for it:

```json
"converge": {"mode": "enforce", "nodes": ["zebulun"]}
```

and then only on `start` steps: a class the operator **pinned** to this node, whose template unit is installed but not
running, by fixed argv (`launchctl enable` + `bootstrap`, or `kickstart`), at most once per unit per 30 min. It never
stops, evicts or renders a unit. Rendezvous-placed (unpinned) shares are `needs-lease` until P3b settles them with quorum
leases. macOS only for now; Linux residents publish their plan and do not act.

Without `classes` in the signed intent (today's revision 2026100604) every resident reports mode `none` and does nothing.

## Quorum leases (P3b)

Shares that placement assigns by rendezvous (not pinned) are settled with quorum leases on the same signed voters as the
jobs (`policies.job-voters`; the voters serve `/leases/…` with the fleet token). A lease is `{node class slot epoch
until}`; it lives in a chain of single-decree Paxos registers `l_<sha256(class)>_<slot>_<epoch>` (the jobs acceptor),
because a single decree is write-once:

- `acquire`: ask the voters for the highest accepted epoch (hint), learn that epoch's value (completing a partial
  accept), then renew (holder, < 10 min left), take over (other holder, past `until` + 60 s), report the holder, or claim
  an open epoch. A lost race adopts the other value. Preempted rounds retry with backoff.
- TTL 15 min; the holder trusts its lease until `until − 60 s`; voters keep the last 3 epochs per slot.
- Two of three voters down: nothing is acquired or renewed and no node starts an unpinned share (fail closed).

A resident takes leases only when the signed intent enforces convergence for it and it can act (macOS). A held lease
turns the share's `needs-lease` step into `start`; the plan publishes `converge.leases` (holder, epoch, until).

Still to do: rendering a missing unit from its template, and eviction (stopping a running share that is no longer
placed) — the two operations that remove capacity — after leases have run on the fleet.
