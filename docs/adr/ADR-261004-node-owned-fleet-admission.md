# ADR-261004: Node-owned admission and replicated inference gateways

Status: Implemented in source; production migration and qualification pending.

## Failure and decision

The public fleet can fail while nine independent model heads still answer: several
Workers VPC origins and the image gateway depend on one gad connector. Replicating
that connector alone is insufficient. The existing router owns in-flight counters
in its process, so two replicas can independently admit work to the same one-slot
head. Existing overlay adapters include placeholders and cannot be counted as a
working alternative transport.

Each model node runs `murakumo resident serve --config FILE`. Every request goes
through the resident that owns its backend. Multiple `murakumo gateway serve`
instances can admit requests at any location, but own no model slots. There is no
controller leader or cross-node restart election: a resident can only reconcile
its own explicitly declared local lanes. Lanes sharing GPU/unified memory use the
same resource group, including canaries and repair work. Gateways randomly order
ready capable nodes; residents atomically reject stale concurrent admissions.

Text readiness requires an idle `/slots` and a real correct-answer canary. Context
admission conservatively reserves one token per request byte plus output budget.
Image readiness checks that the requested checkpoint exists in ComfyUI, not merely
that an HTTP bridge is alive. This dependency probe is NOT a generated-image proof.
Production acceptance also requires a periodic real image canary.

Receipts contain a body digest, status and deadline, never prompts or image data.
They are fsynced before dispatch and atomically replaced. A known ID cannot execute
again; a changed payload is rejected. Gateway job IDs encode their original node
and lane, so a replay stays with its owner after topology changes. A broken stream,
connection or deadline is `execution_unknown`; it is not sent to another node.
Only an explicit pre-admission refusal stating `executed:false` permits another
candidate. Disconnects and deadlines abort upstream and release the local group;
a new canary must pass after ambiguous failure. On restart, old running receipts
become unknown and external model occupancy prevents admission. This provides
node-scoped replay prevention, NOT global transactional exactly-once execution.

The receipt limit fails closed rather than consume unlimited memory. Archival,
retention and disk-pressure recovery are production follow-up gates. Atomic file
replacement is not replicated storage; losing the receipt disk loses replay proof.
Peer membership and per-node declarations are operator-provided trusted files.
This does not yet implement signed dynamic membership or DID transport.

## Recovery policy

A restart is allowed only after three failed probes, with a declared argv and a
30-minute cooldown. Occupied backends are excluded and never restarted as a
health repair. Command invocation has no shell and a 30-second timeout; the group
remains reserved until it completes. A successful command is not readiness: the
next canary must pass. Process supervision belongs to the local OS. No resident
has authority to power-cycle another machine or restart a fleet-wide network.

## Migration prerequisites

1. Audit every node's real model IDs, context, bind addresses, ComfyUI checkpoints,
   shared memory and verified local restart argv. Examples are not live inventory.
2. Install residents with private peer credentials, loopback or restricted private
   listeners and OS supervision. Direct writes to model backends must be removed
   from normal callers; wrapping a backend still used by legacy callers does not
   establish exclusive ownership.
3. Install at least two gateways on distinct hosts. Verify text and image routing
   privately, then integrate the public Worker with authenticated gateway calls.
   The old image proxy supplies no origin auth and cannot simply be pointed at
   this authenticated gateway.
4. Provide independent ingress connectors and routes to those gateways. Changes
   involving Cloudflare credential transfer and persistent public exposure are
   blocked by the prior automatic approval review until explicit approval.
5. Replace UI configuration-only availability and stale failure locks with live
   admission/readiness and job settlement. Existing app locks are not changed by
   the resident receipt implementation.
6. Qualify production by stopping a resident, gateway and connector separately;
   kill a resident during a job; verify unknown receipts and cancellation; recover
   a backend; verify canary-gated re-entry, text, image artifacts and sustained SLO.

## Current evidence and limits

The local HTTP integration test starts two gateways and two residents, races
multiple callers, observes one backend job at most per node, verifies both nodes
receive work, persists replay rejection across resident restart, removes a dead
peer, serves a mock image, survives one gateway loss and refuses to retry an
ambiguous backend failure. These are simulated backends, not fleet qualification.

Tailscale and Cloudflare remain external dependencies. Removing a single model
head and router dependency is a distributed inference step, not a claim of fully
independent networking, storage or identity. Production is still blocked and the
public endpoint outage is not resolved by this source change.
