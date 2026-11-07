# A safetensors reader on the Metal route (I3a, first slice, JUN-927)

Recorded 2026-11-06T17:16:42+0900 on 21mbp.local, Apple M1 Max.

- amu: `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, `bin/amu` sha256 `3839d2616ba737f59835cb17bb58d79543c99098da73ffe17a67a7039427dd7c`, profile `amu-program-metal` (ADR 0405)
- oracle: Python 3.11.13, safetensors 0.4.4, numpy 1.26.4 (a local oracle that writes the fixture and the expected output; not a route)
- command: `sh bench/kernels/safetensors-metal/run.sh <amu checkout>`

```
catalog and checksum equal the Python expectation (33 lines)
mutant (data offset shifted by one): the short read traps, no checksum (detected)
truncated file refused (exit 133): refused: data_offsets do not match the data region size
file with a wrong shape refused (exit 133): refused: a tensor's size differs from its shape and dtype
gate passed
```

## What this shows
- A Kotoba program built by `bin/amu` on the Metal route reads a safetensors file with libSystem's `open`, `pread` and `lseek` (the profile gives the program the whole process's authority, ADR 0405), parses the JSON header with a small byte scanner into a tensor catalog (name, dtype, shape, data_offsets; the `__metadata__` entry skipped), reads the whole data region into a **Metal buffer** (`newBufferWithLength` plus a `pread` into its contents) and folds it. On a synthetic 28-tensor file (SDXL-style names, F16, F32, F64, I64 and I8 tensors, a scalar; 51,368 data bytes) the output equals what Python's `json` and a Python fold of the same bytes give, line for line: 31 catalog entries in file order, the count, the data-region size and the checksum.
- It admits what it reads: before the data is read it checks, per tensor, that the data_offsets span equals the product of the shape times the dtype size and that the dtype is one it knows (F16, BF16, F32, F64, I8, U8, I16, U16, I32, U32, I64, U64, BOOL), and that the largest data_offsets end equals the size of the data region. A copy truncated by 5 bytes and a copy whose first tensor's shape disagrees with its offsets are refused (a message on stderr, a trap, no checksum). A mutant that shifts the data offset by one byte traps on the short read.
- This is the first step of I3a (checkpoint admission) and shows that, on this route, a guest **can** read files: the decision D3 of the I3 ADR (more than one input into a guest) does not need an extension of the 'S4' capability for the Metal route, because libc is reachable. The S4 route for end-user guests (a granted, read-only map) is a separate matter and is untouched.

## What it does not show
- No SDXL checkpoint was read: a synthetic fixture only (the real file needs the owner's decision D1 and a download approval). The catalog is not yet used to place tensors into the model graph, and the SDXL-architecture detection from tensor names is not written.
- No sha256 of the file. The language has a `hash/sha256` capability (`KW-HASH-SHA256` in the checker), not wired on this route here; verifying the pinned digest of a multi-gigabyte file before use is still open.
- The whole data region is read in one `pread` into one buffer: fine for the fixture, untested for multi-gigabyte files (a `pread` may return short and a single buffer may exceed the device's maximum), and nothing maps the file instead of copying it. Names with backslash escapes are not handled; overlap between tensors is not checked; a data region whose size is not a multiple of 8 bytes is not supported.
- The Metal profile is a platform route with arbitrary loads, stores and C calls, "not an end-user app route" (ADR 0405); a file read here is not the browser-consent operation of the workspace's client-runtime contract.
