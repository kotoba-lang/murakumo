# Legacy accelerator consumer refusal gate

At main `ec1d138`, the shipped legacy accelerator KIR still has SHA-256
`8990876d9868005ca6ca79bab40ff90673febd92b8f2cdd318957240e7507baa`.
The previously built signed native KIR diagnostic
`4ffa6fa2f57e177aafa9d0ede49961893a6d23da3eb852de5530f1e3a00967dc`
again returned 0 (admit) for the 100 usable / 20 resident / 5 opportunistic /
76 request counterexample. The native corrected source diagnostic
`5544db5b19e73e0d3ca76524deaf09bc86881eacc1310d65e615673b10d05325`
returned 3 (refuse). These are previously built source-unity diagnostics,
not the host interpreter or proof of a deployed artifact.

The existing legacy host consumer now checks the known counterexample and grant
before trusting the oracle; it refuses and grants zero if either differs. It
also refuses and grants zero when the probe reports unreadable claims or omits
the unreadable count. With the currently shipped KIR this intentionally blocks
all admissions through this consumer until a corrected artifact passes the
check. The check detects a specific known divergence; passing it is not proof of
all oracle semantics, correct host observation, resource isolation or a
qualified native governor. In particular, the underlying probe may fail to
observe holders without emitting an unreadable claim.

Targeted host tests are included with mocked oracle responses but were not run:
the existing consumer and its harness use the retired JVM/Node paths, and this
change does not introduce either as a new operational or CI dependency. A
qualified native artifact and end-to-end consumer validation remain necessary
before enabling this lane. No running service or model was changed.

## Native source-to-KIR candidate audit

At Amu candidate `f7e899792fae1bc861199c7395436bc076d679f4`,
`seed/05-checked-source.kotoba` SHA-256
`af4adb63d28cf6b7103ee1829eb7db1e527ad43edeac4eea32ec261597eb864b`
contains a checked-source diagnostic KIR emitter. Its output constructor fixes
`:entry nil` and `:exports []`; its own source describes schema handling as
unsupported on this format. The source diagnostic harness
`scripts/seed/check-checked-source-native.py` SHA-256
`14d3a3b374ead067d03b9611c654b4b4c741f8c554cb26be0b7abd8a9d8b744b`
builds the producer on Linux and does not claim whole-compiler qualification.
The accelerator source has an `:accel/verdict` schema, and the existing consumer
artifact carries that schema and a nonempty export list. The older `amu kir`
route recorded under AWAI-32 was subsequently moved to retired legacy public
entry documentation; its old existence does not establish a current selfhost
replacement for this artifact.

This is a source and metadata audit only: the diagnostic producer was not run
on the accelerator source, and no conclusion about the exact runtime refusal
or compatibility of a patched output is established. Do not add fields by hand
or replace the stored oracle based on this audit. A source-locked native
producer with export/schema compatibility and actual consumer parity is still
needed, along with the qualified execution environment.
