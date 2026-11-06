# JUN-936: model and optimizer sharding

Active owner goal:3B distributed training, including three physical fleet M4s,
each requested10GiB/fatal9GiB, speed/loss/communication and saved-state restart.
No developer-Mac model execution. Full-model replication is not this acceptance.

`sharded_model.hy` assigns contiguous layers to their only owner. Embedding/input
and tied output head/final norm have one owner (rank0). Per-key initialization
defines the same global random model independently of2/3-rank layout. No teacher
checkpoint is imported. FP32-master SGD is assigned to the same owners on NVMe.
Activations, shared mainKV/index state, CED source and their cotangents cross
boundaries; weights/optimizer tensors do not travel with activations.

## Boundary correctness unit

Fleet naphtali only, small F32 full-model oracle vs sequentially composed2/3
logical stages, tiny512 vocabulary,12layers, sequence16/window4/chunk4,
attention/MoE checkpointing. Compare loss and all155 gradients against `model.loss`.
Both layouts match loss exactly and have maximum gradient difference9.313226e-10
or less. The test includes shared mainKV/indexer, decoder CED source and both
uses of embedding. Existing absolute2e-5/relative1e-4 controls were not loosened.
Requested2GiB/fatal1GiB, peak footprint109,691,504bytes, supervisor exit0/reaped.
This is the local-on-fleet mathematical control, not physical distributed3B.

Three-node qualification, physical pipeline, optimizer/global clipping, global
checkpoint/refusal controls and real3B save/restart were pending at that unit.
Original single-rank checkpoint-bound sources remain unchanged. No existing
service, credential/host key or unrelated workspace is changed.

## Physical2/3-owner controls

Both physical layouts complete three F32 SGD updates on the small512-vocabulary
model. Each rank compares every owned gradient, weight and FP32-master value to
the complete single-host mathematical oracle before/after each update. Keys
cover the full model exactly once. Maximum gradient error3.516675e-6, maximum
weight/master error2.980233e-8; loss error<5e-7, global norm error<1.1e-5.
Existing2e-5 absolute controls are retained. Every child exits0/reaped without
timeout under requested2GiB/fatal1GiB. These are real separate-M4 runs, but the
full-model oracle is small; they do not constitute3B capacity measurements.

The first3-rank attempt refused an unsupported uint32 selection-state dtype;
its peers reported loss and all children exited1/reaped. Add explicit uint32
transport (discrete state only); retain those failed supervisors. The successful
rerun is physical3-v2, not a reinterpretation of the failed run.

benjamin is a distinct M4/16GiB/macOS26.7.2 host, free90%, swap0, wired link-local.
The existing pinned runtime/site-packages/corpus were streamed directly from
naphtali, with no package downloads or GB storage on the developer Mac. Its
CPU128MiB/Metal384MiB positive-allocation then fatal-limit controls both pass
and bind its own qualification receipt to host/OS/source. No daemon/systemwide
limit or pre-existing job was changed. Whole-interface counters and transfer
bytes are distinct from measured training.

Global checkpoint and actual3B save/restart remain pending at this unit.
