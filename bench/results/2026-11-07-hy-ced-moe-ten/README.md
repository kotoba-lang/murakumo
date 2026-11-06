# JUN-936: actual10-owner RAM vs NVMe AdamW state comparison

Owner:10×M4/16GiB fleet, eachrequested10GiB/fatal9GiB, no developer-Mac
model/training/oracle execution. Reuse PR624/JUN936; preserve old source-bound
single/2/3-node checkpoint sources. Study ledger preregisters32updates/2048tokens
per placement,16+new-process16 withcheckpoint16/32 and fixeddev512tokens.

Rank0joseph retains its existing inference server and owns tiedembedding/head
only; other9owners hold contiguous layers. Everyrank has trainable parameters,
participates in forward/backward, clipping and globalcheckpoint. Rank0's empty
layer stage propagates shared/source cotangents through an identity VJP.
This deliberately uneven layout is the same for bothconditions.

FP32 master/m/v use the same optimizer equations and global step/warmup in
both placements. RAM uses evaluated MLX arrays in unified memory (no extra
CPU memory pool); NVMe streams individual FP32 tensorfiles. Both export full
weights/master/m/v to owner-local durable checkpoints; placement mismatches
refuse before allocation. Logical state access bytes are not physicaldiskbytes.

Qualify host-specific CPU/Metal fatal controls and physical10rank F32 gradients/
weights/master/m/v against fullmodel reference, then2+2 vscontinuous4 and
cooperative corrupt/missing/options/placement/generation refusal controls in
both backends. Actual3B follows only after those pass.

All nodes currently negotiate1Gbps, not10Gbps. Existing trusted name/Tailscale
SSH-key aliases resolve stale LAN-IP associations; no knownhost/credential or
network/service change. Small JSON/rawreceipts go here; GB artifacts remain on
owners. Runtime/data copied from alreadyqualifiedfleet, no new package/model
artifact download. No productionruntime/CI additions.

## Qualified implementation unit

All10hosts pass freshly executed host-specific CPU/Metal fatal controls. Both
placements pass physical10owner3update F32 vsfullmodel loss/allownedgradients/
weights/master/m/v comparisons: maxgradient3.12925e-6,weight/master5.961e-8
under existing2e-5 absolute limit. Tiny2+newprocess2 vscontinuous4 restores
allweights/master/m/v exactly at boundary plus sampler/MLXRNG; maximum
continuation7.824e-7<2e-5, not bitwise equivalence.10 cooperative refusalcases
include wrongplacement, missingm/v, badbeta andmixedgeneration, all before
tensor loading/store construction. Failedprepare creates no globalcommit.
Allcontrol children exit0/reap within requested2/fatal1GiB. Actual3B capacity,
RAM/NVMe performance/loss comparison and fullcheckpoint readback remain pending.
Eachhost has its own recorded OS qualification; do not assume identicalmacOS.
