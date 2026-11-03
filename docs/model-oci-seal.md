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
the test and the actual filesystem flow have not run. Full-closure native
behavior, crash and fsync failures, real model-sized blobs, registry transfer,
and the existing consumer's recovery path require qualification before use.
High-load builds and deployments remain subject to the qualified governor.
