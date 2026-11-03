# Refresh lease source in the cron composition

The [previously checked source](../awai150-current-source-check-20261103/)
included historical lease code. Comparing function bodies against this
worktree found that `valid-value?`, `decide`, `decide-epoch`, `held-now?`,
`lg-epoch-ready?` and `lg-locked-request` lacked current time bounds or
explicit refusal of an unknown existence response. The old source is not a
safe basis for a new build. This is a source mismatch, not evidence that a
specific deployed voter accepted a malformed lease.

The attached test-only assembler adds the current entire lease core and
group to the previous assembly. All 20 and 8 corresponding function bodies
respectively matched their current worktree files byte-for-byte. Together with
the previously refreshed files, 186 selected functions are drawn from the
current sources. Other dependencies still come from the historical unity input
and have not all been pinned or qualified. The Mac bootstrap compiler accepted
the new `unity.kotoba` with `check` under a five-second CPU limit; it was not
compiled or run as a controller.

As a targeted distinction, a separate native fixture extracted `lease-skew`
and `held-now?` from each unity source. Given a synthetic minimum i64 expiry,
the refreshed function refused and exited 0; the earlier function treated the
lease as held and the negative control exited 1. This does not prove that a
real authenticated voter accepts that input, or test lease quorum, controller
execution, GitHub, model inference or production readiness.

| Artifact or source | SHA-256 |
| --- | --- |
| refreshed unity | `1cf279ecea4495d4e57aee659477e574f7a82f30a8d1ee6c5fa3fb4bf9e1daf6` |
| test-only assembler | `6d3732bc1b6482e742b72a819847d558929b803e2553975234c63265d941542a` |
| current lease core | `76bba32a0e8c5057069b302139f5b55e1fd396ed9fc749e7a882d7806d491279` |
| current lease group | `a59b17fff673dcb6280caf704d08fe7f0a3293f2d3315f7d1553164ced24516f` |
| signed current fixture | `313563984cf3157d9be18eca1c4c96a28df5a7608edf325645858bee0672fec3` |
| signed old control | `4cef0da973b03f1fd3ea5dd396c76070e23e8bde751c7a55663323e1756fef11` |

Compiler and historical assembly-input hashes are in the earlier source
composition evidence. The assembler uses task-specific paths and a temporary
historical source; adapt it before rerunning.
