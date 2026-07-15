"""Import compatibility shim for headless Hunyuan3D Paint.

The pipeline only needs Blender for final OBJ→GLB conversion; Murakumo replaces
that step with trimesh, so importing mesh_utils in the isolated ROCm venv does
not require the large Blender Python wheel.
"""
