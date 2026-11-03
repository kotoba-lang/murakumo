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
bytes.

## Native counterexample

The stored KIR was copied to a temporary fixture. Its function bodies were
unchanged; the fixture added an exported zero-argument `main` calling
`(admit-code 0 76 100 20 5)` and removed the top-level `oracle-value`,
`schema-identities` and `blocks` metadata that the native KIR reader does not
accept. The native compiler's `compile-kir` path compiled it on macOS ARM64;
the resulting signed diagnostic executable returned `0` (admitted). A second
fixture compiled the current Kotoba source using the same arguments and
returned `3` (no remaining capacity). No legacy runtime was used.

This tests the copied KIR through the native reader, not the legacy host
interpreter or an actual deployed process. Source/compiler pinning, effective
host policy, consumer integration and process resource limits remain separate
qualification requirements.

| Diagnostic artifact | SHA-256 |
| --- | --- |
| KIR fixture with metadata removed and test entry added | `ed9304f70c0cc69761a60a70687e4512af01e65695d366fc8d3b74593bc1e337` |
| signed native KIR fixture | `4ffa6fa2f57e177aafa9d0ede49961893a6d23da3eb852de5530f1e3a00967dc` |
| signed native source fixture | `5544db5b19e73e0d3ca76524deaf09bc86881eacc1310d65e615673b10d05325` |

The native source fix must therefore not be presented as protecting that
consumer or as a qualified Mac fleet governor. Replace or regenerate the
artifact through a qualified native compiler and verify the actual consumer
and deployed artifact, or retire the legacy path as part of a qualified native
replacement. No live host or workload was changed during this inspection.
