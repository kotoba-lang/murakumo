# E1 S2: chunked GGUF reads on the Metal route (2026-11-14, M1 Max)

The S1 reader refused files over 16 MiB. S2 reads the header through a prefix of at most 64 MiB and preads the data
region into the Metal buffer in 8 MiB chunks, so the real 531 MB Qwen2.5-0.5B file is no longer refused by size.

Gate (`sh bench/kernels/gguf-metal/run.sh <amu checkout> python3`, amu `f759f4f74`, `amu-program-metal`): every S1 row
still passes, plus a new row: a 27,638,592-byte fixture (the S1 file plus one 20 MiB F32 tensor) is read in 4 chunks and
its catalog and checksum equal the Python oracle.

Negative control, run by hand on the same day: reading every chunk from the start of the data region
(`(+ dstart o)` → `dstart`) makes the big-file row fail with a mismatch; the source was restored before the passing run.

Not covered: the real Qwen2.5-0.5B Q8_0 file has not been run (it is not on this Mac; download pending), headers over
64 MiB, any forward pass.
