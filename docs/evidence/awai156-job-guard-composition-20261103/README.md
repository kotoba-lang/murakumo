# OCI job lease guard in the resident branch

The resident branch still contained the older standalone OCI job-node entry,
whose `rogue` test argument bypassed the holder check. The resident's profile
turn does not call this entry, but an OCI job-node built from the same branch
could still expose it. Commit `9c8d657` cherry-picks the existing fix from
[PR #555](https://github.com/kotoba-lang/murakumo/pull/555): the runtime bypass
is removed, and the selected lease is checked for canonical shape, holder,
job, slot, epoch, revision and deadline before the cell call. The historical
rogue verifier example is built as a separate test-only mutant.

A local ARM64 Mac native fixture combined the current job lease predicate and
its test with this branch's current lease core and selected historical JSON
and related dependencies. Matching lease, wrong holder/job/slot/epoch/revision,
expiry, excessive duration and malformed values were checked. It exited 0;
a mutant dropping the job-class comparison exited 1. The test evaluates the
predicate, not a live cell, quorum or the complete job-node runtime.

This cherry-pick carries the scope of #555 only. The separate
[PR #576](https://github.com/kotoba-lang/murakumo/pull/576) also checks the
lease after execution and requires a live clock; that stronger path is not
included at the time of this test. A later commit combines those behaviors
with the strict guard; see [the subsequent evidence](../awai156-job-live-lease-20261103/).
Neither guard is an atomic sink fence or a qualified resource governor. Real production authority, workload termination, effect fencing,
MTP inference and cross-host quorum remain unverified. Resolve and validate
the overlapping PRs in dependency order before release.

- Job source SHA-256 `d3428be90e2bac4ad229fd69638f88fda62496c912a9a4ddcdc7f4a4617e6e81`; lease core `76bba32a0e8c5057069b302139f5b55e1fd396ed9fc749e7a882d7806d491279`; test source `0331c086e77464f9a3e4f7adf91768f256b8528a4ea1d5bceb8ad9cc82d9936c`.
- Fixture source `8bfe426a8a13a432d4a3ee028387ff5ee086d5e49e1a42ad2822b64885a0be10`, signed binary `f70e89ceb425ce749328f9a9100d8e63876952dea9f09a898c72815f9dd2fbca`.
- Mutant source `cbd86a4eee437d3e88ae55bb047119e9d7609fd5adb7ca695bd8f86326ddcfb9`, signed binary `c52e3b4697a5d5c4458adc899c207a3fc8820e67abecfa42133a2ed9246ccdb9`.
