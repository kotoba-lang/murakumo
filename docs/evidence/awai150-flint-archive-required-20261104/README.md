# Require the archive gate for Flint residents

The existing resident accepted an omitted `archiveAck` and used its environment
file as the runtime. That permits a Flint resident to proceed without requiring
GitHub archive acknowledgement. The pins decision now rejects the Flint runtime
kind in that legacy no-archive mode, even when other runtime fields are missing.
Existing non-Flint no-archive behavior is unchanged. A Flint deployment must
configure the archive gate and archive the bootstrap checkpoint before its
first cycle. The gate is an acknowledgement of an independently verified
archive; it is not a distributed lease or an atomic write fence.

On a local diagnostic Mac, selected source and historical dependencies passed
Amu `check`. A signed native fixture used actual local FS2 reads, JSON and CID
helpers: matching file pins plus a Flint kind with no archive were refused,
while matching file pins plus a legacy kind were accepted. Removing the new
guard made the same fixture fail. The fixture also executed the existing
`test-flint-public-environment` cases against native JSON helpers. Fixture
source SHA-256 was
`45a9f6a075a0c46749d1cd30e7efd517819c7e3c8fa63b862654d21cd8864ed0`; signed
fixture SHA-256 was
`24ce6ffdda0a0cc82e55660295316b2e42a4eb80f87ecc58a76bbff65cf7cf0b`.

The no-archive Flint input was intentionally minimal, and the positive public
environment cases did not execute a complete resident or archive cycle. No
actual GitHub transaction, filesystem failure injection, qualified native
provider, server, resource governor or selfhost build was tested. Host-owned
path isolation and validated archive credentials remain required.

## Positive archive-gate decision

A separate signed local Mac native fixture exercised the current `rt-pins-ok?`,
`rt-public-environment?` and `rt-archive-ack?` functions with real diagnostic
FS2 file reads and CID/JSON helpers. The fixture used matching synthetic Flint
runtime, public environment and policy file pins with a configured ack path.
A syntactically valid matching checkpoint acknowledgement was accepted;
wrong checkpoint, expired acknowledgement and missing file were refused. The
clock alone was replaced by a fixed test value. Fixture source SHA-256 was
`5a39bf3c05f21ec2eef8acb2f49c08ece82fb730dd056664ce4025835df96bbb`;
signed image SHA-256 was
`a6a83a6cf52509dbc2e2f4c711b1f12c88590157ab67b1377a542cda60841bb1`.

This evaluates a host-local acknowledgement decision, not a real GitHub
fetch, archive publication or verified remote commit. The pinned diagnostic
compiler/provider and historical dependencies are not a release closure, and
no real lease, full resident cycle, server or governor was exercised.
