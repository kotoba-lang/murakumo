"""Vectors for kotoba/fleet_node_enrollment.kotoba (docs/node-enrollment.md).

RFC 8032 test keys 1 and 2; OpenSSL 3 signs, Python hashlib digests, and the
base58btc here is written from the multibase rule. Run in an empty directory:
it writes k.pem (removed at the end), m.bin and vectors.json.
"""
import hashlib, subprocess, base64, json, os, sys
OSSL = "/opt/homebrew/opt/openssl@3/bin/openssl"
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58(b):
    n = int.from_bytes(b, "big"); out = ""
    while n: n, r = divmod(n, 58); out = B58[r] + out
    return "1" * (len(b) - len(b.lstrip(b"\0"))) + out
def unb58(s):
    n = 0
    for c in s: n = n * 58 + B58.index(c)
    return n
def pem(seed, path):
    der = bytes.fromhex("302e020100300506032b657004220420") + seed
    open(path, "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
def pub(seed):
    pem(seed, "k.pem")
    der = subprocess.run([OSSL, "pkey", "-in", "k.pem", "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
    return der[-32:]
def sign(seed, msg):
    pem(seed, "k.pem"); open("m.bin", "wb").write(msg)
    return subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", "k.pem", "-rawin", "-in", "m.bin"], capture_output=True, check=True).stdout
def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def decl(node, did, fd, av, rc):
    return json.dumps({"availability": av, "did": did, "failure-domain": fd, "kind": "tsukasa.declaration/1",
                       "node": node, "runtime-classes": rc}, sort_keys=True, separators=(",", ":"))
def hb(node, did, d, at, drain):
    return json.dumps({"at": at, "declaration": d, "did": did, "drain": drain, "kind": "tsukasa.heartbeat/1",
                       "node": node}, sort_keys=True, separators=(",", ":"))
seed1 = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
seed2 = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
pk1, pk2 = pub(seed1), pub(seed2)
did1 = "did:key:z" + b58(b"\xed\x01" + pk1); did2 = "did:key:z" + b58(b"\xed\x01" + pk2)
spec = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"
specpk = unb58(spec[9:]).to_bytes(34, "big")
x25519 = "did:key:z" + b58(b"\xec\x01" + pk1)
tA = decl("b70", did1, "site-a", "always-on", 2); tB = decl("b70", did1, "site-a", "always-on", 3)
dA = hashlib.sha256(tA.encode()).hexdigest(); dB = hashlib.sha256(tB.encode()).hexdigest()
AT = 1792500000000
h1 = hb("b70", did1, dA, AT, False); h2 = hb("b70", did1, dA, AT, True); h3 = hb("b70", did1, dB, AT, False)
v = dict(pk1=pk1.hex(), pk2=pk2.hex(), did1=did1, did2=did2, spec=spec, specpk=specpk.hex(), x25519=x25519,
         tA=tA, tB=tB, dA=dA, dB=dB, sigA=b64u(sign(seed1, tA.encode())), sigB=b64u(sign(seed1, tB.encode())),
         sigA2=b64u(sign(seed2, tA.encode())), h1=h1, h2=h2, h3=h3,
         hb1=b64u(sign(seed1, h1.encode())), hb2=b64u(sign(seed1, h2.encode())), hb3=b64u(sign(seed1, h3.encode())),
         hbA2=b64u(sign(seed2, h1.encode())))
# OpenSSL verifies its own signatures over the exact texts
json.dump(v, open("vectors.json", "w"), indent=1)
for k in ["did1", "did2", "x25519", "dA", "dB", "sigA", "sigB", "sigA2", "hb1", "hb2", "hb3", "hbA2", "tA", "h1"]: print(k, v[k])
print("spec-round-trip", "did:key:z" + b58(specpk) == spec, specpk[:2].hex())
os.remove("k.pem")
