# Local Murakumo Node host monitor

JUN-343 adds `kotoba/node_host_monitor.kotoba`, an **unregistered local host
observation component** for the Murakumo Node console. Itonami is the
agent-first workspace above the execution host. The historical namespace
and `itonami.node.local-observation.v1` schema remain stable; neither names
an agent identity. It is not the resident
agent described by ADR-2610222140, signed L2, peer enrollment, a Hata Cell
conductor, or an OCI launcher. Those gates remain JUN-287 / JUN-288 / AWAI-22.

The native image reads `/proc/meminfo` and `/proc/uptime`, writes a JSON
snapshot to the explicitly granted output directory, and waits 15 seconds.
The snapshot is Linux VM memory and uptime, not physical Mac capacity, host
availability for placement, or GPU qualification. Unknown peer and Cell counts
are `null`, never invented zeroes. No DID, key material or authority is created.

Its three arguments are a local host label, a local run label, and an output
directory. Labels are 1..64 lowercase ASCII letters, digits or hyphens and
are not identities. A private `127.0.0.1:18716` listener supplies the bounded
wait through the existing native TCP gate. No port is published and there is
no application protocol. No dialing or spawn grant is used.

The current candidate stops normally after 2,000 samples (about 8h20m). It
does not install a login item, service unit, scheduler or automatic restart.
The snapshot file is not atomically replaced; a reader must reject partial
JSON and retain its last valid snapshot. The browser console does this.

## Build and launch evidence

The 2026-10-23 local run used the Amu selfhost-built AArch64 Linux static root
from amu main `457042ab741520980bceb5bf12970937d1bc8dba`:

`1739f52f02fc90c28a1fa4a6c3a3f780caeff481893e3a1cd35badd8c227682e`

Build, inside the native Linux VM, with a **new** output path:

```sh
KEXE_CAP_RESOURCES_35=/source:/work /compiler/S3.elf compile \
  /source/node_host_monitor.kotoba --target aarch64-linux \
  --output /work/node-host-monitor.elf --format static
```

Run the resulting image with the exact explicit grants:

```sh
KEXE_ALLOW=35,37,38,43,44,47 \
KEXE_CAP_RESOURCES_35=/state:/proc \
KEXE_CAP_RESOURCES_43=127.0.0.1:18716 \
  /node-host-monitor local-mac-linux-vm local-run-1 /state
```

The tested local Podman container had network `none`, read-only rootfs,
all Linux capabilities dropped, no-new-privileges, 16 PIDs, 384 MiB memory,
and a 0.5 CPU limit. Its process table contained only the native monitor.
The cached Alpine image supplied the container envelope; the ELF has no
interpreter or dynamic-library dependency. No Node, JVM, KBB, C compiler or
C loader was invoked by compilation or the running monitor. Shell/Podman
launch and diagnostic orchestration are **not** a qualified native public
CLI, packaging controller or CDCI replacement.

`test-fields` checks field extraction, missing data, uptime, and safe label
handling. A native test entry returned 7. Changing uptime's expected value
from 123 to 124 made the negative control return 0. The product entry runs
the same checks before sampling. Empty search patterns are refused by the
native string primitive, so the first uptime field starts at zero without
issuing an empty search.

See `docs/evidence/node-host-monitor-20261023.json` for source/compiler/image
pins, successive real observations and the test receipts. The corresponding
UI candidate is `network-awai/network-awai-apex/apps/node-console/`.

The 2026-10-24 review rebuilt the unchanged source with Amu selfhost
compiler `8385ac69…`, reproduced monitor digest `007ff7a1…`, and reran the
positive field result (7), mutated expectation (0), and invalid-label refusal
(exit 2). `docs/evidence/node-host-monitor-review-20261024.json` records this
review separately from the historical launch receipt. The Mac monitor was
still advancing and remained explicitly unsigned/unregistered.

## Keep the roles separate

Do not serve `/state` from the app origin. The browser imports an observation
file into local state; the preview does not silently poll a node endpoint.
This unsigned file must never become an admission, scheduling or execution
grant. A real operator connection needs the canonical shared client-runtime
broker, signed observations, identity binding and lease enforcement first.
