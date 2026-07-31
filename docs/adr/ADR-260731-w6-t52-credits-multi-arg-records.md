# ADR-260731: T5.2 native guest record — credits multi-arg residual

- Status: accepted
- Date: 2026-07-31
- Depends: T5.2 mt-work/charge records; Progress 31en classify closed
- WBS: T5.2 residual multi-arg pure (integer credits core)

## Decision

Fold remaining multi-scalar pure exports on `infer_credits_core` into single
guest records (max-parameters 5 discipline; host float `job-cost` / settle
folds stay host):

| Export | Schema | Fields |
|--------|--------|--------|
| `token-cost` / `unit-cost` | `:credits/mul` | price, n |
| `cut` | `:credits/cut-in` | total, num, den |
| `pool` | `:credits/pool-in` | total, treasury, head |
| `mt-sum-2` | `:credits/mt-pair` | w0, w1 |
| `mt-sum-3` | `:credits/mt-triple` | w0, w1, w2 |
| `share-floor` | `:credits/share-in` | pool-amt, w, sumw |
| `job-cost-2` | `:credits/job2` | p0, n0, p1, n1 |
| `job-cost-3` | `:credits/job3` | p0, n0, p1, n1, p2n |
| `share-record-2` | `:credits/shares-w` | pool-amt, w0, w1 |
| `settle-pool-shares-2` | `:credits/settle-w` | total, w0, w1 |

Internal composition uses `record-new` (share-record-2 → share-floor; settle →
cut/pool/share-record-2). Existing `:credits/mt-work` and `:credits/charge`
unchanged.

## Non-claims

- Host `job-cost` / `settle` float folds still host-side (registry throws).
- Digit-scanner multi-arg internals and schedule tournament helpers remain.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `infer_credits_core`
- Focused credits parity + authority: 75 tests / 1451 assertions green
