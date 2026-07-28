# ADR-260728: W6 pure-planner oracle — plan-fits-3 + moe pick compose

Status: accepted after partition-3-ends integer walk (#68)

## Decision

Compose pure plan go/no-go and mlx-moe pick helpers on `infer_plan_core.kotoba`:

| export | role |
|---|---|
| `assignment-span` | hi−lo layer span |
| `plan-fits-3` | 3-node integer plan `:fits?` (total + positive-span gates) |
| `ok-mark` | report ✓/✗ |
| `pick-max-idx-3` | mlx-moe best usable index (ties → earlier) |
| `moe-capacity-ok` | capacity>0 ⇒ fits |

### Not ported

- n≠3 plan assembly with node ids
- full `moe/plan` map / capacity tier custom profiles beyond capacity-default
- `report` rows with GiB floats

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj` (9 tests / 58 assertions)
