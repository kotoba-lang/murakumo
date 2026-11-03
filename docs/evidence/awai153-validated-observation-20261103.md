# Validated shape for native fleet observations

The read-only observer formerly validated the framing of all four reports,
but accepted any printable text as pressure and disk output and only loosely
checked swap labels. It now requires a single canonical 0–100 pressure
percentage, one `df -Pk /` root row with numeric fields, and a structured
swap summary. A malformed section refuses the complete observation. These
checks validate syntax, not authenticity, freshness, free unified memory or
permission to start a workload.

On 2026-11-03 a bounded diagnostic Mac host ran the compiled observer over
Tailscale with pinned SSH host keys, at most three probes concurrently. An
initial version that validated pressure and disk completed on all ten nodes.
After adding swap validation, a second run completed on Benjamin, Dan,
Issachar, Joseph, Judah, Naphtali and Zebulun. Asher, Levi and Simeon each
returned SSH transport exit 255 during the first section; their observation is
unknown, not zero or offline. These are separate, non-atomic passes.

The seven complete reports all showed 16 GiB physical memory. Joseph reported
23% memory-pressure free and 3,262.81 MiB swap used; Zebulun reported 88% and
1,080.44 MiB. The other five reported 88–91% free, with Dan reporting 133.25
MiB swap and the others zero. Pressure free is not reservable RAM, and the
snapshot does not identify the cause of swap or authorize stopping anything.
No model, bot or existing service was changed.

The native unit fixture covered valid and malformed pressure, disk and swap.
A second native fixture replaced only the process host with deterministic
responses and checked the complete `observe-node` path: valid data passed,
while malformed pressure or disk returned no observation. A control that
removed both checks incorrectly accepted both malformed responses. The real
process host, remote marker authenticity, resource governor, process lifetime,
reservations and ten-node admission are not qualified by these tests.

| Artifact | SHA-256 |
| --- | --- |
| observer source | `f93d04871f1486f3a317caf5cebba92772530fdc81eb15c5ab68767a2a2a7bc3` |
| unit source | `c36e869963576116023a0fe18dd980225268c22a86ecdf34c5c412327c92cd56` |
| signed live observer fixture | `bb8347aebd7b4bc11b7231a836450c7fef64c32b57e0c660e2ee310bd58ce7af` |
| signed native unit fixture | `037f752241a4e782a8e2368ca7aae09b79edeb1c3af83b0b0edad1b3f6806cda` |
| signed integration fixture | `fb5b8991e2c50f086ad42e82130d7bfdf6b07b9b018fb2a29cf65528e7d61ba3` |
| signed removed-checks control | `f4395268f3509ceda108419442dbadd307dae868f234a6479f458144dad89e4d` |
