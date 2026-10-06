# One resident, one job contract

Murakumo owns measured model readiness, admission, placement, execution and local
recovery. Itonami owns business intent, tenant, deadline and budget. It submits a
stable job ID and observes the same job from any surviving endpoint. A CLI is a
control surface; OS supervision keeps the resident running after the CLI closes.

Enable `jobs` on the existing resident config. All actors must use the same ordered
list of three (or another odd number, up to nine) fixed voters declared in the
signed topology's `policies.job-voters`. Every voter needs durable, private local storage. Membership changes
require an explicit migration; editing the voter list is rejected against the
retained ledger. No new central service or Cloudflare state is used by this lane.

```json
{"jobs":{"directory":"/var/lib/murakumo-resident/jobs",
 "voters":[{"node":"xavier","url":"http://100.87.226.80:8796"},
           {"node":"k16","url":"http://100.66.205.17:8796"},
           {"node":"6600hs","url":"http://100.98.24.37:8796"}]},
 "drain-file":"/var/lib/murakumo-resident/resident.draining"}
```

The authenticated `/jobs/ID` contract accepts `{kind,request}` for both text and
image generation. Submission is asynchronous (202). GET returns the assignment
and terminal receipt/result. `/jobs/ID/vote`, `/read`, `/run` and `/receipt` are
private resident operations. Existing gateways forward the public job contract
on their authenticated Tailnet listeners; loopback OpenAI compatibility listeners
do not expose it. Existing OpenAI streaming and image-poll interfaces remain
compatibility adapters and do not yet use this asynchronous job ledger.

```sh
murakumo up /var/lib/murakumo-resident/config.json
murakumo status --config /var/lib/murakumo-resident/config.json
murakumo drain --config /var/lib/murakumo-resident/config.json
murakumo resume --config /var/lib/murakumo-resident/config.json
murakumo jobs submit tenant_job_0001 input.json --config client.json
murakumo jobs status tenant_job_0001 --config client.json
```

A client config declares `token-file` (the existing private fleet admission
credential) and `endpoints` (resident/gateway Tailnet URLs). Its token never goes
in the workload manifest. The new lane is for trusted internal business runners,
not a multi-tenant public authentication or billing migration.

## Safety and failure behavior

Placement selects a measured ready, free matching lane with the same job membership
and signed topology/owner identity. A single-decree Paxos reservation fixes the
execution owner; two of three durable acceptors must accept the same value before
dispatch. Prepare adopts the highest previously accepted value; each unique ballot
cannot accept two values. Acceptor state is fsynced and atomically replaced before
acknowledgment. Indexed records carry content digests and a retained owner binding;
missing/corrupt records or an index mismatch refuse startup.

The selected resident persists execution intent before contacting its existing
node-owned admission endpoint. No other worker may execute that ID. It retains
bounded JSON results, publishes terminal receipts to the voters and repairs
incomplete publication in the background. Duplicate submissions return the same
receipt, and input changes under an existing ID are refused. An Itonami runner or
gateway can fail without abandoning ownership or losing a replicated result.

One voter failure permits new work; a minority cannot reserve or dispatch new
work. Loss of the execution owner after intent leaves `unknown`, including after
restart. This implementation deliberately does not reassign an in-flight job or
promise exactly-once external effects. Result reconciliation is required; a timeout
or a missing reply never automatically creates a replacement ID. Mail, financial
effects, workflow composition, cancellation and model/tensor sharding are outside
this generation lane. Old jobs are not automatically deleted; configured receipt
capacity refuses new jobs rather than discarding deduplication history.

The existing signed desired roles and minimum replicas remain authoritative.
Residents repair their declared local engines; a failed host is excluded from new
placement. This does not provision new physical capacity or manufacture another
failure domain when available nodes all share one site.

Verification: `sh scripts/build-node-cli.sh`, then
`node --test test/quorum_jobs_test.mjs test/fleet_resilience_test.mjs
test/replicated_topology_test.mjs test/node_cli_distribution_test.mjs`.
