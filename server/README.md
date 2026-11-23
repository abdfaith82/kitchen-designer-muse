# Cloud Render Server (simulated)

A dependency-free Python 3 (stdlib-only) HTTP server implementing the cloud
render API used by the Android app's `HttpCloudRenderClient`.

## Run

```sh
python3 render_server.py            # listens on 0.0.0.0:8080
PORT=9000 python3 render_server.py  # custom port
```

Docker:

```sh
docker build -t kitchen-render-server .
docker run -p 8080:8080 kitchen-render-server
```

## Test

```sh
python3 -m unittest test_render_server -v
```

## API summary

| Method | Path | Description |
|---|---|---|
| GET | `/health` | `{"ok": true}` |
| POST | `/v1/renders` | Submit a job. Body: `{"projectId", "quality", "cameraName", "lightingMode", "scene": {...}}` → `201 {"id": "job-0001", "status": "QUEUED", "progress": 0}` |
| GET | `/v1/renders/{id}` | Job status: `{"id", "status", "progress", "resultUrl", "errorMessage"}`; `404 {"error": "unknown job"}` for unknown ids |
| POST | `/v1/renders/{id}/cancel` | Cancels the job → `{"id", "status": "FAILED", "errorMessage": "cancelled by client"}` |
| GET | `/v1/renders/{id}/result.png` | The rendered PNG (`image/png`) once the job has SUCCEEDED |

Jobs advance deterministically: QUEUED → RUNNING with progress +25 every
0.25 s tick → SUCCEEDED at 100 after ~1 s. If the submitted `scene` JSON
contains `"forceFail": true`, the job instead fails with
`"scene validation failed (forced)"`. Full contract: `openapi.yaml`.

## Honest note

This is **simulated path tracing**: progress is simulated by a worker thread
and the "render" is a small placeholder gradient PNG generated in pure
Python. No GPU, no ray tracing. A real GPU path tracer is expected to plug
in behind this exact same API (same endpoints and JSON shapes) without the
Android client changing.
