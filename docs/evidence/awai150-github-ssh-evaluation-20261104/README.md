# Synthetic native checkpoint archive to GitHub over SSH

On 2026-11-04 a task-only fixture composed the current checkpoint and archive
source and ran a signed Darwin native diagnostic image. It captured a synthetic
Git working tree with staged, unstaged and untracked content, verified its
closure, archived the blocks and manifest to an evaluation branch of the
existing private `cloud-itonami/itonami-bot-state` repository, fetched and
verified the Git tree, restored the checkpoint, and compared head, index,
worktree, untracked contents and mode against the input. Wrong policy,
existing destination, corrupt block and wrong recovery epoch controls refused.
A successor and recovery record were also captured and checked locally.

The evaluation used ref
`refs/heads/itonami/eval-native/aca95834e261d92e/bafkreieabusrnlkitfvqejfa5qh7ny7yqcusgkfxfgbzezrhxwggj5tyrq`.
Independent GitHub API reads observed commit
`84acede32396535c1a47a31ad94a5026dbd3b4e5`, no parent, and tree
`84f318b34a3c864dd23738e56039534cc1382361`. The recursive tree had eight
block blobs and one manifest and was not truncated. GitHub separately reported
the repository private and the operator's permission ADMIN. The evaluation
branch is retained as synthetic evidence; it is not the canonical bot head.

The fixture uses a privileged test-only Git adapter. It stubs the in-program
privacy check and substitutes a fixed SSH Git URL and evaluation ref for the
production HTTPS URL and archive ref. SSH used the operator's existing GitHub
identity, not a bot-scoped credential. The fixture and observer are local at
`/private/tmp/awai150-github-native-eval/`; their SHA-256 values are
`84fe9200e41858005d0638216fc05687fdf9118eae1edf6bd471625870552b71`
(generator) and
`ff6997afbda6a5f7d6795a69a184ccff41ee7667c46de8bc62d95eb5a9c3d988`
(observer). The composed archive fixture SHA-256 was
`e42c99961d1f9854c18168bb81ff092050e2c9956272a217fbc147d58945f541`;
compiler and packager were the pinned diagnostic images
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` /
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.

This validates one small synthetic SSH GitHub round trip with that adapter. It
does not exercise the production HTTPS path, in-program GitHub privacy check,
bot authentication, source-locked release runtime, supervisor, actual inference,
concurrent publication or crash/failure recovery. No model or persistent
service was started; native governor and end-to-end bot qualification remain
open. Do not reuse this temporary fixture as a privileged production adapter.

## Real in-program private-repository check

A second evaluation kept the actual `ga-private?` function and called the real
GitHub API from the native diagnostic image. The operator-authenticated test
adapter permitted the local `gh` binary and used `/usr/bin/env` to supply
the operator's HOME, because the diagnostic host's minimal child environment
alone returned no GitHub login. It did not put a token in arguments. An
inaccessible synthetic repository was refused without creating archive work;
the actual private state repository passed. This is not a public-repository
negative test or a bot identity/least-privilege test.

With that check, another synthetic staged/unstaged/untracked checkpoint was
archived over the test-only fixed SSH transport, fetched, verified and restored
with matching snapshot. Independent GitHub API readback observed root commit
`c4eeea2abd43dae3b9e90bacdfd88871216ecfbb` and tree
`0494dbcdc72432bd5c197cb0802de2d073870e36`, with eight block blobs and one
manifest. The retained evaluation ref is
`refs/heads/itonami/eval-native/8a487a530b7867f8/bafkreiahjn44pvge2iaashj5pkazxcthajd6wqdaf3x35esrx6prornz2a`.
Wrong policy, existing destination, corrupted block and wrong recovery epoch
controls were also refused. GitHub remained the real private repository; the
fixture's remote URL and ref are still substituted, and the Git adapter remains
privileged and test-only.

The composed archive fixture SHA-256 was
`8912a29d37f0165b94a5c41503e48e0f02a1f1c41a4890ced0007d9a845adda9`.
The local observer/generator SHA-256 values were
`5a7344e4f63b5293a0e7a4d2c96f94463adb59042c7d8793016cf04bdaa3331f` /
`118cb16c8aaff738b3b53511fa594cf2a4794a279fc881bdf8f81b252f8e1bf3`.
They are test-only files in `/private/tmp/awai150-github-private-eval/`.
This does not validate HTTPS credential handling, a production host adapter,
autonomous bot credentials, real inference or resource-governor qualification.

## HTTPS and real private/public checks

A third local diagnostic kept both the actual `ga-private?` implementation and
the source's `https://github.com/<repo>.git` remote expression. Its privileged
test-only adapter used the operator's existing `gh` login. For Git HTTPS it
set an isolated HOME and a temporary task-owned `GIT_ASKPASS` script that
supplied the operator credential to Git through the normal askpass pipe, with
terminal prompting and global/system Git configuration disabled. The script
was part of the temporary fixture and was removed with that fixture. It did
not place the credential in arguments or persist it as a file. This shell
adapter is a test instrument, not an operational dependency or credential
boundary. The diagnostic host itself does not provide this authentication.

The actual native privacy predicate refused an inaccessible repository and the
public `cli/cli` repository before creating their staging directories. It
accepted the existing private `cloud-itonami/itonami-bot-state` repository.
The native checkpoint and archive source then captured a synthetic working
tree, pushed the archive over HTTPS, fetched and verified it, and restored a
matching staged/unstaged/untracked snapshot. Wrong policy, existing
destination, corrupt block and wrong recovery epoch controls refused.

Only the archive ref was substituted for a unique evaluation namespace:
`refs/heads/itonami/eval-native/10976b9c886e4a79/bafkreiftsnasczw4r6rifnyliaifpnvaiyywgioq3behvbidypbjt5p57a`.
An independent GitHub API read observed root commit
`eb46c9d8bc58a132510759b3b1232c553544ebc5` and tree
`aa115b212c8fe77e9b1fbffd6fed555f47439a62`; the recursive tree contained
eight block blobs and one manifest and was not truncated. The branch remains
as synthetic evidence and does not change the canonical bot head.

The composed archive fixture SHA-256 was
`63a5730377b7d0f4c644392bcc8e121c7d64b20f129765e8f057c6a815780cc3`;
the local observer/generator SHA-256 values were
`162d4103df70592890acc78189ea6339593d7f4cdc2521161112786ed49edc21` /
`1ea4c4d772ae9c59d06d71f7ac8fbf621ef6fe6d6403cddccd647a72bd5609ff`.
They are test-only files in `/private/tmp/awai150-github-https-eval/`. The
compiler and packager remain the diagnostic pins above. This one synthetic
round trip does not qualify a production host credential broker, bot identity,
concurrent or crash recovery, the full source-locked runtime, resource governor,
or actual agent/bot inference.
