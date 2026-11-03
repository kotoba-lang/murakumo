# Dispatch marker reuse with filesystem reads

On one local ARM64 Mac, a source-unity fixture extracted the current
`pr-run-decision` and the request/marker status and content bindings from
`pr-step-range`. It used the diagnostic FS2 provider and real task-owned
files. The unit state was supplied as a test argument, and CID comparison was
stubbed as text equality. This is not the actual systemd or inference path.

An empty directory with a not-found unit returned `profile/new-unit`. A
matching request with a missing marker, a marker with incorrect contents, or
an unreadable mode-000 marker returned `profile/submission-unknown`. A valid
marker permitted reuse for both running and exited unit states. A mutant
without the marker reuse requirement incorrectly reused the missing and
corrupt cases. The observer restored permissions and removed its own test
files. The check depends on an FS2 provider that does not turn access errors
into absence, plus host-owned paths and serialization.

Fixture source SHA-256 `469ffb742df3739f469336f0c9c612616d6c81508a97b5c70fc8233b05a0ac9d`;
mutant `59f9b29182900cbadb8ba0f9dfb626083055f59ee7e32adc17f6be5129e49531`;
signed fixture `423ee5a032bb2c82890e447ff2692696259906f6d2771ccfa08aafc126cef723`;
signed mutant `1978e4a0e8770e25c7c08befbbf2a9d28feba80d88bd2cf0709bda774f7b3595`.
Compiler and diagnostic host match the earlier
[FS2 caller observation](../awai150-fs2-caller-guard-20261103/). Real
systemd/curl, host crash, real model and distributed fencing were not tested.
