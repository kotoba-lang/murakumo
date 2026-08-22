# Murakumo distributed CI/CD runbook

Murakumo is the execution and verdict authority. GitHub only submits signed
events and displays the external `murakumo.cloud/ci` status. Radicle submits
repository-change events signed by an RID-authorized DID. No GitHub Action is
required for execution or deployment.

## Provision

1. Give each runner a distinct 32-byte Ed25519 seed and list the derived DIDs in
   `:ci.coordinator/runner-signers`. Keep seeds only in the environment named by
   `:ci.runner/seed-env`.
2. Set the webhook secret, optional GitHub status token, and each deployment
   issuer seed in the environment. Never put them in EDN.
3. Copy `examples/ci-coordinator.edn` and `examples/ci-runner.edn`, then replace
   RID, pipeline CID, overlay addresses, paths, remotes, and identities.
4. For automatic deployment, copy the map in
   `examples/ci-deployment-policy.edn` under
   `:ci.coordinator/deployments`. Every target must resolve to its QUIC CD node.
   Keep `:deploy-refs` narrow (normally only `refs/heads/main`); pull-request
   and feature refs never create a CD action unless explicitly listed.
5. Seed the rollback bundle and all its component objects into the coordinator
   CAS. Each import is rejected unless its bytes reproduce the supplied CID:

   ```sh
   clojure -M:artifact-import /var/lib/murakumo/artifacts \
     '<bundle-cid>=/secure/bootstrap/release.bundle.edn' \
     '<component-cid>=/secure/bootstrap/app.wasm'
   ```

## Start

Start fleet CD nodes first, then the coordinator, then at least the configured
replica count of runners:

```sh
clojure -M:cd-node examples/cd-node.edn
clojure -M:ci-coordinator examples/ci-coordinator.edn
clojure -M:ci-runner examples/ci-runner.edn
```

The coordinator must have durable, separately backed-up paths for its broker
store and CAS. Runners may discard workspaces, but their signing seeds and
pinned environment digest must remain stable.

## Observe and recover

`GET /ci/v1/health` checks ingress liveness. `GET /ci/v1/runs/<logical-id>`
returns quorum state plus durable GitHub/CD action delivery state. A `pending`
delivery includes its attempt count and last error and is retried automatically.

Restarting the coordinator replays undelivered outbox entries. Completed CD
actions are recognized from durable environment state and become no-ops.
Expired runner leases are requeued; partial artifact uploads expire and are
deleted. Do not delete the broker store merely to retry a run, and do not move
environment state forward by hand. Restore the store and CAS as one backup
generation so verdict references cannot outlive their objects.

If rollout fails, Murakumo attempts rollback in reverse deployed order and does
not advance environment state. Repair the failed node or missing bootstrap
object; the outbox will retry. Rotate a compromised runner or issuer by removing
its DID from coordinator/node allowlists before installing the new seed.
