# One-off local measurement (not a gate, not a route): parameter count and
# multiply-accumulate budget of ComfyUI v0.44.0's SDXL UNet, built on the meta
# device (no weights) and run with attention replaced by a shape-recording stub.
#   cd <ComfyUI-0.44.0> && PYTHONPATH=<bench/oracle/comfyui/stubs>:. python3.11 sdxl_budget.py
# Counts Conv2d and Linear output-size x fan-in, plus 2*B*Lq*Lk*D per attention.
# GroupNorm, SiLU, softmax, layernorm and elementwise work are not counted.
import logging; logging.disable(logging.CRITICAL)
import torch, comfy.ops
import comfy.ldm.modules.attention as at
from comfy.ldm.modules.diffusionmodules.openaimodel import UNetModel
ops=comfy.ops.disable_weight_init
att={"macs":0,"rows":[]}
def T(x): return x.peek() if hasattr(x,"peek") else x
def fake(q,k,v,heads,mask=None,*a,**kw):
    q=T(q); k=T(k)
    B,Lq,Dq=q.shape; Lk=k.shape[1]
    att["macs"]+=2*B*Lq*Lk*Dq
    return torch.empty(B,Lq,Dq,device="meta")
at.optimized_attention=fake; at.optimized_attention_masked=fake
cfg=dict(image_size=32,in_channels=4,model_channels=320,out_channels=4,num_res_blocks=[2,2,2],
  dropout=0,channel_mult=(1,2,4),conv_resample=True,dims=2,num_classes="sequential",use_checkpoint=False,
  num_heads=-1,num_head_channels=64,use_scale_shift_norm=False,resblock_updown=False,use_spatial_transformer=True,
  transformer_depth=[0,0,2,2,10,10],transformer_depth_output=[0,0,0,2,2,2,10,10,10],transformer_depth_middle=10,
  context_dim=2048,adm_in_channels=2816,use_linear_in_transformer=True,disable_self_attentions=None,legacy=False)
with torch.device("meta"):
    m=UNetModel(**cfg,operations=ops,device=torch.device("meta"),dtype=torch.float32)
print("unet params",sum(p.numel() for p in m.parameters()))
macs={"conv":0,"linear":0}
def hook_conv(mod,inp,out): macs["conv"]+=out.numel()*(mod.in_channels//mod.groups)*mod.kernel_size[0]*mod.kernel_size[1]
def hook_lin(mod,inp,out): macs["linear"]+=out.numel()*mod.in_features
for mod in m.modules():
    if isinstance(mod,torch.nn.Conv2d): mod.register_forward_hook(hook_conv)
    elif isinstance(mod,torch.nn.Linear): mod.register_forward_hook(hook_lin)
for res in (64,96,128):
    macs["conv"]=macs["linear"]=0; att["macs"]=0
    x=torch.empty(1,4,res,res,device="meta"); t=torch.empty(1,device="meta"); c=torch.empty(1,77,2048,device="meta"); y=torch.empty(1,2816,device="meta")
    m(x,t,context=c,y=y)
    tot=macs["conv"]+macs["linear"]+att["macs"]
    print(f"latent {res}x{res} ({res*8}px) per UNet call, batch 1: conv {macs['conv']/1e9:.0f} G, linear {macs['linear']/1e9:.0f} G, attention {att['macs']/1e9:.0f} G, total {tot/1e9:.0f} GMAC")
