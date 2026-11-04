# Flint count and generation share an initial time window

The resident previously passed the same remaining lease/retention seconds to
both the token-count request and the later generation request. A slow count
could leave generation starting after that initial budget. The candidate now
passes the initial wall-clock sample and an absolute deadline to the profile
step. Immediately before starting a new count or generation unit, it samples
the host wall-clock capability and caps curl's timeout to the remaining whole
seconds; invalid, expired, out-of-range or observed backward readings refuse
the new submission. A sample after count is also used as a lower bound for
the generation sample. If a dispatch marker was written before a refusal,
existing unknown-state recovery remains necessary. A standalone profile step
has no resident-supplied window, so its Flint new submission fails closed.
Existing non-Flint request timing is unchanged.

This is only a partial mitigation. It does not re-learn or revalidate the
lease between count and generation, make the time check atomic with starting
the unit, guarantee a monotonic clock between samples, bound queue/startup or
polling overhead, stop an already running unit at the deadline, authenticate
the model host, or provide receiver-side fencing. The resident path remains
unsafe to enable until those conditions and the resource governor are
qualified. A reused pre-existing unit is not stopped by this change.

On 2026-11-04, selected current resident and recovery source plus historical
dependencies passed the existing Amu source `check`. The complete locked
project and integration cases were not built or run. A local signed Darwin
source-unity fixture compiled the actual `pr-window-seconds` function and
passed normal, reduced, expired, too-short and invalid/rollback boundary
cases. A mutation that removes the initial-time rollback check failed as
expected. Each compiler and native fixture process had a three-second CPU
limit; no hard memory cap was applied. This tests arithmetic only: no wall
capability, systemd, lease, inference, concurrent client or GitHub ran.

Source SHA-256: recovery
`6f22f01e4226be51ea711642dcec50f84989a0b6bc2677fea4a9524880710d2c`, resident
`51b5c85823de219d60d3c903db9f96f754bd41e73c300e1dff53d7d93f0e92ba`, and
integration test definitions
`76dff4f0e491526d5ec8a08bf073f2a0ec6d3361a673ba57c7b998c9cf134ebd`.
The diagnostic script SHA-256 was
`db6358e94a2248db910994a5294af23bda7ed4978b29a4ec3feed576c24088b3`;
compiler and diagnostic packager SHA-256 were respectively
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
The signed good and rollback-mutant fixture hashes were
`674c07eb5a30d50a07cb02b5b6e24b8ce6ca7e05e87ea294cade726df044211a` and
`708f9e47d7a598c60215e3ab84739d83ca753578a9393f17d72be0b14996420d`.

## Count submission seam with synthetic dependencies

The local signed Darwin diagnostic was extended to compile the actual
`pr-count` and `pr-window-seconds` source together. Test-only stubs supply
time, systemd state, filesystem responses and the submit operation. With a
10-second initial allowance and 3.5 seconds remaining, the positive case
returned the synthetic count only when the submit stub saw a three-second
timeout. An already-expired case returned unknown. A source mutation that
passed the original ten seconds instead of the remaining window failed the
fixture. The test does not independently observe whether the submit stub was
called in the expired case. Each native process had a three-second CPU limit;
no hard memory cap was applied.

The updated script SHA-256 was
`b67831c2d5e0db85f9306a982c51c14a84690516101e6f0e798fc8077c5f3ee7`.
The signed count-seam and bypass-mutant images hashed respectively to
`c704c95707b1129b7f70aff54ab818394ed63f54096ce2d0994740a5981fab1f` and
`3c3c77bd83f9b72e62111ec99fe39dd0dea7092cf13e6fb7d40687582ef64282`.
This is a synthetic seam test, not an actual clock, systemd/curl, lease,
generation, source-locked application or governor test. The lease refresh,
atomic fence, wall-clock and workload-lifetime limitations above remain open.
