# Replicated resident topology

The production inference pool uses two gateways, nine Mishima residents and two
dedicated Animagine residents. A gateway failure does not own or abandon a model
slot: residents persist admission before execution and reject replay themselves.
`deploy/fleet-topology.json` declares their intended roles and minimum replicas.

## Configuration without a mandatory controller

An operator signs a monotonically increasing topology revision with a separate
Ed25519 key. Runtime services receive only its public key, a signed initial
snapshot and a few bootstrap peers. They cannot sign topology changes. The
private operator key is never installed on residents, gateways or in Git.

Every resident and gateway serves the same authenticated `/topology` protocol.
They exchange signed snapshots in bounded, rotating batches of three peers.
They retain and atomically persist only a newer valid revision. Failed peers,
missing bootstrap servers and network partitions preserve the last verified
snapshot; inference does not require a topology server to remain online.

An approved node joins by receiving the public key, initial snapshot, fleet
authentication and its own resident configuration. Publish its signed membership
once to any reachable replica; the change converges without editing both
gateway manifests. Disabling membership stops new admissions and canaries after
convergence. It does not cancel a job already admitted. Model installation and
qualification precede membership: discovery alone is not inference readiness.

Equal revisions with different valid contents are a conflict. The affected
replica persists that conflict and refuses new admissions/routing until an
operator signs a higher revision. Restarts cannot erase the conflict. Bad
signatures and older snapshots never replace the current revision.

Configure a service with:

```json
{
  "topology-file": "/var/lib/murakumo-resident/topology.json",
  "topology-public-key-file": "/var/lib/murakumo-resident/topology.pub",
  "topology-seeds": [
    "http://100.87.226.80:8797",
    "http://100.98.24.37:8797"
  ],
  "topology-poll-ms": 10000,
  "topology-fanout": 3
}
```

Production snapshots accept literal tailnet endpoints on ports 8796/8797.
Redirects, public hosts, embedded credentials and query/fragment endpoints are
rejected. Documents are bounded to 256 KiB. Loopback and test ports require an
explicit test-only configuration flag. Fleet authentication still protects the
transport; the operator signature independently authorizes the desired state.

Sign a reviewed input on the operator's machine:

```sh
murakumo topology sign --input deploy/fleet-topology.json \
  --private-key-file /absolute/private/operator-key.pem \
  --output /absolute/private/topology.json
```

The private key must be a regular non-symlink file with mode 0600. The signer
refuses a non-increasing revision when replacing an existing output file.
Keep that output as the operator's revision record; use a higher revision to
roll back a role change. Send the signed JSON to authenticated `POST /topology`
on any replica. Do not rotate the public key as part of ordinary membership
changes: key rotation requires an explicit separately coordinated operation.

## Admission, observation and resilience

A resident can advertise and execute only an enabled role in the signed
topology. A gateway uses current observations only when their roles and topology
revision/digest match its own verified snapshot. During convergence, mismatched
reports are excluded until they match. Disabled gateways refuse inference but
remain able to exchange topology and accept a higher signed revision.

Gateway health includes its signed topology revision and per-model observed
ready/free counts, minimum replicas and a degraded flag. A lost replica reports
degradation while the surviving replica can continue serving. Report timestamps
expire; a signed membership entry alone never makes a node eligible.

Receipts remain pinned to their original resident. Each resident persists a
separate receipt-owner UUID; signed membership binds its name to that UUID.
A retired owner stays in the topology as disabled. Removing or replacing that
binding is refused, so a new machine without the original receipts must join
under a new node name. Past job IDs cannot be dispatched onto a replacement
that silently reused a name. Restoring a node requires its owner identity and
receipt ledger together. Startup refuses a missing or malformed ledger when
the owner identity is already present, so storage loss cannot silently authorize
previous work again. A fresh identity writes its empty durable ledger before
opening admission. Unknown execution is never
redispatched to a different owner. Receipt replication without a fenced consensus
protocol would weaken this guarantee, so this topology change does not copy
receipts or claim transparent mid-generation restart on another node. The
separate asynchronous image-job pool uses its existing fenced lease protocol.

## Fault boundaries and acceptance

- Node/process failure: OS supervision, bounded declared repair and removal of
  unreachable/stale capacity; verified with actual inference and failover.
- Gateway failure: independent gateways and Cloudflare tunnel connectors;
  verified public text generation through the remaining gateway.
- Image node failure: independent dedicated residents; verified public image
  completion on Levi while Zebulun was stopped.
- Topology publisher failure: remaining replicas and persisted snapshots keep
  serving; tested with actual CLI processes, rejoin, restart and new admission.
- Site/power/ISP failure: **not qualified**. The current pool is declared as one
  local site. Two hosts do not establish two sites. Add and qualify a node and
  ingress in a separate site before claiming site-level resilience.
- Public transport: Cloudflare remains a dependency of the public API. Tailnet
  gateways expose an independent authenticated private entrypoint; topology
  exchange and execution use that data path. This does not remove Tailscale
  coordination as an onboarding dependency.

Gad, Asher and Simeon were offline during this change and are not eligible
capacity. Their physical recovery is not claimed. A new node is not advertised
until its model, receipt storage, OS service ownership and end-to-end generation
are qualified.

Run `node --test test/replicated_topology_test.mjs test/fleet_resilience_test.mjs`
against the packaged CLI. Tests exercise approved join and retirement, signature
rejection, rollback rejection, publisher loss, persisted rejoin and conflict
recovery as well as multi-gateway admission and ambiguous-execution handling.

## Legacy empty-owner upgrade

An older resident created its owner identity before its first admission and
could therefore legitimately have no ledger. Do not infer emptiness from a
missing file. For a node confirmed to have never admitted any job, the operator
may set `legacy-empty-ledger-owner-id` to that exact owner UUID for one upgrade.
The resident writes an empty ledger and a durable `.bound-owner` marker before
opening admission. Once that marker exists, the migration setting cannot
authorize a missing ledger again. Nodes with any previous admission must
restore their paired ledger instead. Remove the migration field after upgrade.

## Ingress lifecycle and live process supervision

A connected Cloudflare connector does not prove its local origin works. Bind
`murakumo-recovery-tunnel.service` to `murakumo-fleet-gateway.service` with the
committed systemd drop-ins. Stopping or restarting an ingress must withdraw or
restart its image connector too; the other ingress continues independently.

The gateway also supports `tunnel-start-argv` and `tunnel-stop-argv`. After three
failed observations, it withdraws image ingress when it cannot reach a fresh,
conformant image resident, or its gateway role is disabled. It waits until
`inflight` is zero before withdrawing an otherwise live connector. Reachable
but busy image lanes retain ingress: occupied capacity is not origin failure.
A healthy image resident makes the connector return automatically. The desired
connector state is reasserted every 30 seconds, so an unexpected service stop
does not remain hidden behind cached state.

An optional root-owned `drain-file` refuses new inference with `executed:false`
and exposes `draining` plus `inflight` in private health. Existing streams keep
running. Remove the file to resume; the connector follows automatically.

For Linux CLI services, declare `watchdog-argv` as
`["/usr/bin/systemd-notify","WATCHDOG=1"]` and apply
`deploy/systemd/cli-watchdog.conf` before the safe restart into this release.
The CLI emits bounded notifications only while its server is listening. An
unresponsive event loop misses the systemd watchdog deadline and is restarted.
The receipt fence remains authoritative on restart; unknown work is not replayed.

Use the portable notification command above: older fleet systemd versions reject `--no-block`. Verify that `WatchdogTimestampMonotonic` advances across multiple notification periods before qualifying a node. A configured watchdog without successful notifications repeatedly restarts healthy services.
