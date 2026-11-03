# OCI candidate on current resident parent

The former OCI draft branch diverged from resident at
`c2116b99fc9e26fa9d9de54673e007338dc38b86`. A new branch starts at resident
`8439a66637825fe5de80020910d14581eb3924db` and cherry-picks the 14 OCI
commits in their original order, without rewriting either existing branch.
All 32 paths changed by the original OCI branch were compared against original
head `6380a54248bad3c0707f681e1fe2be2b6c1507af`; `git diff --exit-code`
confirmed byte-for-byte identity. The current resident head is an ancestor of
this branch. This verifies source composition, not runtime compatibility.

A test-only slicer selected 451 functions from the current resident controller,
current OCI layout/digest/staging/recovery sources and a historical dependency
unity. Its entry selects either controller or model recovery, and the resulting
unity passed the pinned Mac Amu compiler's `check`. It was not built or run as
a full artifact; inventory is not wired into recovery. The unity was assembled
from existing local diagnostic dependencies and is not a reproducible release
closure. No model was read or deployed.

The OCI recovery remains conditional: it does not detect unknown extra files,
and trusted host directory identity, exclusive control, authenticated asset
mask and a qualified inventory/loader are not integrated. The pinned Linux
bootstrap also lacks the FS2 U required by the current job attempt path. Real
MTP, GitHub state, qualified runtime, receiver-side fencing and resource
governor are still required before deployment.

- Source-check unity SHA-256 `8caa97cdd96501010fb43b529bf026b208e7873c26141c3e80d975efcd298357`.
- OCI layout `d7db899f0d0aa903caef1b2aca82b6e875ea2fa75974ce70b150ff188dc47a74`; model staging `85323f565dd805b039ace18dc4bd7aef843ddefeded21052bcb537a7eb35189a`.
- Resident `69afb94a0ff46add964df7867a2f9308ab3b59fd256c9ee8ec74e4b932600df4`; job node `c56f95d099dc0bb928b9ec7309eb880152327b88bc76ae5b5467176953112620`.

## Reconciled parent follow-up

The original measurements above refer to the earlier resident branch. The OCI
candidate was subsequently replayed on resident commit `ba956ef`, itself based
on the current retention-refresh branch. Every path changed by the original OCI
commits matched the earlier OCI candidate byte-for-byte after replay. This is a
source comparison only: the combined artifact, CI, model loader and governor
have not been rebuilt or qualified.
