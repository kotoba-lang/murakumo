import json,math,pathlib
from safetensors import safe_open
import mlx.core as mx
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear
world=mx.distributed.init(backend='ring');rank=world.rank();size=world.size();assert size==3
model=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai/model.safetensors'
prefix='language_model.model.layers.63.self_attn.v_proj.'
mx.random.seed(41); x=mx.random.normal((1,5120)).astype(mx.float16)
# The full reference exists only on rank 0, exclusively for this correctness test.
if rank==0:
 with safe_open(str(model),framework='numpy') as f:
  whole={k:mx.array(f.get_tensor(prefix+k)) for k in ('weight','scales','biases','signs')}
 reference_layer=HadamardQuantizedLinear(5120,1024,block=1024);reference_layer.update(whole)
 reference=reference_layer(x);mx.eval(reference)
 del reference_layer,whole
else: reference=mx.zeros((1,1024),dtype=mx.float16)
reference=mx.distributed.all_sum(reference,group=world);mx.eval(reference)
# All-to-sharded: weights are sliced by output rows; input and signs replicated.
rowlo,rowhi=((0,342),(342,683),(683,1024))[rank]
with safe_open(str(model),framework='numpy') as f:
 raw={'weight':f.get_slice(prefix+'weight')[rowlo:rowhi,:],
      'scales':f.get_slice(prefix+'scales')[rowlo:rowhi,:],
      'biases':f.get_slice(prefix+'biases')[rowlo:rowhi,:],
      'signs':f.get_slice(prefix+'signs')[:]}
rowbytes=sum(a.nbytes for a in raw.values())
rowlayer=HadamardQuantizedLinear(5120,rowhi-rowlo,block=1024);rowlayer.update({k:mx.array(v) for k,v in raw.items()})
row=rowlayer(x);mx.eval(row)
rowdiff=float(mx.max(mx.abs(row-reference[:,rowlo:rowhi])).item())
assert rowdiff==0
# Sharded-to-all: weights and signs are sliced on whole input rotation blocks.
del rowlayer,raw,row
start,end=((0,2048),(2048,4096),(4096,5120))[rank]
with safe_open(str(model),framework='numpy') as f:
 raw={'weight':f.get_slice(prefix+'weight')[:,start//16:end//16],
      'scales':f.get_slice(prefix+'scales')[:,start//128:end//128],
      'biases':f.get_slice(prefix+'biases')[:,start//128:end//128],
      'signs':f.get_slice(prefix+'signs')[start:end]}
colbytes=sum(a.nbytes for a in raw.values())
collayer=HadamardQuantizedLinear(end-start,1024,block=1024);collayer.update({k:mx.array(v) for k,v in raw.items()})
partial=collayer(x[:,start:end]); merged=mx.distributed.all_sum(partial,group=world);mx.eval(merged)
diff=float(mx.max(mx.abs(merged-reference)).item());maximum=float(mx.max(mx.abs(reference)).item())
assert math.isfinite(diff) and diff<=0.25
print(json.dumps({'rank':rank,'ranks':size,'inputWidth':end-start,'outputRows':rowhi-rowlo,'rowShardTensorBytes':rowbytes,'inputShardTensorBytes':colbytes,'fullLayerTensorBytes':1495040,'referenceFullLayerLoadedOnThisRank':rank==0,'outputShardMaxAbsDiff':rowdiff,'inputShardMaxAbsDiff':diff,'referenceMaxAbs':maximum,'tolerance':0.25}),flush=True)
