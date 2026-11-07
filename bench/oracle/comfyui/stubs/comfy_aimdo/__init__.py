# Stub for ComfyUI's optional model-memory manager comfy-aimdo (not installed on
# purpose). The comparison runs two nn.Modules on CPU and never loads a model
# through ComfyUI's patcher, so none of this is reached; any real use raises.
import sys, types
class _Stub(types.ModuleType):
    def __getattr__(self, name):
        if name.startswith("__"): raise AttributeError(name)
        def _unavailable(*a, **k): raise RuntimeError(f"comfy_aimdo stub: {self.__name__}.{name} is not available")
        return _unavailable
for _n in ("host_buffer","vram_buffer","model_vbar","torch","malloc_graph","control","storage","model_mmap"):
    _m = _Stub(f"comfy_aimdo.{_n}"); sys.modules[f"comfy_aimdo.{_n}"] = _m; globals()[_n] = _m
