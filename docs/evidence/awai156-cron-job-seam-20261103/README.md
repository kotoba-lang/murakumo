# Compose cron job input with strict job lease and attempt gate

The cron adapter in cloud-itonami/itonami-agent PR #163 expects the private
`jn-run-with-source` seam and an attempt root at argument 18. The resident
branch previously carried neither. This change integrates the private seam and
the host-owned local attempt marker from Murakumo PRs #556/#565 into the newer
job-node lease checks. Normal `run` passes its CID-checked input unchanged;
a separately composed, trusted adapter may pass its already verified source.
Both bind the job, lease, marker and receipt to the original input envelope.

After a valid initial lease, the job records an attempt and checks the lease
and clock again before invoking the cell. An existing or unknown marker stops
execution. The post-cell lease recheck added earlier remains. The attempt
root and ancestors must be private and host-owned; local markers are not a
distributed fence and cannot prove an old workload has stopped.

An ARM64 Mac source-derived fixture copied the current run and lease predicate
but stubbed attempt storage, quorum, clock, admission, cell and signing. The
normal path passed its unchanged empty fixture input, the trusted seam passed
`validated-source`, an existing marker and wrong job stopped before the cell,
and post-cell quorum loss suppressed the receipt. A mutant ignoring the
attempt result reached the cell for an existing marker. An Amu source check
passed on a selected dependency unity composed from current job source, the
actual cron adapter source, current lease core and historical dependencies.
This was not a full runtime build or a real filesystem/quorum/cell/cron test.
It does not validate concurrent markers, power loss, sink fencing or resource
governance; the earlier PRs' separate evidence is not a qualification of this
combined revision.

- Job source SHA-256 `7e02c1a3fcaec81e03fffd9da8a6a6b429b07d511856706120c956a5e8bbc294`; adapter source `74963065c4d9c0860880e15f33d76fa7b655afae16505857fe7c93c74471f89d`.
- Native fixture source `4364c1bf7b97675c13aa4f76fca94d2fa618b757c83d1f21a1a4245cb1adcd8c`, signed binary `4cf4e31bc5818c9b279d5f989855043ea7eb613e6cbad01c9dc4a4d9238dc8b2`, signed mutant `8e6d7f41984ded76444195f3a1abd2a91b1b40126225d6a68e5faea0dd6649ca`.
- Source-check unity SHA-256 `ea903180b88406a40afda9fcd2394947dc7aebcec1dffd38a3a0aeaceb796b0f`.
