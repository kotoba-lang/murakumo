#!/usr/bin/env python3
"""Measure one-slot Mishima heads independently and as a fleet.

Each --head is NAME=URL.  One request is issued per head for the fleet row;
--node-concurrency is only a diagnostic for one selected head and does not
change the production one-slot policy.
"""
import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.request


def completion(name, url, tokens, prompt, rounds=1):
    body = {"model": "mishima", "messages": [{"role": "user", "content": prompt}],
            "max_tokens": tokens, "temperature": 0, "stream": False}
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"content-type": "application/json"})
    started = time.perf_counter()
    used = 0
    finishes = []
    for _ in range(rounds):
        with urllib.request.urlopen(req, timeout=900) as response:
            result = json.load(response)
        used += int(result.get("usage", {}).get("completion_tokens", 0))
        finishes.append(result.get("choices", [{}])[0].get("finish_reason"))
    elapsed = time.perf_counter() - started
    return {"node": name, "latency_s": elapsed, "completion_tokens": used,
            "completion_tok_s": used / elapsed if elapsed else 0,
            "rounds": rounds, "finish_reasons": finishes}


def aggregate(rows, wall, concurrency):
    tokens = sum(row["completion_tokens"] for row in rows)
    latencies = sorted(row["latency_s"] for row in rows)
    return {"concurrency": concurrency, "heads": len(rows), "wall_s": wall,
            "completion_tokens": tokens, "aggregate_completion_tok_s": tokens / wall,
            "steady_state_sum_head_tok_s": sum(row["completion_tok_s"] for row in rows),
            "mean_head_tok_s": statistics.mean(row["completion_tok_s"] for row in rows),
            "p50_latency_s": latencies[len(latencies) // 2], "max_latency_s": max(latencies),
            "rows": rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--head", action="append", required=True, help="NAME=URL")
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument("--rounds", type=int, default=1,
                        help="sequential requests per head; removes one-shot load variance")
    parser.add_argument("--prompt", default="Hello")
    args = parser.parse_args()
    heads = [item.split("=", 1) for item in args.head]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(heads)) as pool:
        rows = list(pool.map(lambda pair: completion(pair[0], pair[1], args.max_tokens, args.prompt, args.rounds), heads))
    print(json.dumps(aggregate(rows, time.perf_counter() - started, len(heads)), indent=2))


if __name__ == "__main__":
    main()
