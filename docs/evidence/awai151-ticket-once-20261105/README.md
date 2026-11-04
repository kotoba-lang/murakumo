# RGQ5 permanent ticket claims: native diagnostic

The candidate uses a new `RGQ5` queue format and a durable `queue.used.<ticket>`
marker published and synced before enqueue can publish a ticket. A marker is
never removed by the FIFO. A caller must reconcile an ambiguous claim before
retry. A marker already present refuses reuse even after
later reservations. The FIFO remains a host-owned primitive: it does not
authenticate a caller or establish that a workload or its descendants ended.

The host must exclusively own the queue, lock and entire marker namespace,
prevent marker deletion, replacement or rollback across restart and backup
restore, and ensure FS2 D distinguishes a missing file from errors. No
automatic migration is safe. An RGQ5 deployment needs a fresh empty queue and
a distinct ticket namespace, exclusion of old writers and all outstanding old
release requests, and reconciliation of older workloads. Failure or unknown
storage state must halt admission. Marker storage grows with ticket count;
safe compaction and disaster recovery are not implemented.

On one Darwin ARM64 host, `scripts/native/check-fifo-ticket-once.py` compiled
the current source with pinned diagnostic compiler and packager. It completed
two full enqueue/reserve/release/dequeue/settle sequences and refused reuse of
the older ticket. A source-injected failure after marker and directory sync
returned unknown and the unmodified image refused retry of that ticket. A
separately compiled mutation bypassing the marker check admitted the old
ticket. The prior RGQ4 native image refused RGQ5 and the candidate refused
RGQ4. Each native process had a three-second CPU cap; no hard memory cap was
applied. These tests use isolated temporary files.

The initial diagnostic source SHA-256 was `6c3e9c48fc4337116b805f9ce4c6571baacaf34da84d58a66d5c7fdd1e89b762`;
its signed candidate image SHA-256 was
`eefea18ec593be484b9c417e80ad3701820f860f09b376b756df86118b55af32`;
compiler SHA-256 `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic packager SHA-256 `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`;
previous signed FIFO SHA-256 `e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7`.

The failure is injected, not a power-loss or actual I/O failure. Other RGQ4
integration diagnostics, production provider,
authenticated supervisor, whole-workload termination, resource limits and
deployment remain unqualified. This is not a complete replay or resource
governor solution and does not authorize Flint deployment.

## Existing bounded FIFO regression, adapted for RGQ5

The source-bound `check-fifo-bounded-fixture.py` and its boundary cases were
adapted to the new header and run with the pins above. Normal operations,
old-format refusal, synthetic old/new journal reconciliation, injected short
reads and sync failures, one eight-caller enqueue sample, and owned-process
kills at five queue-journal boundaries passed. The bounds-removal mutant was
detected. A real-file same-size replacement still caused the old bytes to be
accepted, confirming the host must exclude competing writers and aliases.
The synthetic recovery setup directly seeds and resets queue files, sometimes
with tickets lacking RGQ5 claim markers; it exercises legacy recovery logic,
not a valid migration or a proof of the new claim invariant. Other historical
RGQ4 integration scripts remain pinned to their own format and images.

This regression used per-process three-second CPU limits and no hard memory
cap. It did not simulate power loss, actual sync failures, claim-marker loss
or rollback, or establish authenticated release or whole-workload lifetime.

## Refuse reservations without an intact claim

The reserve operation now requires a complete bounded read of its ticket's
exact `RGU1` marker before consuming history or creating a hold. A native test
removed the marker and then replaced it with malformed contents; both were
refused and the missing-marker case created no reservation. A separately
compiled mutant without that guard admitted the missing-marker reservation.
The ticket-reuse and bounded-FIFO diagnostics were rerun successfully. One
eight-caller sample admitted four requests and reported four busy; this is not
a fairness measurement. The synthetic recovery fixture now seeds markers for
its directly injected tickets; it is still not a valid migration procedure.

The current source SHA-256 is
`0142b7864d6ff5bd91cfd6455569049d09fcc39c08f5729f70f78a175b55e750`
and the signed FIFO image SHA-256 is
`d2aaa21b5a6d130dae28e34413b9759f9070fe2cf588a4be694950f19d21ac15`.
The same compiler and packager pins and per-process CPU limit were used.
This guard detects an absent or corrupt marker for the ticket being reserved;
it cannot detect historical markers silently removed after their ticket left
the queue, restore rollback, unauthenticated release, power loss or a malicious
writer. The host must still preserve the complete marker namespace.

## Claim interruption on three Mac minis

The source-bound observer was extended with two instrumented claim variants.
Each pauses its own child after writing the marker or after syncing the marker
and directory, before enqueue publishes the ticket. The observer confirmed the
marker was visible and the queue empty, killed and reaped that child, then
verified the unmodified image refused a retry of the same ticket. These are
process-kill observations; visibility before sync does not establish durable
storage after power loss.

The observer passed locally and on naphtali, benjamin and judah using strict
Tailscale SSH host keys. All three reported Darwin ARM64 macOS 26.7.2 build
25G332. The transport checked hashes of the compiler, packager, previous FIFO,
source and observer and each host reported its task directory removed. Each
host built the same signed candidate image with SHA-256
`d2aaa21b5a6d130dae28e34413b9759f9070fe2cf588a4be694950f19d21ac15`.
Observer SHA-256 was
`ee07b1d0b69b7dfc5371725ca2d3f6ef3988c2216b05e0469a55d70d91bcdf0f`.
Processes had three-second CPU limits, without an independently enforced
whole-host memory cap. No model or persistent service was started. This does
not test machine crash, power loss, storage rollback, descendant termination,
production provider, authenticated release or a qualified governor.

## No-replace claim publication

The initial implementation checked existence, then wrote the claim directly.
A provider returning `0` for a read-permission error could misclassify an
existing write-only marker as missing and allow that direct write to overwrite
it. The revised candidate writes and syncs `queue.claim.<ticket>`, publishes it
as `queue.used.<ticket>` with FS2 T (no-replace rename), then syncs the marker
and directory before enqueue proceeds. A T error or ambiguous outcome is
unknown; a qualified provider must implement true no-replace with no fallback.

In the local native diagnostic, a source-injected false missing response and
an actual write-only existing marker both produced `fifo/ticket-unknown`
without changing the marker. A mutant bypassing the lookup and replacing T
with replacing rename L admitted reuse. After an owned child was killed before
the staging file was published, a test retry succeeded; after directory sync,
retry was refused as used. The caller must not infer which outcome occurred
from a lost response. The bounded FIFO and ticket diagnostics passed with the
same compiler and packager pins and three-second per-process CPU limit; there
was no hard memory cap. This does not prove power-loss durability, recovery
from rollback, production provider behavior or authenticated release.

The no-replace revision source SHA-256 is
`dfacce206668b331ebf233b9f20b926c9d9e6d7ea2276aa1681ee75710428ac4`;
its signed diagnostic image SHA-256 is
`d9314a5e9c6f1dce76adf0da2d5a63efbf0741e0e856bcda916f776b20eabbf1`.
The same source-bound diagnostic then passed on naphtali, benjamin and judah
(Darwin ARM64 macOS 26.7.2 build 25G332) using the pinned test compiler,
packager and old FIFO. Each built the image above, observed the false-missing
and write-only-marker refusals, and reported its temporary directory removed.
Observer SHA-256 was
`0271969f692f71b99acca78f3c850657f93ced96e96beb61a8d7d0a687e29db7`.
These are diagnostic provider observations, not qualification of the target
production provider. FS2 T support and refusal when unavailable require an
independent production-host test.

## Preserve the barrier when a claim is lost

The current candidate also validates the exact claim before release, dequeue,
settle and settle recovery. In a local source-bound diagnostic, removing a
claim after reserve refused release and retained the hold; corrupting it
after release refused dequeue and retained the release barrier. Removing it
after dequeue refused both settle and a synthetic settle-recovery state;
restoring it allowed the matching recovery to finish. The adapted bounded
FIFO regression and ticket-once diagnostics passed locally with the same
compiler and packager pins. The prior three-Mac observation above used the
earlier image, not this source revision.

Current source SHA-256:
`7d48c9bccab640d20791f452650260aefb6b6d885f36803014e3c359ec680f50`.
Signed diagnostic FIFO SHA-256:
`6557d0f1bc716c1ddfacd6cc96857e4d0229fd788cb025297204254f727f0085`.
The recovery state was manually constructed in a task-owned temporary
directory; this is not a power-loss or production recovery test. Preserving
all historic claims and authenticating the host remain necessary.

The updated source-bound ticket-once diagnostic also passed on naphtali,
benjamin and judah using `scripts/native/check-fifo-ticket-once-fleet.py` and
the pinned compiler, packager and old FIFO above. The three hosts reported
Darwin ARM64 macOS 26.7.2 build 25G332, built the current diagnostic image
with the hash above, and reported their task-owned transfer directories
removed. The observer exercises missing/corrupt markers, retained barriers,
synthetic settle recovery, claim process kills and the guard-bypass controls.
It is not a machine-crash, storage rollback, production provider or governor
qualification. The fleet wrapper SHA-256 was
`5934c846658039fff6966e5f1e3dc690cbdfa280061bc5b6e1bdf45bdcb8aa7b`;
the updated observer SHA-256 was
`f58e3d72fae1cb6dd155510ee7416ba6be0cef51b11a3a5f1add6fb52f2a8738`.
