import json
import mlx.core as mx
import mlx.nn as nn
from mlx.nn.layers.distributed import shard_linear
class LocalGroup:
 def size(self):return 1
 def rank(self):return 0
class RotatedPacked(nn.Module):
 def __init__(self):
  super().__init__();self.bits=2;self.group_size=128;self.block=512
  mx.random.seed(42)
  self.weight,self.scales,self.biases=mx.quantize(mx.random.normal((128,512)).astype(mx.float16),128,2)
  self.signs=mx.where(mx.arange(512)%2==0,mx.array(1.0),mx.array(-1.0))
 def __call__(self,x):
  z=(x.astype(mx.float32)*self.signs).reshape(-1,self.block)
  z=mx.hadamard_transform(z,scale=1/(self.block**0.5)).reshape(x.shape).astype(x.dtype)
  return mx.quantized_matmul(z,self.weight,self.scales,self.biases,transpose=True,group_size=128,bits=2)
layer=RotatedPacked(); x=mx.random.normal((1,512)).astype(mx.float16); original=layer(x);mx.eval(original)
control=nn.QuantizedLinear(512,128,bias=False,group_size=128,bits=2); plain=control(x);sharded=shard_linear(control,'all-to-sharded',group=LocalGroup()); control_after=sharded(x);mx.eval(plain,control_after);print(json.dumps({'case':'ordinary-quantized-control','maxAbsoluteDifference':float(mx.max(mx.abs(plain-control_after)).item()),'groupSize':1},sort_keys=True))
try:
 converted=shard_linear(layer,'all-to-sharded',group=LocalGroup()); after=converted(x);mx.eval(original,after)
 difference=float(mx.max(mx.abs(original-after)).item())
 print(json.dumps({'case':'synthetic-rotated-quantized-projection','groupSize':1,'sameShape':original.shape==after.shape,'maxAbsoluteDifference':difference,'parity':difference==0.0,'convertedClass':type(converted).__name__},sort_keys=True))
except Exception as e:print(json.dumps({'case':'synthetic-rotated-quantized-projection','groupSize':1,'errorType':type(e).__name__,'error':str(e)[:200]},sort_keys=True))
