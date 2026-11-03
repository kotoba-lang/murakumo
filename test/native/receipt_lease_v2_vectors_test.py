"""Independently validate the public v2 receipt vectors; this does not build Amu."""

import base64
import hashlib
import json
import re
import unittest
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


ROOT = Path(__file__).resolve().parents[2]
AGENT = (ROOT / "kotoba/fleet_agent_step.kotoba").read_text()
OUTPUT = (ROOT / "kotoba/fleet_job_output_test.kotoba").read_text()


def literal(source, pattern):
    match = re.search(pattern, source)
    if not match:
        raise AssertionError(f"missing source vector: {pattern}")
    return json.loads(match.group(1))


def named_function(name):
    return literal(AGENT, rf'\(defn- {re.escape(name)} \[\] :string ("(?:\\.|[^"\\])*")\)')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def signature(encoded):
    return base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))


class LeaseReceiptVectors(unittest.TestCase):
    def test_lease_digest_and_signed_receipt(self):
        key = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"))
        job = named_function("as-jobId")
        lease = {"class": job, "epoch": 2, "node": "k16", "revision": 0,
                 "slot": 0, "until": 1792500060000}
        digest = hashlib.sha256(canonical(lease)).hexdigest()
        self.assertEqual(digest, named_function("as-lease-digest"))
        receipt = {"at": 1792500000000, "did": named_function("as-didB"), "epoch": 2,
                   "exit": 0, "job": job, "kind": "itonami.agent-step-receipt/2",
                   "lease": digest, "node": "k16", "output": named_function("as-output")}
        sig = signature(named_function("as-sigBv2"))
        key.public_key().verify(sig, canonical(receipt))
        receipt["lease"] = "a" * 64
        with self.assertRaises(InvalidSignature):
            key.public_key().verify(sig, canonical(receipt))
        lease["until"] += 1
        self.assertNotEqual(hashlib.sha256(canonical(lease)).hexdigest(), digest)

    def test_output_fixture_v2_signature_and_cid(self):
        key = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        receipt = literal(OUTPUT, r'\[receipt ("(?:\\.|[^"\\])*")')
        pattern = r'receipt-v2 \(string-replace-all receipt ("(?:\\.|[^"\\])*") ("(?:\\.|[^"\\])*")\)'
        match = re.search(pattern, OUTPUT)
        self.assertIsNotNone(match)
        old, new = map(json.loads, match.groups())
        self.assertEqual(receipt.count(old), 1)
        raw_receipt = receipt.replace(old, new).encode()
        parsed = json.loads(raw_receipt)
        self.assertEqual(raw_receipt, canonical(parsed))
        sig = signature(literal(OUTPUT, r'sig-v2 ("(?:\\.|[^"\\])*")'))
        key.public_key().verify(sig, raw_receipt)
        encoded = literal(OUTPUT, r'output ("(?:\\.|[^"\\])*")')
        data = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        raw_cid = b"\x01\x55\x12\x20" + hashlib.sha256(data).digest()
        cid = "b" + base64.b32encode(raw_cid).decode().lower().rstrip("=")
        self.assertEqual(parsed["output"], cid)
        parsed["lease"] = "b" * 64
        with self.assertRaises(InvalidSignature):
            key.public_key().verify(sig, canonical(parsed))


if __name__ == "__main__":
    unittest.main()
