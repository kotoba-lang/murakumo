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
checkpoint/refusal controls and real3B save/restart are still pending at this unit.
Original single-rank checkpoint-bound sources remain unchanged. No existing
service, credential/host key or unrelated workspace is changed.
