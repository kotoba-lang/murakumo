"""Vectors for kotoba/fleet_agent_step.kotoba (docs/agent-step.md).
Run in an empty directory: OpenSSL 3 signs, hashlib digests."""
import base64, hashlib, json, os, subprocess
OSSL = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58(b):
    n = int.from_bytes(b, "big"); out = ""
    while n: n, r = divmod(n, 58); out = B58[r] + out
    return out
def pem(seed):
    der = bytes.fromhex("302e020100300506032b657004220420") + seed
    open("k.pem", "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
def did(seed):
    pem(seed); der = subprocess.run([OSSL, "pkey", "-in", "k.pem", "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
    return "did:key:z" + b58(b"\xed\x01" + der[-32:])
def sign(seed, msg):
    pem(seed); open("m.bin", "wb").write(msg)
    return base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", "k.pem", "-rawin", "-in", "m.bin"], capture_output=True, check=True).stdout).rstrip(b"=").decode()
def cid(data): return "b" + base64.b32encode(bytes([1, 0x55, 0x12, 0x20]) + hashlib.sha256(data).digest()).decode().lower().rstrip("=")
c = lambda o: json.dumps(o, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
A = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
B = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
profile = "sha256:" + hashlib.sha256(b"itonami bot profile index").hexdigest()
inp, pol = cid(b"turn input"), cid(b"capability policy")
job = c({"input": inp, "kind": "itonami.agent-step/1", "policy": pol, "profile": profile, "rail": "murakumo"})
jid = hashlib.sha256(job.encode()).hexdigest()
out = cid(b"turn output")
def receipt(node, d, epoch): return c({"at": 1792500000000, "did": d, "epoch": epoch, "exit": 0, "job": jid,
                                        "kind": "itonami.agent-step-receipt/1", "node": node, "output": out})
dA, dB = did(A), did(B)
rA, rB = receipt("b70", dA, 1), receipt("k16", dB, 2)
v = {"profile": profile, "input": inp, "policy": pol, "job": job, "jobId": jid, "output": out, "didA": dA, "didB": dB,
     "receiptA": rA, "sigA": sign(A, rA.encode()), "receiptB": rB, "sigB": sign(B, rB.encode()),
     "receiptBbyA": rB, "sigBbyA": sign(A, rB.encode())}
json.dump(v, open("vectors.json", "w"), indent=1)
os.remove("k.pem"); os.remove("m.bin")
print(job); print(jid); print(rB)
