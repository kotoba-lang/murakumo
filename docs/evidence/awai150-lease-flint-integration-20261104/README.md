# Lease and Flint resident integration diagnostic

The resident branch incorporates lease hardening from Murakumo #596 at
`7e8e82bdeb9fd6e24d3f84381600e7e93ede9d50` as a merge parent. This is a
branch integration, not a qualification or merge to main.

On 2026-11-04, local Darwin source-unity diagnostics passed for Flint response
validation and its invalid-response negative control; archive acknowledgement
and head clock guards with old-instance-collision negative controls; and the
job lease zero-clock, complete voter line, voter values and register shape
cases with a zero-clock guard mutation. The integrated lease diagnostic source
SHA-256 was `c1db787db8c0b7147331ad88f46dafa3fe29afb5054bbdae7a9333ff25fe78d4`.
These diagnostics compose selected current functions with historical fixture
dependencies, synthetic inputs and a previously built compiler and packager;
they do not qualify the complete source graph or the compiler.

The local Git checkpoint/archive fixture passed capture, verification,
restore, successor and recovery checks, including refusal cases. It uses a
privileged test-only spawn adapter and local repositories. It does not test
GitHub authentication, remote publication or production process isolation.

Outstanding qualification includes the native governor's real host supervisor,
resource accounting and process termination, multi-host lease quorum and
failure recovery, real GitHub-backed checkpoint publication, and end-to-end
agent/bot inference. No three-host deployment is established by these tests.
