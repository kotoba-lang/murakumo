# itonami-ci — CI jobs on itonami nodes (MVP)

Design: `docs/adr/0002-itonami-node-ci-on-tsukasa.md`. Bootstrap tooling on
kbb (SCI on Node); the pure decision half is `itonami_ci/core.cljk`.

| file | role |
|---|---|
| `itonami_ci/core.cljk` | job spec, placement, receipt body, fail-closed status decision (pure) |
| `itonami_ci/host.cljk` | files, sha256, ed25519 did:key, argv-only processes, `gh` calls |
| `node.cljk` | tsukasa ledger operator CLI (verdicts from compiled `ledger_core.kotoba`) |
| `runner.cljk` | poll → job → lease → gate/stub → signed receipt → status |
| `core_test.cljk` | fail-closed and status de-duplication regression checks |
| `config.edn` | targets, gate ref/argv, replicas, context |
| `itonami_ci/gate.cljk`, `gate_test.cljk` | pinned gate process boundary and its regression checks |
| `nbb.edn` | runner-only SCI bootstrap config; no ambient Maven/JVM resolution |
| `container.cljk` | itonami container supervisor: ephemeral GitHub Actions runners in rootless podman, driven by the ledger state (ADR-0002 §itonami container runner) |
| `container.edn` | repos (private only), image, labels, cpu/memory limits |
| `container/Containerfile` | pinned linux/arm64 runner image (base by digest, runner + node by sha256) |

Container runner: start, stop (`touch ~/.itonami/containers/STOP`), drain
(`node.cljk transition --slot 0 --to 3`) and its security boundary are in
ADR-0002 §itonami container runner. Log: `~/.itonami/containers/log`.

State lives outside the repo: `~/.itonami/tsukasa/` (ledger + compiled
ledger_core) and `~/.itonami/itonami-ci/` (node key 0600, leases, jobs,
receipts, state, heartbeat, `runner.log`, `runner.pid`).

`SAHAI` below is a checkout of `kotoba-lang/sahai` main (`src/` is used).

```bash
CP="scripts/itonami-ci:$SAHAI/src"

# 0. compile tsukasa's ledger_core with amu (records commit, KIR and artifact SHA256)
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
kbb --backend sci --config scripts/itonami-ci/nbb.edn --classpath "$CP" scripts/itonami-ci/core_test.cljk
kbb --backend sci --config scripts/itonami-ci/nbb.edn --classpath "$CP" scripts/itonami-ci/gate_test.cljk
kbb --backend sci --config scripts/itonami-ci/nbb.edn --classpath "$CP" scripts/itonami-ci/runner.cljk --once --dry-run

# 3. long-lived (5-minute ticks), from this repo's root
nohup kbb --backend sci --config scripts/itonami-ci/nbb.edn --classpath "$CP" scripts/itonami-ci/runner.cljk \
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

Explicit `:revisions` targets pin `:pr`, `:base-sha` and `:head-sha` for a
historical PR, including a merged one. Normal `:prs` polling still selects
only open PRs. The original comparison is retained rather than substituting
the current main as its baseline.

Jobs bind the seed payload hash from the rung record, the policy contents
at its immutable Git commit, dependency-lock and runner-source digests.
Missing pins refuse execution. Attempts have distinct work directories;
stdout/stderr are retained with hashes, and a prior attempt's verdict cannot
be reused. A counted verdict must have the expected format, source pair,
gate commit and matching exit code. Gate/ratchet summaries and measurement
pins enter the signed receipt events; a measured FAIL remains failure, and
one measured PASS remains pending while two failure domains are required.

The MVP posts with the operator's `gh` login. kagi's GitHub lease replaces
that (ADR-0002 §Reporting).

Review regression checks:

```bash
kbb --backend sci --config scripts/itonami-ci/nbb.edn --classpath "$CP" scripts/itonami-ci/host_test.cljk
python3 scripts/itonami-ci/container_test.py
```

Ledger operations verify the recorded artifact SHA256 before importing any
executable bytes, then check the KIR digest. The operator owns both files.
The container library is inert on import; only `container.cljk` starts it.
An active node must declare `container/podman` and `gh-actions-runner`.
Adoption is scoped to the node label. Rebuild the runner image together with
the supervisor: it waits (at most 120 seconds) for the supervisor's
`egress-ready` marker before reading the token or registering with GitHub.
The supervisor refuses images without the `app.itonami.egress-barrier=1`
label before minting a token. A failed filter/start removes the container, volume and token directory.

Status de-duplication persists the repo/head/gate/verdict/state key per PR.
A legacy record without that complete key triggers one post on upgrade;
equal description text cannot establish that the gate or verdict is equal.

Gate admission requires a full gate SHA, matching Amu/policy pins and all
configured gate-file digests. The returned verdict must explicitly attest a
clean gate and match the entry/ratchet/boundary bytes as well as the source
pair, policy digest and exit code. An aggregate PASS with a failed subgate
or recorded failures is refused. Gate fixtures run subprocesses with an
isolated allowlisted environment and keep logs only in their temporary
workspace directories.
