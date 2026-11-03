# Tunnel core native syntax and fixture

The prior source failed the current Mac Amu bootstrap check because it used
implicit two-argument `record-get` and integer conditions in `trim-ws`.
Record descriptors are now explicit throughout this component and whitespace
predicates are booleans. The full source passes `amu check` on the local ARM64
Mac. Its complete source, with a test entry point added, was also compiled via
the native bootstrap and diagnostic packager. The resulting test image passed
SSH status, whitespace/digit parsing, SCP destination, port forwarding and
command-wrapping examples; an old status-logic mutant failed.

The test appends its own `main` and uses the full component source, but only
the called functions were exercised. This is a bounded local diagnostic,
not qualification of every export, the legacy CLJK adapter or its tests, a
real SSH host, secure marker framing, release compiler provenance or a bot.
The runtime remains dependent on an independently authorized host and the
existing line-based marker is not a distributed receipt.

Source SHA-256 `e040b35e26d93cb100653b74bb053d027b7b4535f87929c0e1e8b1b2ea6512e1`;
fixture `ca1317effa10eedc0325b09360f693012e162eae2b9aa0cd597da24fd74fadf7`;
signed fixture `a748fc9bce35cd1926013731d2ceb0a98778b94a63cec16cdb6e857cb21b8d1c`;
signed mutant `11a3e6a9b24cdc0dea68f853e529c6e2a24bdb4d7fbfdaa00267a5d8edc46240`.
The compiler and diagnostic host match the earlier
[bridge lock fixture](../awai150-standalone-bridge-lock-20261103/).
