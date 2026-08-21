# ADR-0001: Murakumo-native distributed CI/CD

- Status: Accepted
- Date: 2026-07-14
- Owners: `kotoba-lang/murakumo`
- Decision scope: CI scheduling, execution attestation, artifact custody,
  external status publication, and progressive CD
- Related: central ADR `2606231200-kotoba-rad-sovereign-actor-identity`,
  `2606251200-kotoba-rad-git-transport-binding`

## Context

The Kotoba repositories require CI/CD whose execution and deployment authority
does not depend on GitHub Actions. GitHub remains useful as an event source,
review surface, and commit-status consumer; Radicle and kotoba-git provide the
sovereign repository and signed-reference model. The system must tolerate
runner and coordinator restart, reject stale or forged execution claims, keep
artifacts content-addressed from build through activation, and roll out to a
Murakumo fleet without granting a controller ambient deployment authority.

The following failure modes are in scope:

- duplicate forge events and coordinator restart;
- a runner disappearing, replaying a stale lease, or claiming artifacts it did
  not upload;
- one runner attempting to satisfy a multi-runner threshold;
- two honest runners producing different results or artifacts;
- external status or deployment APIs failing after the verdict is durable;
- capability expiry, canary failure, partial promotion, and rollback;
- accidental deployment of a pull-request or feature ref;
- power loss while publishing broker state, outbox state, or CAS objects.

## Decision

Murakumo is the CI execution, verdict, artifact, and deployment authority.
GitHub Actions is not part of the execution path. GitHub and Radicle ingress
normalize authenticated events into one immutable logical RunRequest.

### Scheduling and execution

1. The coordinator persists broker transitions before exposing them, expands a
   logical run into a configured number of stable replica jobs, and leases them
   with short-lived random bearer tokens.
2. A runner DID may execute at most one replica of a logical run. Leases expire
   and requeue; start, heartbeat, completion, and artifact upload reject stale
   tokens.
3. Runners perform an immutable detached checkout, import it into kotoba-git,
   verify the checked-out pipeline CID, and execute argv-only steps in a
   digest-pinned rootless OCI sandbox with bounded resources and no network by
   default.
4. Every declared artifact is read and hashed by the host. Runner-supplied
   artifact identities are not trusted.

### Artifact custody and verdict quorum

1. Each runner writes to a verified local CAS and mirrors every write in bounded
   chunks to the coordinator over the existing Murakumo QUIC RPC session.
   Upload authorization is bound to the active run ID, runner DID, and lease
   token. The coordinator recomputes the CID before CAS admission.
2. A runner stores its receipt and verdict as kotoba-git objects and signs the
   shared logical result ref with kotoba-rad sigref. The verdict excludes
   runner-specific timing/log details but includes source, pipeline, terminal
   result, and exact artifact identities.
3. Completion is accepted only if the signer owns the active lease, is an
   allowed runner, signs the expected RID/ref/verdict CID, the included verdict
   recomputes to that CID, and the verdict, receipt commit/snapshot, and every
   declared artifact already exist in the coordinator CAS.
4. Only distinct authorized DIDs count toward threshold. Competing verdicts do
   not resolve by arrival order; a split threshold is a hard error.

### Finalization and external effects

1. A canonical quorum is the only terminal CI authority. GitHub commit status
   is a projection under the stable `murakumo.cloud/ci` context.
2. Every GitHub or CD side effect is first written to a content-addressed
   durable outbox. Delivery is marked only after success. Restart retries
   pending actions; already delivered actions are not repeated.
3. `GET /ci/v1/runs/<logical-id>` exposes quorum and durable action-delivery
   state. GitHub does not become the status source of truth.

### Deployment

1. Automatic CD is opt-in per repository and exact source ref. Normally only
   `refs/heads/main` is allowed. A passed canonical verdict must contain exactly
   one typed `:murakumo/release-bundle`; ordinary CI artifacts cannot receive
   deployment authority.
2. Before rollout, the coordinator verifies the current bundle, all current
   components, the previous rollback bundle, and all rollback components in its
   CAS.
3. The coordinator issues a short-lived signed capability bound to RID,
   environment, canonical verdict, current and previous artifact CIDs, current
   and previous revisions, time window, and nonce.
4. Every destination node independently validates the capability from its local
   issuer allowlist. The controller may not supply node trust policy.
5. Deployment stages content before execution, deploys a canary, requires
   health, then promotes in configured batches. Any deploy, health, transport,
   staging, or capability-expiry failure rolls touched nodes back in reverse
   order. Durable environment state advances only after success.
6. The terminal rollout receipt is stored as kotoba-git objects, signed on its
   deployment ref, mirrored to the coordinator CAS, and referenced from durable
   environment state. Replaying an already-advanced action is a no-op.

### Durability and secrets

- Broker documents, streams, outbox records, environment state, and CAS objects
  use write-and-force followed by atomic rename; directory entries are forced
  where the platform permits it.
- Runner and deployment issuer seeds, webhook secrets, and GitHub tokens are
  provided only through named environment variables. EDN configuration contains
  identities and environment-variable names, never secret material.
- The broker store and coordinator CAS are backed up and restored as one
  generation so a durable verdict cannot outlive its referenced objects.

## Rejected alternatives

- **GitHub Actions as executor or deployment authority:** rejected because it
  makes a centralized forge part of the trusted execution base. GitHub remains
  an authenticated source and external display.
- **One runner / first result wins:** rejected because it provides no
  independent execution agreement and makes nondeterminism arrival-dependent.
- **Shared runner filesystem as artifact distribution:** rejected because it
  is not a distributed custody boundary and cannot prove coordinator access at
  completion time.
- **Completion payload carries artifact bytes:** rejected because receipts and
  large artifacts need bounded streaming, retry, and independent CID checking.
- **Call GitHub/CD directly from broker completion:** rejected because a remote
  failure after durable completion would either lose the effect or incorrectly
  fail an already-committed transition.
- **Static, unscoped deployment credentials on nodes:** rejected in favor of
  short-lived artifact/verdict/revision-scoped capabilities checked at every
  batch and destination.
- **Repository-only deployment policy:** rejected because it could deploy PR or
  feature refs; an exact ref allowlist is mandatory.

## Consequences

Positive consequences:

- CI/CD remains operable through Murakumo and Radicle when GitHub Actions is
  unavailable or intentionally disabled.
- Execution agreement, artifact custody, and deployment authorization have
  separately verifiable identities and failure boundaries.
- Restart and transient failure are recoverable without silently losing an
  external effect or advancing deployment state prematurely.

Costs and constraints:

- At least the configured replica count of distinct authorized runners is
  required to reach quorum.
- Runner output is mirrored before completion, increasing QUIC traffic and CAS
  storage.
- The initial rollback bundle and components must be CID-verified and seeded in
  the coordinator CAS before enabling automatic CD.
- Operators must rotate DID allowlists and environment secrets together and
  preserve broker-store/CAS backup consistency.

## Implementation evidence

The accepted decision is implemented by:

- `murakumo.ci.coordinator`, `broker-service`, `worker`, `runner-daemon`,
  `artifact-upload`, `attest`, `quorum`, `finalizer`, and `github-status`;
- `murakumo.artifact-store`, `artifact-replication`, and `artifact-import`;
- `murakumo.cd.automation`, `release`, `capability`, `controller`, `executor`,
  `receipt`, `service`, and the node daemon/operations adapters;
- `examples/ci-coordinator.edn`, `examples/ci-runner.edn`,
  `examples/ci-deployment-policy.edn`, and
  `docs/murakumo-cicd-runbook.md`.

Acceptance evidence on 2026-07-14:

- JVM CI/CD suite: 84 tests, 285 assertions, zero failures/errors;
- portable suite: 228 tests, 983 assertions, zero failures/errors;
- real localhost QUIC deployment RPC test passes;
- checked-in coordinator and runner configurations validate;
- repository diff whitespace validation passes.

Future changes that weaken quorum identity separation, coordinator CAS presence
gating, exact deploy-ref gating, destination-side capability verification, or
durable outbox semantics require a superseding ADR.
