# ADR-260728: kekkai residual ops config inject

Status: accepted after ops-config-inject (#137) + product pure dual-source (#144)

## Decision

Route remaining **kekkai** exact-name config getenv through `murakumo.config`
inject helpers (no bare `System/getenv` in gate resolution paths):

| helper | env | role |
|---|---|---|
| `config/kekkai-ledger` | `MURAKUMO_KEKKAI_LEDGER` | ledger path override |
| `config/kekkai-dir` | `MURAKUMO_KEKKAI_DIR` | sibling checkout override |
| `config/home-dir` | `HOME` | default-kekkai-dir base |

`murakumo.kekkai.gate/ledger-path` and `kekkai-dir` accept inject `getenv`
(fn or map); 0-arity uses process exact getenv. Host shell `murakumo.kekkai`
no longer keeps a private getenv alias.

### Secrets unchanged

Named secret fetch remains `murakumo.secret`.

## Evidence

- config unit inject tests for new keys
- existing kekkai_gate unit tests (inject maps)

## Related

- ADR-260728-w6-ops-config-inject
- handoff Delivery residual ops shells
