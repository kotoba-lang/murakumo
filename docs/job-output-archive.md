# Verified agent-step output archive

AWAI-147: the ordinary job-node entry still emits a signed output CID only.
For the separately composed cron adapter, a host may explicitly put
`capture-output` at argument 23. In that mode the node also emits one
`output-base64url` line containing the stdout returned by the cell spawn.
The host must capture this stream in a private file; base64 is not encryption.
The runtime's bounded spawn may limit stdout, so this is the bytes returned
by that runtime, not a guarantee of unlimited or untruncated application data.
No output line is emitted by default, for a refused job, or on a duplicate.

`fleet_job_output.kotoba` composes the existing node and checkpoint modules.
The host supplies the expected signer DID, exact job ID and a private 0700
block store. The native verifier requires one receipt, signature and output;
valid canonical receipt JSON; exact pins; successful exit; a valid Ed25519
signature; canonical bounded base64url; and output bytes matching the signed
CID. It then writes the bytes using the existing checkpoint block-store
functions, checks them and fsyncs the directory. It does not establish that
a lease is still live, authorize the output's disclosure, or advance a head.

`scripts/itonami-job-output-archive.sh VERIFIER WORKROOT LOG OWNER/REPO DID JOB`
is an explicit host operation for an independently authorized, classified
output. It checks that the exact GitHub repository is private, snapshots the
input, runs the native verifier, and archives only the verified output,
receipt, signature and manifest to an immutable per-job ref. It never force
pushes. It fetches the ref independently, checks the tree and each file, and
re-verifies the output. A conflicting tree is refused. The GitHub ref is an
archive, not a distributed fence, accepted bot head or cron cursor. Separate
host policy must protect the log and decide which outputs can leave the node.

An isolated ARM Linux Podman VM ran an actual cron job and network-disabled,
read-only native cell. Explicit capture decoded to the same bytes as the
signed output CID; a default run emitted no output line. The native verifier
persisted the result and rejected changed output, invalid signature, duplicate
receipt and truncated JSON. A 16 KiB synthetic output also round-tripped.
The signature-bypass native negative control failed. The test result was
archived to a private GitHub ref, fetched and verified; a different validly
signed result for the same job ID was rejected as a conflict. The VM and
ephemeral token were removed. [Evidence](evidence/awai147-job-output-github-20261103.json).

The fixture used a public test key, three colocated voters and a rule-based
cell. It did not exercise MTP inference, a real production identity, automatic
archive scheduling, distributed effect fencing or a full bot state restore.
