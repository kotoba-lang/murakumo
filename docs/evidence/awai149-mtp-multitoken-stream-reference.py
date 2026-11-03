import hashlib,json,pathlib
import numpy as np
import mlx.core as mx
import mlx.nn as nn
from mlx.utils import tree_unflatten
from mlx_lm.models.qwen3_5 import TextModelArgs,DecoderLayer
from mlx_lm.models.cache import ArraysCache,KVCache
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear,HadamardQuantizedEmbedding
from safetensors import safe_open
from mtplx.mtp_patch import MTPContract,inject_mtp_support,validate_mtp_support
mx.set_memory_limit(2*1024**3)
model=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai';config=json.loads((model/'config.json').read_text());args=TextModelArgs.from_dict(config['text_config']);assert args.num_hidden_layers==64 and args.hidden_size==5120
model_file=str(model/'model.safetensors')
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
class SparseEmbedding(nn.Module):
 def __call__(self,ids):
  token_id=int(ids.item());assert 0<=token_id<args.vocab_size;embed=HadamardQuantizedEmbedding(1,5120,block=1024);prefix='language_model.model.embed_tokens.'
  with safe_open(model_file,framework='numpy') as f:values={'weight':mx.array(f.get_slice(prefix+'weight')[token_id:token_id+1,:]),'scales':mx.array(f.get_slice(prefix+'scales')[token_id:token_id+1,:]),'biases':mx.array(f.get_slice(prefix+'biases')[token_id:token_id+1,:]),'signs':mx.array(f.get_slice(prefix+'signs')[:])}
  embed.update(values);return embed(mx.zeros_like(ids))
class Inner(nn.Module):
 def __init__(self):super().__init__();self.embed_tokens=SparseEmbedding()
class Text(nn.Module):
 def __init__(self,head):super().__init__();self.args=args;self.model=Inner();self.lm_head=head
caches=[ArraysCache(size=2) if (i+1)%args.full_attention_interval else KVCache() for i in range(64)]
head=HadamardQuantizedLinear(5120,args.vocab_size,block=1024);pre='language_model.lm_head.'
with safe_open(model_file,framework='numpy') as f:values={k:mx.array(f.get_tensor(pre+k)) for k in ('weight','scales','biases','signs')}
head.update(values);del values;norm=nn.RMSNorm(5120,eps=args.rms_norm_eps)
with safe_open(model_file,framework='numpy') as f:norm.update({'weight':mx.array(f.get_tensor('language_model.model.norm.weight')).astype(mx.float16)})
draftmodel=Text(head);contract=MTPContract().with_config_defaults(config);assert inject_mtp_support(draftmodel,model,config,contract) and validate_mtp_support(draftmodel);draftcache=draftmodel.make_mtp_cache()
prompt=[9419,11,353,1044];primary=None;history=0;results=[];draft=None
for step in range(len(prompt)+7):
 token=prompt[step] if step<len(prompt) else primary;embed=HadamardQuantizedEmbedding(1,5120,block=1024);pre='language_model.model.embed_tokens.'
 with safe_open(model_file,framework='numpy') as f:values={'weight':mx.array(f.get_slice(pre+'weight')[token:token+1,:]),'scales':mx.array(f.get_slice(pre+'scales')[token:token+1,:]),'biases':mx.array(f.get_slice(pre+'biases')[token:token+1,:]),'signs':mx.array(f.get_slice(pre+'signs')[:])}
 embed.update(values);h=embed(mx.array([[0]],mx.int32));mx.eval(h);del embed,values
 for i in range(64):
  layer,_=load_layer(i);h=layer(h,mask=None,cache=caches[i]);mx.eval(h);del layer;mx.clear_cache()
 hidden=norm(h);mx.eval(hidden)
 if step<len(prompt)-1:
  hist=draftmodel.mtp_update_cache(hidden,mx.array([[prompt[step+1]]],mx.int32),mtp_cache=draftcache,concat_order=contract.concat_order,mtp_hidden_variant=contract.hidden_variant);mx.eval(hist);history+=1
 else:
  logits=head(hidden);mx.eval(logits);primary=int(mx.argmax(logits,axis=-1).item());previous_draft=draft
  draftlogits=draftmodel.mtp_forward(hidden,mx.array([[primary]],mx.int32),mtp_cache=draftcache,concat_order=contract.concat_order,mtp_hidden_variant=contract.hidden_variant);mx.eval(draftlogits);draft=int(mx.argmax(draftlogits,axis=-1).item())
  results.append({'target':primary,'precedingDraft':previous_draft,'nextDraft':draft,'targetSha256':hashlib.sha256(np.asarray(logits).tobytes()).hexdigest(),'draftSha256':hashlib.sha256(np.asarray(draftlogits).tobytes()).hexdigest()})
 print(json.dumps({'phase':'reference-progress','step':step,'layers':64}),flush=True)
print(json.dumps({'phase':'multi-token-reference','historyUpdates':history,'results':results}),flush=True)
