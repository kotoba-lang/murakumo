# FIFO component on two Mac minis

The diagnostic artifact and instrumented process-kill images from the prior
[portability](../awai151-fifo-portable-20261103/) and
[Mac kill](../awai151-fifo-mac-kill-20261103/) observations were copied to
fresh private directories on levi and benjamin over Tailscale SSH. `contract.py`
(or the equivalent `contract-levi.py` with a fresh directory name),
`lock-check.py` and `kill-observer.py` run from a directory containing those
images. Each observer is test-only and creates its own synthetic paths.

Both hosts passed the sequential cases, a single eight-contender race, a
separately held flock and five process SIGKILL boundaries. The first Levi
attempt was refused by the OS because its transferred image lacked execute
permission; after chmod of that task-owned image the test passed in a new
fixture. The receipt pins each artifact, observer and outcome. Temporary
remote directories were removed after the check.

These were independent local tests, not a distributed queue or resource
governor. No model inference or bot turn was performed. The diagnostic
runtime and test pauses are not production artifacts.
