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
