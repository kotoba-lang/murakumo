# ADR-260728: W6 product-shell oracle authority — tunnel + config pure paths

Status: accepted after bulk product-shell catalog (#99)

## Decision

Wire pure string/path helpers of two catalog-only cores to kotoba SSoT:

| catalog id | host | SSoT / KIR |
|---|---|---|
| `:tunnel` | `murakumo.tunnel` | `kotoba/tunnel_core.kotoba` → `tunnel_core.kir.edn` |
| `:config` | `murakumo.config` | `kotoba/config_core.kotoba` → `config_core.kir.edn` |

### tunnel (JVM + cljs/nbb dual-source)

Delegates when `oracle/ready? :tunnel`: defaults (`default-connect-timeout-s`,
`default-control-persist-s`, `rc-marker`), `conn-opts` / `ssh-opts` fragments
(`batch-mode-opt`, `connect-timeout-opt`, `strict-host-key-opt`, `control-*`),
`wrap-cmd`, `parse-rc` marker/digits (`marker-prefix?`, `strip-marker-digits`,
`parse-digits`), `scp-dest`, `close-master-control-opt`,
`ensure-forward-command` / `replace-forward-command` / `remote-curl-command`.
Mirrors + try/catch remain for cljs KIR substring/i64-str bounds failures.

### config (JVM)

Delegates: default path constants, `default-kotoba-dir` / `kotoba-dir-from`,
pinned/release/wit path builders, `kotoba-bin` / `resolve-local-bin` /
`resolve-wit-dir`, `peers-path` / `launchd-template-path` / `build-manifest-path`.

### Still host

- tunnel: line-split of stdout, argv vector concat, SSH/SCP subprocess
- config: EDN parse/IO, env map folds, filesystem existence probes, operator-seed
- config still JVM-only dual-source (cljs rewire incremental)

## Evidence

- authority tests for tunnel + config (API + live-compile parity + KIR drift)
- existing `tunnel_kotoba_parity_test` / `config_kotoba_parity_test` / unit tests
- nbb smoke after cljs tunnel dual-source

## Related

- inventory Next: incremental cljs host rewire (config/report/persist/…)
- murakumo#86–#124 product-shell + cljs load pattern
- ADR-260728-w6-cljs-tunnel-dual
