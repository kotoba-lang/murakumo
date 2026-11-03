# Resident request replacement guard

The cron bridge already refused a pending `request.tmp`, but a separately
invoked periodic resident cycle did not. It could treat the visible prior
request as current while its replacement remained unresolved. The resident now
requires an explicit FS2 D absence result before either refreshing or running.
Unknown and present results both stop the cycle for reconciliation. A host
must still supply an FS2 provider that distinguishes errors from ENOENT and
must serialize all request writers with the resident.

On one local ARM64 Mac, a small fixture extracted the actual `rt-cycle` from
current and prior source and used the diagnostic FS2 provider with a real
private test directory. Other authority, JSON and inference functions were
stubbed. With no temporary file both versions reached the stubbed runtime-pin
refusal. With an ordinary temporary file and a mode-000 temporary file, the
new version returned `resident/request-unknown`; the prior version reached
the stubbed runtime-pin refusal instead. Fixture files were removed after the
check. This tests the local branch and actual lookup, not real inference,
full controller integration, race freedom, restart or distributed fencing.

The compiler and provider are those pinned by the earlier
[FS2 caller guard](../awai150-fs2-caller-guard-20261103/). Source SHA-256
`d664607aa1367c6977eabb640df78acbede9ea2f1732d380328b92f15f267ace`;
current fixture `c06dd5038878afeb53377c6fdcec2bfb0052eef2bc6001f3ba5137286d0d92d7`;
previous fixture `64880bc8c6223d7494e6b48937cac5a1b0f75beef4bf91997242b4ec3c76d483`;
current image `032b4c4aa1b3a6052f14c325ec2fa9e5c6f553ef3749a23a5da3d5ada8beac26`;
previous image `beb895aa22cc5a73d8401b2acd54b67261b0761eb5b23c542598db3b81ed1444`.
