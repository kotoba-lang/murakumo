# Process death at FIFO write boundaries

`stage.py` takes the pinned `resource_fifo.kotoba` and a new empty output
directory; it generates five test-only source-unity variants, each writing a
readiness marker to stderr and pausing at a different point in `rq-commit`.
Compile them with the ARM64 Linux compiler pinned in `receipt.json`. Place the
resulting images, `observer.py` and the separately pinned uninstrumented
`normal.elf` in `/private/tmp/awai151-fifo-crash` inside a disposable VM. The
observer creates new fixture directories, kills only the child it launched and
runs the normal image to inspect the resulting queue. The receipt records the
source and artifact hashes and observed outcomes.

This tests actual process SIGKILL and subsequent lock acquisition, not a
machine power loss or a full governor. A deliberately paused instrumented
program is not the same artifact as the uninstrumented component.
