# Flint OCI seal candidate

`model-flint-oci-seal-main` defines a separate experimental OCI artifact type
for a Flint model file, an experts file, and a metadata tar, in that order.
The three layer media types are `application/vnd.awai.model.flint.model.v1`,
`application/vnd.awai.model.flint.experts.v1`, and
`application/vnd.awai.model.flint.assets.tar.v1`. They are not Bonsai MTP
layers. All digests, sizes and the expected manifest digest are independent
host inputs; the fixed synthetic test values are not model pins.

The seal entry expects a private prepopulated OCI layout and streams each blob
through the existing digest observer before sealing. It then checks the
resulting manifest and blob digests. It does not create or inspect the
metadata tar, verify the Kapitsa manifest or directory inventory, transfer
blobs, publish a registry artifact, load a model, or provide crash recovery.
The caller must quarantine partial layouts and establish exclusive ownership
and path identity. No sealed result is permission to start inference.

The separate `model-flint-oci-stage-main` candidate accepts that pinned layout
and stages the two weights plus an exact set of seven regular metadata files,
including `.kapitsa/manifest.json`. Its tar parser checks names, duplicate
entries, padding and the pinned tar digest; partial stages remain unknown.
It reports `staged-unverified-manifest` because it does not validate Kapitsa's
manifest semantics, independently inventory the directory, atomically publish
the stage or recover an interrupted operation. It is not a loading grant.

The observed converted Flint 0.2.26 directory contains `model.safetensors`,
`experts.safetensors`, metadata files and `.kapitsa/manifest.json`. A tar
producer, manifest verifier, inventory and publication/recovery procedure are
still required. Their exact content pins must be independently established; file
sizes in Kapitsa's manifest are not content hashes. Real hashing, copies and
deployment require the qualified native resource governor. This candidate's
source checks do not establish native execution or OCI interoperability.
