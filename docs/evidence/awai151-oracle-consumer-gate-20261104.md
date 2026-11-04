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

## Negative observed claim

The legacy parser previously accepted a negative numeric claim as a measured
allocation and left the unreadable count at zero. Since the consumer sums
claims, a negative value could reduce the recorded commitment. The parser now
counts negative claims as unreadable, so the consumer's existing observation
gate refuses them and returns zero grant.

On 2026-11-04 a CPU-limited local Babashka diagnostic evaluated the actual
selected parser and consumer forms with a synthetic corrected oracle and fixed
usable capacity. The prior parser accepted `-50` with zero unreadable claims;
the changed parser retained the positive claim, counted both negative and
non-numeric claims as unreadable, and the selected consumer returned
`invalid-observation` with zero grant. This is not the complete host test
suite, a ClojureScript execution, a real oracle or probe, or resource-governor
qualification. The underlying probe can still miss holders without reporting
an unreadable claim.

## Strict numeric and caller-supplied claim validation

The ClojureScript parser previously used `parseInt`, which accepts a numeric
prefix such as `42garbage`. It now requires an entire decimal integer within
the cross-platform exact-integer range. Admission also refuses missing,
malformed, negative or oversized caller-supplied claims and totals above that
range before folding commitments; callers can supply a probe directly without
using the parser. The same total bound prevents lossy JavaScript addition.

Local CPU-limited selected-form Babashka diagnostics used actual parser and
consumer forms with a synthetic oracle: ordinary input admitted, while bad
numeric text, out-of-range text, a missing claims vector, a negative direct
claim and a too-large combined claim total were refused. A separate Node
expression confirmed the prior `parseInt` prefix behavior. This is not a
ClojureScript execution of the application, the whole host suite, a real
observation provider, a corrected KIR or a qualified governor.

## Unknown registry classification

The legacy commitment fold accepted arbitrary registry values as map keys,
although admission only reads the resident and opportunistic totals. A
CPU-limited local Babashka evaluation of the prior selected source with an
80-byte claim and `:unknown` registry value raised an exception when adding
to the missing key. The fold now treats any unrecognized value as
opportunistic, preserving the holder in the total. Selected-form diagnostics
passed unknown values by PID and by name, a known resident and an absent
registry. These are synthetic tests, not the complete host suite, a
ClojureScript runtime, a real probe or governor qualification.
