# Native GitHub checkpoint archive candidate

`kotoba/github_checkpoint_archive.kotoba` composes with the existing native
checkpoint component. It verifies the signed contract and every block, checks
an exact private GitHub repository through a host-granted `gh`, creates a fresh
private work directory, copies only the verified closure, and uses Git to make
an immutable content-addressed ref. It fetches that ref independently, matches
the tree, runs Git fsck and re-verifies the signed closure. A push failure is
not considered success unless that readback matches; other outcomes are unknown
or conflicting. The component does not acquire a lease or authorize a bot.

This is an unqualified draft. It is not yet wired to the resident, cron or an
archive acknowledgement, and no autonomous production service uses it. The
host must fix the exact repository and executable identities, classify every
byte for GitHub, grant a short-lived repository-scoped credential, isolate
source/work directories, set disk limits and reconcile/clean retained attempts.
A fresh unique work path is required on every attempt; existing paths are
refused and no data is automatically deleted.

The private API check by itself does **not** bind the later Git connection:
Git config can rewrite URLs, use proxies or helpers, or invoke other programs.
The host must independently pin and validate the Git and `gh` configuration,
credentials and egress so the exact `github.com` destination is used, with no
unreviewed rewrite, proxy, helper, hooks or extra process authority. Do not put
credentials in paths, arguments, the checkpoint, manifest or logs. The current
spawn provider's deadline and detached-child behavior also require the host
lifecycle boundary in AWAI-152. No retry is safe merely because a process
returned an error or a local lock was released.

The [Linux fixture](evidence/awai150-native-github-archive-fixture.kotoba) and
[record](evidence/awai150-native-github-archive-20261103.json) exercise real
Git against a local bare remote with a **stubbed** private API response. Their
URL rewrite is deliberate test setup and is forbidden in the production host
policy described above. The separate signed GitHub CLI fixture in
[git-checkpoint.md](git-checkpoint.md) used operator-side Git; it did not run
this archive candidate. No native GitHub transport, production secret path,
Mac runtime or automated bot has been qualified by either test.

The candidate now asks real Git for matching effective configuration key names
before copying the closure or initiating Git transport. It refuses known URL
rewrites, proxy settings, credential helpers, included config files and selected
TLS/redirect overrides; errors also refuse. Git transport explicitly requests
TLS verification and no HTTP redirects. The values are not emitted by this
check. A [bounded test](evidence/awai150-git-config-guard-20261103.json)
exercised the refusal cases with real Git. Its positive control modified the
remote expression to point at a local bare repository.

This check is **not** a complete security boundary: environment-supplied
proxies, trust stores, credentials, Git/GH executables, configuration races,
DNS and other host effects remain outside its proof. The host requirements
above still apply, and a failed or unknown operation must be reconciled before
retrying. The earlier local-remote fixture documents the earlier source before
this guard and does not qualify the new transport configuration.
