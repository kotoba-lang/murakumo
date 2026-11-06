# SHA-256 of a file in Kotoba on the Metal route (JUN-927)

Recorded 2026-11-06T17:18:39+0900 on 21mbp.local, Apple M1 Max, load average 11.53 11.92 12.34 (busy host: throughput is diagnostic).

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- oracle: `shasum -a 256` (6.02)
- command: `sh bench/kernels/sha256-metal/run.sh <amu checkout>`

```
empty: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 ok
abc: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad ok
len55: d5e285683cd4efc02d021a5c62014694958901005d6f71e89e0989fac77e4072 ok
len56: 04c26261370ee7541549d16dee320c723e3fd14671e66a099afe0a377c16888e ok
len63: 75220b47218278e656f2013bb8f0c455a25eaf01e86c64924e9d48d89776d6f2 ok
len64: 7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c ok
len65: 9537c5fdf120482f7d58d25e9ed583f52c02b4e304ea814db1633ad565aed7e9 ok
len119: 000b48d4edf0fa7bee3c6236ecd2785baa5db4eeb8bb54341b029e0d9fa5fb0c ok
len120: 13f05a0b594787f5ecd315edc96141bd3243203d1b7d4f0836f37308b276ba98 ok
len127: 70156a14adbabf98cff3a71c7084b417abf057a8efd27329ca36b7202c87d81f ok
len128: 24da1b81d0b16df6428eee73c69fcb2a93c76bc6df706f0c6670fe6bfe800464 ok
million-a: cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0 ok
random-5MiB: b224d967351cd305d9425ba8c2eb3acbb9596b98b52af2bf46261176c4410232 ok
mutant (round constant 0x428a2f98 changed): digest differs (detected)
throughput: 5 MiB in 0.07 s = 71.48 MiB/s (diagnostic)
gate passed
```

## What this shows
- SHA-256 (FIPS 180-4) written in Kotoba, built by `bin/amu`, reads a file with libSystem's `open` and `pread` in 1 MiB chunks and prints the digest. It equals `shasum -a 256` on 15 inputs: empty, "abc", nine lengths around the padding boundaries (55 to 128 bytes, where the length field spills into a second block), a million `a`, and 5 MiB of random bytes that crosses the read chunk. Changing one round constant changes the digest.
- This closes the digest half of "pin by revision and sha256, verify before execute" (principle 4 of the media roadmap) for a checkpoint on the Metal route: with the safetensors reader of [2026-11-06-safetensors-metal](../2026-11-06-safetensors-metal/README.md) a file can be hashed and admitted by a program built by Amu selfhost, without the guest-side `hash/sha256` capability.
- Throughput is about 74 MiB/s on one core in plain i64 arithmetic (a 5 MiB file in 0.07 s), so a 6.9 GB checkpoint would take about 1.6 minutes: arithmetic from that rate, not a measurement of a large file.

## What it does not show
- Not run on a multi-gigabyte file. The program reads at most `chunk` bytes per `pread` and checks the byte count, so a short read traps, but a file over 4 GiB, or a filesystem that returns short reads, is untested. The digest is printed, not compared with a pinned value, and not yet joined to the safetensors reader (the two programs are separate).
- One core, no SIMD, no use of the GPU; the hash is inherently serial per file, so the figure scales only with the CPU's integer speed. Host load average was 17 to 20 for the first run recorded here.
- The Metal profile is a platform route with arbitrary loads, stores and C calls (ADR 0405), not the browser-consent operation of the workspace's client-runtime contract. `shasum` is a test-time oracle.
