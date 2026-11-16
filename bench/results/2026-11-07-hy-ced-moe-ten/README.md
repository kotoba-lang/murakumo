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

## Actual3B NVMe condition complete

Frozen source087603fd,10physical owners,32updates/2048tokens,16+newprocess16.
All10guards/finiteupdates/exit0-reap receipts pass, maxfootprint4.20942GiB.
FixeddevCE10.625805→8.739648; periodicdev-inclusive4.29910tok/s,update-only
4.60493,init/eval/save/load-inclusive3.93505tok/s over520.45142maxrank seconds.
Save16/32max11.787/5.226s, load4.611s; boundaryCE exact. Separate fullreadback
checks bothglobal generations'1216files/42,046,905,998bytes and sampler/update/
warmup/source/runtime/data/options/layout/generation. RAMcondition pending.

Joseph retained its co-located inference server. Hostswap4419.50→5354.56MiB
(+935.06) during thiscondition, others did not increase (zebulun decreased).
This is shared-host, fixed-order evidence; do not claim isolated backend
bandwidth, zero paging or unchanged inference latency. No service was stopped.
Initial NVMe-prefix snapshots use executable-name process listings, insufficient
to identify Hyargument names; own-child completion is proved by supervisors.
Later snapshots/finalaudit inspect fullarguments only for our ownscript names.

## Paired actual3B result: same2048tokens/32updates per placement

| Metric | NVMe state | RAM state |
| -- | --: | --: |
| FixeddevCE initial | 10.625805 | 10.625805 |
| FixeddevCE final | 8.739648 | 9.042288 |
| Update-only tok/s | 4.60493 | 9.09449 |
| Periodicdev-inclusive tok/s | 4.29910 | 7.97905 |
| Init/eval/save/load-inclusive tok/s | 3.93505 | 7.00156 |
| Sum maxrank supervisor seconds | 520.45142 | 292.50637 |
| Max learner footprintGiB | 4.20942 | 8.36196 |
| Save16 /save32 max seconds | 11.787 /5.226 | 9.543 /5.634 |
| Actualglobal restore seconds | 4.611 | 2.451 |
| BoundaryCE error after newprocess restore | 0 | 0 |
| Logicalboundarypayload globalonce | 254.04096MB | 254.04096MB |
| WholeNIC TX perrank | 602.65–615.01MB | 602.31–611.20MB |
| OSprocess diskread all10 | 999.999GB | 2.53230GB |
| OSprocess diskwrite all10 | 1309.595GB | 84.10473GB |

All10owners pass requested10GiB/fatal9GiB readback and finite updates, all
children exit0/reaped/no timeout. Exact32inputhash sequences, config, source,
corpus, partition and options exceptplacement match. FinaldevRAM is0.302639
higher: both improve, but equalconvergence/causal qualityranking is not proved.
Do not substitute a better intermediate loss. F32 controls prove the optimizer
equations withinexisting tolerance; BF16 crossrun trajectory parity remains open.

Observed RAM all-in speed is1.77928x, periodicdev-inclusive1.85598x, with93.5778%
fewer processdiskwritebytes. RAM updates perform no statefilewrites;84.105GB
remainingwrites include its two fullglobal durablecheckpoints. Readcache effects
are included. Rates count globaltokens once and sum each phase's maxranktime;
recording/CI/Linear gaps and independentreadback are excluded. No repeat/cache
flush. WholeNIC includes background/control; exclusivewirebytes=null. Logical
stateaccessbytes inRAM are not diskI/O. SSDdevice-wide snapshots are retained
and independently parsed, including otheractivity; no NAND/exclusivetransferclaim.

RAM also passes separateprocess readback of both1216file/42,046,905,998byte
globalgenerations; allhash/header/shape/dtype/owner/source/runtime/data/options/
seals/globalcertificate and32samplerinputs/globalstep/warmup pass. Eachbackend
changedweight/master/m/v303/304/303/303shards from16→32. Finalnodeaudits confirm
all10 ownworkers/supervisors/readers andport25711listeners absent. Allfourcomplete
snapshots remain onowners; older1B/2/3owner checkpoints/sources are preserved.

Joseph remains head-only and co-located inference server is retained. NVMe
hostswap grows935.06MiB, RAMhostswap unchanged; othernodes show no growth.
Do not interpret this fixedorder sharedhost observation as an isolated intrinsic
backend benchmark or infer unchanged productioninference latency. Currentlinks
are1Gbps. This qualifies limited10owner/3B placement+restart, not sufficient
pretraining, Bonsai intelligence, nativeinference integration, elasticresharding
or30Bcapacity/duration.
