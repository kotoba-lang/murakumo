"""Authenticate the private Modal origin without putting its key in argv.

Response framing (2026-09-15).  A JSON completion is read WHOLE from vLLM
and answered with its own Content-Length; only `text/event-stream` (a
client that asked for `stream: true`) is relayed chunk by chunk.  The
first version streamed every body with `transfer-encoding` and
`content-length` stripped, so uvicorn re-framed it as chunked, and in the
minutes after a memory-snapshot restore Modal's own relay logged
`ClientPayloadError: Response payload is not completed (Not enough data
for satisfy transfer length header)` four times in two minutes, each on a
request vLLM had answered 200 (qwen38-flash-next-cyber, 12:44-12:45Z).  A
buffered body cannot be cut short between its length header and its end.

The httpx client keeps no idle connection to vLLM: the process is
snapshotted with vLLM asleep and restored minutes or days later, and a
pooled keep-alive socket from before the snapshot is exactly the kind of
half-dead connection that yields a truncated read on the first request
after restore.  The hop is localhost; a new connection per request is
free.
"""

import os
import secrets

import httpx
from fastapi import FastAPI, Request
from starlette.background import BackgroundTask
from starlette.responses import JSONResponse, Response, StreamingResponse

UPSTREAM = "http://127.0.0.1:8001"
HOP_BY_HOP = {
    "connection",
    "content-length",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
}

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
client = httpx.AsyncClient(
    timeout=None,
    limits=httpx.Limits(max_keepalive_connections=0),
)


def _authorized(request: Request) -> bool:
    expected = os.environ.get("MURAKUMO_MODAL_ORIGIN_TOKEN", "")
    supplied = request.headers.get("authorization", "")
    return bool(expected) and secrets.compare_digest(supplied, f"Bearer {expected}")


@app.api_route("/{path:path}", methods=["GET", "POST"])
async def proxy(request: Request, path: str) -> Response:
    if not _authorized(request):
        return JSONResponse({"error": "unauthorized origin"}, status_code=401)

    headers = {
        key: value
        for key, value in request.headers.items()
        if key.lower() not in HOP_BY_HOP and key.lower() != "host"
    }
    try:
        upstream_request = client.build_request(
            request.method,
            f"{UPSTREAM}/{path}",
            params=request.query_params,
            headers=headers,
            content=await request.body(),
        )
        upstream = await client.send(upstream_request, stream=True)
    except httpx.ConnectError:
        # The public gateway retries a 503 while a scale-to-zero model loads.
        return JSONResponse({"error": "model loading"}, status_code=503)

    response_headers = {
        key: value
        for key, value in upstream.headers.items()
        if key.lower() not in HOP_BY_HOP
    }
    media_type = upstream.headers.get("content-type")
    if media_type and media_type.startswith("text/event-stream"):
        return StreamingResponse(
            upstream.aiter_raw(),
            status_code=upstream.status_code,
            headers=response_headers,
            media_type=media_type,
            background=BackgroundTask(upstream.aclose),
        )
    try:
        body = await upstream.aread()
    except httpx.HTTPError as error:
        # vLLM dropped the connection mid-body: say so with a status the
        # gateway treats as a failed run, never a 200 with a torn body.
        await upstream.aclose()
        return JSONResponse(
            {"error": {"type": "upstream_read_error", "message": type(error).__name__}},
            status_code=502,
        )
    await upstream.aclose()
    return Response(
        content=body,
        status_code=upstream.status_code,
        headers=response_headers,
        media_type=media_type,
    )
