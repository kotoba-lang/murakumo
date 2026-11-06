# Digest before read: the render program refuses an unpinned checkpoint (stage M slice 37, JUN-927)

Recorded 2026-11-06T22:33:57+0900, M1 Max, load average 9.68 10.17 10.66. Program: `bench/kernels/pipeline-metal/pipeline_metal.kotoba.in`; gates `run.sh` (both layouts) and `big_f16.sh`.

The SHA-256 code of slice 36 is now helpers inside the render program. Before anything else touches the checkpoint, `main` hashes the whole file, reads the pinned digest (the first 64 characters of a digest file whose path is the new `@EXPECT@` parameter) and traps on any difference or unreadable file. The placeholder has no default, so a build without a pinned digest does not compile into a working program.

| check | result |
|---|---|
| `run.sh`, hf layout, 256 px, 4 Euler steps | digest matches, render agrees with ComfyUI as before (ids exact, ctx 1.0e-2, steps 2.9e-2, image 1.6e-2), seven existing mutants detected |
| `run.sh`, single-file layout | gate passed, three layout mutants detected |
| negative control: same program, digest file differing in the last character | refused, no render output (no dispatch line, no image) |
| `big_f16.sh`, 6.87 GB real-size random single file, 2 steps | hashed (about 80 s by the slice-36 rate) and rendered: finite latents and image, 4,322 dispatches, 6.87 GB resident, 15.33 GB peak footprint |

Follow-up, same day (slice 38): the hash-then-read gap is closed. `main` now opens the checkpoint once, hashes through that descriptor, and every later read (header, tensor data) uses the same descriptor, so replacing the path after the hash (rename over it) cannot change what is read. Test: a mutant renames a 4096-byte file over the checkpoint path right after the digest check; the path then holds the other file (checked by its size), and the render is unchanged from the clean run in both layouts (hf: ids exact, image 1.6e-2; single-file: ids exact, image 1.7e-2). A write into the same file after the hash, with the descriptor open, is not detected: that needs write access to the file itself.

What this is and is not: it is the mechanism. The digests in these gates are computed from the fixture by `shasum`, so they prove the program hashes the file it then reads and refuses a different digest. They are not a pin from a source; for a real checkpoint the expected digest has to come from the model's publisher (D1), and until then that artifact stays "unverified" in the AGENTS.md sense.
