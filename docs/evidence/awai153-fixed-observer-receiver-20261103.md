# Fixed-function native observation over SSH

The separate Amu host candidate [#1806](https://github.com/kotoba-lang/amu/pull/1806)
emits a fixed 56-byte Darwin record as lowercase hexadecimal. Its instructions
query system metadata directly and do not spawn a remote process. This branch
adds a receiver for that record: it checks exact length, framing, magic and
version, every hexadecimal byte, numeric bounds, swap consistency, pressure
range and safe multiplication of block size by available blocks. Its output is
explicitly tagged untrusted; it is not an admission or capacity reservation.

On one Mac, the full native unit fixture accepted a known record and rejected
truncation, noncanonical text, out-of-range pressure, excessive integers,
zero block size, swap greater than total and disk multiplication overflow. A
control with the multiplication guard removed failed the same unit test.

For a cross-host diagnostic, the signed fixed host was copied to a newly
created private temporary directory on Benjamin; SHA-256 and mode 0700 were
checked. A native Kotoba receiver using the AMUH diagnostic host, with only
the remote executable path substituted for the test, ran it over pinned-key
Tailscale SSH. It returned a complete validated record: 16 GiB memory, zero
swap used, memorystatus level 91 and 62,447,210,496 disk bytes available.
After verifying the exact remote executable was not running, its digest and
directory contents, the task executable and directory were removed; a
marker-framed absence check succeeded. No persistent install or model was
started or stopped.

The remote fixed executable did not need a process grant, but the local SSH
receiver still used a task-only diagnostic AMUH host. A fixed in-band SSH
completion marker is forgeable, and values can change between syscalls. The
normal installed path, production source seal, host capability, authenticated
telemetry, all other minis, resource reservation and bot inference remain
unqualified. This is not a production fleet scheduler.

| Artifact | SHA-256 |
| --- | --- |
| observer/parser source | `9cec02c04ebe9690bd13639027f34550204b237d739d5d10461e3bca18bcc57e` |
| observer unit source | `d7013f1455fe0ad82f297f8e51ee068640216026170d35accf384392263cfae7` |
| macOS receiver source | `833219dbd4b09eec5fc53ae8c141a751aeaf4cb9a9517f84b1aa06dbbbbd8acf` |
| signed remote fixed host | `ec79cbe947271a633394fb02ce56567328b57d0402738a40306aeaa27039e5b3` |
| signed receiver fixture | `02a986ed5cda38b529c99708e5d49c98abde68d69cc8987d0a12db71b2c1d527` |
| signed native unit fixture | `39ae940f1fb9cf145479495fdb961896ee9fe7076aa8319074a9aeef662e58fd` |
| signed removed-overflow-check control | `7b01f7c48f7a3c6995b65a0b045f3b9def8c74ca22ab7ed0b0859dc0733e15c3` |
