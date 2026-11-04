# Current native checkpoint archive against local Git

On 2026-11-04, the current checkpoint and archive sources were composed and
run on jacob, macOS 26.3.1 (a), build 25D771280a. The initial native archive
attempt returned `archive/git-config-refused`: `ga-config-safe?` called
`cp-git`, which itself supplied `-c` settings such as `core.hooksPath`, and
the guard rejected those same configuration names. A separate Git command
confirmed the four internally supplied names appeared in the check.

The candidate now inspects configuration via the pinned `/usr/bin/git`
spawn path without those internal overrides and with includes disabled. The
include directives themselves remain visible to the name check. In a fresh
private root, native checks accepted a clean config and refused a synthetic
local credential helper and include directive. The current checkpoint was
captured, archived to a disposable local bare Git remote, independently
checked against the remote tree, fetched and restored; HEAD, index, worktree,
untracked file and mode matched. A synthetic false-privacy result and wrong
policy were refused. Capture, closure and recovery record controls also passed.

Checkpoint source SHA-256:
`ee6996b5919b1e266dc19edbe5193fa1510bfdb2360a30e3d8ea4117cd909478`;
archive source SHA-256:
`a5e8808cd21e2d68f3b79e9ed74f7ec1919805532db61f8ecd9403ab2d74846e`;
composed archive fixture SHA-256 for this run (contains the unique temporary
path): `4eee3f6e6f0d419ad411629cff8910884f647f48236d21225a2c1de01028f0a4`.
Generator SHA-256:
`b98ffb170be10d9f4d8a84fd6ff04a389eb3fd1bbc1a8c1a33683c24703e616e`;
harness SHA-256:
`28257dfad2093f36180ccf70c6fb2519cd3d763f3193ede88e647e98cb1800ff`.
The signed diagnostic compiler was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
the signed diagnostic host packager was
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.

This uses a privileged test-only Git adapter, a stubbed privacy check, a local
file remote and synthetic data. It does not exercise real GitHub transport,
credentials, hostile config races, a release provider, a governor, crash
recovery or bot inference. Configuration validation does not replace a trusted
host's environment, executable, filesystem, credential and egress controls.
