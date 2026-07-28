# ADR-260728: W6 product-shell oracle authority — token pure path

Status: accepted second product-shell dual-source cutover after kekkai.gate (#86)

## Decision

Extend Option A (product-shell oracle authority) to `murakumo.token` pure wire:

| layer | role |
|---|---|
| `kotoba/token_core.kotoba` | SSoT pure definitions |
| `resources/murakumo/oracle/token_core.kir.edn` | precompiled KIR product artifact |
| `murakumo.kotoba.oracle` catalog `:token` | load + `ir/execute` |
| `murakumo.token` (JVM) | pure helpers **delegate** to oracle |
| cljs host-mirror fns | fallback + semantic documentation |

HMAC-SHA256 + base64url codecs remain host (live adapter #87).

### Regenerate

```bash
clojure -Sdeps '{…compiler + kir…}' -M -m murakumo.kotoba-oracle-gen
```

CI: `token-precompiled-kir-does-not-drift` + existing gate drift tests.

## Evidence

- `test/murakumo/kotoba_oracle_authority_test.clj` (token suite)
- `test/murakumo/token_test.cljc` / token_kotoba_parity
- 21 tests / 113 assertions combined with token suites

## Related

- ADR product-shell oracle authority (#86)
- ADR live HMAC/AES adapters (#87)
- murakumo#83 token wire pure
