# FIFO process death on a Mac diagnostic runtime

`stage.py` composes the pinned source and generates five test-only variants;
it calls the prior portability stager and replaces each Linux clock pause with
a write to an unread pipe. `build.py` compiles those variants with the pinned
Mac native compiler and packages them with the diagnostic FS2 runtime. Its
paths point to the exact local test artifacts; use a fresh test directory and
verify the receipt's compiler and packager hashes first.

`observer.py` creates a fresh task-owned directory per phase, gives the native
fixture FD3 scoped to that directory, waits for its pipe marker, kills the
child it started and invokes the separately pinned uninstrumented image. The
receipt records source and binary hashes, phase outcomes and limitations.
Neither the staging scripts nor the diagnostic packager are an operational
runtime. This is process SIGKILL on one Mac, not power loss or a qualified
resource governor.
