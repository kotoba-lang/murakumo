# Bonsai MTPLX source observation

`source-observation.json` records the bytes published at the named immutable
Hugging Face revision. It is an observation for OCI packaging and review, not
an operational model lock, publisher authorization, or evidence that the
OCI loader accepts or runs the model.

On 2026-11-03, the 14 small files and pack manifest were fetched from that
revision and hashed. The two large weight hashes and sizes were obtained from
the revision's LFS metadata; their bytes were not downloaded for this record.
The observed README differs from the README entry inside the published pack
manifest. Both values are recorded; the pack manifest is unchanged. All other
small-file hashes and both LFS descriptors matched the manifest. The pinned
source revision is a third-party pack, not an assertion of Prism authorship.

Separately, on the same date, Dan's installed base and MTP files were read and
hashed against the LFS descriptors. The base read used one no-follow FD,
uncached reads, a 32 MiB/s cap and a memory-pressure stop threshold; file
identity and metadata were checked before and after. The 14 smaller files on
Dan matched the actual published revision, including the README that differs
from its manifest entry. The pack manifest also matched the published bytes.
These reads were not an atomic directory snapshot or authenticated inventory;
the directory additionally contained `.cache` and `.gitattributes`.

The MTP file was also read on Issachar and Levi, with matching SHA-256 and
stable metadata. Their base weights and full asset sets were not read. This
does not establish trustworthy publisher identity, sealed files after the
observation, verified assets on all ten Macs, a built OCI artifact, native MTP
execution, resource reservations or a running bot. No model or service was
changed. Track qualification and the README discrepancy in AWAI-149.
