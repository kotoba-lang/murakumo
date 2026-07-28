# ADR-260728: W6 product-shell oracle authority — report pure path

Status: accepted after kekkai (#86) + token (#88) dual-source cutovers

## Decision

Third product-shell dual-source cutover for CLI report formatting:

| layer | role |
|---|---|
| `kotoba/report_core.kotoba` | SSoT pure string helpers |
| `resources/murakumo/oracle/report_core.kir.edn` | precompiled KIR product artifact |
| `murakumo.kotoba.oracle` catalog `:report` | load + `ir/execute` |
| `murakumo.report` | public API **delegates** pure formatters to oracle |

### Host remains

- `reconcile-lines` **mapcat** of apps + Unicode-safe char-width field pad
  (guest `pad-to` is ASCII-byte oriented; host pads then calls
  `reconcile-app-row` / `reconcile-app-line`)
- Process / SSH / I/O shells

### Regeneration

```bash
# with compiler on classpath (:test)
clojure -M:test -e '(require (quote murakumo.kotoba-oracle-gen)) (run! println (murakumo.kotoba-oracle-gen/regenerate-all!))'
```

CI drift: `murakumo.kotoba-oracle-authority-test` / `report-precompiled-kir-does-not-drift`.

## Evidence

- report product-shell tests in `kotoba_oracle_authority_test.clj`
- existing `report_test` + `report_kotoba_parity_test`

## Related

- inventory Next: expand product-shell catalog (report pad/header)
- murakumo#86 kekkai, #88 token
