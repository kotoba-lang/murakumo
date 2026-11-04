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

## Darwin diagnostic clock capability observation

On one local Darwin ARM64 host (macOS 26.3.1, build `25D771280a`), a
source-unity fixture containing the actual `pr-wall-ms` and a host-time window
assertion was built with the pinned compiler and `fs2-cli-stdio-general`
diagnostic packager named above. The signed fixture exited with SIGTRAP
(subprocess return `-5`), without stdout or stderr. Its source and image
SHA-256 were `c6f3716ee28d6927a93f538e5fe9274dd4a47b04f9c032b4aa5fa3807457f96f`
and `d5a2f34bd9ade2c93c660f55c276c9fb4e5f4be33f7de77ce05816e37ec45a9c`.

A separate fixture performing only `typed-cap-call 7` with `wall` also exited
with SIGTRAP (`-5`); its source/image SHA-256 were
`7cce6bc18352531845022ba471ce0c2892a1c787c9c709931472a21ed9249279` /
`c53977bdd6028a6ecacfb190a27bfcfaecea261f1e9d24c0edbedc855c6aa927`.
With that call replaced by a fixed decimal test string, the selected
`pr-wall-ms` parser returned the expected number (exit 0); its source/image
SHA-256 were `0ed1705924d7251c7eb4b5de71dbf21b0c7329140708bd52d947e66c0911dcb6`
and `aa51bcae5114715f69fe1c8cb1c06c14303b76060bd368fd3cfe712daeda7953`.
Each compiler and test process had a three-second CPU limit, without a hard
memory cap. Signed images and private temporary directories were task-owned.

This demonstrates that the tested diagnostic combination did not supply a
usable wall-clock call in these fixtures; the exact trap stage is unverified.
It is not a test of a production Mac runtime, Linux runtime, clock rollback,
real lease or governor. Darwin clock capability and the complete resident
runtime closure must be independently qualified before using the deadline
path; do not infer that the arithmetic-only diagnostic exercised wire 7.

## Source-derived Linux clock observation on Xavier

On 2026-11-04, a Tailscale SSH session to Xavier checked the peer's advertised
host key with strict verification and used the source-derived Linux ARM64 F3
compiler from Actions run 41833823850, SHA-256
`46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b`.
It compiled a short-lived guest containing the actual `pr-wall-ms` source and
a stdout print. Guest source and ELF SHA-256 were respectively
`c6e0175c4c6196b2e322d293f9e7be9bda26632563733a179ac6770b7bc244ab` and
`a46db7c74ae4fb45631b65152ead118fea46eb7bd78c21d74c8be2eca881946c`.
With wire 7 permitted, the guest exited zero, printed an integer, and the
observer checked that it lay between the same host's Python wall-clock samples
with a five-second tolerance. An unpredictable in-band result marker was
required; SSH exit status alone was not treated as proof. The temporary
observer script SHA-256 was
`261eaeb4c1d34bcab37e4068083ab911f0d5eaca9d1d029cbff3d316706599b0`.

The owned compiler and guest processes had 3/4-second soft/hard CPU limits,
8 GiB virtual-address limits and 50 ms RSS sampling with termination above
200 MiB. Observed sampled peaks were 16,512 KiB and 360 KiB respectively;
sampling is not a hard memory cap. The task-owned remote diagnostic directory
was retained. No systemd unit, model, bot, persistent service or existing
workload was started or stopped.

This is one successful point sample on a source-derived Linux diagnostic,
not the pinned public compiler, a Darwin clock test, an independent clock
authority, a monotonicity/rollback test, an end-to-end deadline test, lease
revalidation or governor qualification. The separate Darwin SIGTRAP
observation remains unchanged.

### Expired submission call counter

A follow-up native fixture records calls and passed timeout values in a
test-owned vector. The valid case recorded one call with three seconds; the
expired case recorded zero submit calls. A separate mutant bypassing the
deadline only after expiry failed this assertion, while the original full
budget bypass also failed. This supersedes the preceding limitation that the
expired-case submit call was not independently observed. The clock, unit,
filesystem and submit operation remain stubs; the caveats above still apply.

Updated script SHA-256:
`b5070f271d4c4a630602686d8732f504e29d86a64f8bc5c1007d35f343fe5b09`.
The signed count fixture and expired-only bypass mutant image SHA-256 values
were `a651f0ea2458e333377f2e3fe2253644d3790170d977d88863289ee87fba8d81` and
`44c7e7a6e4adafc30586a6a2db3566cb8d8936f6f171e699fe2a028067beb62d`.
