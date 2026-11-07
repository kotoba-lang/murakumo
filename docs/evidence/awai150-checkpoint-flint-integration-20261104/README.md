# Checkpoint and Flint diagnostic integration

The Flint resident branch incorporates checkpoint/archive branch head
`731be22` as a merge parent, including its bounded metadata and capture read
hardening. This is a dependency integration candidate; the upstream branch
remains a draft and its independent qualification is not implied.

On a local Apple Silicon Mac, the integrated checkpoint source passed the
existing privileged diagnostic for capture, local bare-Git archive,
verification, recovery and restore, including its wrong-policy, corrupted
block and existing-destination refusals. The fixture's injected empty-profile
read was refused, and its short-read guard-bypass mutant was detected. These
are test-only source injections, not provider or kernel I/O failures.

A separate signed native source-unity fixture combined the integrated
checkpoint/archive functions and the current Flint response and integration
cases with historical assembled dependencies. Both cases passed; the
error-response guard mutant failed. Fixture source/image SHA-256:
`84942f5f9d08e4bf1f2e3f4230220c13d68a926025980a0f5d6bf922152c20d5` /
`1aa0a12f36acbb356fb2dc45bf1397ab35d405f4e170834aaaf52973b05c59eb`.
Clock-zero acknowledgement and head-admission/replication fixtures also
passed with their guard-bypass mutants. Another fixture used the integrated
`pr-read-bounded` to read two previously saved Flint responses from its
private FS2 scope and checked the expected model and compact raw digests.
Fixture source/image SHA-256:
`d211a12ab0b296f54ffa4fb798d195658f2926ce2007685c1c0adbe2114230a1` /
`afdf547b6bc45955d91d0cfeec3b1d4d08a00d8b5ff35f5c7b482290e7758fc2`.

These diagnostics use an older pinned compiler, diagnostic providers,
historical dependencies and test-only orchestration. They do not establish a
complete source-locked selfhost build, authenticated model, live inference,
real GitHub archive, production host boundary or resource governor. The
private copies of saved responses made for the test were removed.
