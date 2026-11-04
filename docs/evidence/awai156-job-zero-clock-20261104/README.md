# Reject zero clock in job dispatch

The job-node entry and its lease predicate previously accepted a zero clock.
A syntactically valid short lease with an epoch-near-zero deadline could pass
that predicate when `now=0`, even though zero may represent an unavailable
clock. The job entry now rejects a nonpositive clock before beginning the
quorum round; the shared job lease predicate independently rejects it before
cell dispatch and live receipt verification. This does not show that a real
job was incorrectly dispatched.

On one local Apple Silicon Mac, a signed native source-unity fixture combined
the current actual job predicate, initial clock/ballot decision and test cases
with the current lease-value predicate and historical JSON dependencies. Valid,
expired, malformed and zero-clock cases passed. Independently built mutants
that allowed zero in either predicate failed. Fixture source/image SHA-256:
`25f7dc51df4959031af2c0155c6cc457ad9e5e07ace8e14dedb1e59a24816ac5e` /
`cac562192cc2d086ea318fc4833f2a661c2d7ac3068fd0f51e1b59a24816ac5e`.

A separate signed source-derived native fixture used the current actual
`jn-run-with-source` and lease predicate, with a test-only zero clock and
synthetic identity, admission, quorum, cell and signing functions. The actual
entry returned `job/time-or-ballot-refused` before the quorum stub was called.
A separately built initial-clock-guard mutant reached that stub, showing that
the test checks the entry's side-effect boundary. Fixture source/image SHA-256:
`60bc171f6cc7561e7cfa49311b499afc2bed1e324126c2068e3918e3d6d85d84` /
`a8c2bb7052e9236e83e28e67ea589057f39d2159877b70b4f2291a9d2e9b5c78`.

Job source SHA-256:
`5a3bf11211da246f0c4d0ca1243872494dac037c3ebb3f2f6f6f3630f001d880`;
test source SHA-256:
`5902e20f3ea4f5562f3b0be2f1cbd024c65c960c07ef399fa53c3655e87b308b`.
The pinned diagnostic compiler and packager were
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` /
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
These tests do not exercise a real clock failure, quorum, cell, inference,
GitHub, production host, authenticated time, resource governor or full
source-locked selfhost build. The core lease primitives permit zero for other
callers and were not changed here.
