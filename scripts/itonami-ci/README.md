# itonami-ci — CI jobs on itonami nodes (MVP)

Design: `docs/adr/0002-itonami-node-ci-on-tsukasa.md`. Bootstrap tooling on
kbb (SCI on Node); the pure decision half is `itonami_ci/core.cljk`.

| file | role |
|---|---|
| `itonami_ci/core.cljk` | job spec, placement, receipt body, fail-closed status decision (pure) |
| `itonami_ci/host.cljk` | files, sha256, ed25519 did:key, argv-only processes, `gh` calls |
| `node.cljk` | tsukasa ledger operator CLI (verdicts from compiled `ledger_core.kotoba`) |
| `runner.cljk` | poll → job → lease → gate/stub → signed receipt → status |
| `core_test.cljk` | fail-closed regression checks (18) |
| `config.edn` | targets, gate ref/argv, replicas, context |

State lives outside the repo: `~/.itonami/tsukasa/` (ledger + compiled
ledger_core) and `~/.itonami/itonami-ci/` (node key 0600, leases, jobs,
receipts, state, heartbeat, `runner.log`, `runner.pid`).

`SAHAI` below is a checkout of `kotoba-lang/sahai` main (`src/` is used).

```bash
CP="scripts/itonami-ci:$SAHAI/src"

# 0. compile tsukasa's ledger_core with amu (records commit + KIR digest)
kbb --backend sci --classpath scripts/itonami-ci scripts/itonami-ci/node.cljk compile \
    --tsukasa <tsukasa checkout> --amu <amu checkout>

# 1. register this node (did "self" = the runner's node key) and activate it
kbb --backend sci --classpath scripts/itonami-ci scripts/itonami-ci/node.cljk register \
    --slot 0 --id main-2 --did self --failure-domain owner-site/main-2 \
    --availability intermittent --caps git,kbb,node,cc
kbb ... node.cljk transition --slot 0 --to 1     # admitted
kbb ... node.cljk transition --slot 0 --to 2     # active
kbb ... node.cljk show

# 2. tests, then one dry tick (no status posted)
kbb --backend sci --classpath "$CP" scripts/itonami-ci/core_test.cljk
kbb --backend sci --classpath "$CP" scripts/itonami-ci/runner.cljk --once --dry-run

# 3. long-lived (5-minute ticks), from this repo's root
nohup kbb --backend sci --classpath "$CP" scripts/itonami-ci/runner.cljk \
    --config "$PWD/scripts/itonami-ci/config.edn" >> ~/.itonami/itonami-ci/runner.log 2>&1 &!
```

## Stop

```bash
touch ~/.itonami/itonami-ci/STOP                 # graceful: exits before the next tick
kill "$(cat ~/.itonami/itonami-ci/runner.pid)"   # immediate
```

Remove `STOP` before starting again. To take the node out of placement
instead: `node.cljk transition --slot 0 --to 3` (draining).

## What it will not do

- post `success` for the stub, for one failure domain while `:replicas` is 2,
  for disagreeing verdict CIDs, or for an attempt whose lease was fenced out;
- run anything from the gate branch until `scripts/gate/run.cljk` exists on
  the **remote** ref (it never checks out an operator worktree);
- pass a GitHub credential to the gate process (env is an allowlist).

The MVP posts with the operator's `gh` login. kagi's GitHub lease replaces
that (ADR-0002 §Reporting).
