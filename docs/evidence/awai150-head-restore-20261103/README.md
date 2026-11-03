# Head-driven checkpoint restore: local Git observation

On one local ARM64 Mac, the source-unity fixture used synthetic signed
checkpoints, a local bare Git repository, a stubbed privacy check, and a
privileged diagnostic FS2/spawn host. A concurrent head update first selected
one of two checkpoint successors. The head-restore candidate then fetched the
pinned head commit, read its pointer and manifest, independently fetched and
verified the signed archive closure, and restored a separate checkout. The
restored HEAD, index, working tree, listed untracked file and mode matched the
synthetic source repository.

The fixture refused an incorrect head commit, an incorrect author pin and an
existing destination. A separate normal non-force commit deliberately
corrupted the synthetic archive block in the task-owned bare repository; the
candidate refused it and did not materialize a destination. No remote GitHub
repository, bot credential, production data, autonomous bot or model was used.

`assemble.py` and `add-json.py` compose the prior diagnostic source and the
needed JSON validator; the remaining Python files are test-only setup,
observation and build harnesses. They use fixed paths and must be adapted
before rerunning. The earlier dependency and host fixture are documented in
[the head-update observation](../awai150-github-head-candidate-20261103/).
A trusted external head pin, anti-rollback authority, secure credentials,
process control and a current lease remain host responsibilities; the restore
operation itself authorizes no execution.

Head source SHA-256 `0f13d847cc8e6fb57e3f5b84ef4589c61cec1f2ec3adf67cf6c1c3d163cb6e0b`;
fixture source `274f50c7fb18e9ded69e87a0ebb3b696a5043c00092593ccac6593cdef105de3`;
signed fixture `a708f3af9d6047ead3f294d6758b90b5571dd6b39bb49d52ff7c1a786477f6f2`;
bootstrap compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic host packager `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
