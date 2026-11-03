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
| earlier resident source | `1bef0b1eda1aa049af56caa3ac5aeeed5e88c66de24ee39f8324adf3d60183f5` |
| controller source | `2e36087bc0589ee804e18f8f38228acda7774c46fdf0341c9f29ae459ec2656b` |
| earlier test source | `6657f8c2a24f5cba3e710d4e8999f7f4d715a0e5c23c6c1a780e06a89645e5ad` |
| earlier signed native fixture | `f333089916f9d112beab2ed643aaf653b6884b568a2a9f5f98e71028adee6fab` |
| signed removed-store-check control | `e8a2d2f87d32631cf98170c9f8b0146da9a5b49a6a6086dc12c4baf9986e4ef7` |

The invocation's staging directory and GitHub CLI are separate arguments, not
config fields. The archive now checks those too, before any archive call: an
acknowledgement cannot overlap the staging directory or replace the CLI. A
new native fixture passed, and controls removing either check failed. The full
controller source composition again passed `check`. This still does not test
the real GitHub archive or filesystem aliases.

| Additional diagnostic artifact | SHA-256 |
| --- | --- |
| current resident source | `0e119b24fda2bfab69ce637333041054325bf092ae16359471f1e08f3004b357` |
| current test source | `2b64c88e9c80ad1823a4c07b3e72a6a54d9f6c83366267dc7dccce9bdacce1eb` |
| signed updated fixture | `62ceb4d6128640342aea6e5f9d6f6c35d3e7136a5594484509d64a48ead1cd39` |
| signed removed-CLI-check control | `256c1a75ff8bd41a501ac76f4104f28bee0fb12244124574bf11b6b5700aa422` |
| signed removed-work-check control | `dfcc2fa290baed36909216b0b1de146d6980ea909fc7c44d4324c6097377bd8f` |
