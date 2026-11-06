# Stub for ComfyUI's optional accelerator package comfy-kitchen (not installed on
# purpose): reports every accelerated path unavailable so ComfyUI falls back to its
# plain PyTorch attention. It does not change any math the comparison runs.
def int8_attention_is_available(*a, **k): return False
def sol_attn_is_available(*a, **k): return False
def __getattr__(name):
    raise AttributeError(f"comfy_kitchen stub has no {name}")
