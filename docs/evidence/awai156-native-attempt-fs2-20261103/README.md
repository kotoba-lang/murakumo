# FS2 native local attempt marker

The job attempt marker previously spawned `/usr/bin/stat` and `/usr/bin/mkdir`
through wire 20. This candidate uses scoped FS2 operations instead: `U` reads
the complete mode and type of the host-provisioned private root; `I` attempts
exclusive directory creation; `H` sets mode 0700; `U` verifies it, and `M`
syncs the marker and parent. Any unknown or failed result refuses. Once `I`
has created a marker, later failure leaves it in place and retries refuse.
The root and all ancestors must remain private and trusted throughout: these
path-based calls do not protect against a noncooperating same-privilege writer.
A local marker is not a distributed fence or workload-lifetime proof.

On an ARM64 Mac using a diagnostic FS2 host, the actual source-derived helper
created a 0700 marker, rejected duplicate, missing/invalid ID, nonprivate,
symlink and mode-01700 roots, and admitted exactly one of four simultaneous
claims for the same ID. Two test-only mutants forced chmod or sync failure
after creation: each returned `job/attempt-unknown`, left the marker, and a
second call refused it. These are injected responses, not actual fsync
failure or power-loss tests.

On Xavier, the same fixture compiled with staged diagnostic Linux compiler
`4a42e07083beb87337ad56dbf9759890e48d4502154e361d54cfee0b71e89da9` had the
same one-winner, duplicate, nonprivate, symlink, special-mode and bad-ID
results; the resulting marker was 0700. The task directory was removed.
Strict SSH host-key checking used a temporary file populated from the current
Tailscale peer record. The test invoked no existing service or real workload.
Compiler and fixture processes had five-second CPU limits and short wall
limits. The `linux-observe.py` script uses a local compiler path and must be
adapted to choose the staged compiler; it is diagnostic only.

**Runtime compatibility:** the currently pinned Linux bootstrap compiler
`914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4` generated
a runtime that traps with `fs2/refused` stage 8 on `U` before creating a marker.
Do not treat this candidate as usable with that artifact. The staged compiler
is not a release-qualified production closure. A pinned capable runtime,
real job-node integration, failures/recovery, independent replicas, resource
governor and MTP/GitHub operation still need qualification.

- Job source `c56f95d099dc0bb928b9ec7309eb880152327b88bc76ae5b5467176953112620`; fixture source `74eea3c0e5e70d1b2083142c470ffc0f96e2593a3fa850a250e3f8a3b6570b82`.
- Mac signed fixture `1afae2cd68638e5ff9d276bda2b10d1f7c89e1a89ad1a20799eaa800c50c0595`; chmod-failure mutant `ba4e34ee45cb3fa3650a9c1b9fb86547606ab53870412473e36049d1ec035968`; sync-failure mutant `0e718d84ced6c5115460363f1ada017361fc866f40321d2da78a86c08e586e84`.
- Xavier staged ELF `7b943e937d25571585453057b86b1df93faca1ff1594ce51c0df62d51bfb9895`; observer `6bc004537ac6b37251c9e81751b69f8000342ce1f937a9731f59af4d18248036`.
- Selected full source-unity check input `742f4049053c0afeec68cad978b39907a1cf6453a2a659d573c4bfc4e29eba20` passed Amu source check, not full runtime execution.
