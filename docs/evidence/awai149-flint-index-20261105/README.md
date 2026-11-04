# Flint index check: diagnostic evidence

The Flint profile staging and parent inventory checks now require a valid JSON
`weight_map`, with nonempty keys and values restricted to the two expected
shards, both of which must occur. Duplicate JSON keys and indexes over 1 MiB
are refused. The size limit is specific to this profile, not a general
safetensors format requirement. This checks references, not tensor headers,
tensor existence, model provenance, or whether Kapitsa will load the model.

On 2026-11-05 a read-only, strict-host-key SSH observation of naphtali's
existing index found 105,174 bytes, SHA-256
`003bb87e318dd21a6555bcb0053b8fd926a3337d81319c9d5ef7d469ee5079dc`.
Its 1,469 mappings referenced `model.safetensors` (1,328) and
`experts.safetensors` (141). Metadata checked before and after reading was
unchanged; this was not an atomic snapshot or publisher attestation. A
task-owned temporary copy was parsed by the native diagnostic in 0.579 s and
removed. No weights, inference, or service were started. The native image
hash was `e7d0c38d44437834fc3eff2abea3fb9db459674ebb552d8a5ef7744701c379be`.

Native unit tests accepted the synthetic valid index and rejected empty,
one-shard, traversal, non-string, duplicate-key and empty-key cases; a mutant
that accepted an unexpected shard was detected. The full synthetic staging
and inventory scripts accepted a valid example and refused a correctly pinned
archive whose index referenced `../experts.safetensors`, including on
inventory recheck. Other existing corrupted-file and inventory checks passed.
The ADI2 diagnostic also reproduces the existing counterexample: a frame and
expected identity both supplied for the wrong directory can be accepted;
the test-only host binding refuses it before the verifier. This is not a
production host binding.

The exact full-stage source-unity fixture SHA-256 is
`f54691c8c9c7678b211d287101e250fb6eddb20d4c0177ae564b3eeccbd73429`;
its diagnostic image SHA-256 is
`bffb6a47019a045a80779ed95cf84a3fff701d24f6e74040a613589cea56faed`.
The three `scripts/verify-flint-*-native*.py` consumers compare the relevant
fixture functions with the current source and use synthetic inputs. The
archived operator scripts are experiment records with local absolute paths and
external pinned diagnostic binaries; they are not deployment commands. The
real-index experiment additionally requires an independently obtained local
copy of the observed index, which is not included here.

The compiler was the pinned Darwin diagnostic compiler
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
the full-stage packager hash was
`df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5`.
The ADI2 helper hash was
`8f8e63db8e32c532f650bb9504ae4dad29c1e7bff79baf6824339dd1b51746aa`.
These are diagnostic artifacts, not qualified toolchain releases. Runs used
short CPU limits, without a hard memory limit; the JSON validator's work can
grow substantially with adversarial input. A byte limit alone does not
provide a CPU budget. Qualified process-tree resource control, trusted FD
custody, immutable reads, loader semantics, and authenticated runtime
integration remain prerequisites for production deployment.
