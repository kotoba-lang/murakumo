import hashlib,json,math,pathlib
import numpy as np
from safetensors import safe_open
import mlx.core as mx
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear
rankgroup=mx.distributed.init(backend='ring');rank=rankgroup.rank();size=rankgroup.size()
assert size==3
model=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai/model.safetensors'
prefix='language_model.model.layers.63.self_attn.v_proj.'
with safe_open(str(model),framework='numpy') as data:
 tensors={k:data.get_tensor(prefix+k) for k in ('weight','scales','biases','signs')}
hashctx=hashlib.sha256()
for k in sorted(tensors): hashctx.update(k.encode());hashctx.update(tensors[k].tobytes())
fingerprint=hashctx.hexdigest()
w,s,b,signs=(mx.array(tensors[k]) for k in ('weight','scales','biases','signs'))
assert w.shape==(1024,320) and s.shape==(1024,40) and b.shape==(1024,40) and signs.shape==(5120,)
assert bool(mx.all((signs==1)|(signs==-1)).item())
mx.random.seed(41);x=mx.random.normal((1,5120)).astype(mx.float16)
def rotate(a,sign):
 return mx.hadamard_transform((a.astype(mx.float32)*sign).reshape(-1,1024),scale=1/math.sqrt(1024)).reshape(a.shape).astype(a.dtype)
def project(a,w,s,b):return mx.quantized_matmul(a,w,s,b,transpose=True,group_size=128,bits=2)
full=project(rotate(x,signs),w,s,b)
actual=HadamardQuantizedLinear(5120,1024,block=1024)
actual.update({'weight':w,'scales':s,'biases':b,'signs':signs})
actual_output=actual(x);mx.eval(actual_output,full)
mtplx_diff=float(mx.max(mx.abs(actual_output-full)).item())
assert mtplx_diff==0.0
lo,hi=((0,342),(342,683),(683,1024))[rank]
row=project(rotate(x,signs),w[lo:hi],s[lo:hi],b[lo:hi]);mx.eval(full,row)
rowdiff=float(mx.max(mx.abs(row-full[:,lo:hi])).item())
# Five 1024-wide blocks cannot be evenly divided into three rank-local blocks.
# Give the first two ranks two blocks each and the third rank one block.
start,end=((0,2048),(2048,4096),(4096,5120))[rank]
part=project(rotate(x[:,start:end],signs[start:end]),w[:,start//16:end//16],s[:,start//128:end//128],b[:,start//128:end//128])
result=mx.distributed.all_sum(part,group=rankgroup);mx.eval(result)
diff=float(mx.max(mx.abs(result-full)).item());reference=float(mx.max(mx.abs(full)).item())
assert math.isfinite(diff) and rowdiff==0 and diff<=0.25
print(json.dumps({'rank':rank,'ranks':size,'modelTensorFingerprintSha256':fingerprint,'module':'layers.63.self_attn.v_proj','input':5120,'output':1024,'localInput':end-start,'block':1024,'allToShardedMaxAbsDiff':rowdiff,'shardedToAllMaxAbsDiff':diff,'fullOutputMaxAbs':reference,'diagnosticTolerance':0.25,'mtplxRuntimeMaxAbsDiff':mtplx_diff}),flush=True)
