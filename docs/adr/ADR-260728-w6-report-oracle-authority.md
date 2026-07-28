# ADR-260728: W6 product-shell oracle authority — report pure path

Status: accepted third product-shell dual-source cutover after kekkai.gate (#86)
and token (#88)

## Decision

Extend Option A (product-shell oracle authority) to `murakumo.report` pure
string/pad/header helpers:

| layer | role |
|---|---|
| `kotoba/report_core.kotoba` | SSoT pure definitions |
| `resources/murakumo/oracle/report_core.kir.edn` | precompiled KIR product artifact |
| `murakumo.kotoba.oracle` catalog `:report-core` | load + `ir/execute` |
| `murakumo.report` (JVM) | pure helpers **delegate** to oracle |

### Wired pure public APIs (1:1 kotoba exports)

- headers: `nodes-header`, `status-header`
- rows: `nodes-row`, `status-row`, `status-down-row` (host projects maps → scalars)
- pad: `pad-to` (private host wrapper; guest `spaces` / `pad-right` / `pad-to`)
- help: `command-help` (full multi-line pure string)
- reconcile pure builders: `reconcile-title`, `reconcile-col-header`,
  `cid-display`, `action-detail`, `reconcile-app-row`, `reconcile-app-line`,
  `reach-line`, `drift-line` (used inside `reconcile-lines`)
- constants and simple lines: mesh/deploy/operator strings, `command-error-line`,
  `mesh-status`, `node-prefix`, etc.

### Host remains

| concern | why host |
|---|---|
| Map/keyword projection | guest ABI is strings + i64 |
| CSV joins (`targets`, `running`, `reach`, `misplaced`) | list reduce not in guest |
| `reconcile-lines` mapcat over apps | structure fold stays host |
| CID truncation (`subs` 16) before `cid-display` | host projects display width |
| `deploy-observed-row` branch + `str/join ", "` | host collection join |
| `status-row*` map destructure | thin host sugar |

### Regenerate

```bash
clojure -M:test -m murakumo.kotoba-oracle-gen
```

CI: `report-precompiled-kir-does-not-drift` + existing gate/token drift tests.

## Evidence

- `src/murakumo/report.clj` (JVM oracle delegation)
- `resources/murakumo/oracle/report_core.kir.edn`
- `test/murakumo/kotoba_oracle_authority_test.clj` (report suite)
- Existing parity: `test/murakumo/report_kotoba_parity_test.clj`

## Related

- ADR product-shell oracle authority (#86)
- ADR token product-shell oracle authority (#88)
- ADR report pad/headers (#72) / help+reconcile (#74) / rows (#82)
