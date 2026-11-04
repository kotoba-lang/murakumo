# Diagnostic FIFO reservation barrier

The candidate adds a host-owned reservation marker bound to the current FIFO
head. `reserve` succeeds only for the head after writing and syncing the marker
and directory. While the marker is present, `front`, `dequeue`, enqueue and
other normal queue operations refuse. A malformed marker or unknown filesystem
response never authorizes release. A matching `release` archives the marker
and syncs the directory; only a trusted host that has independently established
that the *entire* workload stopped may call it. A missing or ambiguous release
response must be reconciled by that host before another dispatch. In particular,
a post-rename sync failure can leave the marker absent even though the command
returns unknown; marker absence alone is not proof of successful reconciliation.
If a pending queue journal and reservation coexist, ordinary recovery and
release both refuse; a trusted host must reconcile that state offline.

This is an opt-in FIFO primitive. The FIFO does not start or supervise a
workload and cannot authenticate the caller or prove termination. The host must
ensure every dispatcher follows reserve-before-start, preserve the filesystem
scope and lock inode, exclude other writers, and never release on missing or
uncertain process evidence. Legacy callers that bypass the protocol are not
safe. The watcher and reservation are not yet connected.

The candidate uses `RGQ2` instead of `RGQ1` so the previously pinned old FIFO
rejects this queue instead of silently ignoring its reservation. New code also
rejects an `RGQ1` queue. No automatic migration is provided: a trusted host
must first reconcile all old operations and workloads, exclude old binaries and
writers, and migrate offline. No existing queue was migrated here.

On one local Mac, the signed native FIFO exercised reserve, wrong-front
refusal, held front/dequeue refusal, wrong-ticket release refusal, correct
release and malformed marker refusal. A separately built reservation-bypass
mutant exposed the front and was detected. A source-instrumented reservation
process was killed after syncing the marker and before its response; a
separate unmodified FIFO still refused front and dequeue until the test host
explicitly released it. The previous old FIFO image rejected `RGQ2`, and the
new image rejected `RGQ1`. Existing FIFO operation, contention, journal and
owned-child kill cases also passed. This is process-kill testing, not power
loss, a real workload termination proof or durable host recovery.

The same signed FIFO and earlier journal-pause variants were tested over
host-key-pinned Tailscale SSH on naphtali, benjamin and judah, all Darwin
ARM64 build `25G332`. Each verified hashes and signatures and passed a short
reservation/held/wrong-ticket/release sequence and existing journal cases;
all test-owned staging files were removed. The reservation-owner SIGKILL case
was only run locally. No model or persistent service was started.

Current source SHA-256:
`6114200c855848bc910ea1f9bf4f7bc69b6f8ebe6501230bcc3e6edf91867fdd`;
signed FIFO SHA-256:
`e58fb02d5bc30444d8bbc16859d70cb083b16fbaa7dac16eae2164bd88f65978`;
artifact manifest SHA-256:
`e010bca4bb00d186cffbec678b768bc7736820224b553c38bd9891c2c8aab72e`;
target observer SHA-256:
`d15de4857d30c442c0c3710fef08e0aeb7c4ed917e523c76e8f0fd8466623afa`.
The target image was built from byte-identical executable code before the
source comment documenting migration was added; the final local rebuild
reproduced its signed image hash. The compiler and packager were the pinned
local diagnostic artifacts
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` /
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
No production provider, authenticated host, hard resource bound, descendant
containment, distributed fence, selfhost build or governor is qualified.
