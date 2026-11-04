# Native bounded FIFO read diagnostic

On 2026-11-04, the current FIFO bounded-read implementation and its existing
case definitions were composed and executed natively on jacob, macOS 26.3.1
(a), build 25D771280a. The native fixture returned 0 for metadata parsing,
5-byte queue content, queue lengths 4165 and 4166, journal lengths 8340 and
8341, and empty and missing files. The over-limit files were refused. A
source mutant removing the `size > maximum` guard returned 1 for the same
cases. This is one local diagnostic run using a private FD3 scope; its temporary
directory was removed. Python stages and observes, while the fixture, compiler
and packager run natively.

Component SHA-256:
`0c1cd481fcb522fa76b0313e1db942c7ca6e4bb6370ffdc641e73dee9c906ae2`;
case source: `d7a306e41568361fa1b008d480df466b3a4ce9ac9799cbfbaf0fd3f0ca4a29db`;
composed fixture: `7336ba1fde9464228c54c374406fe365ab2661b22335dc483eb1e08cd53a69ef`;
harness: `2b240d1d3afd9777d2e7921aff520f3cb76fec7c1a8c64cd12eab4164a4c1290`.
The signed diagnostic compiler and FS2 packager matched the prior portability
receipt, SHA-256 `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

This is not a production provider qualification or a full FIFO test: short
reads, metadata races, injected faults, concurrency, recovery, CPU or memory
limits, process-tree termination, reservations and distributed fencing were not
tested here. An unavailable or trapping provider remains an unknown outcome.

## FIFO operations and journal reconciliation

The extended harness also compiled the current FIFO entry point and, in a new
private root on the same Mac, exercised empty, ordered enqueue, duplicate
refusal, front, wrong-front dequeue refusal, front-cancel refusal, non-front
cancel, dequeue, and pending-marker refusal. A separately held flock returned
`fifo/busy`. A synthetic valid intent with the old queue was reconciled as
`fifo/aborted`; the same intent with the new queue was reconciled as
`fifo/recovered`. Eight concurrent enqueue invocations all returned enqueued
and their tickets matched the independently read queue, with zero busy results
in this run. This does not establish scheduler fairness or all race outcomes.

Extended harness SHA-256:
`3448fccc6c62aae2b2c2f1404ebce5539568d3925427700e038092e0571e7807`.
These are synthetic journal states, not killed-process or power-loss recovery;
provider faults, short reads, adversarial replacement, resource limits and
whole-workload termination remain unqualified.

## Owned process termination at write boundaries

On the same Mac, five source-instrumented test variants paused their own
processes by filling an unread output pipe. The observer verified the marker,
checked queue and intent, SIGKILLed only the paused child, and used a separately
built, uninstrumented FIFO to inspect and reconcile the state. After intent,
pending, rename and directory sync, `front` refused with `fifo/pending-unknown`;
after intent archival it returned the enqueued ticket. Reconciliation returned
`fifo/aborted` after intent, `fifo/recovery-unresolved` with the pending file,
`fifo/recovered` after rename and sync, and `fifo/no-intent` after archival.
The queue remained the expected old or new bytes after reconciliation.

Extended harness SHA-256:
`2a15775020ae45a9608d5c5defbc57a131273142443a026441a63e39c267da01`.
This is a single instrumented process-kill sample per boundary using the
diagnostic provider. It is not power loss, kernel I/O failure, full crash or
workload recovery, resource governor qualification, or proof of what happened
when an archived intent's response is lost. In particular `fifo/no-intent` is
not independent proof that the original operation succeeded or failed.
