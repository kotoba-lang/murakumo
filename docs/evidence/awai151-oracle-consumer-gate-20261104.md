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

## Selected ClojureScript source diagnostic

On 2026-11-04, the existing local nbb checkout and Node v26.9.0 executed the
selected actual `parse-long*`, `parse-probe`, `committed`, and `admit` forms
as ClojureScript. The diagnostic supplied synthetic oracle and capacity
functions. It admitted one valid empty observation; rejected a missing claim
vector, a non-map claim, a negative claim, an overflowing aggregate and a
parser result containing malformed decimal text, an out-of-range integer and
a negative claim; and counted an unknown registry class as opportunistic.
The Node process had an inherited three-second CPU limit; no hard memory cap
was applied. The actual source SHA-256 was
`bff4671f89ec2f7bb18a7774ac4c470664b222a00e8e8f0d8e6751b403db54f7`;
the extracted temporary diagnostic source was
`c774a5cc6ef1705b53ccc9470175b99920dbb0d5dd4af45ad38e3b81bd5ac053`.
The local nbb `nbb_core.js` SHA-256 was
`3a8db4430000d8f0132e0c85ca73168efb01995039047a2e13ef10a6d123c381`.

This is an extracted source diagnostic with stubs, not the complete host
suite, source-locked build, real oracle or probe, or a qualified governor. It
adds no Node or nbb dependency to the deployment or CI path.

The selected-form ClojureScript diagnostic is now reproducible with
`scripts/native/check-accelerator-consumer-cljs.py --node <node> --nbb-cli <cli>`.
On the same Mac, script SHA-256
`fee8a6be4902067bed2e503a33c5473bb16e2f44bcf413f61a4ca090d1e85543`
passed against the source above and detected a source-injected removal of the
invalid-claims guard. Each Node child inherited a three-second CPU limit.
The test extracts source using checked unique anchors and refuses a source
change during execution. The complete host application and real oracle and
probe are still not covered; supplying Node and nbb is an explicit diagnostic
choice, not an operational requirement.

## Exact aperture sum at the host boundary

The legacy consumer now treats VRAM/GTT as unmeasured unless both are
nonnegative exact integers and their sum stays within 9007199254740991, the
shared exact-integer range. This prevents a ClojureScript host from passing a
rounded sum to the oracle. The selected actual `aperture-bytes` form passed
local ClojureScript diagnostics with a synthetic oracle for the exact boundary,
overflow, fractional, string and missing values. Removing the sum guard made
the same diagnostic fail. Source SHA-256 was
`87c2a76ab279c3babf7f6510d7b6aabf7d792fd5e3c92a8686d75c360f942c91`;
selected program SHA-256 was
`e21ac0d0f3cb7ce7e6cc7163f5937ea917b65b856ff16f75ce926ca1eefea7eb`.
This exercises selected source with a stub, not the real probe, real oracle,
full host suite, admission under actual load, or a qualified governor. It does
not remedy a probe that silently misses holders.

The consumer additionally preserves an unknown aperture itself, without
asking `usable-bytes` to interpret it, and refuses admission with zero grant
when usable capacity is unknown or outside the shared exact-integer range.
Selected ClojureScript source passed an integrated case with an intentionally
fail-open synthetic oracle that would otherwise convert unknown to capacity;
removing the propagation guard failed the same case. Source SHA-256 was
`f96c92196703813c4c5831039eff526842295e50f7e2e5ff613002a8bf3d486c`
and the selected diagnostic program SHA-256 was
`7964a4c3aa43dc1c41a70d3b202348ea9d75ececa04f962223feaa68a84cbcc7`.
The synthetic oracle does not establish the actual oracle or host behavior.

A follow-up selected-source ClojureScript diagnostic supplied invalid usable
values from the synthetic oracle: negative, fractional, above the exact range,
null and a string. Each was refused with zero grant. A mutation bypassing the
usable-value guard failed these cases. Program SHA-256 was
`a1b9000a4a575fce187b4a3e648d34cbff2a1cfc8dbd1bd31c1feaaf74c9c9bb`;
the real oracle and full host suite remain untested.

A subsequent consumer change checks that the oracle aperture exactly equals
the two host inputs, and that the usable result is an integer between zero
and that aperture. Wrong synthetic aperture and over-aperture usable values
were refused by the selected ClojureScript diagnostic; removing each check
caused its corresponding case to fail. Because the new usable-output check also
blocks the previously tested unknown-propagation fault, that older negative
control now removes both guards to exercise the shared failure path. Likewise,
the admission measurement control removes the upstream bound as well.
Source SHA-256 was
`1642d8e23643973d71a095630e7ab83e66b88cfd663e9a1935d66bc82e9cb313`
and selected program SHA-256 was
`873bb1f77cd12ad5dc696cb51a0f995c24c553999595c59745beed0ed9c71bd5`.
A usable value within the aperture can still be wrong for the configured
headroom; this is not full oracle qualification or a real probe test.

The consumer now also validates headroom as an integer in 0..1000 and checks
that the oracle usable value equals the configured split-arithmetic formula,
not merely that it fits under the aperture. Selected ClojureScript diagnostics
with a synthetic oracle accepted matching 10% headroom, refused a mismatched
result and invalid headroom, and checked a 1/1000 headroom calculation at the
maximum exact integer using an independently calculated expected result.
Mutation controls passed. Source SHA-256 was
`959d1686eadb835eacb5c8916c3e7cf004a2606b278df9d82e8a4532c7ebc36e`
and selected program SHA-256 was
`9f16fe4d42681f4561ef84e292d81d6ee818eac6844040326ed8ca9b1a254e38`.
This validates selected host-source behavior with a synthetic oracle, not the
real provider, full host suite, resource admission or governor qualification.

## Actual shipped oracle returns BigInt

A local, read-only ClojureScript diagnostic loaded the actual consumer and
shipped KIR `8990876d9868005ca6ca79bab40ff90673febd92b8f2cdd318957240e7507baa`
through the product-shell resource loader. The KIR returned JS BigInt for
integer exports; the previous consumer compared these values to host numbers
and incorrectly treated a valid 100-byte aperture as unknown. The consumer
now normalizes only exact signed safe-integer results, rejects strings and
out-of-range BigInt, and applies the existing checks after normalization.

With the corrected consumer, the real pinned KIR reported aperture 100 for
inputs 100 and 0. Its known stale admission sentinel returned 0 and grant 80
for the overcommit counterexample, so the consumer returned
`oracle-unqualified`, refused and granted zero. Selected-source diagnostics
also used BigInt synthetic results and covered out-of-range conversion. The
real-consumer script is `scripts/native/check-accelerator-shipped-oracle-cljs.py`.
The diagnostic used local, unpinned Kotoba dependency checkouts and Node/nbb,
with CPU limit 3 seconds; it did not run a real probe, corrected production KIR,
full host suite, native supervisor or governor. A first attempt from the repo
root tried to resolve project dependencies and failed because Java was absent;
the successful run used a private working directory and explicit classpaths.
