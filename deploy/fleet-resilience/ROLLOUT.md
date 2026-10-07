# Staged fleet rollout

Run the packaged CLI after `npm ci --prefix release`. Both entrypoints require a
private 0600 peer token file. The deployment examples bind loopback and do not
expose a public service. Replace example model/bind values using `murakumo fleet ps`
and actual ComfyUI inventory. Declare a restart argv only after verifying it on
that host. Keep old processes running until private qualification passes.

Each host owns only its local backend and durable receipt directory. Bind a
resident to the node's restricted private interface only after private auth and
network policy checks. Configure two gateways with the same peer membership.
Do not share an execution backend between two residents. Shared text/image memory
uses one group. All production model writes must pass through their owning resident.

Qualification: `node --test test/fleet_resilience_test.mjs` verifies packaged code
with simulated HTTP backends. `MURAKUMO_TEST_SOURCE=1` runs canonical CLJK sources.
The image fixture checks transport only; run a real image canary and inspect the
artifact on each image lane before marking it available in the product.

Public migration is a separate gate: add origin authentication to the API Worker,
attach independently supervised connectors, verify the public text and image
paths, and change UI readiness and failure-lock settlement. Neither the examples
nor a running private resident establishes that gate.

Rollback: return Worker traffic to its previous configuration, drain resident
requests, then stop new gateway/resident services. Preserve receipts; never delete
unknown job records to make a retry pass. Restore legacy callers only after the
new residents stop accepting work. Do not restart or migrate a busy model.
