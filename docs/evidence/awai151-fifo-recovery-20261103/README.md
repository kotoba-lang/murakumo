# FIFO journal recovery: isolated observations

The source under test has SHA-256
`eec2bfa662c90fe114aa54cbeb4293b8ce22e0e29bfb7f072b32aa19d6dec1d3`.
A local Mac compiled it with bootstrap root SHA-256
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and diagnostic FS2 packager SHA-256
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
The signed, uninstrumented artifact hash was
`8529fe2b7ccee5182fe12a409c02e591198172bbbb69816e1b04b6b193243dc0`.

On that Mac, `contract.py` passed sequential checks and one eight-contender
race, and `capacity.py` passed the 64-ticket boundary. `observe.py` killed
instrumented, test-only processes after intent sync, pending sync, queue
rename, directory sync and intent archival. The uninstrumented artifact
refused unresolved states; it recovered the renamed and synced states without
reapplying the update; and it saw no intent after archival. Invalid magic,
invalid transitions, mismatched state and leftover pending data were refused.
`recovery-interrupt.py` killed recovery itself before and after archival;
subsequent calls respectively completed recovery and returned no intent, while
the same ticket remained at the head. A separately held lock was also refused.
The observer paths refer to temporary isolated test directories and must be
adapted for another run. The injected pause is a test-only blocking pipe write.

On the same local Mac, `dequeue-recovery.py` also recovered a committed head
removal. Attempts to recover a non-head removal, a replaced head, a duplicate
append or two simultaneous removals were rejected without changing the queue.

A fresh 1-CPU, 2-GiB Linux ARM64 VM ran `linux-contract.py` and
`linux-observe.py` against the current source. The uninstrumented Linux ELF
SHA-256 was `4745a1e3ce2cd4fbc0cca0619dcd61836cfa86642b5d14f08666c0642f1ff76c`;
the bootstrap compiler SHA-256 was
`914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`.
The Linux source was the pinned source above with an entry-point namespace
change and a `main` wrapper; its SHA-256 was
`8f5d5b66637116ff00b27073799a3d1e70856b0882b42389606499abac6bce58`.
The result log records sequential, one eight-contender race, the five write
boundaries and four refusal cases. The earlier VM restart failure was avoided
by creating a fresh VM, which was removed after the test. The Linux test does
not cover the Mac-only head-removal cases or recovery-process interruption.

These process-kill and synthetic checks do not simulate power loss, I/O error,
untrusted writers, process-tree termination, actual resource admission, model
inference or bots. The diagnostic runtime is not a production release. Historical
observations in the parent document used an earlier journal format; an old
unfinished intent is invalid to the new reader and needs trusted-host review.
