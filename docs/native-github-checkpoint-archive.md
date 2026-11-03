# Native GitHub checkpoint archive candidate

`kotoba/github_checkpoint_archive.kotoba` composes with the existing native
checkpoint component. It verifies the signed contract and every block, checks
an exact private GitHub repository through a host-granted `gh`, creates a fresh
private work directory, copies only the verified closure, and uses Git to make
an immutable content-addressed ref. It fetches that ref independently, matches
the tree, runs Git fsck and re-verifies the signed closure. A push failure is
not considered success unless that readback matches; other outcomes are unknown
or conflicting. The component does not acquire a lease or authorize a bot.

This is an unqualified draft. The resident exposes an explicit `archive-native`
operation that calls it and writes an acknowledgement only after readback and
rechecking the admitted head. The draft cron controller invokes this operation; no autonomous production service uses it. The
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

A later [Mac fixture](evidence/awai154-mac-github-fixture-20261103/receipt.json)
used the GitHub connector to publish and independently fetch the synthetic
signed checkpoint closure in a dedicated private `itonami/fixture-mac/` ref.
The Mac checkpoint fixture verified the fetched blocks and restored the local
Git tree, including staged, unstaged and listed untracked data. A locally
corrupted downloaded block and an existing destination were refused. This is
operator-side connector transport with the privileged test-only Mac spawn/path
adapter: neither the native archive candidate nor an autonomous bot performed
the GitHub transaction. The fixture ref is not an `itonami/archive/` ref and
is not discovered automatically by the native restore candidate.

The candidate now asks real Git for matching effective configuration key names
before copying the closure or initiating Git transport. It refuses known URL
rewrites, proxy settings, credential helpers, included config files and selected
TLS/redirect overrides; errors also refuse. Git transport explicitly requests
TLS verification and no HTTP redirects. The values are not emitted by this
check. A [bounded test](evidence/awai150-git-config-guard-20261103.json)
exercised the refusal cases with real Git. Its positive control modified the
remote expression to point at a local bare repository.

The [Mac host authentication observation](evidence/awai154-mac-host-gh-auth-20261103/receipt.json)
ran `gh auth status` through the diagnostic private host. The operator's CLI
was signed in, but the child launched with the host's minimal environment
reported no GitHub authentication. This explains why the local Git and
connector fixtures do not provide native bot credentials. It does not prove
that every other possible credential path is absent. A scoped issuer and
secure delivery remain required; do not copy an operator token into the host.

This check is **not** a complete security boundary: environment-supplied
proxies, trust stores, credentials, Git/GH executables, configuration races,
DNS and other host effects remain outside its proof. The host requirements
above still apply, and a failed or unknown operation must be reconciled before
retrying. The earlier local-remote fixture documents the earlier source before
this guard and does not qualify the new transport configuration.

The [resident acknowledgement fixture](evidence/awai150-resident-native-archive-20261103.json)
uses real Git and filesystem operations with stubbed head/retention admission,
clock and privacy response. It confirms that a changed head, refused repository
or leftover temporary acknowledgement does not publish an acknowledgement. A
separate native lock fixture covers contention for this operation. Neither test
establishes full resident or GitHub integration.


The draft also exports `github-checkpoint-restore-main`, which accepts a fresh
private work path, a fresh destination, the independently pinned checkpoint
identity and contract, repository and `gh` executable. It fetches the archive
ref, checks Git integrity, compares the manifest against canonical bytes or canonical bytes followed by one LF, verifies the
signed checkpoint and closure, then materializes a new checkout. It does not
select a current head, acquire a lease or start a bot. Failed and unknown work
remains for host reconciliation. The host must isolate paths and constrain Git,
credentials, environment and egress as for archive.

The [bounded local fixture](evidence/awai150-native-github-restore-20261103.json)
restored a signed synthetic checkpoint and refused wrong author, a tampered
block, a non-private response and an existing destination. It stubs privacy,
changes the remote to local Git and uses a byte-patched test compiler; it is
not a real GitHub or production recovery qualification. A later [local fixture](evidence/awai150-native-github-restore-legacy-20261103.json)
restored the operator-made synthetic manifest with one trailing LF as well as
the native no-newline form. Two LFs and a tampered block were refused. This
only addresses that specific manifest format: the earlier GitHub test ref uses
`itonami/fixture`, while this candidate fetches `itonami/archive`; it does not
automatically discover or migrate older refs. Real GitHub recovery and other
legacy formats remain unqualified.

The standalone archive entry point returns exit 0 only when the archive
operation reports a verified stored result, including a verified readback after
an unacknowledged push. Refusal and unknown results return exit 1; they do not
authorize an automatic retry. The [entry-point fixture](evidence/awai150-archive-exit-20261103.json)
uses a stubbed archive operation and is not an end-to-end GitHub test.

Archive now refuses lexical overlap between work and source store; restore
refuses overlap between work and destination. The shared absolute-path
validation rejects noncanonical slash and dot-component spellings. The
[isolated input-gate observation](evidence/awai150-archive-path-isolation-20261103/)
includes negative controls. The host must still prevent filesystem aliases and
concurrent path replacement, and this is not a production archive qualification.

A [Mac local-Git observation](evidence/awai150-mac-local-archive-20261103/)
exercised the current archive and restore path end-to-end using a synthetic
privacy response, a local bare remote and a privileged test-only spawn adapter.
It found that system Git config caused a refusal in the earlier diagnostic
host. A newer candidate disables system Git config in that host, after which
local archive and restore succeeded; repo-local helper configuration was still
refused. Real GitHub authorization and production transport remain unqualified.

The same diagnostic local-Git archive and restore path was subsequently checked
independently on [two Tailscale-connected Mac minis](evidence/awai150-two-minis-local-archive-20261103/).
The test used a local remote and synthetic privacy response on each host; it
did not test GitHub, cross-host storage or autonomous bot operation.
