"""Topologies for docs/lease-node.md "Rotation", after the Membership one
(revision 7: b70 enabled, k16 not). OpenSSL 3 signs each canonical payload
with RFC 8032 key 1 (the operator), except the forged one.
  topology-r6.json        revision 6, k16 enabled: older than the one held
  topology-r9-forged.json revision 9, k16 enabled, revision 8's signature
  topology-r9-owner.json  revision 9, k16 enabled, b70's owner-id replaced
  topology-r8.json        revision 8, k16 enabled, b70 disabled"""
import base64, copy, json, os, subprocess, sys
OSSL = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
out = sys.argv[1]
seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
der = bytes.fromhex("302e020100300506032b657004220420") + seed
open(f"{out}/op.pem", "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
OWNER = "01234567-89ab-cdef-0123-456789abcdef"
node = lambda n, en, owner=OWNER: {"node": n, "url": "http://100.64.0.10:8796/", "enabled": en, "owner-id": owner, "roles": []}
def payload(rev, b70, k16, b70_owner=OWNER):
    return {"schema": 1, "revision": rev, "nodes": [node("b70", b70, b70_owner), node("k16", k16)],
            "gateways": [{"node": "gw", "url": "http://100.64.0.1:8796/", "enabled": True}], "policies": {"minimum-replicas": {"m1": 1}}}
def sign(p):
    open(f"{out}/m.bin", "wb").write(json.dumps(p, sort_keys=True, separators=(",", ":")).encode())
    return base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", f"{out}/op.pem", "-rawin", "-in", f"{out}/m.bin"],
                                                   capture_output=True, check=True).stdout).rstrip(b"=").decode()
def write(name, p, sig): open(f"{out}/{name}", "w").write(json.dumps({"payload": p, "signature": sig}))
p6 = payload(6, True, True); write("topology-r6.json", p6, sign(p6))
p8 = payload(8, False, True); s8 = sign(p8); write("topology-r8.json", p8, s8)
p9 = payload(9, True, True); write("topology-r9-forged.json", p9, s8)
p9o = payload(9, True, True, "fedcba98-7654-3210-fedc-ba9876543210"); write("topology-r9-owner.json", p9o, sign(p9o))
os.remove(f"{out}/op.pem"); os.remove(f"{out}/m.bin")
