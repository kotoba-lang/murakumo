# Recheck fenced recovery lease before inference

`rf-run` learned and admitted a recovery lease before restoring a checkpoint.
The previous implementation calculated a remaining deadline after restore but
did not re-learn the lease before inference. A changed or unavailable quorum
value could therefore go undetected until after the model step.

`rf-dispatch` records the previously admitted lease and loads its runtime,
then independently re-learns the same epoch. It checks the exact value,
current admission, retention deadline and remaining budget before calling
`pr-step-bounded`. An unsuccessful step returns without a post-step quorum
query; a completed step retains the prior post-inference checks. The record
write precedes the new lookup and does not itself authorize dispatch.

A native ARM64 Mac fixture copied the actual `rf-dispatch` source and stubbed
filesystem, quorum, clock, budget and inference. It passed matching lease,
changed and unavailable lease, expired retention, insufficient budget, and
record-write failure cases. Removing the lease guard in a mutant caused the
fixture to fail. The assembled controller source with the current fenced
recovery functions and historical dependencies passed Amu's source check.
Neither test ran a real restore, quorum, inference or full production artifact.
These checks do not provide an atomic effect fence, prove previous workloads
have stopped, qualify an independent multi-host quorum or govern resources.

- Fenced recovery source SHA-256 `bb3178f40c1338e8b7dc4b6d80e0d2739d1aa49cbe29ebd83612bc3da6781725`.
- Fixture source `6c21b384639683982d8ee49c058db8688bbbc44cca0bd487a3827b65939665ec`, signed binary `0447024b5dbffa5a87749ced693f6813013075fc883c1d1cfc2f272cbc69903c`.
- Mutant source `67a14ce33a7825a1505df34db5d2914de09974e5f142652b93a2811a164d06e8`, signed binary `07e60c920e21ab7011e51435740fba751f1d380bbcb813a6aa5902b370a04ada`.
- Source-unity check input `27de32fc33bb5b473eb60d92f8d9139a50bf717b1ebfbd8d518290afe6eccf5b`; compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`, diagnostic packager `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
