import hashlib,json,pathlib
import mlx.core as mx
import mlx.nn as nn
from mlx.utils import tree_unflatten
from mlx_lm.models.qwen3_5 import TextModelArgs,DecoderLayer
from mlx_lm.models.cache import ArraysCache,KVCache
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear
from safetensors import safe_open
mx.set_memory_limit(2 * 1024**3)
world=mx.distributed.init(backend='ring');rank=world.rank();size=world.size();assert size==3
model=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai';config=json.loads((model/'config.json').read_text());args=TextModelArgs.from_dict(config['text_config'])
def load_layer(index):
 layer=DecoderLayer(args,index);prefix='model.layers.%d.'%index;modules=dict(layer.named_modules());replacements=[]
 for record in config['modules']:
  if record['path'].startswith(prefix):
   name=record['path'][len(prefix):];original=modules[name];assert isinstance(original,nn.Linear) and 'bias' not in original
   rows,width=original.weight.shape;replacements.append((name,HadamardQuantizedLinear(width,rows,block=record['block'])))
 layer.update_modules(tree_unflatten(replacements));packed=tuple(name+'.' for name,_ in replacements);fileprefix='language_model.'+prefix;h=hashlib.sha256();readbytes=0
 with safe_open(str(model/'model.safetensors'),framework='numpy') as f:
  names=sorted(k for k in f.keys() if k.startswith(fileprefix));weights=[]
  for key in names:
   name=key[len(fileprefix):];value=f.get_tensor(key);readbytes+=value.nbytes;h.update(name.encode());h.update(value.tobytes());v=mx.array(value)
   if not name.startswith(packed) and mx.issubdtype(v.dtype,mx.floating) and v.dtype!=mx.float16:v=v.astype(mx.float16)
   weights.append((name,v))
 layer.load_weights(weights,strict=True);mx.eval(layer.parameters());return layer,h.hexdigest(),readbytes
def make_cache(layer):return ArraysCache(size=2) if layer.is_linear else KVCache()
if rank==0:
 ref=[load_layer(i) for i in (2,3,4)];stage,shash,stagebytes=ref[0];refcaches=[make_cache(x[0]) for x in ref];refhashes=[x[1] for x in ref]
else:
 stage,shash,stagebytes=load_layer((2,3,4)[rank]);ref=None;refhashes=[]
stagecache=make_cache(stage);mx.random.seed(41);results=[]
for step in range(2):
 if rank==0:
  x=(mx.random.normal((1,1,5120))*0.01).astype(mx.float16);expected=x
  for (layer,_,_),cache in zip(ref,refcaches):expected=layer(expected,mask=None,cache=cache)
  mx.eval(expected);y=stage(x,mask=None,cache=stagecache);mx.eval(y);mx.eval(mx.distributed.send(y,1,group=world));got=mx.distributed.recv_like(mx.zeros((1,1,5120),mx.float16),2,group=world);mx.eval(got)
  assert bool(mx.all(mx.isfinite(got)).item());diff=float(mx.max(mx.abs(got-expected)).item());assert diff==0.0;results.append(diff)
 else:
  x=mx.distributed.recv_like(mx.zeros((1,1,5120),mx.float16),rank-1,group=world);mx.eval(x);y=stage(x,mask=None,cache=stagecache);mx.eval(y);mx.eval(mx.distributed.send(y,(rank+1)%size,group=world))
print(json.dumps({'rank':rank,'stage':(2,3,4)[rank],'isLinear':stage.is_linear,'layerTensorBytes':stagebytes,'stageFingerprint':shash,'referenceFingerprints':refhashes,'tokens':2,'maxAbsoluteDifferences':results}),flush=True)
