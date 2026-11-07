# Completion audit:3B model/optimizer sharding on fleet

Objective:モデルとoptimizer状態を分割し、fleetの3B分散学習を3台でも検証。
各台10GiB以内、速度・loss・通信量・保存再開を確認し、このMacでは動かさない。

| Requirement | Direct observed evidence |
| --- | --- |
| Actual3B model, partitioned parameters and optimizer state | `3b-w{2,3}-{prefix,suffix}-rank*.json`:3,003,341,824 coefficients,304 distinct parameter tensors, exact complete ownership; BF16 model and owner-local FP32 SGD/NVMe masters. Summary checks layer/tied-owner assignments and total counts. |
| Physical2 and3 fleet nodes | Distinct host cohorts, world2/world3 actual process reports, independent native limit receipts; each group completes all four updates. Physical small-model oracle checks all gradients, weights, masters and clipping on2/3 separate M4s. |
| Every node within10GiB training envelope | Every actual process requests10GiB/readbacks fatal9GiB, lifetime footprint peaks8.4759GiB(two nodes) and6.5804GiB(three). All source/host/OS-bound CPU/Metal qualification controls pass; before/after swap unchanged. |
| Speed, loss and communication measured | `3b-summary.json` plus raw trace/mesh/NIC/processIO/supervisors:256tokens/layout once, phase4.9514/6.6312tok/s, init/load/eval/save-inclusive2.1228/3.2297tok/s; CE and bounded network-accounting scopes retained. |
| Checkpoint save and actual restart | Prefix checkpoint2 then new suffix processes restore that global manifest/startstep2 and reachstep4; boundary CE error0 and complete sampler stream identical. Same-layout global certificates agree on every rank. |
| Saved files actually readable and consistent | `3b-w*-readback-rank*.json` plus supervisors:all608 tensor files/generation for both generations and layouts rehashed/header-checked in separate processes; sampler states exact;249weight/304master hashes changed2→4. Six small-model cooperative refusals occur before tensor loading. |
| No model execution on developer Mac | All model/gradient/optimizer/weight/reference checks run via wired SSH on the three named fleet hosts. Local `summarize_3b.hy` imports only stdlib and derives metadata arithmetic. GB artifacts stay on fleet. |

This is operational distributed-learning and same-layout restart verification:
four updates/256tokens per layout. **Held-out improvement is not established**
(10.599606→10.615460/10.604201), and this audit does not qualify convergence,
cross-layout BF16 bitwise parity, AdamW, EP, overlapping pipeline, elastic
resharding/recovery or30B duration. Those remain in the broader JUN-936 plan.
No production route, runtime, CI workflow, existing service or unrelated work
was changed. PR publication/remote-head verification is recorded in JUN-936.
