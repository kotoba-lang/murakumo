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
