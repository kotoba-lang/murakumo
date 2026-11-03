# Model artifact supporting assets

The earlier two-layer proposal did not contain the configuration or tokenizer
needed to use the weights. A read-only pinned-key SSH directory listing on
Issachar showed the historical Bonsai MTPLX directory contains `config.json`,
`tokenizer.json` (12,809,320 bytes), `tokenizer_config.json`,
`chat_template.jinja`, `hadamard.json`, `mtplx_runtime.json`, a model index,
generation/processor configs, the two weight files, and license/notice and
manifest files. No file content, model execution, service or configuration was
changed. This is a point-in-time listing, not version or integrity proof and
not an exhaustive specification of the runtime's actual required closure.

The experimental OCI profile now requires three independently pinned layers:
base weights, MTP weights, and an assets tar blob with media type
`application/vnd.awai.model.bonsai.assets.tar.v1`. It streams and hashes all
three. Missing, extra or wrong-media layers and duplicate pins are refused.
The asset tar's structure and contents are **not parsed or extracted**; digest
success does not establish that required files are present or safe to unpack.
The media types and packaging remain proposals, not a standard or installer.

On one local Darwin ARM64 Mac, ORAS created a synthetic three-layer layout
with tiny model/MTP fixtures and a tar containing synthetic config/tokenizer
files. A native fixture composed actual JSON, SHA-256, OCI descriptor and
diagnostic FS2 code. The valid layout passed; corrupted base, MTP and assets,
missing or additional layers, wrong media type or pins, altered manifest and
wrong index size/schema were refused. The single-file digest entry also passed
a regression. The tar was not extracted; no registry or real model was used.

The host must still provide a stable private snapshot and separately validate
publisher, archive contents, safe extraction, model/runtime compatibility,
resource admission and the real inference path. None is qualified by this test.

| Artifact | SHA-256 |
| --- | --- |
| model reader source | `f27fe3316227d96bf1240059ec85f88dcb9f090ce252f7bc0257831b0e78109c` |
| test unity source | `11a126ceac6c83114350a9d131355f30c16218eb2350be459019c39f3bb592e4` |
| signed diagnostic fixture | `a60081945d4086e44c1512085ba3d352e7531c9b383e52ac321fdf4326af61ec` |
