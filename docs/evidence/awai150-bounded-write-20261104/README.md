# Native bounded checkpoint write diagnostic

On 2026-11-04 on jacob, macOS 26.3.1 (a), build 25D771280a,
`scripts/native/check-checkpoint-bounded-fixture.py` compiled and executed the
fixture generated from the current checkpoint component and its existing cases.
The explicitly pinned, strictly signature-verified compiler and diagnostic FS2
packager matched the prior local checkpoint receipt: compiler SHA-256
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`, packager
SHA-256 `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
The earlier receipt records its source SHA-256 as
`ea02a141eb6961582f7c0063b45cfcee9c01b321aebb0e7f7f5b1b5d75d3566a`.
These are diagnostic artifacts, not a qualified compiler or provider.

The component SHA-256 was
`ee6996b5919b1e266dc19edbe5193fa1510bfdb2360a30e3d8ea4117cd909478`,
cases SHA-256 `f867ccde8bed1a53cc767abe9ab79548dc1326c8c694367121a3122c11e90308`,
and composed source SHA-256
`6fbcd453075524de9cc4dd5b00b17527f612fa66d89e9eca40b3aaa3921262e6`.
The positive native fixture returned 0; its empty and 4097-byte files were
independently read and matched on the host. The comparison-removal mutant
returned 1 after creating matching empty and 4097-byte files. These cases
also exercise the existing 4096-byte and altered-data checks. The task-owned
temporary directory was removed by the harness. Generator SHA-256:
`4309f46ca433f2e676c28f871d733f553c539027e502f5936a29f3e91ded7524`;
harness SHA-256:
`8063c21201ea0e0ed26b7ef86e9f2b2e0786c5a4faeb2b155d0b4810a8e3edac`.

This is one local native fixture with a diagnostic provider and exclusive
fresh root. It does not test a current rebuilt provider closure, all FS2
failures, concurrent mutation, power loss, production state, real GitHub,
bot inference or resource governance. A trapped or lost provider remains an
unknown outcome. The harness is not currently a CI gate.
