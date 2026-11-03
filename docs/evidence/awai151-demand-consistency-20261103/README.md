# Demand observation consistency

`demand-score` is queued demand plus a nonnegative weighted completion count.
A challenger score smaller than the queue count therefore cannot describe the
same observation. Previously, such a mismatch could be treated as no challenger
and still release an idle holder, or allow a starvation release. The planner now
returns `invalid-observation` and does not request revocation. The host must
obtain the two values from the same coherent sample; the inequality alone does
not prove freshness or atomicity.

The actual Kotoba source and boundary tests were combined into a small unity
fixture and compiled on the local ARM64 Mac using the same native compiler and
diagnostic packager as the [previous demand observation](../awai151-demand-overflow-20261103/).
The signed fixture passed inconsistent zero and positive scores, a valid
starvation case, and the existing boundary cases. A test-only mutant removing
the new comparison exited 1. These tests do not exercise a real workload,
coherent host sampling, governor integration, process supervision or fleet
admission; the policy is not an authorization to terminate existing work.

Planner SHA-256 `87b918ec0659692908266946ef5ec6bb7753054ff68e151c4be0ecb51eca2a07`;
test `35985560b9a2b6b3c1aabf4e31996f42f7d3bfcc336a9f1523c277e485e113be`;
signed fixture `338db0e31913ffa32d628e935c50f2615311e3223237d6efe6da8575bcbe3223`;
signed mutant `7a9608f5f204af1055b5cc4525d4cf157ad6098760c3ee2efc70a03aaea3652d`.
