# Current native archive and restore against local Git on Mac

This test composes the current checkpoint and GitHub archive source with the
prior Mac fixture's privileged, test-only Git spawn/path adapter. It replaces
`ga-private?` with a synthetic repository check and replaces both HTTPS remote
expressions with an absolute `file://` URL for a disposable bare Git repo. All
other archive and restore functions, including Git configuration checks, signed
closure verification, independent fetch and tree comparison, are current
source. The synthetic seed is all zeros, never a real key. No GitHub API,
credential, network transfer or production bot was involved.

On one local Mac the fixture captured a signed checkpoint from a synthetic Git
repo, refused the false-privacy case before work creation, pushed the verified
archive to the local bare repo, checked the remote tree independently, fetched
and restored it to a separate directory, and matched HEAD, staged/unstaged
changes, listed untracked file and mode. Wrong policy was refused. A separate
fixture extracted the actual Git configuration guard: a clean local repo was
accepted and a repo-local synthetic `credential.helper` was refused.

The initial attempt with Amu #1796 `b490eaaf` stopped at
`archive/git-config-refused`: its private host loaded a system Git credential
helper even without a caller HOME. No credential value was read. The successful
rerun used diagnostic host candidate `ed73d0fd`, which sets
`GIT_CONFIG_NOSYSTEM=1` in its minimal child environment. A per-repo helper was
still refused. This candidate disables all system Git configuration, including
any trusted proxy or trust settings; production transport requires its own
explicitly qualified configuration. Before the rerun only the task-owned
fixture directory was removed and recreated.

An ARM64 Linux bootstrap compiled the Mac fixture in a disposable 1-CPU,
2-GiB VM; the VM was removed. The signed Mac fixture SHA-256 was
`949cbb2818db1a75429173656bc48a2cd06bd984fef5b0041cf080391c64957d`,
its source `de7677d71db0ed8e48d8be914d690445deea83b44769c0adc9013932e24e6072`,
the raw main `a795751ec848cd92a733d9e4818d97ed234d7f6fe833bfbbe196816d61ba7c39`,
and the Linux compiler
`914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`.
The diagnostic packager was
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The config fixture source and image were respectively
`09672befcd133ed071ba929f321a90359e53833c5242061f355017814aa8a75e`
and `e685d5b39820ba42f3c959f6e6c030d16dc0a2ccff0da97d0a2ea61434347086`.
The exact assembled fixture is included; scripts use task-specific paths and
must be adapted for reuse.

This is not a real GitHub archive, private repository authorization, scoped
credential delivery, production process isolation, concurrency/power-loss
qualification, MTP inference or autonomous bot. The private host and test
adapter are privileged and cannot be exposed to untrusted guest code.
