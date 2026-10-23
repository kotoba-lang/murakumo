# One agent-step job on two nodes: run once, confirmed once

Tracked in [JUN-289](https://linear.app/kotobalabs/issue/JUN-289). Two of its
acceptance items are covered here:
- one bot profile's turn runs on a node, and its output CID and receipt can
  be read back;
- the same job given to two nodes is confirmed only once, through the
  fence.

`kotoba/fleet_job_node.kotoba` puts the earlier modules in order.

- **`run`, on each node:**
  1. The job is checked and named (`job-problem`, `job-id`).
  2. The node takes one round on the quorum lease register over TCP
     (`lease-round`, [lease node](lease-node.md)). Its own value is
     `lease-text(node, job id, slot 0, epoch 1, now + ttl)`, and the chosen
     value names the holder.
  3. A node that does not hold the lease stops (`held-by`).
  4. The holder derives its own signed state and runs the cell with
     `cell-execute`: the release verdict, the image layout, and podman with
     a fixed argv ([cell run](cell-run.md)).
  5. It signs the agent-step receipt with the lease epoch
     ([agent step](agent-step.md)).
- **`verify`, on anyone:** it learns the chosen lease with one round, maps
  the holder to its did through the trust, and judges a node's receipt with
  `receipt-verdict`.

Three refactors make the composition possible:
- `fleet_agent_step`'s `receipt-text` is now `step-receipt-text`, so it no
  longer clashes with `fleet_cell_run`'s;
- the lease proposer takes its voter ports as a list, through the exported
  `lease-round`;
- `fleet_cell_run` exports `cell-execute`, `own-state` and `key-seed`.

The agent-step, lease-node and cell-run gates were all rerun and give the
same results.

## Reproduce

First run [cell run](cell-run.md)'s main block, which writes the release,
the layout and b70's key into `$G`. Then, with the same `$G` and `$K`:

```sh
pin() { git -C "$K/$1" show "$2:$3" | sed '1,/\]))$/d'; }
{
  echo '(ns itonami-job-node (:export [main]))'
  pin org-nist-sha2 ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b kotoba/sha2/core.kotoba
  pin org-ietf-ed25519 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa kotoba/ed25519/selfhost.kotoba
  pin org-ietf-jcs 6331a9b461f5858be484b9d6859f314f9c3a2510 kotoba/jcs/reader.kotoba
  pin org-oci-distribution 90463924db65eea4d9021dc6623105472282b5a9 kotoba/oci/itonami_artifact.kotoba
  pin grant fa7e9b567286cd38cc4b229d8c980bfe676d9cce kotoba/grant/runtime_class.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/ledger_core.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/node_state.kotoba
  for f in fleet_node_enrollment fleet_cell_launch fleet_oci_layout fleet_cell_run; do sed '1,/\]))$/d' kotoba/$f.kotoba; done
  pin sahai ba19195945c6f4482b46818d0570aa840f9ef681 kotoba/sahai/fence.kotoba
  for f in fleet_agent_step fleet_intent_core fleet_topology_verify fleet_lease_core fleet_lease_node fleet_job_node; do sed '1,/\]))$/d' kotoba/$f.kotoba; done
  echo '(defn main [] :i64 (if (= (typed-cap-call 38 :string :string "0") "voter") (lease-node-main) (job-node-main)))'
} > "$G/job.kotoba"
# a verifier without the fence: no holder or epoch check
sed -e 's/(not (= did holder-did)) "job\/not-the-holder"/false "job\/not-the-holder"/' \
    -e 's/(not (= epoch holder-epoch)) "job\/stale-epoch"/false "job\/stale-epoch"/' "$G/job.kotoba" > "$G/nofence.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in job nofence; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done'
# RFC 8032 test key 2 for k16, for this gate only
printf '4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb\n' > "$G/k16.key"
cat > "$G/scenario.sh" <<'EOF'
G=$1; MODE_B=$2; TAG=$3; B=$4
R=$(cat $G/release.digest)
IN=bafkreihbgymriz33lmog7chhk7xlpqbv4bea7i7eyflwopbcbvfvrbnuey
PO=bafkreicag2vl77rqrkqabdchmccylgru2qdcqn377gpefuebimv4junsgq
DA=9af9509efcef6c13f34293a15319f37468ca5f42a55383a0ac8c270a8e678c29
DK=64f1f5f59f863a9f851efccf40b65df6af43dc7406cbe92b6160f7b605f103fb
PORTS=${B}1,${B}2,${B}3; DIAL=127.0.0.1:${B}1,127.0.0.1:${B}2,127.0.0.1:${B}3
N=1792500000000
TRUST=b70=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw,k16=did:key:z6MkiaMbhXHNA4eJVCCj8dbzKzTgYDKf6crKgHVHid1F1WCT
rm -f $G/$TAG-v*.state
for v in 1 2 3; do
  env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_43=127.0.0.1:$B$v \
    $G/job.elf voter $B$v $G/$TAG-v$v.state 25000 > $G/$TAG-v$v.log 2>&1 &
done
sleep 0.3
node() {  # name key fd admitted t mode
  env KEXE_ALLOW=20,35,37,38,45,46,47 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_45=$DIAL \
    KEXE_CAP_RESOURCES_20=/usr/bin/podman KEXE_SPAWN_ENV=HOME:XDG_RUNTIME_DIR:PATH \
    $G/job.elf run $R $IN $PO murakumo $1 $G/$2 $3 $4 $N 2 $G/release.json $G/layout-cell $PORTS $5 $6 > $G/$TAG-$1.out 2>&1
}
node b70 node.key site-a $DA 100 follow &
sleep 0.3
node k16 k16.key site-b $DK 101 $MODE_B &
wait %4 %5 2>/dev/null; sleep 0.5
for n in b70 k16; do
  echo "$TAG $n: $(grep -E '^(lease|held-by|verdict|layout) ' $G/$TAG-$n.out | tr '\n' ' ')$(grep -q '^receipt ' $G/$TAG-$n.out && echo '| receipt')"
done
for n in b70 k16; do
  if grep -q '^receipt ' $G/$TAG-$n.out; then
    env KEXE_ALLOW=35,37,38,45,46,47 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_45=$DIAL \
      $G/job.elf verify $PORTS $TRUST $G/$TAG-$n.out 200 2>&1 | grep -v '^{:status' | sed "s/^/$TAG verify $n's receipt: /"
  fi
done
EOF
cat > "$G/verify-again.sh" <<'EOF'
G=$1; IMG=$2; B=753; DIAL=127.0.0.1:${B}1,127.0.0.1:${B}2,127.0.0.1:${B}3
TRUST=b70=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw,k16=did:key:z6MkiaMbhXHNA4eJVCCj8dbzKzTgYDKf6crKgHVHid1F1WCT
for v in 1 2 3; do env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_43=127.0.0.1:$B$v $G/job.elf voter $B$v $G/rogue-v$v.state 1500 > /dev/null 2>&1 & done
sleep 0.3
for n in b70 k16; do
  env KEXE_ALLOW=35,37,38,45,46,47 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_45=$DIAL \
    $G/$IMG.elf verify ${B}1,${B}2,${B}3 $TRUST $G/rogue-$n.out 300 2>&1 | grep -v '^{:status' | sed "s/^/$IMG verifier, $n's receipt: /"
done
wait
EOF
podman machine ssh -- "sh $G/scenario.sh $G follow honest 752; sh $G/scenario.sh $G rogue rogue 753; sleep 26; \
  sh $G/verify-again.sh $G job; sh $G/verify-again.sh $G nofence" 2>&1 | grep -v 'Copying\|Getting image\|Writing manifest'
```

Expected output, measured on 2026-10-23 (the podman machine, arm64 Linux
6.15, rootless podman):

```text
honest b70: lease b70 verdict launch/ok layout layout/ok | receipt
honest k16: lease b70 held-by b70
honest verify b70's receipt: holder b70 verdict job/confirmed
rogue b70: lease b70 verdict launch/ok layout layout/ok | receipt
rogue k16: lease b70 verdict launch/ok layout layout/ok | receipt
rogue verify b70's receipt: holder b70 verdict job/confirmed
rogue verify k16's receipt: holder b70 verdict job/not-the-holder
job verifier, b70's receipt: holder b70 verdict job/confirmed
job verifier, k16's receipt: holder b70 verdict job/not-the-holder
nofence verifier, b70's receipt: holder b70 verdict job/confirmed
nofence verifier, k16's receipt: holder b70 verdict job/confirmed
```

- **honest.** b70 and k16 race for the job's lease. k16 starts 0.3 s later,
  so its round adopts b70's chosen value. b70 runs the cell and signs. k16
  stops, and nothing of it runs.
- **rogue.** k16 runs the cell anyway and signs a receipt. The verifier
  confirms only b70's, so the job is confirmed once although it ran twice.
- **verify-again.** The voters start again from their state files. The
  verifier still learns b70, and a verifier without the fence confirms both
  receipts.

**Check b70's receipt independently.** OpenSSL verifies the signature, and
hashlib recomputes the job id and the output CID:

```sh
python3 -I - "$G" <<'PY'
import base64, hashlib, json, subprocess, sys, os
g = sys.argv[1]
lines = dict(l.split(" ", 1) for l in open(f"{g}/honest-b70.out").read().splitlines() if l.startswith(("receipt ", "signature ")))
text, sig = lines["receipt"], lines["signature"]; r = json.loads(text)
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"; n = 0
for c in r["did"][9:]: n = n * 58 + B58.index(c)
der = bytes.fromhex("302a300506032b6570032100") + n.to_bytes(34, "big")[2:]
open(f"{g}/pub.pem", "w").write("-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PUBLIC KEY-----\n")
open(f"{g}/r.txt", "wb").write(text.encode()); open(f"{g}/r.sig", "wb").write(base64.urlsafe_b64decode(sig + "=="))
ossl = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
v = subprocess.run([ossl, "pkeyutl", "-verify", "-pubin", "-inkey", f"{g}/pub.pem", "-rawin", "-in", f"{g}/r.txt", "-sigfile", f"{g}/r.sig"], capture_output=True, text=True)
cid = lambda d: "b" + base64.b32encode(bytes([1, 0x55, 0x12, 0x20]) + hashlib.sha256(d).digest()).decode().lower().rstrip("=")
job = json.dumps({"input": "bafkreihbgymriz33lmog7chhk7xlpqbv4bea7i7eyflwopbcbvfvrbnuey", "kind": "itonami.agent-step/1",
                  "policy": "bafkreicag2vl77rqrkqabdchmccylgru2qdcqn377gpefuebimv4junsgq", "profile": open(f"{g}/release.digest").read(),
                  "rail": "murakumo"}, sort_keys=True, separators=(",", ":"))
print("signature:", v.stdout.strip() or v.stderr.strip())
print("canonical:", json.dumps(r, sort_keys=True, separators=(",", ":")) == text, "| kind", r["kind"], "| epoch", r["epoch"], "| node", r["node"])
print("job id:", r["job"] == hashlib.sha256(job.encode()).hexdigest())
print("output CID:", r["output"] == cid(b"itonami cell: hello from a loaderless static image\n"))
PY
```

Expected: `Signature Verified Successfully`, then `canonical: True | kind
itonami.agent-step-receipt/1 | epoch 1 | node b70`, `job id: True` and
`output CID: True`. The receipt is
[evidence/job-node-20261023.json](evidence/job-node-20261023.json).

## What this does not show

- Separate hosts. Everything runs on the podman machine.
- Real-time contention between proposers. k16 starts 0.3 s later.
- A real bot profile's turn with an inference rail, and the consent before
  inference leaves the node.
- The fleet token and membership on the register, and an fsync for voter
  state.
- x86_64 execution.
