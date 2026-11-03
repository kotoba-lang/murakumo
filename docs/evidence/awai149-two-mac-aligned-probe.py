import json, math
import mlx.core as mx
world=mx.distributed.init(backend='ring'); rank=world.rank(); n=world.size()
assert n == 2
check=mx.distributed.all_sum(mx.array(rank+1),group=world);mx.eval(check)
assert int(check.item())==3
mx.random.seed(41)
x=mx.random.normal((2,2048)).astype(mx.float16)
w=mx.random.normal((128,2048)).astype(mx.float16)
weight,scales,biases=mx.quantize(w,group_size=128,bits=2)
signs=mx.where(mx.arange(2048)%3==0,mx.array(-1.,dtype=mx.float32),mx.array(1.,dtype=mx.float32))
def rotate(a,s,block):
 return mx.hadamard_transform((a.astype(mx.float32)*s).reshape(-1,block),scale=1/math.sqrt(block)).reshape(a.shape).astype(a.dtype)
def matmul(a,w,s,b):
 return mx.quantized_matmul(a,w,s,b,transpose=True,group_size=128,bits=2)
def require_aligned(width,block,n):
 if width%n or (width//n)%128 or (block and (width//n)%block): raise ValueError('unaligned-shard')
for block in (0,1024):
 z=rotate(x,signs,block) if block else x
 full=matmul(z,weight,scales,biases)
 rows=64;start=rank*rows
 y=matmul(z,weight[start:start+rows],scales[start:start+rows],biases[start:start+rows])
 expected=full[:,start:start+rows]
 mx.eval(y,expected)
 diff=float(mx.max(mx.abs(y-expected)).item())
 assert diff==0.0
 width=2048;require_aligned(width,block,n); half=width//n; a=rank*half;b=a+half
 local=rotate(x[:,a:b],signs[a:b],block) if block else x[:,a:b]
 partial=matmul(local,weight[:,a//16:b//16],scales[:,a//128:b//128],biases[:,a//128:b//128])
 merged=mx.distributed.all_sum(partial,group=world);mx.eval(full,merged)
 error=float(mx.max(mx.abs(merged-full)).item())
 print(json.dumps({'rank':rank,'size':n,'block':block,'allToShardedMaxDiff':diff,'shardedToAllMaxDiff':error,'shardedToAllTolerance':0.25}),flush=True)
 assert error <= 0.25
try: require_aligned(2048,2048,n);raise AssertionError('accepted invalid split')
except ValueError: print(json.dumps({'rank':rank,'case':'split-hadamard-block','result':'refused'}),flush=True)
