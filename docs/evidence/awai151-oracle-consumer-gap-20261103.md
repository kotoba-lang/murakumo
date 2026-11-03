# Accelerator admission: source and consumer differ

At source revision `9b7b19fe9cc0c2c6d079e2ff9e160afd197c09e9`, the native
candidate `kotoba/accelerator_budget_core.kotoba` accounts for both resident
and opportunistic allocations when admitting a resident. Its SHA-256 was
`baf4fc89f16be55de64321455bc2ccd15591d9da1807dc5613101805a2706256`.

The separately stored legacy product KIR
`resources/murakumo/oracle/accelerator_budget_core.kir.edn` had SHA-256
`8990876d9868005ca6ca79bab40ff90673febd92b8f2cdd318957240e7507baa`.
Static inspection of its `admit-code` shows that its resident branch compares
only `request + resident` with `usable`, ignoring opportunistic allocations.
For usable 100, resident 20, opportunistic 5 and request 76, that branch
returns admission even though the allocations total 101. The native candidate
returns refusal for this boundary in its existing compiled fixture.

The legacy host's `murakumo.fleet.accelerator/admit` calls the oracle's
`admit-code`; the oracle catalog maps `:accelerator-budget` to this KIR
resource, and `call` executes the loaded KIR. This is static tracing of the
repository path, not proof that a particular deployed process loaded these
bytes. The KIR was not executed in this observation.

The native source fix must therefore not be presented as protecting that
consumer or as a qualified Mac fleet governor. Replace or regenerate the
artifact through a qualified native compiler and verify the actual consumer
and deployed artifact, or retire the legacy path as part of a qualified native
replacement. No live host or workload was changed during this inspection.
