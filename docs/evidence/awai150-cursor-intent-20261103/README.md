# Cursor intent at the resident controller and bridge

The separate cron cursor candidate now persists `.intent` before renaming
its pending cursor. The integrated controller previously checked only
`.pending`; its idle and due paths could proceed while the intent remained.
The standalone bridge did not check either marker. Both now require explicit
FS2 absence for both, before planning or request installation. The controller
also rejects archive staging and sensitive paths that overlap the cursor's
`.intent` or `.last`, and the bridge rejects its request or temporary request
overwriting those cursor paths. Filesystem ownership and serialization remain
host responsibilities.

On a local Darwin ARM64 Mac, test-only native fixtures used the candidate FS2
provider in a private temporary directory. Other dependencies, including cron
planning, archive, inference and cursor advancement, were stubbed. With no
marker, the controller idle case returned `cron-resident/idle`. With an intent,
both idle and due returned `cron-resident/cursor-unknown`, leaving the cursor
unchanged and without invoking the test cell. A work directory equal to the
intent path was refused by the actual path predicate. The standalone bridge
returned `bridge/cursor-unknown` and nonzero for both `prepare` and `complete`.
The no-intent due control trapped in the diagnostic host before the fixture
reported a result; the due execution path is therefore unverified here.

This is not evidence of a deployed controller or of end-to-end autonomy. A
real composed build must incorporate the new itonami-agent cursor source from
PR #164 and these bridge/controller sources, and separately qualify the FS2
provider, durable recovery, GitHub archive, fencing and resource admission.
Old generated source-unity fixtures do not acquire these checks automatically.

SHA-256: controller source `4137d8945609d71fa876efce8922bcb5fa13acf55e25900e2117754cbc9401f1`;
bridge source `f404c159fbf2ad891425616898a501be2e953523113d0d3fe18dd4a2467968cf`;
controller fixture `cce08b293cfcd41024c473b86fb1b4618ff3309647e6bb4bbf1cc6ac372201af`;
bridge fixture `e78bdc17d3f890a06f04c2d66f904bc3c3b88d7a206879ef983dc8a07669b708`.
