# Itonami cell launch: admission from the fetched bytes

Tracked in [JUN-288](https://linear.app/kotobalabs/issue/JUN-288), under
network-awai-apex ADR 0010 §2.2–§2.5 and com-junkawasaki/root
ADR-2610222140 §3, steps 4 and 5.

`kotoba/fleet_cell_launch.kotoba` decides whether this node may launch an
itonami release, before any container starts. It decides from the bytes the
node fetched, not from fields a host parsed for it. `launch-verdict` takes:
- the image index bytes;
- the cell's L1 digest and runtime class;
- the node's ledger state code and declared class mask, from its verified
  statements (see [node enrollment](node-enrollment.md));
- the host's allow mask.

It answers `launch/ok` or the first refusal, in this order:

| # | check | refusal |
| --- | --- | --- |
| 1 | the node is active | `launch/node-not-active` |
| 2 | the L1 digest is `sha256:` and 64 hex digits | org-oci-distribution's `oci/bad-digest` or `itonami/unsupported-digest` |
| 3 | SHA-256 of the bytes is that digest, checked before the bytes are parsed | `launch/digest-mismatch` |
| 4 | the bytes are strict JSON (org-ietf-jcs `jcs.reader`) | `launch/index-not-strict-json` |
| 5 | an image index whose artifactType is the itonami app type | org-oci-distribution's reasons |
| 6 | the `org.itonami.runtime-class` annotation is the L1 class | org-oci-distribution's reasons |
| 7 | the host's policy and the node's declaration allow the class | grant's reasons |

Check 4 is what makes the fields trustworthy. A release that gives
`mediaType` twice, first as a manifest and then as the index, would satisfy a
last-wins parser and fail a first-wins one. The strict reader refuses
duplicate keys, so every node reads the same thing or refuses.

`index-cid` gives the release's CIDv1 (raw codec), for the execution
receipt.

## Reproduce

Run this from this repository with clean pinned sources. Choose a new
task-owned directory for `G`. The shell container must already be a local
**linux/arm64** image (`--pull never`).

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
K=/Users/junkawasaki/github/kotoba-lang
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 457042ab741520980bceb5bf12970937d1bc8dba:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 1739f52f02fc90c28a1fa4a6c3a3f780caeff481893e3a1cd35badd8c227682e
shasum -a 256 "$G/root.elf"
{
  echo '(ns itonami-cell-launch-gate (:export [main]))'
  git -C "$K/org-nist-sha2" show ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b:kotoba/sha2/core.kotoba | sed '1,/\]))$/d'
  git -C "$K/org-ietf-jcs" show 6dfee43e80c273eb7d473c0a01e2779662a4a7c3:kotoba/jcs/reader.kotoba | sed '1,/\]))$/d'
  git -C "$K/org-oci-distribution" show 90463924db65eea4d9021dc6623105472282b5a9:kotoba/oci/itonami_artifact.kotoba | sed '1,/\]))$/d'
  git -C "$K/grant" show fa7e9b567286cd38cc4b229d8c980bfe676d9cce:kotoba/grant/runtime_class.kotoba | sed '1,/\]))$/d'
  git -C "$K/tsukasa" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/ledger_core.kotoba | sed '1,/\]))$/d'
  git -C "$K/tsukasa" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/node_state.kotoba | sed '1,/\]))$/d'
  sed '1,/\]))$/d' kotoba/fleet_cell_launch.kotoba
  cat <<'EOF'
(defn main [] :i64
 (+ (if (and (test-index) (test-classes) (test-digests) (test-digest-cid) (test-round-trip)) 1 0)
 (+ (if (and (test-class-codes) (test-masks) (test-default) (test-verdicts) (test-precedence)) 2 0)
 (+ (if (and (test-registration) (test-duplicates) (test-transitions) (test-retired-terminal) (test-ledger) (test-audit) (test-runtime-classes)) 4 0)
 (+ (if (and (test-target) (test-steps) (test-legal) (test-paths)) 8 0)
    (if (test-launch) 16 0))))))
EOF
} > "$G/gate.kotoba"
sed 's/(not (= (string-concat "sha256:" (sha256-hex index)) cell-digest)) "launch\/digest-mismatch"/false "launch\/digest-mismatch"/' "$G/gate.kotoba" > "$G/no-digest.kotoba"
sed 's/(not (json-valid? index)) "launch\/index-not-strict-json"/false "launch\/index-not-strict-json"/' "$G/gate.kotoba" > "$G/no-strict.kotoba"
sed 's/(not (= gv "grant\/ok")) gv/false gv/' "$G/gate.kotoba" > "$G/no-grant.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in gate no-digest no-strict no-grant; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
      echo "$c $(KEXE_STRUCTURED_REPORT=1 /gate/$c.elf | cut -c1-28)"
    done'
```

The reported result is a bitmask of five groups:

| bit | group | from |
| --- | --- | --- |
| 1 | OCI release rules | org-oci-distribution |
| 2 | runtime-class policy | grant |
| 4 | the ledger | tsukasa |
| 8 | node state | tsukasa |
| 16 | `test-launch` | this file |

`test-launch` covers every refusal in the table above. Its five index
documents and their digests come from Python `hashlib`. The CID was computed
in Python from the multibase rule.

Measured on 2026-10-23 (arm64 Linux 6.15 under podman, amu `457042ab` root):

| source | required reported result |
| --- | --- |
| unchanged | 31 |
| the digest is not checked | 15 |
| strict JSON is not required: the duplicate `mediaType` is read as a manifest | 15 |
| the host policy is not checked | 15 |

The gate image is 348,672 bytes and runs in 0.06 s with a 716 KiB maximum
resident set. The receipt is
[evidence/cell-launch-20261023.json](evidence/cell-launch-20261023.json).

## What this does not show

- Starting the container: rootless podman via wire 20, read-only rootfs, no
  network by default.
- The signed execution receipt.
- Fetching the index from a registry.
- Checking the image manifests and layers that the index lists.
- x86_64 execution.
