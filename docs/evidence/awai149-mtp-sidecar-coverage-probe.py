import json,pathlib
import mlx.core as mx
import mlx.nn as nn
from safetensors import safe_open
from mlx_lm.models.qwen3_5 import TextModelArgs
from mtplx.mtp_patch import MTPContract,inject_mtp_support,validate_mtp_support,_load_mtp_weights
from mlx.utils import tree_flatten
from mtplx.models.prism_hadamard_qwen35 import HadamardQuantizedLinear,HadamardQuantizedEmbedding
mx.set_memory_limit(2*1024**3)
path=pathlib.Path.home()/'.murakumo/awai147-mtplx-bonsai';cfg=json.loads((path/'config.json').read_text());args=TextModelArgs.from_dict(cfg['text_config']);model_file=str(path/'model.safetensors')
class SparseEmbedding(nn.Module):
 def __call__(self,ids):
  token=int(ids.item());assert 0<=token<args.vocab_size;embed=HadamardQuantizedEmbedding(1,5120,block=1024);pre='language_model.model.embed_tokens.'
  with safe_open(model_file,framework='numpy') as f:values={'weight':mx.array(f.get_slice(pre+'weight')[token:token+1,:]),'scales':mx.array(f.get_slice(pre+'scales')[token:token+1,:]),'biases':mx.array(f.get_slice(pre+'biases')[token:token+1,:]),'signs':mx.array(f.get_slice(pre+'signs')[:])}
  embed.update(values);return embed(mx.zeros_like(ids))
class Inner(nn.Module):
 def __init__(self):super().__init__();self.embed_tokens=SparseEmbedding()
class Text(nn.Module):
 def __init__(self):super().__init__();self.args=args;self.model=Inner();self.lm_head=HadamardQuantizedLinear(5120,args.vocab_size,block=1024)
model=Text();pre='language_model.lm_head.'
with safe_open(model_file,framework='numpy') as f:vals={k:mx.array(f.get_tensor(pre+k)) for k in ('weight','scales','biases','signs')}
model.lm_head.update(vals);del vals
contract=MTPContract().with_config_defaults(cfg);assert inject_mtp_support(model,path,cfg,contract) and validate_mtp_support(model)
loaded=_load_mtp_weights(path/'mtp.safetensors',cfg,prequantized=True)
actual={k for k,_ in tree_flatten(model.mtp.parameters())};provided=set(loaded);missing=sorted(actual-provided);extra=sorted(provided-actual)
print(json.dumps({'mtpParameterLeaves':len(actual),'sidecarKeys':len(provided),'missingCount':len(missing),'extraCount':len(extra),'missingSample':missing[:5],'extraSample':extra[:5]}),flush=True)
assert not missing and not extra
logits=model.mtp_forward(mx.zeros((1,1,5120),mx.float16),mx.array([[11]],mx.int32),mtp_cache=model.make_mtp_cache(),concat_order=contract.concat_order,mtp_hidden_variant=contract.hidden_variant)
mx.eval(logits);assert bool(mx.all(mx.isfinite(logits)).item());print(json.dumps({'draftForward':'executed','headLayers':len(model.mtp.layers),'logitShape':list(logits.shape),'finite':True,'candidateTokenId':int(mx.argmax(logits,axis=-1).item()),'input':'synthetic-zero-hidden','contractPositionMode':contract.mtp_position_mode}),flush=True)
