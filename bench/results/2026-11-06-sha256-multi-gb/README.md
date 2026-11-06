# SHA-256 of a 4.5 GiB file in Kotoba on the Metal route (stage M slice 36, JUN-927)

Recorded 2026-11-06T22:07:41+0900, M1 Max, load average 8.28 8.74 12.70. Gate: `bench/kernels/sha256-metal/big.sh` (new); the program is unchanged.

The SHA-256 program had only been run on a 5 MiB file. A checkpoint is multi-gigabyte and its digest must be checked before it is read, so I ran it on a 4,831,838,211-byte file (a random 64 MiB block repeated 72 times plus a 3-byte tail). That is past 2^32 bytes, so the 64-bit bit-length field, the file offset and the 1 MiB `pread` loop are exercised beyond 32 bits.

| | digest |
|---|---|
| Kotoba program | c3a7a671402a569dc1fa3f41933d5881781523b74df40ac6a0371d6d17ff1e05 |
| `shasum -a 256` | c3a7a671402a569dc1fa3f41933d5881781523b74df40ac6a0371d6d17ff1e05 |

Equal. 52.7 s wall, about 87 MiB/s on one core, so a 6.9 GB SDXL single file would take about 80 s. The script deletes the file. Built with `amu compile --runtime-profile amu-program-metal` from the `jun-927-amu8` checkout (the `kotoba-lang/amu` checkout on this Mac predates that flag).

Not done: the digest is not yet joined to the render program, which still reads whatever path it is given. That join needs the checkpoint's expected digest, which comes with D1.
