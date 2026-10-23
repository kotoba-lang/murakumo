# Agent-step jobs: identity and one confirmation per job

Tracked in [JUN-289](https://linear.app/kotobalabs/issue/JUN-289). A bot's
turn is a murakumo job for itonami nodes. JUN-289 also asks that a job given
to two nodes is confirmed once, through the fence.

`kotoba/fleet_agent_step.kotoba` is the part that can be decided now:

- **`job-text` and `job-id`.** The job is the canonical JSON of the issue's
  fields, and its id is that text's SHA-256:
  `{"input":<CID>,"kind":"itonami.agent-step/1","policy":<CID>,"profile":"sha256:…","rail":…}`.
  - `profile` is the bot profile's OCI digest.
  - `input` and `policy` (the capability bound) are CIDv1.
  - `rail` is an itonami inference rail, `murakumo` or `mishima`, and never a
    Hermes model provider (itonami-agent, "Two CLIs").
- **`job-problem`.** It answers `job/ok`, or names the first field that is
  not one of those.
- **`receipt-text`.** The node's signed execution receipt for one run:
  `{"at":ms,"did":…,"epoch":n,"exit":n,"job":<id>,"kind":"itonami.agent-step-receipt/1","node":…,"output":<CID>}`.
- **`receipt-verdict`.** It answers `job/confirmed` only for a receipt signed
  by the job lease's current holder, at the lease's current epoch, using
  sahai's fence. A stolen or re-taken lease has a higher epoch, so a job that
  two nodes ran is confirmed once. Otherwise it answers one of
  `job/bad-signature`, `job/not-the-holder`, `job/stale-epoch` or
  `job/bad-output-cid`.

Placement (runtime class, capability) belongs to
[cell placement](cell-placement-integration.md) and
[cell launch](cell-launch.md). Running the turn belongs to
[cell run](cell-run.md). As in `sahai.fence`, the lease registry and the
clock are the caller's.

## Reproduce

Run this from this repository with clean pinned sources. Choose a new
task-owned directory for `G`. The shell container must already be a local
**linux/arm64** image.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
K=/Users/junkawasaki/github/kotoba-lang
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 457042ab741520980bceb5bf12970937d1bc8dba:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 1739f52f02fc90c28a1fa4a6c3a3f780caeff481893e3a1cd35badd8c227682e
shasum -a 256 "$G/root.elf"
pin() { git -C "$K/$1" show "$2:$3" | sed '1,/\]))$/d'; }
{
  echo '(ns itonami-agent-step-gate (:export [main]))'
  pin org-nist-sha2 ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b kotoba/sha2/core.kotoba
  pin org-ietf-ed25519 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa kotoba/ed25519/selfhost.kotoba
  pin org-ietf-jcs 6331a9b461f5858be484b9d6859f314f9c3a2510 kotoba/jcs/reader.kotoba
  pin org-oci-distribution 90463924db65eea4d9021dc6623105472282b5a9 kotoba/oci/itonami_artifact.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/ledger_core.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/node_state.kotoba
  sed '1,/\]))$/d' kotoba/fleet_node_enrollment.kotoba
  pin sahai ba19195945c6f4482b46818d0570aa840f9ef681 kotoba/sahai/fence.kotoba
  sed '1,/\]))$/d' kotoba/fleet_agent_step.kotoba
  cat <<'EOF'
(defn main [] :i64
 (+ (if (and (test-beats) (test-expired) (test-claim) (test-epoch)) 1 0)
 (+ (if (and (test-index) (test-classes) (test-digests) (test-digest-cid) (test-round-trip)) 2 0)
    (if (test-agent-step) 4 0))))
EOF
} > "$G/gate.kotoba"
sed -e 's/(not (= did holder-did)) "job\/not-the-holder"/false "job\/not-the-holder"/' \
    -e 's/(not (= epoch holder-epoch)) "job\/stale-epoch"/false "job\/stale-epoch"/' "$G/gate.kotoba" > "$G/no-fence.kotoba"
sed 's/(not (= epoch holder-epoch)) "job\/stale-epoch"/false "job\/stale-epoch"/' "$G/gate.kotoba" > "$G/no-epoch.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in gate no-fence no-epoch; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
      echo "$c $(KEXE_STRUCTURED_REPORT=1 /gate/$c.elf | cut -c1-26)"
    done'
```

The reported result is a bitmask:
- `1`: sahai's fence tests;
- `2`: org-oci-distribution's tests;
- `4`: `test-agent-step`.

`test-agent-step` exercises the job and the fence. The job, CIDs, the job id
and the receipts come from Python `hashlib` and OpenSSL 3 (RFC 8032 keys 1
and 2), using the script
[`evidence/agent-step-vectors-20261023.py`](evidence/agent-step-vectors-20261023.py).

- **The job.** Its text and id, both rails accepted, a Hermes provider
  refused, and a bad digest, input CID or policy CID refused.
- **The lease.** b70 takes the free lease at epoch 1. k16 is held off while
  it lives, and steals it at epoch 2 once it has expired.
- **Both nodes ran the job.** k16's receipt is `job/confirmed` and b70's is
  `job/not-the-holder`, so exactly one is confirmed.
- **Other receipts.** k16's own receipt after the lease moved on to epoch 3
  is `job/stale-epoch`. A receipt signed by the wrong key, or altered after
  signing, is `job/bad-signature`.
- **Form.** Receipts are canonical JSON (`json-canonical?`).

Measured on 2026-10-23 (arm64 Linux 6.15 under podman, amu `457042ab` root):

| source | required reported result |
| --- | --- |
| unchanged | 7 |
| the fence ignored (no holder or epoch check): both receipts confirmed | 3 |
| the epoch not checked | 3 |

The gate image is 475,648 bytes and runs in 3.7 s with an 8,856 KiB maximum
resident set. The receipt is
[evidence/agent-step-20261023.json](evidence/agent-step-20261023.json).

## What this does not show

- Distributing the job to nodes.
- The lease registry: the quorum register of ADR-2610071730 P3b.
- Running a bot profile's turn with an inference rail.
- Consent before inference leaves the node.
- x86_64 execution.
