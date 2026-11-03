import hashlib,json,pathlib
import numpy as np
import mlx.core as mx
import mlx.nn as nn
from mlx.utils import tree_unflatten
from mlx_lm.models.qwen3_5 import TextModelArgs,DecoderLayer
from mlx_lm.models.cache import ArraysCache,KVCache
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear,HadamardQuantizedEmbedding
from safetensors import safe_open
mx.set_memory_limit(4*1024**3)
world=mx.distributed.init(backend='ring');rank=world.rank();size=world.size();assert size==3
model=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai';config=json.loads((model/'config.json').read_text());args=TextModelArgs.from_dict(config['text_config']);assert args.num_hidden_layers==64 and args.hidden_size==5120
starts=(0,21,42);ends=(21,42,64);start,end=starts[rank],ends[rank];model_file=str(model/'model.safetensors')
def load_layer(index):
 layer=DecoderLayer(args,index);prefix='model.layers.%d.'%index;modules=dict(layer.named_modules());replacements=[]
 for record in config['modules']:
  if record['path'].startswith(prefix):
   name=record['path'][len(prefix):];original=modules[name];assert isinstance(original,nn.Linear) and 'bias' not in original
   rows,width=original.weight.shape;replacements.append((name,HadamardQuantizedLinear(width,rows,block=record['block'])))
 layer.update_modules(tree_unflatten(replacements));packed=tuple(name+'.' for name,_ in replacements);fileprefix='language_model.'+prefix;readbytes=0
 with safe_open(model_file,framework='numpy') as f:
  names=sorted(k for k in f.keys() if k.startswith(fileprefix));weights=[]
  for key in names:
   name=key[len(fileprefix):];value=f.get_tensor(key);readbytes+=value.nbytes;v=mx.array(value)
   if not name.startswith(packed) and mx.issubdtype(v.dtype,mx.floating) and v.dtype!=mx.float16:v=v.astype(mx.float16)
   weights.append((name,v))
 layer.load_weights(weights,strict=True);mx.eval(layer.parameters());return layer,readbytes
layers=[];totalbytes=0
for i in range(start,end):
 layer,n=load_layer(i);layers.append(layer);totalbytes+=n
 if (i-start+1)%5==0:print(json.dumps({'rank':rank,'phase':'loading','layers':i-start+1}),flush=True)
caches=[ArraysCache(size=2) if layer.is_linear else KVCache() for layer in layers]
if rank==2:
 head=HadamardQuantizedLinear(5120,args.vocab_size,block=1024);pre='language_model.lm_head.'
 with safe_open(model_file,framework='numpy') as f:
  raw={k:f.get_tensor(pre+k) for k in ('weight','scales','biases','signs')};headbytes=sum(v.nbytes for v in raw.values());values={k:mx.array(v) for k,v in raw.items()}
 head.update(values);del values,raw;norm=nn.RMSNorm(5120,eps=args.rms_norm_eps)
 with safe_open(model_file,framework='numpy') as f:norm.update({'weight':mx.array(f.get_tensor('language_model.model.norm.weight')).astype(mx.float16)})
 mx.eval(head.parameters(),norm.parameters())
else:headbytes=0
print(json.dumps({'rank':rank,'phase':'ready','layerStart':start,'layerEnd':end,'layerTensorBytes':totalbytes,'headTensorBytes':headbytes}),flush=True)
mx.eval(mx.distributed.all_sum(mx.array(1),group=world));token=9419;generated=[];digests=[]
for step in range(2):
 if rank==0:
  assert 0<=token<args.vocab_size
  embed=HadamardQuantizedEmbedding(1,5120,block=1024);pre='language_model.model.embed_tokens.'
  with safe_open(model_file,framework='numpy') as f:
   values={'weight':mx.array(f.get_slice(pre+'weight')[token:token+1,:]),'scales':mx.array(f.get_slice(pre+'scales')[token:token+1,:]),'biases':mx.array(f.get_slice(pre+'biases')[token:token+1,:]),'signs':mx.array(f.get_slice(pre+'signs')[:])}
  embed.update(values);h=embed(mx.array([[0]],mx.int32));mx.eval(h);del embed,values
 else:
  h=mx.distributed.recv_like(mx.zeros((1,1,5120),mx.float16),rank-1,group=world);mx.eval(h)
 for layer,cache in zip(layers,caches):
  h=layer(h,mask=None,cache=cache);mx.eval(h)
 assert bool(mx.all(mx.isfinite(h)).item())
 if rank<2:mx.eval(mx.distributed.send(h,rank+1,group=world))
 else:
  logits=head(norm(h));mx.eval(logits);assert bool(mx.all(mx.isfinite(logits)).item());digests.append(hashlib.sha256(np.asarray(logits).tobytes()).hexdigest());choice=mx.argmax(logits,axis=-1).astype(mx.int32).reshape(1);mx.eval(choice);generated.append(int(choice.item()));mx.eval(mx.distributed.send(choice,0,group=world))
 if rank==0:
  choice=mx.distributed.recv_like(mx.zeros((1,),mx.int32),2,group=world);mx.eval(choice);token=int(choice.item());generated.append(token)
print(json.dumps({'rank':rank,'phase':'complete','steps':2,'generatedTokenIds':generated,'logitSha256':digests,'layerStart':start,'layerEnd':end,'layerTensorBytes':totalbytes,'headTensorBytes':headbytes}),flush=True)
