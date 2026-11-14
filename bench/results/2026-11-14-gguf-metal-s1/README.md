# E1 S1: GGUF reader on the Metal route (2026-11-14, M1 Max)

First slice of [engine-plan](../../../docs/engine-plan.md) E1. A Kotoba program built by
`bin/amu compile --runtime-profile amu-program-metal` (amu ADR 0405, amu `f759f4f74`) checks the file's SHA-256 first,
parses the GGUF header into a KV and tensor catalog, reads the data region into a Metal buffer and folds it.

Gate (`sh bench/kernels/gguf-metal/run.sh <amu checkout> python3`): all rows pass. See [evidence.json](evidence.json);
[expect.txt](expect.txt) is the oracle output the program matched line for line.

The 2026-11-13 attempt trapped at wire 42 because it was built with a compiler other than amu `bin/amu` on macOS
(the fs2 profile, which does not grant `:gpu/compute`). amu `main`'s macOS CLI already accepts `amu-program-metal`.

Not covered: the real 531 MB Qwen2.5-0.5B file (refused over 16 MiB until chunked reads), any forward pass.
