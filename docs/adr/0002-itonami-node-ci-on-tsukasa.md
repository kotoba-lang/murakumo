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

## Bootstrap boundary

`scripts/itonami-ci/*.cljk` host files are bootstrap tooling (kbb = SCI on
Node), flagged in their first lines (amu `docs/selfhost-priority.md` rule 9).
The pure decision half (`itonami_ci/core.cljk`) has no host calls and is the
part to port to `.kotoba` (ledger-style) first. tsukasa itself already runs
as a compiled Kotoba artifact here.

## Rejected alternatives

- **GitHub Actions self-hosted runners**: still GitHub's scheduler and
  credential model in the execution path.
- **Run the gate inside kototama now**: kototama has no host-process
  contract; a receipt claiming kototama execution would be false.
- **One node, first result wins, reported as success**: no independent
  agreement; rejected for the same reason as in ADR-0001.
- **Long-lived PATs on nodes**: kagi ADR-0003's reasons; the MVP's `gh`
  login is a disclosed stopgap on the operator's own machine only.
- **A third CI system**: this extends ADR-0001's verdict/attestation shape and
  is meant to converge with it (JUN-254 children), not to run beside it.
