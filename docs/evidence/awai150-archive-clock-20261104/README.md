# Archive acknowledgement clock refusal

The acknowledgement gate previously compared its expiry against two separate
wall-clock reads and did not require a usable clock. A failed read returning
zero could accept a canonical acknowledgement with an expiry in the first
900000 milliseconds after the epoch. Two different samples could also make
its comparisons inconsistent. The later lease admission independently
requires a positive clock; this finding does not establish a completed
unauthorized inference or archive.

The acknowledgement decision now samples the clock once, rejects nonpositive
or out-of-range values, and uses that same sample for the expiry window.
Retention refresh also returns `resident/clock-unknown` for those clock values
instead of reporting a retained head ready. The legacy no-ack path remains
unchanged; Flint separately requires an archive gate. The host must still
provide an authenticated, trustworthy and suitably monotonic clock. These
checks do not establish clock accuracy or eliminate rollback.

On one local Apple Silicon Mac, a signed native diagnostic source unity used
the current resident source and tests with historical assembled dependencies,
a fixed test clock and a diagnostic FS2 provider. The existing file-boundary
ack test and retention test passed, including zero, negative and overflow-range
clocks, expiry boundaries, missing or malformed acknowledgements, and normal
positive cases. A separately built mutant removing the positive-clock check
failed. A second native fixture set the clock to zero, created a canonical
acknowledgement with `until=500000` in its private FS2 scope, and verified the
actual file-backed `rt-archive-ack?` refused it. A separately built mutant
without the positive-clock check failed that same fixture. The private test
acknowledgement was removed between positive and mutant runs.

Resident source SHA-256:
`257d0846959177b2b226065f59edf5b61b7d8a3e2edf3612c91e32de767d7fd3`.
Test source SHA-256:
`5cd336d70d31319771f5d9d099215c037d27ee59ebe1f4c75a0a47a1a4577ce8`.
First unity/image SHA-256:
`e7b667102a6781ac8a2815cfeb8f6f85dda66dbb059a2825ae393dc3e7de5f14` /
`4bcf0a4f7b37494449b6f5b5ba4d0190d7a9612885b3643ce325279ecdab8c97`.
Clock-zero file unity/image SHA-256:
`3e4abd7ce3015f61a29b3d24934a1e48ad36cd375da3f520fc52fb513358b3f3` /
`006ea104c812b45ab7d8505ab428a80264ba92637674444c3b0ac610fe451bbc`.
Compiler SHA-256:
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
packager SHA-256:
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
This is a diagnostic fixture, not a full source-locked selfhost build, actual
clock failure, production FS2 qualification, GitHub archive or bot run.

## Head admission and replication follow-up

A related entry point, `rt-admitted-head?`, formerly accepted any intact head
with positive expiry if the clock returned zero. This head decision is used by
archive and publication as well as normal cycles. `rt-replicate` also formed a
replica expiry from the clock and began peer upload before checking that the
clock was usable. We did not establish that either condition caused an actual
GitHub publication or inference.

Head admission now requires one positive, representable clock observation.
Replication checks the same clock and the full two-day expiry margin before
calling the peer. The archive acknowledgement writer, reader, and retention
refresh use a common bound so a written acknowledgement cannot be rejected
solely because its deadline is outside the safe integer range. An unavailable
clock leaves replication refused; it does not authorize another workload.

A signed local native fixture used actual `rt-admitted-head?`, `rt-replicate`
and current retention boundary tests, replacing the clock with zero, head
integrity with a known-true stub, and peer upload with a file marker. The
head was not admitted and no upload marker was written. A separately built
mutant removing the positive-clock check failed and did write the marker.
With a positive test clock, the head passed the stubbed integrity check and
the upload stub was reached. This verifies branch ordering with test doubles,
not real head signatures, peer transport or external side effects. Existing
acknowledgement and retention native fixtures were also rerun with the common
clock guard and their guard-bypass mutants failed.

Current resident source SHA-256:
`4fc4f3a4df86f67400ac8f4cd5bbf77c61509b00f3079e2db58fdebdef32cdcb`.
Test source SHA-256:
`62a1b87fc88747386711b4ba0f3410a5f4daa5e5e170cf08543e1161d060a7d7`.
Clock-zero head/replication fixture source and signed image SHA-256:
`fab2f2997a26c960b38db75182be83d344537087e7eb0e7d44b7ea62ac867463` /
`833dab799b0e7e093724ed094062bdd6ffdda8411251d3aef9421e8ea550097c`.
Positive-clock fixture source and signed image SHA-256:
`65c2b204c935bc8529f42188033bbc6a26d603950ac8ade034b20a75ce5f57f8` /
`f181c2be3b539dda3a83a24e3d71bfcea54e8d762383c4cd7f1d176efe2769d6`.
Historical dependencies, diagnostic compiler/provider and test host remain
unqualified; no GitHub, inference, production replica or bot was run.
