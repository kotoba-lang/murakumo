# Experimental model OCI layout completion

`fleet_model_oci_seal.kotoba` completes a private, host-provisioned OCI layout
whose `blobs/sha256` directory already contains the base model, MTP model and
asset tar. The caller independently pins their three digests and sizes and the
expected manifest digest. The component hashes all three blobs, constructs the
proposed Bonsai profile's OCI empty config, manifest, `oci-layout` and index,
and then invokes the existing `ma-check` verifier on the resulting layout.
It writes the index last and syncs each created file and parent directory.

The host must supply an exclusively owned, non-aliased private layout, prevent
concurrent mutation, and reconcile or quarantine any partial result. File
operations reopen paths and do not provide a stable snapshot against a hostile
writer. The three blobs must already exist: this component neither constructs
the asset tar nor copies or downloads model weights. A matching tar digest is
not a check of the tar's internal asset inventory; the existing staging and
inventory boundaries remain separate. It does not authenticate the publisher,
publish to a registry, qualify a loader, reserve resources or launch a model.

The descriptor test has an independently calculated compact-JSON digest. The
selected source and historical native dependencies passed Amu source `check`;
the new seal test and its actual filesystem flow have not run.

The test-only `test/native/model_oci_existing_consumer_test.py` independently
constructs the same descriptor shape with small synthetic bytes. On one Mac,
the previously built native publication fixture with SHA-256
`d5a852ecc05069d9fc93e044714a369c0a665d9b2c6db17c0a665b97155120c1`
accepted that layout, restored its synthetic blobs and eight assets, and
refused a wrong base digest. The fixture's `ma-check`, `md-observe`, and
`ma-stage-checked` matched the current source. This exercises the existing
consumer with a Python-created layout; it does **not** execute the new seal
component or prove its exact output, crash behavior or production ABI.

Full-closure native
behavior, crash and fsync failures, real model-sized blobs, registry transfer,
and the existing consumer's recovery path require qualification before use.
High-load builds and deployments remain subject to the qualified governor.
