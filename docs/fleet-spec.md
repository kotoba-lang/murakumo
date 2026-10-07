# Fleet intent (`murakumo spec`) — ADR-2610071730 P1

The fleet is managed in three layers, by rate of change (root ADR-2610071730):

| layer | what | written by | travels by |
|---|---|---|---|
| L1 intent | models, classes (template + model + replicas + eligibility), trusted node DIDs, pinned versions | the operator, signed | the signed topology's gossip (`:8796`), schema 1 + `spec-version 2` keys |
| L2 observed | each node's own health, workloads, versions, with a timestamp | each node, its own entry only | HTTP self-report (today: the itonami peer `:7420`; P2: resident `/health`) |
| L3 placement | which node runs which class | every node, with the same function | computed, settled by quorum leases (P3) |

Git is an audit mirror of L1 at most. Fast-changing state never goes through git or an operator signature.

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

## Known gap (P2)

L2 today is the itonami peer's public, signed self-report. Nodes without a peer (xavier, k16, 6600hs, …) and image
lanes are not observable yet, so `drift` shows them as `no fresh observation` / `missing`. P2 moves the self-report
into the resident's `/health` and gossips it.
