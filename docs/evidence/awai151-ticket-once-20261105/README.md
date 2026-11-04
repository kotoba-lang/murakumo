# RGQ5 permanent ticket claims: native diagnostic

The candidate uses a new `RGQ5` queue format and a durable `queue.used.<ticket>`
marker written and synced before enqueue can publish a ticket. A marker is
never removed by the FIFO. A failed or ambiguous claim burns that ticket;
retries need a new ticket. A marker already present refuses reuse even after
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
