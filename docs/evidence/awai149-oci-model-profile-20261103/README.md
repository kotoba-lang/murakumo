# Experimental OCI model and MTP artifact reader

This is the earlier two-layer diagnostic. The later [assets-layer revision](../awai149-oci-model-assets-20261103/README.md)
supersedes the profile with three required layers.

`model-artifact-main` connects the bounded model digest observer to a proposed
private OCI layout profile. The host supplies an absolute layout path and
independently pinned manifest, base and MTP SHA-256 digests and sizes. The
candidate requires a single index descriptor, a digest-matching OCI image
manifest, the OCI empty configuration blob, and exactly two ordered layers:

* artifact type `application/vnd.awai.model.bonsai.v1`;
* base layer `application/vnd.awai.model.bonsai.weights.v1`;
* MTP layer `application/vnd.awai.model.bonsai.mtp.v1`.

These media types are a proposal, not an adopted project standard. Both layer
contents are streamed and independently hashed against the supplied pins;
success is `model-artifact/digests-observed`, not permission to load or run.
Model blobs are limited to 16 GiB each and manifest/index reads to 1 MiB.

On one Darwin ARM64 Mac, ORAS 1.3.0 created a local OCI layout containing
1,025-byte and 513-byte synthetic layers. A source-unity native fixture used
the current model component, OCI layout reader, JSON parser, SHA-256 and
descriptor validator with the extended-range diagnostic FS2 host. It accepted
the intact layout. Corrupt base, corrupt MTP, altered manifest, wrong layer pin,
wrong index size/schema, wrong layer media type and an extra layer were each
refused. No registry or remote host was contacted. A separate native check
confirmed the original single-file entry still accepts a valid file and refuses
malformed input and a directory.

This is not an immutable snapshot: FS2 reads reopen paths and a concurrent
writer can change content after validation. The host must ensure exclusive
scope; source publisher identity, credentials, registry transport, model
format/compatibility, stable delivery to a loader and resource admission are
not established. The artifact producer, actual Bonsai weights, large-file
throughput, MTP execution, GPU access, and the fleet remain unverified.

| Artifact | SHA-256 |
| --- | --- |
| model reader source | `60c3fb9ea6cb93cc010adca3d410759dd900026e6bf694565a34e66fe2ffdc7c` |
| test unity source | `02d7e84ba1e014d39d97954628788c07ea99ccf39970a6869dcf097e468e8e39` |
| signed diagnostic fixture | `f16baefa7436c9f1f588342fcba8aa87a3d2d66daf82347702f5e0af5b9c3eeb` |
