# The lease register as separate processes

Tracked in [JUN-289](https://linear.app/kotobalabs/issue/JUN-289), under
ADR-2610071730 P3b. [Lease core](lease-core.md) holds the register's rules.
`kotoba/fleet_lease_node.kotoba` runs them as separate processes, voters and
a proposer, each a loaderless static image, over the runtime's TCP wires.

- **Voter** (`voter <port> <state file> <idle ms>`). For each connection it:
  1. reads one request line;
  2. decides with `prepare-ok?` / `accept-ok?`;
  3. writes its new state to the state file (wire 35), **before it
     replies**;
  4. replies one line and closes.

  It exits after `<idle ms>` with no connection. The state file is its only
  memory, so a voter that is started again keeps its promise and its
  accepted value. Every request is handled in `arena-scope`.
- **Proposer** (`proposer <mode> <t> <id> <value> <port>…`):
  - `full` prepares, adopts the value accepted under the highest ballot (or
    else its own), accepts, and on a majority prints `chosen <value>`;
  - `prepare` runs only the prepare phase;
  - `accept` runs only the accept phase, with the value given.
- **Lines.** Requests are `prepare <t> <id>` and `accept <t> <id> <value>`;
  replies are `ok|no <pt> <pid> <at> <aid> <value>`. A value is a
  `lease-text`, canonical JSON with no spaces. An accept whose value is not
  canonical JSON is refused.

## Reproduce

Run this from this repository. Choose a new task-owned directory for `G`.
Each voter may listen only on its own address, and the proposer may dial
only the three voters.

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
  echo '(ns itonami-lease-node (:export [main]))'
  git -C "$K/org-nist-sha2" show ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b:kotoba/sha2/core.kotoba | sed '1,/\]))$/d'
  git -C "$K/org-ietf-ed25519" show 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa:kotoba/ed25519/selfhost.kotoba | sed '1,/\]))$/d'
  git -C "$K/org-ietf-jcs" show 6331a9b461f5858be484b9d6859f314f9c3a2510:kotoba/jcs/reader.kotoba | sed '1,/\]))$/d'
  git -C "$K/tsukasa" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/ledger_core.kotoba | sed '1,/\]))$/d'
  git -C "$K/tsukasa" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/node_state.kotoba | sed '1,/\]))$/d'
  for f in fleet_node_enrollment fleet_intent_core fleet_topology_verify fleet_lease_core fleet_lease_node; do sed '1,/\]))$/d' kotoba/$f.kotoba; done
  echo '(defn main [] :i64 (lease-node-main))'
} > "$G/node.kotoba"
# careless voters: accept ignores the promise
sed 's/(not (= value "")) (>= (ballot-cmp bt bid pt pid) 0)/(not (= value "")) true/' "$G/node.kotoba" > "$G/careless.kotoba"
cat > "$G/run.sh" <<'EOF'
VA='{"class":"qwen-text","epoch":1,"node":"b70","revision":7,"slot":0,"until":1792500900000}'
VB='{"class":"qwen-text","epoch":1,"node":"k16","revision":7,"slot":0,"until":1792500900000}'
voters() {
  for v in 1 2 3; do
    env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:740$v \
      /gate/$1.elf voter 740$v /gate/$1-v$v.state 1500 > /gate/$1-v$v.log 2>&1 &
  done
  sleep 0.3
}
prop() {
  env KEXE_ALLOW=37,38,45,46,47 KEXE_CAP_RESOURCES_45=127.0.0.1:7401,127.0.0.1:7402,127.0.0.1:7403 \
    /gate/node.elf proposer $2 $3 $4 "$5" 7401 7402 7403 2>&1 | grep -v '^{:status' | sed "s/^/$1: /"
}
for img in node careless; do
  rm -f /gate/$img-v*.state
  echo "== voters: $img"
  voters $img; prop "1 A prepare (100,a)" prepare 100 a "$VA"; wait
  voters $img; prop "2 B round   (101,b)" full 101 b "$VB"; wait
  voters $img; prop "3 A accept  (100,a)" accept 100 a "$VA"; wait
  voters $img; prop "4 A round   (102,a)" full 102 a "$VA"; wait
  for v in 1 2 3; do echo "voter $v state: $(cut -c1-60 /gate/$img-v$v.state)"; done
done
EOF
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in node careless; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done
    sh /gate/run.sh'
```

Expected output, measured on 2026-10-23 (arm64 Linux 6.15 under podman, amu
`457042ab` root; `…` is the rest of the lease):

```text
== voters: node
1 A prepare (100,a): prepared 3
2 B round   (101,b): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
3 A accept  (100,a): no-majority accept 0
4 A round   (102,a): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
voter 1 state: 102 a 102 a {"class":"qwen-text","epoch":1,"node":"k16","rev
voter 2 state: 102 a 102 a {"class":"qwen-text","epoch":1,"node":"k16","rev
voter 3 state: 102 a 102 a {"class":"qwen-text","epoch":1,"node":"k16","rev
== voters: careless
1 A prepare (100,a): prepared 3
2 B round   (101,b): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
3 A accept  (100,a): chosen {"class":"qwen-text","epoch":1,"node":"b70",…}
4 A round   (102,a): chosen {"class":"qwen-text","epoch":1,"node":"b70",…}
voter 1 state: 102 a 102 a {"class":"qwen-text","epoch":1,"node":"b70","rev
…
```

Every step starts the voters again, so their state comes only from their
files.

- **Correct voters.** B's lease (k16) is chosen. A's accept under its old
  ballot is refused by all three voters, which promised (101, b). A's retry
  adopts k16, so the chosen lease stays chosen.
- **Careless voters.** They accept under an old ballot. After k16 was
  chosen, A's stale accept also reports `chosen` for b70: two leases chosen
  for one id.

The receipt is [evidence/lease-node-20261023.json](evidence/lease-node-20261023.json).

## Membership

As in `lease.cljk`'s acceptor, a voter given a topology checks every lease
value against it.

- **At start.** The voter verifies the operator-signed topology with
  `topology-verdict` ([topology verify](topology-verify.md)). On any other
  verdict it does not start.
- **On accept.** It takes only a value that `valid-value?` admits against
  that topology:
  - an enabled node of the topology;
  - class 1..128 and slot 0..255;
  - epoch at least 1;
  - `until` no later than now + ttl + skew.
- **Without a topology** the voter is open, as in the run above, and checks
  only the value's form. That is for tests, not for a fleet.

With the same `$G`, `$K` and `node.elf` (each voter now takes `<topology>
<trust> <threshold> <now>` after its idle time):

```sh
python3 -I docs/evidence/lease-membership-topology-20261023.py "$G"   # topology.json: b70 enabled, k16 not; and a forged copy
cat > "$G/member.sh" <<'EOF'
N=1792500000000; U=1792500900000; FAR=1792502000000
TRUST=op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw
VK='{"class":"qwen-text","epoch":1,"node":"k16","revision":7,"slot":0,"until":'$U'}'
VA='{"class":"qwen-text","epoch":1,"node":"b70","revision":7,"slot":0,"until":'$U'}'
VF='{"class":"qwen-text","epoch":1,"node":"b70","revision":7,"slot":0,"until":'$FAR'}'
voters() {  # tag topology-file-or-empty
  for v in 1 2 3; do
    if [ -n "$2" ]; then set -- "$1" "$2" /gate/$2 $TRUST 1 $N; else set -- "$1" "" "" "" "" ""; fi
    env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:760$v \
      /gate/node.elf voter 760$v /gate/$1-v$v.state 8000 $3 $4 $5 $6 > /gate/$1-v$v.log 2>&1 &
  done
  sleep 3
}
prop() {  # label t id value
  env KEXE_ALLOW=37,38,45,46,47 KEXE_CAP_RESOURCES_45=127.0.0.1:7601,127.0.0.1:7602,127.0.0.1:7603 \
    /gate/node.elf proposer full $2 $3 "$4" 7601 7602 7603 2>&1 | grep -v '^{:status' | sed "s/^/$1: /" | cut -c1-110
}
rm -f /gate/*.state
voters k16 topology.json; prop "k16 proposes, topology says disabled" 101 k16 "$VK"; wait
voters b70 topology.json; prop "b70 proposes" 101 b70 "$VA"; wait
voters far topology.json; prop "b70 proposes until too far ahead" 101 b70 "$VF"; wait
voters forged topology-forged.json; prop "voters given a forged topology" 101 k16 "$VK"; wait
echo "forged voter says: $(head -1 /gate/forged-v1.log)"
voters open ""; prop "control: open voters, k16 proposes" 101 k16 "$VK"; wait
EOF
podman run --rm --pull never --platform linux/arm64 --network none -v "$G:/gate" -w /gate \
  docker.io/library/python:3.12-alpine sh /gate/member.sh
```

Expected output, measured on 2026-10-23 (`…` is the rest of the lease):

```text
k16 proposes, topology says disabled: no-majority accept 0
b70 proposes: chosen {"class":"qwen-text","epoch":1,"node":"b70",…}
b70 proposes until too far ahead: no-majority accept 0
voters given a forged topology: no-majority prepare 0
forged voter says: voter topology/needs-signatures
control: open voters, k16 proposes: chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
```

- **The topology.** It is signed by the operator key (RFC 8032 key 1, for
  this gate only) and lists b70 enabled and k16 disabled.
- **The forged copy.** It enables k16 without a new signature, so no voter
  starts on it.
- **The control.** Open voters let k16 be chosen, so the membership check is
  what refuses it.

The membership receipt is [evidence/lease-membership-20261023.json](evidence/lease-membership-20261023.json).

## What this does not show

- Separate hosts.
- The fleet token and lease ids of `lease.cljk`.
- An fsync: a voter survives a process restart, not a power cut.
- Concurrent proposers racing in real time. The interleaving here is
  scripted.
- x86_64 execution.
