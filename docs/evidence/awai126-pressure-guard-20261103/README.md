# Physical placement pressure guard

`cell-capacity-reason` previously accepted pressure 0 (unobserved), negative
pressure and unsupported values when the inherited legacy predicate did not
reject them. The physical-pool proposal now also requires a supported observed
pressure (1, 2 or 4) and max-pressure (0/default, 1, 2 or 4). Existing legacy
eligibility semantics are unchanged. An independent fresh pressure reading,
verified pool identity, all resource dimensions and durable reservation are
still host responsibilities; this decision is not a launch permit.

On one local ARM64 Mac, a source-unity fixture reused the earlier capacity
gate's dependency prefix, replaced its cell-placement component with the
current source, and ran freshness, eligibility, state-path and capacity tests.
It exited 0. The test-only variant removing the pressure observation check
exited 1. Cases include absent, negative and unsupported pressure, invalid
maximum, valid pressure and the existing default maximum. The signed fixture
uses the bootstrap compiler and diagnostic FS2 packager, not a release runtime.
No actual telemetry, reservation, workload or Mac mini was involved.

Cell source SHA-256 `0677c37d079df2bdede566dfd8103e81cfe472f175c032aa8d01854d10b054be`;
earlier gate template `9c89821bdd8cb8a82a093b27c9ebe1d2e170516f8051fd37bf2bf6df5993bb54`;
unity fixture `ff06a1233e58306c2f3d49c3247710808ebefbe2dfb33c14008189c7fa38ba62`;
signed fixture `f59ab4350cf31b5c856f49d4e5d1fb50864f76a07b17f1d90d12f1b917e0c017`;
signed mutant `810a6bd2c4cc6dc50630b9d24dfa53716236e305c91984d20c9ea93e3a80353d`.
Compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
packager `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
