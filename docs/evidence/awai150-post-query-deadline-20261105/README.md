# Flint deadline after unit query and response read

`pr-count-wait` and Flint `pr-wait` now read the clock again after a unit
query and after consuming the bounded response. A late or backwards reading
returns unknown/failure and retains the existing occupancy for reconciliation.
The last observed time is carried into the next polling iteration. Non-Flint
`pr-wait` keeps its prior clock behavior. This cannot interrupt an in-flight
query or bound publication work after the wait returns.

A local Darwin ARM64 diagnostic compiled the exact selected source forms with
stubbed unit, response, clock and FS2-backed marker functions. In each of
count and generation paths, an on-time case succeeded; a clock that advanced
past the deadline during the query or during the response read was refused.
A mutant with both post-query and post-read deadline checks removed failed the
same two negative cases. Count fixture SHA-256 was
`6705ceb2bb2c198c14aa81fe8f45440c2b9d92079909beaba3c4781668e9bd98`;
generation fixture SHA-256 was
`29ee33476c68f52830ba28656c82768321c537aacc51d7fc6063a31e1faa7680`.
The diagnostic uses a pinned experimental compiler/FS2 packager, temporary
files and source stubs; it is not a live systemd query, inference server,
monotonic-clock qualification, full resident execution, or governor test.
The recorded diagnostic references local absolute paths and is not a product
runtime or deployment command.
