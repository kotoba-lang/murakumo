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
    else its own), accepts, and on a majority prints `chosen <value>`. A
    round preempted by a voter's higher promise is tried again just above
    it, at most five rounds, as `lease.cljk`'s `round!`
    ([Preemption](#preemption));
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

## Preemption

As `lease.cljk`'s `round!` does, a full round that falls short is tried
again only when it was preempted:
- **Preempted:** a majority of voters answered, and at least one refusal
  carried a promise higher than the round's ballot. The next round runs at
  ballot (that promise + 1, own id) and adopts whatever was accepted by
  then. At most five rounds run.
- **Not retried:**
  - fewer than a majority answered (fail closed);
  - the voters refused the value itself under the round's own ballot, as
    membership does above.

There is no random backoff, because the runtime has no sleep.

Three scripted cases, each with this proposer and with a control that runs
one round only (`ln-tries` 1, the proposer before this change):
- **a.** k16 has prepared at ballot 500, so voters have promised it, and
  b70 then runs a full round at 100: a node whose clock is behind.
- **b.** k16's lease was chosen at 500, and b70 then runs a full round at
  100.
- **c.** Only one of three voters is up.

A race loop then starts b70 and k16 together twenty times. With the same
`$G`, `$K` and `node.kotoba`:

```sh
sed 's/(defn- ln-tries \[\] :i64 5)/(defn- ln-tries [] :i64 1)/' "$G/node.kotoba" > "$G/once.kotoba"
cat > "$G/preempt.sh" <<'EOF'
VA='{"class":"qwen-text","epoch":1,"node":"b70","revision":7,"slot":0,"until":1792500900000}'
VB='{"class":"qwen-text","epoch":1,"node":"k16","revision":7,"slot":0,"until":1792500900000}'
voters() {  # image tag count
  for v in $(seq 1 $3); do
    env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:780$v \
      /gate/$1.elf voter 780$v /gate/$2-v$v.state 1500 > /gate/$2-v$v.log 2>&1 &
  done
  sleep 0.3
}
prop() {  # image label mode t id value
  env KEXE_ALLOW=37,38,45,46,47 KEXE_CAP_RESOURCES_45=127.0.0.1:7801,127.0.0.1:7802,127.0.0.1:7803 \
    /gate/$1.elf proposer $3 $4 $5 "$6" 7801 7802 7803 2>&1 | grep -v '^{:status' | sed "s/^/$2: /" | cut -c1-96
}
state() { for v in 1 2 3; do [ -f /gate/$1-v$v.state ] && echo "  voter $v: $(cut -c1-60 /gate/$1-v$v.state)"; done; }
for img in node once; do
  rm -f /gate/$img-*.state
  echo "== proposer: $img"
  voters $img $img-a 3; prop $img "a1 k16 prepares (500,k16)" prepare 500 k16 "$VB"; wait
  voters $img $img-a 3; prop $img "a2 b70 round    (100,b70)" full 100 b70 "$VA"; wait; state $img-a
  voters $img $img-b 3; prop $img "b1 k16 round    (500,k16)" full 500 k16 "$VB"; wait
  voters $img $img-b 3; prop $img "b2 b70 round    (100,b70)" full 100 b70 "$VA"; wait; state $img-b
  voters $img $img-c 1; prop $img "c1 one voter up (100,b70)" full 100 b70 "$VA"; wait; state $img-c
done
for img in node once; do
  agree=0; short=0; split=0
  for i in $(seq 1 20); do
    rm -f /gate/race-v*.state
    voters $img race 3
    prop $img A full 100 b70 "$VA" > /gate/race-a.out &
    prop $img B full 100 k16 "$VB" > /gate/race-b.out &
    wait
    a=$(grep -o '"node":"[a-z0-9]*"' /gate/race-a.out); b=$(grep -o '"node":"[a-z0-9]*"' /gate/race-b.out)
    if [ -z "$a" ] || [ -z "$b" ]; then short=$((short + 1)); elif [ "$a" = "$b" ]; then agree=$((agree + 1)); else split=$((split + 1)); fi
  done
  echo "race $img: both hold the same lease $agree/20, a proposer without a lease $short/20, two leases $split/20"
done
EOF
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in node once; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done
    sh /gate/preempt.sh'
```

Expected output, measured on 2026-10-23 (`…` is the rest of the lease):

```text
== proposer: node
a1 k16 prepares (500,k16): prepared 3
a2 b70 round    (100,b70): chosen {"class":"qwen-text","epoch":1,"node":"b70",…}
  voter 1: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"b70",
  voter 2: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"b70",
  voter 3: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"b70",
b1 k16 round    (500,k16): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
b2 b70 round    (100,b70): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
  voter 1: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"k16",
  voter 2: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"k16",
  voter 3: 501 b70 501 b70 {"class":"qwen-text","epoch":1,"node":"k16",
c1 one voter up (100,b70): no-majority prepare 1
  voter 1: 100 b70 0 - -
== proposer: once
a1 k16 prepares (500,k16): prepared 3
a2 b70 round    (100,b70): no-majority prepare 0
  voter 1: 500 k16 0 - -
  voter 2: 500 k16 0 - -
  voter 3: 500 k16 0 - -
b1 k16 round    (500,k16): chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
b2 b70 round    (100,b70): no-majority prepare 0
  voter 1: 500 k16 500 k16 {"class":"qwen-text","epoch":1,"node":"k16",
  voter 2: 500 k16 500 k16 {"class":"qwen-text","epoch":1,"node":"k16",
  voter 3: 500 k16 500 k16 {"class":"qwen-text","epoch":1,"node":"k16",
c1 one voter up (100,b70): no-majority prepare 1
  voter 1: 100 b70 0 - -
race node: both hold the same lease 20/20, a proposer without a lease 0/20, two leases 0/20
race once: both hold the same lease 0/20, a proposer without a lease 20/20, two leases 0/20
```

- **a.** b70's round at 100 is refused by voters that promised 500. b70
  tries again at 501. Nothing was accepted, so its own lease is chosen. One
  round alone ends `no-majority prepare 0`.
- **b.** At 501, b70 adopts k16's chosen lease, so the chosen lease stays
  chosen. A late node learns the holder instead of failing.
- **c.** One answer is not a majority, so b70 stops after one round (the
  voter's promise stays at 100). The same holds for the control.
- **Race.** With retries, both proposers report the same lease in every
  run. With one round, one of the two ends without a lease in every run.
  No run chose two leases. The counts are from one run and depend on
  timing; the scripted cases do not.

Membership refusals are not retried. After the membership run above, the
voters of "k16 proposes" and "until too far ahead" still hold the promise
101.

The receipt is [evidence/lease-preemption-20261023.json](evidence/lease-preemption-20261023.json).

## What this does not show

- Separate hosts.
- The fleet token and lease ids of `lease.cljk`.
- An fsync: a voter survives a process restart, not a power cut.
- Concurrent proposers on separate hosts. The race loop runs on one
  machine, and there is no random backoff, so two proposers can in
  principle keep preempting each other for all five rounds.
- x86_64 execution.
