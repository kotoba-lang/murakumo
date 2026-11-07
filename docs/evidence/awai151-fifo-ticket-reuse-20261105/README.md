# Reservation ticket reuse diagnostic

The RGQ4 FIFO does not prevent a previously settled ticket from being enqueued
and reserved again. On one local Darwin ARM64 host, `check.py` created a
private temporary queue and ran the signed diagnostic FIFO through
enqueue, reserve, release, dequeue and settle for one synthetic ticket. It
then enqueued and reserved the same ticket again. A second identical release
was accepted. All operations returned success; the temporary queue was removed.

The second release is a stand-in for a delayed or replayed command, not an
observed network replay or proof that any production workload was affected.
The FIFO trusts its caller and has no authenticated release or durable global
anti-reuse history. A trusted supervisor must bind each release to the current
reservation incarnation, prevent ticket reuse across restart and recovery, and
reject stale commands before invoking the FIFO. A ticket alone is not a fence.
Simply rejecting the immediately previous ticket would not solve reuse of older
tickets. These requirements remain unimplemented and must be qualified before
using the FIFO as part of a governor.

The source SHA-256 was
`bd23560f142c1e417662eca2f026c521c931be5ed48739c7976c91b10588c825` and the
pre-existing signed diagnostic image SHA-256 was
`e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7`.
The test used a three-second CPU cap per process, without a hard memory cap.
No workload, network, inference service or existing queue was used. This does
not qualify the native supervisor, process containment or resource governor.
