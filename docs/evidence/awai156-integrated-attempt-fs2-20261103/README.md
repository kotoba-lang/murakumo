# Job run composed with real FS2 attempt marker

A native source-derived fixture combines the current `jn-run-with-source`,
strict lease predicate and `jn-attempt` FS2 functions. The attempt marker is
real, scoped filesystem I/O; clock, lease/quorum, job validation, node
admission, cell and signing are synthetic stubs. The trusted adapter seam is
invoked with the literal `validated-source`; the actual cron consumer is not
part of this execution fixture.

On an ARM64 Mac with the diagnostic FS2 host, the first invocation reached
the cell stub and returned a synthetic signed receipt; a repeat refused the
existing marker without reaching the stub. Four concurrent invocations with
the same ID and a fresh private root produced one stub call and three marker
refusals. A test-only mutant paused immediately after real marker and parent
sync and before the cell call. The observer killed that exact child after its
marker-synced message; a new fixture process then refused the marker and did
not call the cell stub. This is a controlled process kill, not a power-loss or
actual model recovery test. The pausing mutant intentionally loops: run it
only with a bounded watcher and CPU limit.

The same fixture was compiled to Linux ARM64 static with staged diagnostic
compiler `4a42e07083beb87337ad56dbf9759890e48d4502154e361d54cfee0b71e89da9`
and run on Xavier with a scoped task directory and fixed synthetic inputs.
First and repeat had the same outcomes; four concurrent contenders produced
exactly one cell-stub call and three refusals. The temporary directory was
removed, and no existing service or model was started or stopped. SSH used a
temporary known-hosts file based on Tailscale's current advertised host keys.

The simulated receipt is not an actual signed result. Neither host used a real
quorum, real cell, model, GitHub, distributed fencing, production credentials,
resource governor or qualified release artifact. The currently pinned Linux
bootstrap lacks FS2 U, as documented in the previous native-attempt evidence.
The private attempt-root/ancestor ownership assumption and uncertain outcome
reconciliation remain necessary.

- Job source SHA-256 `c56f95d099dc0bb928b9ec7309eb880152327b88bc76ae5b5467176953112620`.
- Fixture source `fecc5d1b870e36c8b89b3a17aea909e77d9a4532196bf946eea36fce4cab4519`; signed Mac binary `c33bf9ee72a363e558fd00b1d62a293a7024b96d40a6651e22591b6b650257d1`; Xavier ELF `5b22c2d9891a2cbca49a7817e3a71e18c6426009da0eef1d2c186ac7d83f192c`.
- Pausing mutant source `472161f1c7474ac118a121ee788f6d967e22fe99cae4cc22f211d95ab620b735`, signed binary `7bfac4494a30348fb5e121977f03259722ca467588024e5be705cb4cea6eff1f`; Linux observer `3dbd7512b07b06ed471ea4a36f503a31e9ee173fc804f2d0951de2f5f8a86892`.
