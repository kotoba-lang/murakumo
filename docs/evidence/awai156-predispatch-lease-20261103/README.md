# Recheck resident lease after checkpoint restore

`rt-cycle` learns a lease before `rt-turn` materializes a checkpoint. Restore
and local setup can take time, so the former path could use the old lease to
start inference without learning the quorum value again. The deadline budget
could reject an already expired value, but it could not detect a changed or
unavailable quorum value while that local deadline was still in the future.

`rt-dispatch` re-learns the value for the same epoch after restore, compares it
with the originally admitted value, validates the holder and current deadline,
checks the retained head deadline, and requires at least one second of budget
before calling `pr-step-resident`. A failed dispatch does not trigger the
post-inference lease query. The existing checks after inference and before
publication remain. This narrows the stale-admission window; it is not an
atomic fence on the inference receiver and does not prevent the lease changing
after the check or stop a previously started workload.

On a local ARM64 Mac, a native fixture copied the actual `rt-post-problem` and
`rt-dispatch` source; quorum lookup, clock, lease validation, runtime inputs
and inference were deterministic stubs. The held lease reached the inference
stub; changed or unavailable values, an expired retention deadline, and a
nonpositive remaining budget did not. The fixture exited 0; a mutant that
removed the dispatch admission guard exited 1. A separate source-unity build
using the current controller components and historical dependencies passed
Amu's source check. It was not compiled or run as a full production runtime.
No actual quorum, cache, checkpoint restore, inference, distributed fencing,
or process/resource governor was exercised in these tests.

- Resident source SHA-256: `69afb94a0ff46add964df7867a2f9308ab3b59fd256c9ee8ec74e4b932600df4`.
- Fixture source `5a9be1acc442e19fe1872d81016606d3bc94c895c747c5a4385fbd76276f6270`, signed native binary `d82f549950c35688e728b4a1f305d969d1b92e8edcbabc186ce74964b53d456e`.
- Mutant source `cef7b6c6ffe9933928bbb2e4701dfc566d79ef2b31595cf23d0fd71c927bb851`, signed native binary `85bb97f03144bf7b949f3e768f292550bdfe0cf382d612eb0d4f8224b581560f`.
- Source-unity check input `1b6d495fe9c2241af9d185ce8baf149e6f4759a2a46f97d42e7834bfc4051950`; bootstrap compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and diagnostic packager `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

## Resident turn ordering

A second native ARM64 Mac fixture copied the current `rt-turn`, `rt-dispatch`
and `rt-post-problem` together. Quorum, clock, filesystem, restore, inference
and replication were stubs that emitted trace markers. A held lease produced
`quorum`, `inference`, then the post-inference `quorum`; changed or missing
lease, expired retention and inadequate budget produced one `quorum` and no
`inference`. Thus the refused paths did not issue the post-inference lookup.
The held case deliberately made replication fail and returned
`resident/proposal-retained`. An old-order control replacing the pre-dispatch
call with a direct inference call produced `inference` before `quorum` even
for changed, missing, expired and insufficient-budget cases.

All cases in both native binaries exited zero because the fixture reports its
trace for the test observer to check; exit zero alone is not the assertion.
This tests the composed turn ordering with stubs, not the actual quorum,
filesystem, model or production runtime. The fixture source SHA-256 is
`317687bf5e186c11602f5f5beed2c76ed16c7abd1f9bc0488604500e99aca664`, signed
binary `a6285f96813040152f46fcfe48c557c027756fe6c1f7092f704c9400043f381c`;
control source `69ee1810ee8e47a0c39d17af99cd4fc4b1cf0e3f6c9f3b6057346d2b147be25a`,
signed binary `c9887b0a8723fe57df144d3e2f1fa3fd373cb6d05ce87cf00598e27d5d61a538`.
