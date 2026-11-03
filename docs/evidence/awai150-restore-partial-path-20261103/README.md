# Restore staging path collision

A fresh restore uses `dest.partial` for materialization. Previously, `work`
could equal that path: the early absence check passed, then creation of `work`
occurred before the later materializer saw the same path already present.
The operation could fetch an archive and then return an unknown materialization
result even though the collision was deterministic. The input gate now rejects
lexical overlap of `work` with both `dest` and `dest.partial` before contacting
GitHub or creating the work path. Host-enforced alias and race protection remain
necessary.

A small isolated fixture extracted the actual input condition and path
predicates, stubbing filesystem and unrelated checks. On this ARM64 Mac, the
compiled fixture rejected equal and descendant staging paths, accepted a
separate common-prefix path, and retained the earlier path checks; it exited 0.
A mutant removing the new condition exited 1. This is an input-gate test, not
an end-to-end GitHub or bot qualification. The one-off Python generator is a
test observer and is not operational tooling. It uses fixed local paths and
requires adaptation elsewhere.

Archive source SHA-256 `e1fb3bd28fb8a72386ef2c65c56d19562342b62dd16976e7406f1149c5cda6f2`;
fixture source `841320f4df830fd659cd73761bd23c5851ccc33ca46c2d0e107ab6914b0f667c`;
mutant source `947f6ac9dfad66fce7c8146e8a029758779e21c149ea34c4f53ccc56e0c60832`;
signed fixture `15fc90541be3a06cabf189ba7210c8ac9c2d793c374b6b2dabc8ae9c139fb94a`;
signed mutant `2c81e093d94dccdaa3aaa337af743b8db58f245be2f8007810850f2d2807a8f1`.
Compiler and diagnostic packager are those recorded in the earlier
[isolation observation](../awai150-archive-path-isolation-20261103/README.md).
