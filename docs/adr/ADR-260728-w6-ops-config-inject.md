# ADR-260728: residual ops config inject (exact-name getenv)

Status: accepted after product-shell pure dual-source complete (#122–#136)

## Decision

Delivery residual ops shells must not scatter bare `System/getenv` for
**config** values. Centralize exact-name reads on `murakumo.config`:

| helper | env name | default |
|---|---|---|
| `cloud-url` | `MURAKUMO_CLOUD` | `https://api.murakumo.cloud` |
| `api-url` | `MURAKUMO_API_URL` | same default |
| `text-backend-url` | `MURAKUMO_TEXT_BACKEND_URL` | `http://localhost:11434` |
| `image-checkpoint` | `MURAKUMO_DEFAULT_IMAGE_CKPT` | animagine…safetensors |
| `kotoba-cli-bin` | `MURAKUMO_KOTOBA_BIN` | bare `kotoba` |
| `infer-local-url` | `MURAKUMO_INFER_LOCAL_URL` | localhost Ollama /v1 |
| `infer-node-name` | `MURAKUMO_INFER_NODE_NAME` | nil |
| `git-bin-override` | `MURAKUMO_GIT_BIN` | nil |
| `adapter-driver-command` | driver-env names | nil |

Inject `getenv` (fn or map) for tests. Process default uses exact `System/getenv`.

### Secrets unchanged

Named secret fetch remains `murakumo.secret` (service/metrics/token/path-refs).

### Wired hosts

- `infer.orchestrate`, `infer.gateway`, `infer.media` model-map push
- `infer.relay-server` cloud post, `infer.relay-worker` join env
- `core` pin git-bin, `overlay.transport` adapter drivers

## Evidence

- config unit tests for inject maps
- existing gateway/media/relay tests

## Related

- `docs/w6-secret-getenv-audit.md` (config leave vs secret cutover)
- handoff Delivery 5–8 residual ops shells
