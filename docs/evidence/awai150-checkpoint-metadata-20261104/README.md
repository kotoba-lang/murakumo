# Checkpoint bounded-read metadata validation

`cp-size` formerly checked only the fourth FS2 metadata field for regular-file
kind and parsed the second as a size. A malformed response with invalid mode,
block count or trailing fields could therefore be interpreted as usable.
The bounded read now accepts exactly four fields, validates the decimal mode,
size and block count and their length bounds, requires a regular file and a
mode in 0..511, and rejects everything else. The existing nine-digit size
bound is unchanged. This change applies to `cp-size` consumers; it does not
claim to harden every other metadata consumer such as `cp-kind`.

On one local Apple Silicon Mac, a signed native fixture built from the current
metadata functions and `test/native/checkpoint_metadata_cases.kotoba` accepted
normal and empty sizes and refused malformed, nonregular, excessive and
trailing-field values. An independently built mutant removing exact-frame
matching failed the same cases. Fixture source SHA-256 was
`f66787e5e069da0a45d2a503f1b197509feaaf615b6cf12933c25a2c582eda05`; signed
image SHA-256 was
`4cb22ae67fc13c929039174a3547dcfbf1b2a511f67555418d7ce49059a02a8d`.
The current checkpoint source SHA-256 was
`55810bc2db201e8c82bd80f267db5de1f2f53b5531c18618b7b75e09bafc57a8` and
case file SHA-256 was
`0b5ee644a456162d5cb51cebaba25069259b191faa85aa7c70468212fd184dcc`.

The repository's local Git diagnostic was rerun with current checkpoint and
archive sources: capture, verify, restoration, successor capture, recovery,
local bare-Git archive and restoration passed, with wrong-policy, existing
destination, corrupt-block and wrong-epoch refusals. It used the pinned
compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and privileged test-only Git packager
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The synthetic cases do not inject malformed bytes through the actual FS2
provider, and the local Git fixture is not a real GitHub transaction, a
production isolation boundary, a qualified provider, selfhost or governor.
The host must still exclude aliases and concurrent same-size replacement.
