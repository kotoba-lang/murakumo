# Lease group: unknown existence must not create an epoch

The group candidate reads FS2 D once for the epoch file: `1` permits an
existing epoch check, `0` permits an otherwise valid fresh initialization,
and every other response refuses before the watermark is changed. It also
requires explicit `0` for the legacy lock path before attempting the current
flock. This depends on a provider that actually distinguishes errors from
absence; older FS2 providers that return `0` for both do not meet the contract.

On 2026-11-03 an unprivileged user ran the attached Kotoba fixtures as ARM64
Linux static executables in an isolated 1 CPU / 2 GiB local VM. Both candidate
and old-code control used the same diagnostic test compiler with Amu #1796's
FS2 error distinction. The fixtures compose the actual group functions with
the existing `awai150-group-flock-fixture.kotoba` helper closure; their main
functions are fixture entry points and topology behavior is not exercised.

| Test | Candidate | Old-code control |
| --- | --- | --- |
| Existing epoch file mode 000, watermark 0 | refused; watermark remains 0 | refused, but watermark advanced to 1 |
| Epoch file truly absent, watermark 0 | created and ready; watermark 1 | created and ready; watermark 1 |
| Legacy lock mode 000, watermark 0 | refused; watermark remains 0 | acquired flock, voted and advanced watermark to 1 |
| Legacy lock truly absent, watermark 0 | acquired flock and voted | acquired flock and voted |

The files and locks were test-owned; permissions were restored. The test VM
was stopped after the run. The test is not a distributed lease proof, a
production runtime qualification, proof of race freedom, or a real bot turn.
Other callers, including legacy helpers that turn non-`1` into absence, still
need separate audit. Full Mac native host, transport, resource governor and
10-node workload qualification remain open.

The group candidate source SHA-256 is
`a59b17fff673dcb6280caf704d08fe7f0a3293f2d3315f7d1553164ced24516f`.
The staged ARM64 test compiler SHA-256 is
`4a42e07083beb87337ad56dbf9759890e48d4502154e361d54cfee0b71e89da9`;
its construction and limitations are documented in Amu #1796.

| Fixture source | Source SHA-256 | Executed ELF SHA-256 |
| --- | --- | --- |
| epoch-candidate.kotoba | `5d40353a4e74cb53ee2862a5fa2781e18d413ddbc022580651bdf00c7d288fd6` | `268d4484d73e185c785e8e00487ebc9b33408f7bec17d4d602f808a891dbd6f4` |
| epoch-control.kotoba | `f9737e6d8bdce8a75eb26adbc6963c9d77f4f35fb44ae2884b04c4722be561f0` | `4c3f4370afa283cbcabf329d0fe96726d9cb00e3edb0e2fff4131e3e4cbafb47` |
| lock-candidate.kotoba | `7a38ab8a4d11f28221f9c5549c72f059b9a0ce785307a964fe87f6b23639ac08` | `8eac57352f93b8ae86a4ae08474e59a302dc1b5b6b61b4e3047fd86e3d5bba9f` |
| lock-control.kotoba | `d5ca1c3739423bc67f2e086616908905378d66c58adecb243b935eef6d155dba` | `63bbb2ee642e2d9f8bfa280fe41b53a2d13d557a6251754bc668c7082c2bf086` |
