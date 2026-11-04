# macOS memory boundary observations (AWAI-151)

These are diagnostic observations, not a qualified governor or a deployment
permit. On 2026-11-04, each experiment ran sequentially on naphtali, benjamin
and judah: macOS 26.7.2 (25G332), Apple Silicon, 16 GiB unified memory. Each
used short-lived test-owned processes; no service or persistent host setting
was changed. Ordinary test children ran as UID/EUID 501, GID/EGID 20. Python
was used only as an existing diagnostic environment, not as an implementation
or runtime dependency of the proposed Kotoba/Amu governor.

## Single-process limit

A root diagnostic's `task_set_phys_footprint_limit` call returned success for
64 MiB but the test child touched 128 MiB and exited 0; this API return value
alone did not prove enforcement in that experiment. With a fatal 64 MiB
`memorystatus_control` active/inactive limit, the direct child was killed
(signal 9) after reporting 56 MiB touched; the unlimited control completed
128 MiB on each host. A privilege-dropped child behaved likewise when the
root parent set the limit before the child performed work.

The `posix_spawnattr_setjetsam_ext` active/inactive fatal limit gave a useful
unprivileged route for a **single executable**. A UID 501 worker spawned the
final Python.app executable suspended, with 64 MiB attributes. An independent
root observer, which never set a limit, read `[64, 1, 64, 1]` before resume;
the unlimited control read `[-1, 0, -1, 0]`. On all three machines the limited
child subsequently exited by signal 9 while touching 128 MiB, and the control
completed. UID 501 itself received `EPERM` when querying the property; an
error's zero-filled buffer is not a valid readback. The kernel's precise kill
reason was not independently collected.

## Exec and descendants are not contained

A cap applied before an `exec` did not survive it in the tested setup. A
separate initial spawn test appeared to disprove spawn attributes as well:
`proc_pidpath` subsequently showed that even the Python framework's
`bin/python3.9` had switched to
`Resources/Python.app/Contents/MacOS/Python` after resume. Directly spawning
that final executable retained the limit in the single-process test above.
This does not establish that arbitrary executables avoid later `exec`.

A child started with a confirmed fatal 64 MiB limit then called `fork`.
The forked child wrote 128 MiB and exited 0 on all three hosts; its parent
waited for and reaped it. The test did not read the fork child's own property.
The observed outcome suffices to reject a single-PID limit as an aggregate
process-tree boundary. Process groups alone also do not prove containment of
children that create another group; see the existing AWAI-152 evidence.

Amu main `16c32d4f0b34adf542939ce63941147f25e5f39f` contains a Darwin
AMUH1 EXEC source path that sets an exec-preserved timer on the direct child
and waits for that child. In a separate OS-level diagnostic on these three
hosts, a parent armed for SIGALRM after one second forked a child. The parent
exited by signal 14, but the child reported completion after two seconds. This
was not an execution of the Amu artifact; its provenance and coverage remain
unverified. A direct-child timer cannot be assumed to enforce a whole tree.

## Small MLX control

Using existing MLX 0.29.3 and its GPU device, a separate single-process test
allocated and evaluated a float32 128 MiB array. Unlimited controls completed
on all three hosts and reported 134,217,740 bytes of MLX active memory. A
spawn attribute of fatal 128 MiB led to signal 9 before the completion record;
a 256 MiB attribute allowed the same operation to complete. The non-root
observer could not read back the limit in these GPU tests, and no independent
kernel kill reason or full footprint breakdown was obtained. These results
are consistent with a process footprint limit affecting this allocation; they
are not proof of an independent GPU reservation or universal GPU hard limit.
Host-wide memory pressure, swap, and existing-service impact were not sampled
before and after these small tests.

## Remaining qualification

### Self-applied process sandbox probe

On the same three hosts on 2026-11-04, a short-lived UID 501 Python process
called `sandbox_init` after interpreter startup with the profile
`(version 1)(allow default)(deny process-fork)(deny process-exec)`.
Each call returned 0. On every host `os.fork`, `os.execv` of `/usr/bin/true`,
and `os.posix_spawn` of that executable returned `EPERM`; a Python thread
ran and a separately evaluated 1 MiB MLX GPU allocation reported 1,048,588
active bytes. The fork/exec and spawn/MLX checks used separate invocations.
These were small diagnostics, not Flint runs or service-impact measurements.

This is a candidate restriction, not a containment boundary for an arbitrary
workload: the tested process voluntarily applied its own sandbox, and the
profile otherwise allows default operations. The experiment does not establish
how an untrusted or unmodified Kapitsa process would be forced into this
profile after its final exec, whether IPC can delegate work elsewhere, or
whether Flint functions under it. It does not provide aggregate CPU, GPU or
memory accounting, reservation, a queue, or crash recovery. Kernel API
availability and behavior must also be qualified for the supported OS versions.

### Launcher-applied sandbox and same-path exec

On each of the same hosts, `/usr/bin/sandbox-exec` launched the staged Kapitsa
0.2.26 binary with `--version` under `(allow default)`, `(deny process-fork)`
and a `process-exec` denial for every path except that binary's literal path.
Each printed `kapitsa 0.2.26 (6ef19b0)` and exited 0. This checks only command
startup, not model loading or inference.

A separate control used the same policy shape with the final Python.app
executable as its allowed literal path. On all three hosts Python could `execv`
the same executable and print a post-exec marker. Thus the path exception
needed for the launch also permits later same-path exec; this policy alone
does not close the earlier observed exec-related footprint-limit escape. The
control did not combine sandboxing with a memory limit or use Kapitsa itself to
re-exec. On naphtali, `codesign -dv` reported the Kapitsa executable as
ad-hoc signed with hardened-runtime flags; its entitlements command did not
print an entitlement payload. This is descriptive, not an injection or
integrity test.

A production design still needs a native Kotoba/Amu host boundary, a trustworthy
way to control all descendants and later execs, aggregate CPU/memory/GPU and
other resource accounting, admission and reservation, FIFO and lock lifetime,
crash recovery, and actual workload tests. Host-only admission is distinct from
distributed fencing. Unknown observations and incomplete teardown must refuse
new work. See [AWAI-151](https://linear.app/kotobalabs/issue/AWAI-151) and
[AWAI-152](https://linear.app/kotobalabs/issue/AWAI-152).

The temporary diagnostic scripts are not committed tooling and may no longer
exist. Their SHA-256 values identify the tested inputs, not independently
reproducible binaries:

| Temporary diagnostic | SHA-256 |
| --- | --- |
| `awai151-unprivileged-independent-readback.py` | `20acc8a240aa6a01ad08459f1f363f7d582fd781239580376e86aef6cf401856` |
| `awai151-spawn-path-observe.py` | `2f490a3e01d79fc11c17d57f251e6915db4c48c6f4adfba97cfc6b05b466db4c` |
| `awai151-spawn-fork-inheritance.py` | `c9e7e1f57461149580d4e7a9aeb8e006a44f8a2565e7cd03b060d2487695704e` |
| `awai151-mlx-jetsam-probe.py` | `013399ad7848c6499525cb55a1443651a1f9314992325faf99755a82af8ba8bb` |
| `awai151-mlx-jetsam-256.py` | `3a76d483b5e5de6d784addc607b591029e1cc3c9439fbc072e9928b5101212b1` |
| `awai152-alarm-fork-probe.py` | `959cc4232e1b876bf3c8cb4de742b53f5a90966d491fbde5ebfad84a8cd90636` |
