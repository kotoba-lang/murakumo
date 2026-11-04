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
