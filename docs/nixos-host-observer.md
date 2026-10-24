# NixOS host observation

JUN-379: `kotoba/node_nixos_monitor.kotoba` is a bounded native observation
component for the Murakumo NixOS profile. It does not register or admit a node,
create an identity, launch Cells, contact peers or grant authority to an app.

Arguments: local host label, local run label, output directory, scope
(`nixos-vm` or generic `nixos-host`). Both labels accept 1..64 lowercase ASCII
letters, digits or hyphens. The NixOS unit uses its systemd invocation ID as
the run label. Host scope is a configuration label, not physical-host proof.

Each sample reads `/proc/meminfo` and `/proc/uptime`, obtains wall time, writes
a temporary file, fsyncs it, renames it to `observation.json` and fsyncs the
directory. Per-sample arena release bounds retained memory; after 2,000
five-second samples the process exits and systemd restarts it. Failure to
commit the snapshot exits with 3. Invalid arguments or self-checks exit with 2.
The unit grants only clock, app-data, argument and diagnostic-error wires.

The first x86_64 build used Amu e8f77af31a8be62afc7f5154a72099ceb6424273's
native public compiler (`bin/amu`, SHA-256
`fd2fe6dbfcd54912d662f2f9c32c4e2489fb43aab4cc9404ae3d6b67ffa9d211`).
Use the public CLI's exact argument order:

```console
KEXE_ALLOW=35,37,38,39 KEXE_CAP_RESOURCES_35=/work ./amu compile \
  /work/monitor.kotoba --target x86_64-linux --output /work/monitor.elf --format static
```

The seed root's early scalar x86 route is a different entry and refuses this
string/capability workload. It is not a fallback. No Node/JVM/nbb/C compiler
or C loader is used. Nix packages the native artifact as platform tooling.

Observed: successive samples and durable rename on the Linux host, then actual
NixOS/KVM service startup, restricted-user execution, SIGKILL/restart and a
power-cycle followed by a new invocation/snapshot. A mutated field self-check
(`123` expected as `124`) refused startup. Invalid labels produced no file.
The unsigned observations remain outside the app origin and the UI imports
only the local file selected by the user. Unknown peer/Cell counts remain null.
Physical power-loss durability and full participating Node behavior remain open.
