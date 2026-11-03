# Archive-bound cron cursor recovery candidate

The cron resident controller accepts an explicit eleventh argument `recover`.
Under the existing local writer lock it validates the cursor, pinned schedule,
work source, request and path isolation, then checks that the admitted signed
head has consumed the deterministic request for the current slot. It requires
the cursor recovery preflight before attempting archive work. The archive
operation independently reads the pinned checkpoint back; its confirmed root
must match the unchanged admitted head and the local archive acknowledgement.
Only then does the controller call `cc-recover`. It does not run inference or
dispatch a job. A normal invocation without this argument retains the existing
cycle. Unknown states and failures stop rather than retrying automatically.

The target slot is validated using the previous minute as a synthetic cursor:
for a valid minute-granularity cron slot the next matching slot after that
minute must be the target. The canonical trigger binds schedule, input and slot;
it does not bind that synthetic previous cursor. Local intent validity and
the archived signed head are separately required. The host must supply pinned
paths, credentials, network policy, exclusive filesystem access and a fresh
archive work directory. The component does not create a distributed fence,
resource reservation or authenticated host runtime.

On one local Darwin ARM64 host a native diagnostic fixture composed the
actual controller functions with stubbed schedule, head verification, archive,
and cursor recovery. It returned recovered for the positive case; missing
completion, pending cursor, failed intent preflight, invalid binding, expired
head, failed archive and mismatched archived root all returned unknown and
did not call the recovery stub. A separate diagnostic exercised the actual
cursor recovery with the Darwin FS2 candidate. These are not a real GitHub
readback, signature, multi-host lease, crash, inference or production test.
The current partial source-unity passed the Mac native compiler's `check`;
its historical dependencies are not fully provenance-locked. This source
depends on itonami-agent PR #164 at commit `4db1705` or a descendant.

| Artifact | SHA-256 |
| --- | --- |
| controller source | `64becea8a1498595d4f18ab34afae1a48d0e16fc0e1be05e0425682d04c3a616` |
| test fixture | `417e267bdf79f654cbb5017a83bcabdda4edbd16e2580c2dafcc1c2a873f0419` |
| signed fixture | `9fbb0d4a1da37f67688eb127fd362118119f444d898b1fa96bcbd8e703caae11` |
| checked source-unity | `d5e1ad118bc576094a6c17ae085f1e08415aad75727c767ac9a6be49ba2d2159` |
