# Itonami node enrollment: signed declaration and heartbeat

Tracked in [JUN-287](https://linear.app/kotobalabs/issue/JUN-287), under
com-junkawasaki/root ADR-2610222140 §1. The ledger state is a function of L1
and L2, with no coordinator.

`kotoba/fleet_node_enrollment.kotoba` verifies the two statements a node
signs with its own did:key. Only then does tsukasa's `node-target` see their
facts.

- **Declaration.** The node, its did:key, failure-domain, availability and
  runtime classes. The SHA-256 digest of the declaration is what the operator
  admits in L1.
- **Heartbeat.** The time, the digest of the declaration it attests, and the
  node's own drain mark.

`signed-target` takes the signed declaration, the signed heartbeat and the L1
facts (admitted digest, cordon, retire). It returns node-target's answer.
- A declaration whose signature does not verify gives `node/refused`.
- A heartbeat that does not verify does not count as fresh.
- A drain mark counts only when it is signed. Otherwise the node is
  `node/stale` and is never `node/self-drain`.

## Wire forms

Both texts are the JSON that murakumo already signs for observation
documents: `encoded` in `src/murakumo/infer/replicated_topology.cljk` sorts
the keys and adds no spaces. Each text carries its own `kind`, so it cannot be
read as another statement.

```text
{"availability":"always-on","did":"did:key:z6Mk…","failure-domain":"site-a","kind":"tsukasa.declaration/1","node":"b70","runtime-classes":2}
{"at":1792500000000,"declaration":"<sha256 hex>","did":"did:key:z6Mk…","drain":false,"kind":"tsukasa.heartbeat/1","node":"b70"}
```

- **did:key** is strict:
  - `did:key:z`, then base58btc;
  - no leading `1`;
  - exactly 34 bytes, 0xed 0x01 followed by a 32-byte Ed25519 key.

  An X25519 key, a truncated or extended value, a character outside the
  alphabet and non-ASCII text are refused by name.
- **Signatures** are base64url without padding, 86 characters, and canonical:
  the last character's four unused bits are zero.
- **String fields** are printable ASCII without `"` or `\`, at most 128 bytes.
  So the text needs no escapes and is the same bytes `JSON.stringify` gives.
- **Precedence in `declaration-problem`.** ledger_core's `first-problem`
  reasons come first, with the same reason text and order. Then come the
  fields, the did:key, the signature's form and the signature itself.
- **Order in `heartbeat-problem`.** It checks the signature over what the
  heartbeat says. Then the heartbeat must name the current declaration and be
  inside `[now - ttl, now + skew]`.

The node's side is in the same file. `node-did`, `sign-declaration` and
`sign-heartbeat` produce the same bytes as OpenSSL.

A node's did:key is the host's identity. Admitting or placing a node never
admits a Cell (mon CID × agent key, network-awai-apex ADR 0011).

## Reproduce the source-unity native gate

Run this from this repository. Use clean pinned Git sources, not a shared
checkout's uncommitted files. Choose a new task-owned directory for `G`.
Podman must already have `alpine:3`, because `--pull never` refuses a
download.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
TS=/Users/junkawasaki/github/kotoba-lang/tsukasa
ED=/Users/junkawasaki/github/kotoba-lang/org-ietf-ed25519
SHA=/Users/junkawasaki/github/kotoba-lang/org-nist-sha2
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 457042ab741520980bceb5bf12970937d1bc8dba:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 1739f52f02fc90c28a1fa4a6c3a3f780caeff481893e3a1cd35badd8c227682e
shasum -a 256 "$G/root.elf"
{
  echo '(ns itonami-node-enrollment-gate (:export [main]))'
  git -C "$SHA" show ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b:kotoba/sha2/core.kotoba | sed '1,/\]))$/d'
  git -C "$ED" show 2e919bc7f17e8b0e731dfa8f71cd977fa90347e1:kotoba/ed25519/selfhost.kotoba | sed '1,/\]))$/d'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/ledger_core.kotoba | sed '1,/\]))$/d'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/node_state.kotoba | sed '1,/\]))$/d'
  sed '1,/\]))$/d' kotoba/fleet_node_enrollment.kotoba
  cat <<'EOF'
(defn main [] :i64
 (+ (if (and (test-sha512) (test-public) (test-sign) (test-verify)) 1 0)
 (+ (if (and (test-registration) (test-duplicates) (test-transitions) (test-retired-terminal) (test-ledger) (test-audit) (test-runtime-classes)) 2 0)
 (+ (if (and (test-target) (test-steps) (test-legal) (test-paths)) 4 0)
 (+ (if (test-did) 8 0) (+ (if (test-signature-encoding) 16 0) (+ (if (test-declaration) 32 0)
 (+ (if (test-heartbeat) 64 0) (+ (if (test-signed-target) 128 0) (if (test-signed-path) 256 0))))))))))
EOF
} > "$G/gate.kotoba"
sed 's/(not (verify? (did-key-public did) (en-bytes (declaration-text node did fd av rc)) (en-sig-bytes sig)))/false/' \
  "$G/gate.kotoba" > "$G/decl-unsigned.kotoba"
sed 's/(< at (- now ttl)) "enroll\/heartbeat-stale"/false "enroll\/heartbeat-stale"/' "$G/gate.kotoba" > "$G/no-stale.kotoba"
sed 's/(and (> (vector-count b) 9) (= (vector-at b 9) 49))/false/' "$G/gate.kotoba" > "$G/did-leading-one.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate \
  docker.io/library/alpine:3 sh -c '
    for case_name in gate decl-unsigned no-stale did-leading-one; do
      ./root.elf compile /gate/$case_name.kotoba --target aarch64-linux --output /gate/$case_name.elf --format static || exit 1
      chmod +x /gate/$case_name.elf
      KEXE_STRUCTURED_REPORT=1 /gate/$case_name.elf || exit 1
    done'
```

`--output` must name a new file, so remove old `.elf` files before you run it
again. Check the **reported result**, not only a zero exit. It is a bitmask
of nine test groups:

| bit | group | from |
| --- | --- | --- |
| 1 | SHA-512 and Ed25519 (RFC 8032) | org-ietf-ed25519 |
| 2 | ledger: registration, transitions, audit, runtime classes | tsukasa |
| 4 | node state: target, steps, legality, paths | tsukasa |
| 8 | did:key: round trips, the specification's example, every refusal | this file |
| 16 | base64url signature form | this file |
| 32 | declaration: text, digest, signature, the node's own signing, precedence | this file |
| 64 | heartbeat: text, signed drain mark, other key, other declaration, future, stale | this file |
| 128 | `signed-target`: every node-target answer from signed inputs | this file |
| 256 | a node's life from signed inputs, through `next-state` | this file |

Measured on 2026-10-23 (arm64 Linux 6.15 under podman on macOS arm64):

| source | required reported result | what drops |
| --- | --- | --- |
| unchanged | 511 | nothing |
| the declaration's signature is not checked | 351 | declaration 32 and signed target 128 |
| an old heartbeat counts as fresh | 63 | heartbeat 64, signed target 128 and path 256 |
| a did:key with a leading `1` is accepted | 503 | did:key 8 |

The mutations must produce exactly these lower values. They are not passes.

The vectors are RFC 8032 test keys 1 and 2. The texts, SHA-256 digests and
signatures come from OpenSSL 3.6 and Python `hashlib`, independently of this
code. The script that made them is
[`evidence/node-enrollment-vectors-20261023.py`](evidence/node-enrollment-vectors-20261023.py).
Hashes and results are in the
[receipt](evidence/node-enrollment-20261023.json).

## Budget and what this does not show

- The gate image (377,344 bytes) runs in 36 s on an idle VM, and in 91 s at a
  load average of 7.6 from other containers. Its maximum resident set is
  408,360 KiB, measured with busybox `time -v`.
- Each Ed25519 operation keeps about 10 MiB that the image does not reclaim.
  That is about 40 operations here, and the 15 operations of the ed25519
  tests alone reach 96 MiB.
- So a long-lived node agent cannot verify heartbeats indefinitely in one
  process with the current runtime. That has to be solved in the runtime or
  the agent's process model before JUN-288's agent runs continuously.

No Node, NBB, JVM, hosted compiler, C compiler or C loader runs in this gate.
It is a source-unity gate, so it does not show:
- project linkage;
- x86_64 execution;
- the L1 operator signature;
- peer transport or persistence;
- two independent nodes reaching the same state;
- an OCI launch.
