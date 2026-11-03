# Existing request and FS2 existence observations

The bridge distinguishes explicit FS2 D responses: `0` means absent, `1`
means present, and other responses stop request installation. A present
request must be a nonempty canonical JSON object of at most 8192 bytes before
it can be compared with a consumed head or replaced. Empty, unreadable and
invalid prior content requires reconciliation. These checks do not make an
unreliable provider reliable.

On one Mac with the diagnostic FS2 runtime, fixtures extracted the actual
`br-install` function, used real FS2 D/A for local paths and stubbed CID, JSON
classification and `rt-save`. Normal absence and matching cases passed;
invalid content, empty content, an injected failed read and an injected
unavailable existence result were refused. The previous function proceeded to
the stubbed save for each negative case. Files remained unchanged; no actual
save, GitHub, lease, concurrency or bot turn was exercised.

A separate local permission test created an owned directory containing a file,
then removed directory permissions. Python `stat` confirmed permission denial.
The same diagnostic FS2 D returned `0` both for that inaccessible file and for
a missing path. Thus `0` does **not** reliably prove absence in this provider:
the new bridge check and the earlier `.tmp` check cannot distinguish that
error. Do not qualify the provider or autonomous cron based on these checks.
The test restored the temporary directory's permissions afterward. Other
providers and error types were not measured here.

The tested bridge source before subsequent comment-only changes had SHA-256
`1eaf2cd470250dc4b08b35de2f5d167c59fc1b8c9c2926bd524e4a7f5c98d682`.
The signed current fixture, previous fixture and FS2-status images had hashes
`e7c2263506ee628e5b19544f6cf2f3f6d5e101e4f8435b3cbb170f717278da52`,
`4a6cf2ab5d2cf6023f1ab51ead802e15a744e623b0f11d34db8a06599bcd76ab`,
`8bcafce5a1082bb4d79ccb661756ffacfb2a6191e47cab46f218891f9ab9f729`.
The compiler and diagnostic packager pins match the
[previous request observation](../awai150-request-pending-20261103/).
Fixtures are test-only, use task-specific temporary paths, and are not a
complete runtime, cryptographic, persistence or production qualification.
