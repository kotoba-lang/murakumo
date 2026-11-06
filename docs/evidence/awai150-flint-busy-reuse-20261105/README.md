# Flint busy reservation identity reuse

A selected-source Darwin ARM64 diagnostic exercised the current `flint-busy/2`
primitive on a private synthetic cache. It reserved `(instance, turn, count)`,
released it, reserved the same tuple again, rejected a mismatched count, then
accepted the same release tuple and removed the second busy marker. The last
call models a delayed release from the first reservation; the primitive cannot
distinguish it from a release of the second reservation. The signed image
returned 0 for that counterexample. This directly tests the primitive, not an
actual concurrent resident or an authenticated supervisor: a correct trusted
single-writer host must prevent such delayed commands. No inference was run.

Source-unity SHA-256:
`1b305ebeaf34ee60c323730c49f7b8d3583ad4539c03d2206a4876f1ce4ae570`.
Signed diagnostic image SHA-256:
`857a9f7ba8164f7e8cfee520f80a9fb96659d73215a8ca12c0acad26fe8ca9c1`.
The build copies the four current busy functions into a historical assembled
fixture and uses the separately pinned experimental compiler and FS2 provider.
The scripts rely on local absolute paths and temporary artifacts. The child
processes had a 3-second CPU cap and no separate whole-host memory cap. The
successful test's temporary tree was removed.

The host must bind release authority to a unique, non-reused reservation
instance and preserve that binding across crashes, rather than derive release
authority solely from the reusable tuple. A durable uniqueness protocol,
provider guarantees, migration from existing `flint-busy/2`, trusted process
identity, descendant termination and tests of the actual resident remain open.
This observation does not qualify the resource governor or authorize deployment.
