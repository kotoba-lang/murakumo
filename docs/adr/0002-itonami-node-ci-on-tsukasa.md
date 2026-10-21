# ADR-0002: CI jobs on itonami nodes — tsukasa ledger, sahai lease, kagi credential

- Status: Proposed (MVP running, 1 node, see §MVP)
- Date: 2026-10-21
- Owners: `kotoba-lang/murakumo`
- Extends: ADR-0001 (Murakumo-native distributed CI/CD). Nothing here weakens
  ADR-0001's quorum identity separation, CAS gating, outbox or deploy gating.
- Linear: JUN-242 (selfhost acceptance gate), JUN-194 / JUN-197 (tsukasa),
  JUN-211 (kagi GitHub lease); the plan is the JUN-254 tree (children JUN-255..262).

## Context

CI must not run on GitHub Actions runners. The first job is the amu selfhost
acceptance gate (JUN-242): `scripts/gate/run.cljk --base --head --repo`, a
runner-independent CLI that writes a verdict EDN (G0/G1/G2/S2/S5 + ratchet)
and exits non-zero on failure, with pinned inputs (amu commit, seed hash,
policy file, deps-lock digest). Its standard is the Linear document
「合格判定と品質基準（Kotoba / amu selfhost / Kotoba spec / Lisp machine）2026-10-21」.

Survey of what exists (2026-10-21, read from each repo's default branch):

| need | what exists | gap for this job |
|---|---|---|
| node registry | `tsukasa` ledger_core.kotoba: registration verdicts, required failure-domain + availability, lifecycle 0..4, domain audit. Pure, no persistence, 8 slots | nothing persists it; nobody places from it |
| enrollment | `murakumo.enroll` (device key → did:key, claim via `aiueos.enroll`); `cloud-murakumo-installer` installs the node CLI but "never registers a node" | enrollment does not write a tsukasa record |
| heartbeat / liveness | `murakumo.fleet-health` reads itonami-agent `GET /itonami/v1/peer` (signed); `fleet ps` is a read-only ssh probe; CI protocol has `:ci/heartbeat` | not tied to the ledger state |
| placement | ADR-0001 broker leases replica jobs to runner DIDs by capability; root `scripts/fleet-ci` places gates over ssh by `nodes.edn` capability | neither uses failure domains or tsukasa lifecycle |
| leases + fencing | `sahai.core` (lease/budget/expiry) + `sahai.fence` (epoch, claim/steal, holder) — pure | not used by any CI path |
| native exec | ADR-0001 runner: argv-only steps in a digest-pinned rootless OCI sandbox (`murakumo.ci.sandbox`, podman). fleet-ci: tarball over ssh stdin | the selfhost gate needs the host toolchain (cc, git, seed loader, amu) on macOS; no non-container backing is declared |
| VM receipts | `kototama` receipt-v1 (`state-before/after`, `fuel-used`, `events`, `child-receipts`, `authority-decision`); `hata.receipt` validates that shape | kototama runs admitted guests (wasm / native kexe), not host processes |
| verdict quorum + signing | ADR-0001 `attest` / `quorum` (bonsai objects + nekko sigref); fleet-ci signs receipts with a weak `fleet-ci/*` grant | — (reused as the target shape) |
| status reporting | `murakumo.ci.github-status` (context `murakumo.cloud/ci`, refused on kbb: no sync HTTP client); fleet-ci posts `fleet-ci/*` with gh / fine-grained PATs | no short-lived credential |
| credential lease | kagi ADR-0003 (GitHub App issuer, ≤1h installation token sealed to the requester, lease ledger) | **proposed only; no broker code on kagi main** |
| node daemon | itonami-agent peer (:7420), murakumo desired-state agent (launchd, 60 s), ADR-0001 runner-daemon (QUIC, JVM-only transport), Murakumo Client.app | none of them takes CI jobs from the tsukasa ledger |

## Decision

murakumo owns this, because tsukasa's own boundary table assigns
"enrollment, heartbeat, job placement, `fleet ps` observation" to murakumo and
ADR-0001 already makes murakumo the CI verdict authority. tsukasa stays a pure
ledger; sahai stays the lease/fence library; kagi stays the credential owner.

### Job spec (content-addressed)

```clojure
{:itonami.ci/version 1 :itonami.ci/kind :selfhost-gate
 :itonami.ci/canonical "itonami-ci/edn-sorted-v0"
 :job/repo "kotoba-lang/amu" :job/pr 1428 :job/base <sha> :job/head <sha>
 :job/gate {:gate/repo "kotoba-lang/amu" :gate/ref "claude/selfhost-ci-gate"
            :gate/commit <sha|"UNMEASURED"> :gate/argv [...]}
 :job/pins {:pin/amu-commit .. :pin/seed-hash .. :pin/policy-digest .. :pin/deps-lock-digest ..}
 :job/budget {:budget/wall-ms 3600000 :budget/max-output-bytes 1048576}
 :job/capabilities #{:git :kbb :cc}}
```

Job CID = sha256 over the canonical text (maps and sets sorted). Nothing
node-specific enters it, so every replica computes the same CID. A pin that
cannot be measured is the literal `"UNMEASURED"`, never omitted or defaulted.
The bootstrap canonical form is replaced by `murakumo.canonical` when that
loads on the Kotoba route; the `:itonami.ci/canonical` tag changes with it.

### Trigger

Polling `GET /repos/:r/pulls/:n` (or the open-PR list) every 5 minutes. A
webhook needs an inbound, authenticated endpoint (ADR-0001 `ci/webhook`, HMAC)
reachable from GitHub; intermittent home nodes behind NAT do not have one, and
the polling cost (2 requests/target/tick) is well inside rate limits. Webhooks
become an optimisation once an always-on coordinator with ingress exists;
polling stays as the fallback.

### Placement

`place` chooses `replicas` nodes from the tsukasa ledger with lifecycle state
2 (active) only, required capabilities ⊆ node capabilities, pairwise-distinct
failure domains, always-on before intermittent, then by id. proposed /
admitted / draining / retired nodes are never placed. Fewer eligible domains
than `replicas` → `:insufficient-failure-domains`; the placed subset still runs
but the status cannot become success.

### Leasing

A sahai lease per (tenant `"itonami-ci"`, guest = job CID), TTL = wall budget
+ 60 s, claimed with `sahai.fence/claim-lease`. At report time the node
re-reads the registry: unless the holder for that fence key is still this
lease id and epoch, unexpired, the attempt is recorded with
`:lease-live? false` and is not counted. An expired lease is stolen with a
bumped epoch, so a node that timed out cannot report over its successor. The
MVP registry is a node-local file; the multi-node registry is murakumo's
(coordinator-held, ADR-0001 broker transitions), not a shared filesystem.

### Execution — native-exec, not kototama

The gate is host toolchain work (cc, git, seed loader, amu). It is **not**
run through kototama: kototama reduces admitted guests and has no "run this
host process" contract, and pretending otherwise would make its receipt lie.
The backing is declared `native-exec`: argv only (no shell), an allowlisted
environment (PATH, HOME, LANG, a per-job TMPDIR — no GitHub token or other
credential reaches the gate), a runner-owned detached checkout of exactly the
gate commit, wall-clock and output budgets. This is confinement by
convention, not a sandbox. tsukasa reserves `hako` (container) and `fune`
(microVM) for the future exec backing; when one exists the receipt's
`:machine-spec` changes and nothing else does. On Linux nodes ADR-0001's
podman sandbox is an acceptable backing once the toolchain image is pinned.

### Result

A receipt shaped after kototama receipt-v1 (`machine-spec`, `implementation`,
`profile`, `message-cid` = job CID, `program-cid` = gate commit,
`state-before` = job CID + pins, `state-after` = verdict CID, `outcome`,
`fuel-used` = UNMEASURED, `events` (argv, exit, stdout/stderr sha256),
`child-receipts`, `authority-decision` = node id/DID/failure domain/lease
id/epoch/expiry/capabilities). Verdict CID = sha256 over the canonical
verdict EDN. The receipt is signed ed25519 by the node key whose did:key is
the one registered in tsukasa; the private key never leaves the node
(0600 file). Receipts carrying `:token`, `:raw-bearer`, `:authorization`,
`:private-key`, `:secret` are refused.

### Verification and replication

`success` requires: a measured PASS (exit 0 and a readable verdict EDN), the
same verdict CID from every live attempt, and agreement from at least
`:replicas` distinct failure domains. Default `:replicas 2`. With one
registered node today, the MVP can report pending/failure/error but not
success — deliberately. Flipping a repo to `:replicas 1` is an owner decision
and must be visible in the status text.

### Reporting

Only `POST /repos/:r/statuses/:sha`, context `itonami/selfhost-gate`, so it
does not collide with ADR-0001's `murakumo.cloud/ci` or fleet-ci's
`fleet-ci/*`. The credential is a kagi lease (GitHub App installation token,
`statuses: write` + `metadata: read`, one repo, ≤1 h) opened only inside the
reporting process. **MVP:** the operator's `gh` login on the node posts the
status — a stated stopgap until kagi ADR-0003's broker exists. No secret is
in the job, the receipt, the status, the logs or the wire between nodes.

### Fail-closed rules

| condition | status |
|---|---|
| gate CLI absent on the gate ref (stub) | pending, `UNMEASURED: ... (stub, no verdict)` |
| job spec malformed | error |
| no active node / no placement and no attempt | pending, `UNMEASURED: no placement` |
| lease expired or stolen at report time | attempt discarded; not posted from that node |
| receipt refused (job CID mismatch, forbidden material, outcome without verdict, bad signature, signer not the ledger node) | error |
| live attempts disagree on verdict CID | error |
| any live FAIL | failure |
| PASS in fewer than `replicas` domains | pending |
| gate ran but produced no verdict | error |

There is no path to `success` that does not pass through all of them.

### Lisp machine rules

No remote eval: a node never evaluates code sent by another; it runs the
gate commit it fetched and verified itself. What crosses nodes is the job
(content-addressed data) and the signed receipt — "the same DefCID and
contract, verified and run on each machine, exchanging content-addressed
actions and receipts". The boundary encoding (canonical EDN, refused
receipts) makes no judgement and fails closed.

## MVP (2026-10-21)

- Node `main-2` (the owner's Mac) registered in a tsukasa ledger at
  `~/.itonami/tsukasa/itonami-ledger.edn`: slot 0, `did:key:z6MkpECTqc8PFFo5WygtBKxbtCjMaXmJfDBDLcS52jq2Pwrn`,
  failure-domain `owner-site/main-2`, availability `intermittent`,
  capabilities `#{:git :kbb :node :cc}`. Verdicts from tsukasa
  `ledger_core.kotoba` @ `4e0bba08` compiled by amu `1b0687a5` to js-kotoba-v1
  (KIR digest `77b7792e…`): register → `tsukasa/ok`; proposed→active →
  `tsukasa/illegal-transition` (refused, as specified); →admitted, →active →
  `tsukasa/ok`. Audit: 1 node, 1 independent domain (single-domain).
- Runner: `scripts/itonami-ci/` (bootstrap tooling on kbb). Gate not yet on
  the remote `claude/selfhost-ci-gate`, so every job is the UNMEASURED stub:
  lease taken, nothing executed, receipt signed, status `pending`.
- Runbook: `scripts/itonami-ci/README.md`.

## itonami container runner (GitHub Actions on a tsukasa node)

Added 2026-10-21. A second, narrower use of the same node: existing GitHub
Actions workflows of **private** repositories run their check jobs on
`main-2` inside an *itonami container* (podman backing) instead of on
GitHub-hosted runners. This does not reverse §Rejected alternatives: the amu
selfhost gate still goes through the ledger → lease → signed receipt path
above. The container runner is a stopgap for workflows that are already
GitHub Actions YAML (e.g. private network-awai repos, where hosted minutes
are at the mercy of the org's billing state), and it is driven by the same
tsukasa lifecycle.

The name is deliberately generic. tsukasa's README reserves `hako` / `fune`
for a future container / microVM exec backing; adopting either needs owner
approval, so neither is used here.

### Shape

| part | where | what |
|---|---|---|
| node record | `~/.itonami/tsukasa/itonami-ledger.edn`, slot 0 `main-2` | capabilities `+ :container/podman :gh-actions-runner` via `node.cljk declare-caps` (ledger_core has no capability rule; the write is gated on ledger_core's `first-problem` re-verifying the stored identity, and refused for an empty slot or a retired node) |
| image | `scripts/itonami-ci/container/Containerfile` | ubuntu 24.04 arm64 pinned by digest, actions/runner 2.338.0 linux-arm64 and node 24.21.0, both sha256-verified at build; git, build-essential, python3, php-cli. Runs as uid 1001, no sudo. apt packages are not version-pinned; resolved versions are written to `/etc/itonami-runner/manifest.txt` |
| supervisor | `scripts/itonami-ci/container.cljk` + `container.edn` | bootstrap tooling on kbb. Ledger state 2 `active` → keep N=1 ephemeral runner per repo; 3 `draining`, 4 `retired`, anything else, or the STOP file → start nothing, stop idle runners, let a busy one finish its job, delete our runner registrations |
| registration | repo level, `network-awai/kotobase-control-plane` | labels `self-hosted, Linux, ARM64, itonami, node-main-2`; `--ephemeral`, one job per container, then the container and its volume are deleted and a fresh one registers |
| log | `~/.itonami/containers/log` | every start, stop, configure, job start, job end, deregistration, refusal |

### Why repo level

Org level with a runner group restricted to private repositories is the
better shape, but the operator's `gh` token has no `admin:org` scope
(`gh auth refresh -h github.com -s admin:org` adds it). Until then the
supervisor registers per repository, and before **every** registration it
checks the repository is private and refuses otherwise: on a public
repository a fork pull request could run arbitrary code on this Mac.

### Security boundary

What a job **can** reach:

- the internet (github.com, package registries) through the podman
  machine's NAT;
- its own per-container podman volume at `/home/runner/_work` (VM storage,
  deleted with the container), and the repository token GitHub gives the job.

What a job **cannot** reach:

- the Mac's files: nothing from `$HOME` is mounted, no SSH keys, no gh
  login, no `~/.itonami` (the only host mount is a per-container secret dir
  that holds the registration token until the container deletes it, before
  `config.sh` runs and long before a job);
- a container engine: no docker / podman socket is mounted; the container
  is rootless podman in the podman-machine VM, `--cap-drop ALL`,
  `no-new-privileges`, `--cpus 4 --memory 6g --pids-limit 4096`;
- the Mac's localhost services and the LAN / tailnet. Measured 2026-10-21:
  without a rule, `host.containers.internal` (192.168.127.254, gvproxy)
  forwards to services bound to the Mac's 127.0.0.1 (postgres and local
  APIs answered). The runner network `itonami-runner` (interface
  `itonami0`) therefore gets an nftables table `inet itonami_runner` in the
  rootless network namespace that drops traffic to 10/8, 100.64/10, 127/8,
  169.254/16, 172.16/12, 192.168/16, fc00::/7, fe80::/10 and everything to
  the namespace except DNS. Re-probed with the table in place: Mac
  localhost and gateway refused, github.com and registry.npmjs.org 200. The
  supervisor applies the table right after each container start (the
  namespace only exists while a bridge container runs) and removes the
  container if it cannot (fail closed), and re-checks it every tick;
- the registration token: it is never in the container environment (a job
  could read `/proc/1/environ`), only in a 0600 file read and deleted before
  `config.sh`.

Residual risks, stated: a job runs arbitrary code from any branch pushed to
the repository (anyone with write access), with internet egress; the image's
apt layer is not reproducible bit for bit; the egress table has a short
window between container start and its application, before the runner has
registered (no job can be assigned in it).

### Workflows

A job lands here only if its workflow says `runs-on: [self-hosted, itonami]`.
network-awai/kotobase-control-plane#833 (draft) moves seven check-only jobs
(no secrets, no `id-token`, no artifact consumed by a deploy). Release,
deploy, signing, artifact-producing and CodeQL jobs stay on GitHub-hosted
runners. When the Mac is off, moved jobs queue rather than run.

### Operate

```bash
# from this repo's root
kbb --backend sci --classpath scripts/itonami-ci scripts/itonami-ci/node.cljk show
podman build --platform linux/arm64 -t localhost/itonami-runner:2.338.0 scripts/itonami-ci/container
nohup kbb --backend sci --classpath scripts/itonami-ci scripts/itonami-ci/container.cljk \
    --config "$PWD/scripts/itonami-ci/container.edn" >> ~/.itonami/containers/supervisor.out 2>&1 &!

touch ~/.itonami/containers/STOP      # stop: drain (busy job finishes), deregister, exit
rm ~/.itonami/containers/STOP         # before starting again
kbb ... node.cljk transition --slot 0 --to 3    # drain the node: same drain, supervisor stays up
kbb ... node.cljk transition --slot 0 --to 2    # back to active: runners start again
```

## Bootstrap boundary

`scripts/itonami-ci/*.cljk` host files are bootstrap tooling (kbb = SCI on
Node), flagged in their first lines (amu `docs/selfhost-priority.md` rule 9).
The pure decision half (`itonami_ci/core.cljk`) has no host calls and is the
part to port to `.kotoba` (ledger-style) first. tsukasa itself already runs
as a compiled Kotoba artifact here.

## Rejected alternatives

- **GitHub Actions self-hosted runners** (for the selfhost gate; the
  container runner above serves existing Actions workflows only): still GitHub's scheduler and
  credential model in the execution path.
- **Run the gate inside kototama now**: kototama has no host-process
  contract; a receipt claiming kototama execution would be false.
- **One node, first result wins, reported as success**: no independent
  agreement; rejected for the same reason as in ADR-0001.
- **Long-lived PATs on nodes**: kagi ADR-0003's reasons; the MVP's `gh`
  login is a disclosed stopgap on the operator's own machine only.
- **A third CI system**: this extends ADR-0001's verdict/attestation shape and
  is meant to converge with it (JUN-254 children), not to run beside it.
