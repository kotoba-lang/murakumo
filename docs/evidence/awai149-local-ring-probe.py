import json
import mlx.core as mx
import mlx.nn as nn
from mlx.nn.layers.distributed import shard_linear
world=mx.distributed.init(backend='ring'); rank=world.rank(); size=world.size()
total=mx.distributed.all_sum(mx.array(rank+1),group=world); mx.eval(total)
print(json.dumps({'case':'collective','rank':rank,'size':size,'sum':int(total.item())}),flush=True)
mx.random.seed(19)
x=mx.random.normal((1,512)).astype(mx.float16)
control=nn.QuantizedLinear(512,128,bias=False,group_size=128,bits=2)
expected=control(x); mx.eval(expected)
try:
 converted=shard_linear(control,'all-to-sharded',group=world)
 actual=converted(x); mx.eval(actual)
 reference=expected[:,rank*64:(rank+1)*64]
 print(json.dumps({'case':'ordinary','rank':rank,'size':size,'shape':list(actual.shape),'diff':float(mx.max(mx.abs(reference-actual)).item())}),flush=True)
except Exception as e: print(json.dumps({'case':'ordinary','rank':rank,'size':size,'error':str(e)}),flush=True)
class Rotated(nn.Module):
 def __init__(self):
  super().__init__();self.bits=2;self.group_size=128;self.block=512
  self.weight,self.scales,self.biases=mx.quantize(mx.random.normal((128,512)).astype(mx.float16),128,2)
  self.signs=mx.where(mx.arange(512)%2==0,mx.array(1.0),mx.array(-1.0))
 def __call__(self,x):
  z=mx.hadamard_transform((x.astype(mx.float32)*self.signs).reshape(-1,self.block),scale=1/(self.block**0.5)).reshape(x.shape).astype(x.dtype)
  return mx.quantized_matmul(z,self.weight,self.scales,self.biases,transpose=True,group_size=128,bits=2)
try:
 layer=Rotated();converted=shard_linear(layer,'all-to-sharded',group=world);mx.eval(converted(x)); print(json.dumps({'case':'rotated','rank':rank,'size':size,'converted':True}),flush=True)
except Exception as e: print(json.dumps({'case':'rotated','rank':rank,'size':size,'errorType':type(e).__name__,'error':str(e)[:200]}),flush=True)
