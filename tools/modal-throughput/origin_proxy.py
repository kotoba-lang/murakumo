"""Authenticate the private Modal origin without putting its key in argv."""

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
client = httpx.AsyncClient(timeout=None)


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
    return StreamingResponse(
        upstream.aiter_raw(),
        status_code=upstream.status_code,
        headers=response_headers,
        media_type=media_type,
        background=BackgroundTask(upstream.aclose),
    )

