#!/usr/bin/env python3
"""Single-slot OpenAI-compatible server for the Mishima MLX canary."""

import argparse
import asyncio
import json
import sys
import threading
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
import uvicorn


class Message(BaseModel):
    role: str
    content: str = ""


class CompletionRequest(BaseModel):
    model: str = "mishima"
    messages: list[Message]
    max_tokens: int = Field(default=1024, ge=1, le=4096)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    top_p: float = Field(default=0.8, gt=0.0, le=1.0)
    stream: bool = False


def create_app(model_dir, policy_path):
    model_dir = Path(model_dir).resolve()
    sys.path.insert(0, str(model_dir / "runtime"))
    from vision_artifact import chat_config, load_vl_model
    from mlx_vlm import generate
    from mlx_vlm.prompt_utils import apply_chat_template

    load_started = time.perf_counter()
    model, processor, config = load_vl_model(model_dir)
    loaded_seconds = time.perf_counter() - load_started
    policy = Path(policy_path).read_text().strip()
    inference_lock = threading.Lock()
    app = FastAPI(title="Murakumo Mishima MLX", version="1")

    def infer(request):
        messages = [message.model_dump() for message in request.messages]
        messages.insert(0, {"role": "system", "content": policy})
        prompt = apply_chat_template(
            processor, chat_config(config), messages, num_images=0
        )
        started = time.perf_counter()
        with inference_lock:
            result = generate(
                model,
                processor,
                prompt,
                [],
                max_tokens=request.max_tokens,
                temperature=request.temperature,
                top_p=request.top_p,
                verbose=False,
            )
        wall = time.perf_counter() - started
        return result, wall

    @app.get("/health")
    def health():
        return {
            "ok": True,
            "model": "mishima",
            "backend": "mlx-vlm-hadamard",
            "slots": 1,
            "load_seconds": loaded_seconds,
        }

    @app.get("/v1/models")
    def models():
        return {
            "object": "list",
            "data": [
                {
                    "id": "mishima",
                    "object": "model",
                    "owned_by": "murakumo",
                    "context_window": config["text_config"]["max_position_embeddings"],
                    "max_output_tokens": 4096,
                }
            ],
        }

    @app.post("/v1/chat/completions")
    async def completions(request: CompletionRequest):
        if request.model not in ("mishima", "mishima-mlx"):
            raise HTTPException(status_code=404, detail="unknown model")
        result, wall = await asyncio.to_thread(infer, request)
        completion_id = "chatcmpl-" + uuid.uuid4().hex
        created = int(time.time())
        usage = {
            "prompt_tokens": result.prompt_tokens,
            "completion_tokens": result.generation_tokens,
            "total_tokens": result.prompt_tokens + result.generation_tokens,
        }
        timings = {
            "prompt_per_second": result.prompt_tps,
            "predicted_per_second": result.generation_tps,
            "wall_seconds": wall,
            "peak_memory_gb": result.peak_memory,
        }
        if not request.stream:
            return JSONResponse(
                {
                    "id": completion_id,
                    "object": "chat.completion",
                    "created": created,
                    "model": "mishima",
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": result.text},
                            "finish_reason": result.finish_reason,
                        }
                    ],
                    "usage": usage,
                    "timings": timings,
                }
            )

        async def events():
            chunk = {
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": created,
                "model": "mishima",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"role": "assistant", "content": result.text},
                        "finish_reason": None,
                    }
                ],
            }
            yield "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
            chunk["choices"][0]["delta"] = {}
            chunk["choices"][0]["finish_reason"] = result.finish_reason
            chunk["usage"] = usage
            chunk["timings"] = timings
            yield "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
            yield "data: [DONE]\n\n"

        return StreamingResponse(events(), media_type="text/event-stream")

    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", default="/Users/Shared/murakumo/mishima-mlx")
    parser.add_argument("--policy", default="deploy/mishima-uncensor-system.txt")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18082)
    args = parser.parse_args()
    uvicorn.run(create_app(args.model_dir, args.policy), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
