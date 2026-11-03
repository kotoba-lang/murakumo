# Darwin and Linux FIFO component observation

On one local ARM64 Mac, the previous FIFO source trapped on the diagnostic
FS2 string-write operation. A byte-write variant reached queue rename but
trapped on FS2 J. The current source uses byte writes with exact echoed-byte
verification and moves the completed intent to `queue.last` with FS2 L after
the queue directory sync. It then passed `contract.py` and `lock-check.py`
using task-owned FD3 scope and the pinned diagnostic compiler and packager.
The test directory names in those scripts must be fresh. This is neither the
public release runtime nor production governor qualification.

`stage.py` generates a source-unity entry point and five test-only Linux kill
variants from the pinned source into a new directory. Compile all variants
with the receipt's native Linux compiler. In a disposable VM, place them under
`/private/tmp/awai151-fifo-portable-final`, name the ordinary executable both
`fifo.elf` and `normal.elf`, and run `linux-contract.py` and `linux-crash.py`.
The latter pauses each instrumented process at a specific boundary, SIGKILLs
its own child, then checks the queue with the uninstrumented executable.
The [receipt](receipt.json) pins the artifacts, observations and limits.
