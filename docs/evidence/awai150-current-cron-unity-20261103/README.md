# Current cron source: Linux native composition and sequencing

An ARM64 Linux static image compiled from `unity.kotoba` in a disposable 1-CPU,
2-GiB VM. Its entry point is the actual `profile-cron-controller-main`. The
source combines the 149 function definitions in the current checkpoint,
recovery, resident, cron bridge, archive and controller files at Murakumo
`a05126e`, checked for byte-for-byte equality with those source definitions,
with a historical assembled dependency source. A missing configuration returned
exit 2 and `cron-resident/config-refused`. This verifies composition and one
refusal, not the full dependency provenance or a successful production turn.

A separate small source extracts the current controller's path, marker,
advance and cycle functions. It uses real FS2 files for a marker, a cursor
and a submission counter, while stubbing the plan, retention, archive,
resident turn, signed head, lease and cursor implementation. The VM test
confirmed success advances the cursor; failed refresh or initial archive and
idle do not dispatch; an uncertain first dispatch leaves the cursor unchanged
and a retry does not dispatch twice; and a failed post-turn archive leaves the
cursor unchanged until a subsequent confirmed completion, without rerunning
the turn. This does not test main's lock, actual archive or inference, signed
state, real quorum, process crash, power loss, or multi-host concurrency.

The first sequence test failed because its test-only append target had not
been pre-created. A fresh VM rerun after correcting the fixture passed; its
output is in `sequence-result.txt`. Both VMs were removed. The source and
observers use temporary paths and must be adapted for a rerun. Python is test
orchestration only, not part of the native operating path.

`unity.kotoba` SHA-256
`cbdc90d79a3a17e4f6407445b2622650f6809f993f216a73d58a04fe3e24eec8`;
full controller image
`e9a6cb6aa27ee811876b390d730fea71dc646a55160c9db5aa9655ebc13ed127`;
`sequence.kotoba`
`b2707373d925ecce5e542f25de9377fc8260a67d362fa0936d17cf1f0fd0d0dd`;
sequence image
`2fd792fb65e8c02e818762115f8ea46ce5a3fb6b996cc73108c29b89af82c353`;
compiler
`914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`.
The historical assembled dependency input used by `assemble.py` had SHA-256
`483f9cd1cd4c103fe476ee3540d2ca7730d44d739333a14f5c14b0c27b29ee80`;
its task-local location is not part of this evidence. The resulting unity source
is included so its exact compilation input is available.
