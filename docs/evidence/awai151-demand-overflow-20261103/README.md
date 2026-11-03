# Accelerator demand boundary observation

The current Kotoba planner propagates negative or unrepresentable demand as
`-1`; invalid revoke inputs and unsafe scaled comparisons return code 5
(`invalid-observation`) and do not request eviction. Standalone `idle?`,
`challenger-wins?` and `starved?` also fail closed on their invalid inputs.
The score deliberately refuses some mathematically representable results if
its intermediate multiplication exceeds i64; it does not saturate and does
not interpret that refusal as zero demand. Existing valid normal-range policy
is unchanged. This is not a complete resource governor, host monitor, process
lifetime boundary or authorization to evict existing work.

On one local ARM64 Mac, the actual planner source and boundary test were placed
in a small unity fixture, compiled with the native bootstrap root and packaged
with the diagnostic FS2 packager. The compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and the packager SHA-256 was
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`. The signed
fixture exited 0 on normal values, negative/missing values, intermediate and
addition overflow, zero weights and scaled-comparison overflow. A mutant that
removed the negative holder-demand check exited 1. The test does not use actual
GPU metrics, existing services, Mac mini inference or fleet admission.

Planner SHA-256 `4e984ebc335af00ed47b03d0cfd0471885f7f1a7df30e6069e45c385a02d0f3a`;
test `a160c8df03659a5b03e78bf6fe05abb942d6f540718872c2811f548f09a0561d`;
signed fixture `0c1a30d0ac4622d40e12c41b18b2266399c28f3225f76501621f83ab374b1353`;
signed mutant `bb55fec1591f85510b81210e8cffb09722437229dbcae33f60bac3b799415a64`.
