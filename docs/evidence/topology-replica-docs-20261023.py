"""Topologies for docs/topology-replica.md, signed by RFC 8032 key 1 (the
operator) with OpenSSL 3:
  topology-r7-conflict.json  revision 7 like topology.json (Membership), but
                             with k16 enabled: a replica that holds revision 7
                             must record it as a conflict, not adopt it
  topology-net7.json, -net8.json  nodes n1..n4 at 100.64.9.11..14:8796 and the
                             gateway at n1's url, revisions 7 and 8 (n4 disabled)"""
import base64, json, os, subprocess, sys
OSSL = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
out = sys.argv[1]
seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
der = bytes.fromhex("302e020100300506032b657004220420") + seed
open(f"{out}/op.pem", "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
node = lambda n, en: {"node": n, "url": "http://100.64.0.10:8796/", "enabled": en, "owner-id": "01234567-89ab-cdef-0123-456789abcdef", "roles": []}
p = {"schema": 1, "revision": 7, "nodes": [node("b70", True), node("k16", True)],
     "gateways": [{"node": "gw", "url": "http://100.64.0.1:8796/", "enabled": True}], "policies": {"minimum-replicas": {"m1": 1}}}
open(f"{out}/m.bin", "wb").write(json.dumps(p, sort_keys=True, separators=(",", ":")).encode())
sig = base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", f"{out}/op.pem", "-rawin", "-in", f"{out}/m.bin"],
                                              capture_output=True, check=True).stdout).rstrip(b"=").decode()
open(f"{out}/topology-r7-conflict.json", "w").write(json.dumps({"payload": p, "signature": sig}))
def signed(q):
    open(f"{out}/m.bin", "wb").write(json.dumps(q, sort_keys=True, separators=(",", ":")).encode())
    return base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", f"{out}/op.pem", "-rawin", "-in", f"{out}/m.bin"],
                                                   capture_output=True, check=True).stdout).rstrip(b"=").decode()
for rev in (7, 8):
    q = {"schema": 1, "revision": rev,
         "nodes": [{"node": f"n{i}", "url": f"http://100.64.9.1{i}:8796/", "enabled": not (rev == 8 and i == 4),
                    "owner-id": f"0000000{i}-89ab-cdef-0123-456789abcdef", "roles": []} for i in (1, 2, 3, 4)],
         "gateways": [{"node": "gw", "url": "http://100.64.9.11:8796/", "enabled": True}], "policies": {"minimum-replicas": {"m1": 1}}}
    open(f"{out}/topology-net{rev}.json", "w").write(json.dumps({"payload": q, "signature": signed(q)}))
os.remove(f"{out}/op.pem"); os.remove(f"{out}/m.bin")
