# Archive acknowledgement path safety

The native archive publisher writes `archiveAck.tmp` and renames it over
`archiveAck` after GitHub readback. An acknowledgement located inside the
content-addressed checkpoint store could overwrite a stored object, even
though the old guard rejected the store directory itself. Similar overlap
could affect the runtime directory or response cache.

The resident archive path now refuses acknowledgement and temporary paths
that overlap the store, runtime root or cache. It also refuses collisions with
configured input/output files, their temporary paths, the checkpoint binary,
the resident lock and the config path. The cron controller uses the same gate.
The host still has to exclude symlinks, hard links, aliases and concurrent
replacement; lexical checks alone do not establish filesystem isolation.

A bounded macOS ARM64 native fixture composed the actual helper and its
dependencies with `test-profile-archive-path-safety`. It accepted an ordinary
path and a separate directory with a common textual prefix; it refused store,
cache and runtime overlaps, a temporary-file collision, the binary and config
collisions. A control with the store-overlap check removed failed the fixture.
The refreshed full cron controller source composition passed native compiler
`check`; this is not a full runtime or deployment test.

| Artifact | SHA-256 |
| --- | --- |
| resident source | `1bef0b1eda1aa049af56caa3ac5aeeed5e88c66de24ee39f8324adf3d60183f5` |
| controller source | `2e36087bc0589ee804e18f8f38228acda7774c46fdf0341c9f29ae459ec2656b` |
| test source | `6657f8c2a24f5cba3e710d4e8999f7f4d715a0e5c23c6c1a780e06a89645e5ad` |
| signed native fixture | `f333089916f9d112beab2ed643aaf653b6884b568a2a9f5f98e71028adee6fab` |
| signed removed-store-check control | `e8a2d2f87d32631cf98170c9f8b0146da9a5b49a6a6086dc12c4baf9986e4ef7` |
