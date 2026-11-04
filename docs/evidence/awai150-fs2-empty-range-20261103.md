# Darwin diagnostic zero-length FS2 range

On one local arm64 Mac running macOS 26.3.1, the pre-existing diagnostic
`/private/tmp/awai-oci-range/general.macho` (SHA-256
`4bf0268cb855cd11ade05ef7ccf2b6ebcdac0b12d934568dcb52fbac970939de`)
was run with a private temporary directory on FD 3. Its associated source
`/private/tmp/awai-oci-range/general.kotoba` has SHA-256
`5be0d2cb608d8a744b240be11d6114b7049aa4272e84a06cf656437f8c5a5edf`.
These external diagnostic artifacts are not part of the repository or a
qualified release. No image was built for this observation.

The command below creates only task-owned temporary files and checks the
diagnostic's responses:

```
python3 test/native/fs2_empty_range_probe.py --fixture /private/tmp/awai-oci-range/general.macho
```

An `E` request with `0:0` for both an empty file and a one-byte file returned
the five-byte FS2 prefix followed by the diagnostic's output newline, exit 0.
An absent file with `0:0` and an empty file with `0:1` both exited -5 without
output on this run: failure is a trap rather than a normal error response.

On 2026-11-04 the same pinned diagnostic was rerun with a task-owned
4097-byte ASCII file. Ranges `0:4096`, `4096:1` and `100:3997` returned the
expected bytes. The request `100:4096` extended past EOF and exited with
SIGTRAP rather than a recoverable short response. The updated probe asserts
both successful byte comparisons and the failure. This emphasizes that a
metadata/range race may terminate this diagnostic instead of returning false.

The Amu source at commit `ebbcaee09bbaf93ff82c36b1271a0ee354f56015`
contains a corresponding Darwin F/E helper and its assembly derivation:
[`fs2-ef.derivation.s`](https://github.com/kotoba-lang/amu/blob/ebbcaee09bbaf93ff82c36b1271a0ee354f56015/docs/evidence/mac-fs2-ef-20261026/fs2-ef.derivation.s).
For E, its `fstat` size check sends `offset + count > size` to `Lbad`;
after that check, an EOF during the exact-read loop also goes to `Lbad`.
`Lbad` is `brk #0x36`. This explains how both an already-invalid range and
a truncation between `fstat` and `read` can trap in that source. The Amu
derivation checker passed for all 293 F/E words and both relocation slots
on 2026-11-04. This source comparison does not establish that the separately
pinned diagnostic binary was built from this exact commit.

An empty successful frame cannot safely be substituted for this failure:
`0:0` already uses that frame as a successful response. A recoverable error
therefore needs an unambiguous provider contract and audited callers, or a
supervisor that treats termination as an unknown operation outcome. Until
that path is qualified, a trap cannot be reported as a clean checkpoint
refusal or used as evidence that no prior side effect occurred.

The diagnostic requests a **string** response and prints it. The checkpoint
candidate requests a **bytes** response, and the modified checkpoint code
was not executed. This observation therefore does not qualify that ABI, its
error handling, the full host/provider, other Macs, GitHub storage or bot
recovery. Host exclusivity, fault injection, sync/crash testing, and resource
governor qualification are still required.
