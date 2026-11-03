# One-connection fleet snapshot

The current observer makes three separate SSH connections per node. A failure
in any connection discards the entire observation. A task-only diagnostic
fixture instead sent one fixed compound shell command, joining the memory/swap,
pressure and disk probes with `&&` and explicit section markers. It validated
every section and returned no partial metrics on a command or parser failure.
This reduced handshakes; it did not make the measurements atomic or
authenticate the in-band markers. The fixture is not an operational path:
workspace policy requires native Kotoba/Amu tooling, and the compound shell
command was not added to the product observer.

On 2026-11-03 a native diagnostic host used this fixture against the ten
Tailscale minis, with pinned host keys and at most three parallel observers.
All ten returned complete observations in this pass, including Asher, Levi and
Simeon, which had returned SSH 255 in the preceding three-connection pass. The
two passes were sequential, not a controlled reliability experiment: changing
network conditions could also explain the difference.

Every node reported 16 GiB physical memory. Joseph reported 23% memory-pressure
free and 3,262.81 MiB swap used; Zebulun reported 88% and 1,080.44 MiB, and
Dan 88% and 133.25 MiB. The other seven reported 87–91% free and zero swap.
These figures do not establish reservable memory or permission to run a model.
No service or model was started, stopped or changed.

The Mac native unit fixture passed valid and incomplete snapshot cases. A
separate native integration fixture with a deterministic process adapter
accepted a full response and refused invalid pressure, invalid disk, a
missing section and a nonzero remote exit. The live adapter is diagnostic;
process lifecycle, resource governor, authentication of telemetry, reservation
and fleet scheduling still need qualification.

| Artifact | SHA-256 |
| --- | --- |
| task-only modified observer source | `f66c0bc54a9e27c58834bc117513e9b8050ac5a91f30eb51f7146e838fe8c682` |
| task-only modified unit source | `2f96d84f66eb12851366ba6f22461f1062d5a687f0bc454182ff68ba30fb93ff` |
| signed live observer fixture | `78f2c72828813ae1417ae8adffc388202b613e68bdb6ecca48f4e620d1a995fa` |
| signed native integration fixture | `16f11814d091cb6c6cd8039d9d4290875d167f2dfd8d8f449fdf4219e066ea42` |
| signed native unit fixture | `9253c13d37bbc624302ad571fe5e594cce4e5e89c3b8ac7d8fc35bc271dff1af` |

## Native remote component candidate

The Kotoba observer now also has a separate remote-host entry: it invokes
only fixed `sysctl`, `memory_pressure` and `df` executables through wire 20,
validates their results, and prints a single framed snapshot. A separate
receiver entry requests a fixed native binary path via one SSH connection and
validates every section again. The existing three-connection entry remains
available until this path can be deployed and qualified; there is no automatic
fallback. The host must grant only the intended executables and bound process
time, output and authority. Wire 20 on Darwin is not yet qualified.

A Mac native host fixture used a task-only diagnostic process adapter and
successfully ran the actual three local OS commands, yielding all section
markers. It refused an extra argument. A native receiver fixture using a test
process adapter accepted a valid response and rejected malformed pressure,
malformed disk, a missing section and nonzero remote exit. Unit tests covered
the fixed remote executable path and parser. This does not demonstrate that the
new native binary is installed on any mini, that SSH used it, or that a
production host and its process lifecycle are safe. No remote service was
deployed or modified.

| Native candidate artifact | SHA-256 |
| --- | --- |
| observer source | `f5c31a87c80cb339b0a194c4b8e7e9eab355f5665a200bb708b8e608e826a0b3` |
| test source | `5c6cd889fd95a08e431940a3937aea223306a9da77bc3201b4c63ec09abf8ccc` |
| signed native host fixture | `e06d2b27784928b546815f8e51327151a8ff742b1032da8b49c9d7061641c780` |
| signed native receiver fixture | `cc66eaf6f9d847a94a555b36f45c10ae1d273d4264804b1f0da511af9034e1be` |
| signed native unit fixture | `52d05d78358bcbc3fc79846ce46d0a870df45c19cd865dd685a9a8b7c07913b8` |

## Benjamin: native sender and receiver through SSH

On 2026-11-03, a task-only host executable compiled from the current native
component and a diagnostic process adapter was copied to a newly created
private temporary directory on Benjamin. The copied executable's SHA-256
matched the local signed artifact. A native receiver with its remote path
changed only for this task used the existing selected SSH identity and pinned
host key to invoke it over Tailscale. The receiver returned
`observation/untrusted benjamin` with all four validated sections: physical
memory 16 GiB, zero swap used, pressure free 91%, and root filesystem
60,988,988 KiB available. These measurements are a single, non-atomic sample.

After the command returned, a process-list check found no process matching
the task executable. The three exact capture files and task executable were
removed from the task-only directory, and the directory's absence was checked.
No pre-existing service or model was changed. No installation or persistent
observer was created.

Both ends used diagnostic process adapters. The receiver's configured binary
path was substituted with the temporary path and does not prove the normal
installed path works. One successful host does not qualify other minis,
process-tree cleanup, durable deployment, trusted telemetry or resource
admission. The remote marker remains forgeable by the remote process.

| Cross-host diagnostic artifact | SHA-256 |
| --- | --- |
| signed temporary host executable | `15a5a21f96635b9eadef2d40e09ddc908b543639f659feda4cff8dcb71590c95` |
| signed temporary receiver | `107844d141d09e63bc25e2b7fe59a21662aa10b99ebfd1c274a559cdc6a282a6` |
| temporary receiver source | `3957e090ea413acf5f36f1a6d99070f2fe86c779bad24dd77d311a3227cc40e3` |
