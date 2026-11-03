import hashlib,json,os,pathlib
import numpy as np
import mlx.core as mx
import mlx.nn as nn
from mlx.utils import tree_unflatten
from mlx_lm.models.qwen3_5 import TextModelArgs,DecoderLayer
from mlx_lm.models.cache import ArraysCache,KVCache
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear,HadamardQuantizedEmbedding
from safetensors import safe_open
from mtplx.mtp_patch import MTPContract,inject_mtp_support,validate_mtp_support
mx.set_memory_limit(4*1024**3)
world=mx.distributed.init(backend='ring');rank=world.rank();size=world.size();assert size==9
model=pathlib.Path(os.environ.get('AWAI_MODEL_DIR',str(pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai')));config=json.loads((model/'config.json').read_text());args=TextModelArgs.from_dict(config['text_config']);assert args.num_hidden_layers==64 and args.hidden_size==5120
starts=(0,7,14,21,28,35,42,49,56);ends=(7,14,21,28,35,42,49,56,64);start,end=starts[rank],ends[rank];model_file=str(model/'model.safetensors')
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
if rank==size-1:
 head=HadamardQuantizedLinear(5120,args.vocab_size,block=1024);pre='language_model.lm_head.'
 with safe_open(model_file,framework='numpy') as f:
  raw={k:f.get_tensor(pre+k) for k in ('weight','scales','biases','signs')};headbytes=sum(v.nbytes for v in raw.values());values={k:mx.array(v) for k,v in raw.items()}
 head.update(values);del values,raw;norm=nn.RMSNorm(5120,eps=args.rms_norm_eps)
 with safe_open(model_file,framework='numpy') as f:norm.update({'weight':mx.array(f.get_tensor('language_model.model.norm.weight')).astype(mx.float16)})
 mx.eval(head.parameters(),norm.parameters())
 class SparseEmbedding(nn.Module):
  def __call__(self,ids):
   token_id=int(ids.item());assert 0<=token_id<args.vocab_size;embed=HadamardQuantizedEmbedding(1,5120,block=1024);prefix='language_model.model.embed_tokens.'
   with safe_open(model_file,framework='numpy') as f:values={'weight':mx.array(f.get_slice(prefix+'weight')[token_id:token_id+1,:]),'scales':mx.array(f.get_slice(prefix+'scales')[token_id:token_id+1,:]),'biases':mx.array(f.get_slice(prefix+'biases')[token_id:token_id+1,:]),'signs':mx.array(f.get_slice(prefix+'signs')[:])}
   embed.update(values);return embed(mx.zeros_like(ids))
 class Inner(nn.Module):
  def __init__(self):super().__init__();self.embed_tokens=SparseEmbedding()
 class Text(nn.Module):
  def __init__(self):super().__init__();self.args=args;self.model=Inner();self.lm_head=head
 draftmodel=Text();contract=MTPContract().with_config_defaults(config);assert inject_mtp_support(draftmodel,model,config,contract) and validate_mtp_support(draftmodel);draftcache=draftmodel.make_mtp_cache();draft=None;draftdigest=None;accepted=None
else:headbytes=0
print(json.dumps({'rank':rank,'phase':'ready','layerStart':start,'layerEnd':end,'layerTensorBytes':totalbytes,'headTensorBytes':headbytes}),flush=True)
mx.eval(mx.distributed.all_sum(mx.array(1),group=world))
for prompt in ([9419,11,353,1044],[760,3841,13477,37550],[12162,5028,264,854],[760,6511,314,6124,369]):
 caches=[ArraysCache(size=2) if layer.is_linear else KVCache() for layer in layers];primary=None
 if rank==size-1:draftcache=draftmodel.make_mtp_cache();draft=None;history=0;digests=[];draftdigest=None
 for step in range(len(prompt)+1):
  if rank==0:
   token=prompt[step] if step<len(prompt) else primary;assert 0<=token<args.vocab_size
   embed=HadamardQuantizedEmbedding(1,5120,block=1024);pre='language_model.model.embed_tokens.'
   with safe_open(model_file,framework='numpy') as f:values={'weight':mx.array(f.get_slice(pre+'weight')[token:token+1,:]),'scales':mx.array(f.get_slice(pre+'scales')[token:token+1,:]),'biases':mx.array(f.get_slice(pre+'biases')[token:token+1,:]),'signs':mx.array(f.get_slice(pre+'signs')[:])}
   embed.update(values);h=embed(mx.array([[0]],mx.int32));mx.eval(h);del embed,values
  else:
   h=mx.distributed.recv_like(mx.zeros((1,1,5120),mx.float16),rank-1,group=world);mx.eval(h)
  for layer,cache in zip(layers,caches):h=layer(h,mask=None,cache=cache);mx.eval(h)
  assert bool(mx.all(mx.isfinite(h)).item())
  if rank<size-1:mx.eval(mx.distributed.send(h,rank+1,group=world))
  else:
   hidden=norm(h);mx.eval(hidden)
   if step<len(prompt)-1:
    hist=draftmodel.mtp_update_cache(hidden,mx.array([[prompt[step+1]]],mx.int32),mtp_cache=draftcache,concat_order=contract.concat_order,mtp_hidden_variant=contract.hidden_variant);mx.eval(hist);history+=1;reply=mx.array([-1],mx.int32)
   else:
    logits=head(hidden);mx.eval(logits);assert bool(mx.all(mx.isfinite(logits)).item());digests.append(hashlib.sha256(np.asarray(logits).tobytes()).hexdigest());reply=mx.argmax(logits,axis=-1).astype(mx.int32).reshape(1);mx.eval(reply)
    if step==len(prompt)-1:
     primary=int(reply.item());draftlogits=draftmodel.mtp_forward(hidden,reply.reshape(1,1),mtp_cache=draftcache,concat_order=contract.concat_order,mtp_hidden_variant=contract.hidden_variant);mx.eval(draftlogits);draftdigest=hashlib.sha256(np.asarray(draftlogits).tobytes()).hexdigest();draft=int(mx.argmax(draftlogits,axis=-1).item())
    else:
     target=int(reply.item());print(json.dumps({'rank':rank,'phase':'mtp-context-case','promptTokenIds':prompt,'historyUpdates':history,'primaryTokenId':primary,'draftTokenId':draft,'verifiedTargetTokenId':target,'accepted':draft==target,'draftLogitSha256':draftdigest,'targetLogitSha256':digests,'verification':'serial-greedy'}),flush=True)
   mx.eval(mx.distributed.send(reply,0,group=world))
  if rank==0:
   reply=mx.distributed.recv_like(mx.zeros((1,),mx.int32),size-1,group=world);mx.eval(reply)
   if step==len(prompt)-1:primary=int(reply.item())
print(json.dumps({'rank':rank,'phase':'complete','cases':4,'layerStart':start,'layerEnd':end}),flush=True)
