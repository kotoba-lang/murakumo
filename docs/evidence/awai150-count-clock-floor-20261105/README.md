# Carry the Flint count clock floor into resident dispatch

The count wait previously checked rollback within its own polling loop but did
not return its final clock sample. The next sample in `pr-step-range`, and the
resident's sample before re-learning the lease, could therefore be earlier
than the completed count's last sample while still being later than the
original start. The candidate now passes a task-local one-element clock vector
through count and resident phases. A successful count writes its final sample;
`pr-step-range` refuses an earlier or expired next sample while retaining
occupancy, and `rt-dispatch` checks the sample before lease refresh against the
shared floor. After successful lease refresh it advances the floor before the
generation phase. This is a volatile, host-owned observation, not a durable
clock or receiver-side fence.

A local Darwin ARM64 native source-unity test used the selected current
`pr-step-range` and busy-marker forms with a synthetic count that wrote its
last sample. A subsequent earlier sample returned unknown, retained the marker,
left the old state unchanged and made no generation submission; an on-time
control reached count-ready and advanced the floor. A mutant comparing only
against the original start failed the test. The fixture SHA-256 was
`2375c82bb2c8ccfd64b37094406fec664ed199e5bd578d6b4203107f7cb3859b`;
the signed image was
`9a30f6c7a3bb54874d7bcefcf62f3061736db10c08d7a3e426f360b26bc18e56`.

A separate native diagnostic ran the selected `rt-dispatch` with stubbed
lease, clock and step functions. A sample earlier than the last count sample
was refused before the generation step; an on-time control proceeded. A
mutant comparing to the original start failed. Fixture SHA-256 was
`b3df7cf5cd84ad5eb52f2d507678d9a89354bc29d50086b13ad54e4c98701ffc`.
A composed source containing the actual changed `pr-count`, `pr-count-wait`,
`pr-step-range`, `pr-step-resident` and `rt-dispatch` compiled with the pinned
Darwin compiler, but was not run as a full resident. Other dependencies in
these fixtures are historical or stubbed; the scripts use local absolute paths
and a historical temporary fixture. They are not deployment commands.

The count submission now checks its pre-submit sample against the last known
floor, saves the sample before submission, and starts count polling from the
later of that floor and the original start. `submission.py` executes the
actual `pr-count` with stubbed I/O: the normal sample submits, while a
rollback above the original start does not submit and leaves the floor intact.
A mutant that compares against the original start fails. The generated source
SHA-256 was `e091c8b912cf4696550bd3a02794edbc243288334f33b891afc5fb4c30719066`.
The post-query diagnostic additionally checks a rollback between submission
and polling and rejects a mutant that ignores the shared floor.

Generation submission now saves its pre-submit observation and generation
polling begins at the shared floor, saving the successful response observation.
`generation.py` executes the actual `pr-step-range` with stubbed count, clock,
and submission: a rollback during preparation preserves busy and state and
does not submit; the normal control reaches submission. A mutant comparing
with the original start is detected. Its generated source SHA-256 was
`e0cc6fa3110eeaaf73ece0366c803d51cb54c0d4be831beb170efbb17e4a8a94`.
The post-query diagnostic also tests the shared floor on generation polling.
These are diagnostic fixtures, not a live service or model test.

Before publication, the second response read is now independently validated,
and its completion sample is checked against the shared floor. The
`publication.py` selected-source native diagnostic used stubbed I/O and
response validation: the normal case advanced state; a late clock rollback
and an invalid second response retained it without publishing. Removing each
guard caused its respective negative control to fail. Source SHA-256 was
`d9f0c5bc8ea9ffce6296ca39c65d6c06bb8a4dcebd4abe0d5daba016d8a0d2a0`.
This does not test the real response parser or filesystem race. Publication
operations after this last sample may still outlive the deadline; the other
limitations below still apply.

This does not qualify the actual clock provider or monotonicity, protect
against clock changes between observations, retain the floor across crashes,
bound an in-flight query, fence the remote receiver, verify a real lease or
model, or qualify the resource governor. Additional samples later in the
submission/publication path are not all carried in this vector.
