# Standalone cron bridge lock

The integrated cron controller already holds the resident writer lock while
calling the bridge functions. The separately exposed bridge entry point did
not, allowing a cooperating standalone request writer to overlap a resident
cycle. It now validates the host config and takes that same lock before
running the command, refuses contention, and reports unlock failure. The
controller still calls lower-level functions under its own lock; it does not
recursively acquire it. Noncooperating writers, aliases and inode replacement
remain the host's responsibility. This local lock is not a distributed lease.

On one local ARM64 Mac, a source-unity fixture extracted the new bridge entry
point and stubbed config, validation and command execution. A separate test
process held the actual task-owned lock inode with flock. Before and after the
hold, the native fixture ran the command and returned its test status. During
the hold it returned `bridge/local-writer-busy` without running the command.
A test-only mutant skipping lock acquisition executed the command during the
hold and then reported unlock failure. No real checkpoint, resident, cron
controller, inference or remote host was used; crash handling and full source
composition were not requalified by this test. The compiler and diagnostic
packager match the [FIFO portability observation](../awai151-fifo-portable-20261103/).

Bridge source SHA-256 `39f61a27549b1a0dcbfa3551efd3f00f7d4a841d685bd34fb3e0e7a5dd1b5060`;
fixture `455f8998ce666110e9ea333ec96ffc534fe2a8f872eb4ccfb51cd17c21312c37`;
signed fixture `75bf4cb329353464ede6e5ad5f49ed8b53acd9d2e9a88ead0a8474b9af5c1abd`;
signed mutant `f057e04485901b1fcbbbdfa7b8f95d66b63b76dfe2b0c4cf5d8fb9e97b0cb8a4`.
