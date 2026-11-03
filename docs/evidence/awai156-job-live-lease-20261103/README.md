# OCI job: live lease around execution and receipt binding

The branch now combines its existing strict canonical lease, holder, job,
slot, epoch, revision and bounded-deadline checks with the live-clock and
post-cell recheck developed in [PR #576](https://github.com/kotoba-lang/murakumo/pull/576).
`run` requires the `clock` and `follow` arguments and a valid ballot; it
rejects a regressed clock before execution, and re-learns the identical valid
lease after successful execution before signing a receipt. `verify` requires
an independently provided expected job ID and binds it and the receipt's
node to the chosen canonical lease. Unrecognized top-level commands still
refuse. These CLI changes require caller updates; the previous documentation's
numeric-time and no-job-ID examples are explicitly historical.

A native ARM64 Mac source-derived run fixture used the current `jn-run` and
strict lease predicate, with synthetic clock, quorum, identity, cell and
signing. Matching lease produced a receipt; wrong job, rogue mode, numeric
clock and expired lease did not start the cell. Simulated quorum loss after
cell completion withheld the receipt. A mutant bypassing the post-cell
lease checks issued one. An independent native fixture used current predicate
and test sources: expected job/holder and malformed-lease cases passed, and a
mutant ignoring the receipt job failed. Current job-node and lease source,
composed with a historical dependency unity, passed the Amu source check.

No actual quorum, production clock behavior, model, cell, signature, receiver
fence or process governor was exercised. Withholding a receipt does not undo
any effect. Separate earlier loopback quorum evidence for PR #576 does not by
itself prove this composed branch's production behavior; requalify the exact
closure and update its callers before deployment.

- Job source SHA-256 `5f11c32640095f97ec16c2f38285697f387ba1211ba16edcdad8496e6e95bfda`; test source `0be538950117918e0661d1c029bf0a2aaccb0599e5abe42d8ef088259c6ce78c`.
- Run fixture source `18b9ab2a6b4be59879dfd0a75a252d62cfe0a9cca70ae2fb605bc0d1c7b46e50`; signed binary `d4170e94fabc437123d17c4dc692f0642ebaf5eb2657694ffd642a21b9f2663d`; signed mutant `b5d3e3b1ca1db19c2f64f0d895f6313507146a53fe3b1c7044fd84bd42f3f273`.
- Binding fixture source `06727e3680f5f799865a99c85880c2c55d8469c0399aac2b88b61bb1fa257e79`; signed binary `2512266b4f93772ce346c97eb76c5b6762328c8c471159d2ec8d3551c9c4e672`; signed mutant `0b200c8cff6b28708d1a5360796b7ffe545950862ec1280b68e516d7a114a2ce`.
- Source-check unity SHA-256 `a4886732bbf45e79f5af7497b9c3465165ce3f81b25bbf004ec12c60d1c19964`.
