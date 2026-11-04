# FIFO reservation history rename interruption

A diagnostic on jacob.local (macOS 26.3.1 (a), build 25D771280a, ARM64)
compiled the current `resource_fifo.kotoba`
using compiler SHA-256
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and the experimental FS2 packager SHA-256
`df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5`.
`scripts/native/check-fifo-history-crash.py` builds a normal diagnostic FIFO
and a second image instrumented with a blocking stdout pause immediately
after the settled receipt is renamed to the previous receipt, before the
directory sync and new hold write. A private queue had two synthetic tickets;
the first was reserved, released, dequeued, and settled. The instrumented
second reserve was killed and reaped after the pause was reached.

The visible state had the previous receipt and no settled receipt. Normal
`front` and `reserve` returned `fifo/reservation-unknown`; `settle-recover`
with the second ticket was refused. `settle-recover` with the first ticket
succeeded and only then did `front` and `reserve` for the second ticket
succeed. Temporary test files were removed. This demonstrates one
process-kill interruption and recovery on one diagnostic provider, not a
power-loss durability guarantee, production host crash recovery, concurrent
untrusted access, workload termination proof, or an operational governor.
A trusted host must independently reconcile workload lifetime before any
recovery that might permit dispatch.
