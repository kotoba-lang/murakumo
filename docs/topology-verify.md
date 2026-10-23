# The operator-signed topology, verified in a static image

Tracked in [JUN-287](https://linear.app/kotobalabs/issue/JUN-287), under
com-junkawasaki/root ADR-2610222140 §1. L1 is the operator's signed intent,
and every node checks it itself, with no API server.

`kotoba/fleet_topology_verify.kotoba` verifies the document that
`src/murakumo/infer/replicated_topology.cljk` signs and replicates, inside a
loaderless static image. `topology-verdict` takes the document bytes, the
trust, a threshold and the revision held now. It answers `topology/ok` or
the first refusal:

| # | check | refusal |
| --- | --- | --- |
| 1 | at most 262144 bytes (`bounded!`) | `topology/too-large` |
| 2 | strict JSON (org-ietf-jcs `jcs.reader`) | `topology/not-strict-json` |
| 3 | a `payload` object | `topology/no-payload` |
| 4 | the payload has a canonical form here | `topology/not-canonical-domain` |
| 5 | a well-formed trust and threshold | `topology/bad-trust` |
| 6 | at least `threshold` **distinct** trusted keys signed the canonical payload | `topology/needs-signatures` |
| 7 | `schema` is 1 | `topology/schema` |
| 8 | the revision advances (fleet_intent_core's `revision-verdict`) | `intent/bad-revision`, `intent/not-advancing` |

**What is signed.** As in `verify!`, the signed bytes are `encoded
payload`: the canonical re-encoding of the parsed payload, with keys sorted
and no spaces. They are not the bytes as they arrive, so they are rebuilt
with `json-canonical-bytes` ([kotoba-lang/org-ietf-jcs#2](https://github.com/kotoba-lang/org-ietf-jcs/pull/2)).
A document may therefore arrive in any key order or spacing, and still verify.

**Who counts.** This follows `quorum-signers`:
- a legacy `signature` counts for whichever trusted key it verifies against;
- each `signatures` entry `{key, signature}` counts only for its own key id;
- a key counts once, however often it signed.

**The trust** is `id=did:key:…,…`, with 1 to 8 entries and distinct ids and
keys. These are the operators' public keys as did:key, the form the host
derives from its PEM files once.

Checking the signatures comes before reading what the payload says. Payload
validation beyond `schema` and `revision` (nodes, gateways, roles, policies)
stays with `validate!` and `fleet_intent_core`.

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
pin() { git -C "$K/$1" show "$2:$3" | sed '1,/\]))$/d'; }
{
  echo '(ns itonami-topology-gate (:export [main]))'
  pin org-nist-sha2 ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b kotoba/sha2/core.kotoba
  pin org-ietf-ed25519 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa kotoba/ed25519/selfhost.kotoba
  pin org-ietf-jcs 6331a9b461f5858be484b9d6859f314f9c3a2510 kotoba/jcs/reader.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/ledger_core.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/node_state.kotoba
  sed '1,/\]))$/d' kotoba/fleet_node_enrollment.kotoba
  sed '1,/\]))$/d' kotoba/fleet_intent_core.kotoba
  sed '1,/\]))$/d' kotoba/fleet_topology_verify.kotoba
  cat <<'EOF'
(defn main [] :i64
 (+ (if (and (test-wire) (test-names) (test-strings) (test-forbidden) (test-class) (test-trust) (test-cell)) 1 0)
 (+ (if (test-did) 2 0) (if (test-topology) 4 0))))
EOF
} > "$G/gate.kotoba"
sed 's/(and (= (json-kind b e) 1) (= (tv-text b e "key") id)/(and (= (json-kind b e) 1) true/' "$G/gate.kotoba" > "$G/no-key-id.kotoba"
sed 's/(cond (< n threshold) "topology\/needs-signatures"/(cond false "topology\/needs-signatures"/' "$G/gate.kotoba" > "$G/no-threshold.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in gate no-key-id no-threshold; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
      echo "$c $(KEXE_STRUCTURED_REPORT=1 /gate/$c.elf | cut -c1-26)"
    done'
```

The reported result is a bitmask:
- `1`: fleet_intent_core's tests;
- `2`: enrollment's did:key tests;
- `4`: `test-topology`.

`test-topology` runs nine documents. Each is serialized with unsorted keys
and spaces, and signed over the canonical payload by OpenSSL 3 with RFC 8032
keys 1 to 3. The script that made them is
[`evidence/topology-vectors-20261023.py`](evidence/topology-vectors-20261023.py).
The cases:
- a single legacy signature;
- a 2-of-3 quorum;
- one of two needed;
- a revision changed after signing;
- operator 3's signature filed under operator 1's id;
- a duplicate `payload` key;
- a float in the payload;
- one key signing both as the legacy signature and as an entry;
- schema 2;
- in addition: a non-advancing revision, and a duplicate, empty or too-weak
  trust.

Measured on 2026-10-23 (arm64 Linux 6.15 under podman, amu `457042ab` root):

| source | required reported result |
| --- | --- |
| unchanged | 7 |
| an entry counts whatever its key id says | 3 |
| the threshold is not checked | 3 |

The gate image is 479,744 bytes and runs in 12.5 s with a 9,804 KiB maximum
resident set. Every verify runs in `arena-scope`. The receipt is
[evidence/topology-verify-20261023.json](evidence/topology-verify-20261023.json).

`fleet_intent_core`'s private `prefix?` is now `fi-prefix?`, because tsukasa
defines a `prefix?` of its own and this unity composes both. Its gate still
reports 127.

## What this does not show

- Fetching the document from peers.
- Persisting the held revision.
- The itonami node-admission fields that ADR-2610222140 proposes for L1
  (admitted declaration digest, cordon, retire). They are not in the payload
  schema yet.
- Floating-point numbers in a payload.
- x86_64 execution.
