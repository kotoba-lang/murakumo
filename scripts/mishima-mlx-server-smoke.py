#!/usr/bin/env python3
"""Measure the OpenAI-compatible MLX canary, including its queue boundary."""

import argparse
import concurrent.futures
import json
import time
import urllib.request
from pathlib import Path


def request(base, index, max_tokens):
    body = {
        "model": "mishima",
        "messages": [
            {
                "role": "user",
                "content": "Explain resilient distributed inference in numbered steps and continue until the token limit. "
                f"Request {index}.",
            }
        ],
        "max_tokens": max_tokens,
        "temperature": 0,
        "stream": False,
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"content-type": "application/json"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=600) as response:
        result = json.load(response)
    return {
        "http_status": response.status,
        "wall_seconds": time.perf_counter() - started,
        "usage": result["usage"],
        "timings": result["timings"],
        "finish_reason": result["choices"][0]["finish_reason"],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18082")
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument("--output")
    args = parser.parse_args()
    health = json.load(urllib.request.urlopen(args.base.rstrip("/") + "/health"))
    rows = []
    for concurrency in (1, 2):
        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            results = list(
                pool.map(
                    lambda i: request(args.base, i, args.max_tokens),
                    range(concurrency),
                )
            )
        wall = time.perf_counter() - started
        tokens = sum(row["usage"]["completion_tokens"] for row in results)
        rows.append(
            {
                "concurrency": concurrency,
                "wall_seconds": wall,
                "completion_tokens": tokens,
                "aggregate_completion_tok_s": tokens / wall,
                "requests": results,
            }
        )
    evidence = {
        "schema": "murakumo.mishima-mlx-server-smoke/v1",
        "health": health,
        "rows": rows,
        "interpretation": "The canary exposes one serialized MLX slot; concurrency two measures queueing, not continuous batching.",
    }
    encoded = json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
