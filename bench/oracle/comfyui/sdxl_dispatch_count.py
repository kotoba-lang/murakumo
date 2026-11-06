# One-off local measurement (not a gate, not a route): how many module calls (Conv2d, Linear, GroupNorm,
# LayerNorm, SiLU, GEGLU, attention) one forward pass of ComfyUI v0.44.0's SDXL UNet makes, on the meta
# device (no weights), with attention replaced by a shape-recording stub. A lower bound on GPU dispatches:
# residual adds, skip concatenations, reshapes, upsample and the time embedding are not counted.
#   cd <ComfyUI-0.44.0> && PYTHONPATH=<bench/oracle/comfyui/stubs>:. python3.11 sdxl_dispatch_count.py
import logging; logging.disable(logging.CRITICAL)
import torch, comfy.ops, collections
import comfy.ldm.modules.attention as at
from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
ops=comfy.ops.disable_weight_init
def T(x): return x.peek() if hasattr(x,"peek") else x
cnt=collections.Counter()
def fake(q,k,v,heads,mask=None,*a,**kw):
    q=T(q); cnt["attention"]+=1
    return torch.empty(q.shape,device="meta")
at.optimized_attention=fake; at.optimized_attention_masked=fake
cfg=dict(image_size=32,in_channels=4,model_channels=320,out_channels=4,num_res_blocks=[2,2,2],
  dropout=0,channel_mult=(1,2,4),conv_resample=True,dims=2,num_classes="sequential",use_checkpoint=False,
  num_heads=-1,num_head_channels=64,use_scale_shift_norm=False,resblock_updown=False,use_spatial_transformer=True,
  transformer_depth=[0,0,2,2,10,10],transformer_depth_output=[0,0,0,2,2,2,10,10,10],transformer_depth_middle=10,
  context_dim=2048,adm_in_channels=2816,use_linear_in_transformer=True,disable_self_attentions=None,legacy=False)
with torch.device("meta"):
    m=UNetModel(**cfg,operations=ops,device=torch.device("meta"),dtype=torch.float32)
for mod in m.modules():
    n=type(mod).__name__
    if isinstance(mod,(torch.nn.Conv2d,torch.nn.Linear,torch.nn.GroupNorm,torch.nn.LayerNorm,torch.nn.SiLU,torch.nn.GELU)):
        mod.register_forward_hook(lambda mod,i,o,n=n: cnt.update([n]))
    elif n=="GEGLU":
        mod.register_forward_hook(lambda mod,i,o: cnt.update(["GEGLU"]))
x=torch.empty(1,4,96,96,device="meta"); t=torch.empty(1,device="meta"); c=torch.empty(1,77,2048,device="meta"); y=torch.empty(1,2816,device="meta")
m(x,t,context=c,y=y)
print(dict(cnt)); print("module calls per UNet forward (matrix + norm + activation ops):",sum(cnt.values()))
