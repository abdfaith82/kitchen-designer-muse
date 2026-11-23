#!/usr/bin/env python3
"""Simulated cloud render server for the Kitchen Designer app.

Pure Python 3 standard library. Implements the /v1/renders API described in
openapi.yaml. Progress is simulated (a worker thread advances jobs
deterministically); the result is a placeholder PNG. A real GPU path tracer
can be plugged in behind the same API later.

HONESTY NOTE: GPU path tracing is still SIMULATED. Render quality presets
(see PRESETS below) are validated for real and are carried through the job
payload into the progress/result metadata, and the simulated job duration
scales with the preset tier — but no path tracing actually happens and the
result image is a placeholder PNG at a fixed small size regardless of the
requested resolution.

Structure note: the queue logic lives in RenderQueue / make_png so it can be
imported and unit-tested without starting the HTTP server.
"""

import json
import os
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TICK_SECONDS = 0.25

STATUS_QUEUED = "QUEUED"
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"


# ---------------------------------------------------------------------------
# Render quality presets
# ---------------------------------------------------------------------------
# A job payload may carry a preset in one of two forms (both are validated;
# anything outside the allowed sets is rejected with HTTP 400):
#   * "preset": one of the named presets below, and/or
#   * explicit fields "resolutionWidth" / "samplesPerPixel" / "denoiser"
#     which override the named preset's values field by field.
# With neither form present the STANDARD preset applies. If explicit fields
# change any value of the named preset, the resolved preset is reported as
# "CUSTOM" in the job metadata.
#
# "ticks" is internal: the simulated worker needs that many advance ticks to
# finish, so higher tiers visibly take longer in progress polling. The output
# aspect is fixed 16:9, so resolutionHeight is derived from the width.
# These values are mirrored by the Kotlin `RenderPreset` enum — keep in sync.

ALLOWED_RESOLUTION_WIDTHS = (1280, 1920, 3840)
ALLOWED_SAMPLES_PER_PIXEL = (16, 64, 256, 1024)

PRESETS = {
    "PREVIEW":  {"resolutionWidth": 1280, "samplesPerPixel": 64,   "denoiser": True,  "ticks": 2},
    "STANDARD": {"resolutionWidth": 1920, "samplesPerPixel": 256,  "denoiser": True,  "ticks": 4},
    # At 1024 spp the (simulated) image is clean enough that the denoiser is
    # left off to preserve fine detail (wood grain, marble veining).
    "ULTRA":    {"resolutionWidth": 3840, "samplesPerPixel": 1024, "denoiser": False, "ticks": 8},
}

DEFAULT_PRESET_NAME = "STANDARD"


def resolve_preset(payload: dict) -> dict:
    """Validate the preset fields of a submit payload and return the resolved
    preset metadata dict. Raises ValueError with a client-facing message for
    any invalid value (the HTTP layer turns that into a 400)."""
    if not isinstance(payload, dict):
        raise ValueError("request body must be a JSON object")

    name = payload.get("preset", DEFAULT_PRESET_NAME)
    # "CUSTOM" is accepted as a name for clients that resolved overrides
    # themselves (the Kotlin RenderPresetSpec): it starts from the STANDARD
    # values, applies the explicit fields, and keeps the CUSTOM name.
    if not isinstance(name, str) or (name not in PRESETS and name != "CUSTOM"):
        raise ValueError(
            "invalid preset %r: must be one of %s"
            % (name, ", ".join(sorted(PRESETS) + ["CUSTOM"])))

    resolved = dict(PRESETS[DEFAULT_PRESET_NAME if name == "CUSTOM" else name])
    overridden = name == "CUSTOM"

    if "resolutionWidth" in payload:
        width = payload["resolutionWidth"]
        if isinstance(width, bool) or not isinstance(width, int) \
                or width not in ALLOWED_RESOLUTION_WIDTHS:
            raise ValueError(
                "invalid resolutionWidth %r: must be one of %s"
                % (width, ", ".join(str(w) for w in ALLOWED_RESOLUTION_WIDTHS)))
        overridden = overridden or width != resolved["resolutionWidth"]
        resolved["resolutionWidth"] = width

    if "samplesPerPixel" in payload:
        spp = payload["samplesPerPixel"]
        if isinstance(spp, bool) or not isinstance(spp, int) \
                or spp not in ALLOWED_SAMPLES_PER_PIXEL:
            raise ValueError(
                "invalid samplesPerPixel %r: must be one of %s"
                % (spp, ", ".join(str(s) for s in ALLOWED_SAMPLES_PER_PIXEL)))
        overridden = overridden or spp != resolved["samplesPerPixel"]
        resolved["samplesPerPixel"] = spp

    if "denoiser" in payload:
        denoiser = payload["denoiser"]
        if not isinstance(denoiser, bool):
            raise ValueError("invalid denoiser %r: must be true or false" % (denoiser,))
        overridden = overridden or denoiser != resolved["denoiser"]
        resolved["denoiser"] = denoiser

    return {
        "name": "CUSTOM" if overridden else name,
        "resolutionWidth": resolved["resolutionWidth"],
        "resolutionHeight": resolved["resolutionWidth"] * 9 // 16,
        "samplesPerPixel": resolved["samplesPerPixel"],
        "denoiser": resolved["denoiser"],
        # Honest marker: the render itself is simulated, only the preset
        # plumbing (validation, metadata, simulated duration) is real.
        "engine": "SIMULATED",
        "_ticks": resolved["ticks"],
    }


# ---------------------------------------------------------------------------
# Placeholder PNG (pure stdlib: zlib + struct)
# ---------------------------------------------------------------------------

def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    chunk = chunk_type + data
    return struct.pack(">I", len(data)) + chunk + struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)


def make_png(width: int = 16, height: int = 16) -> bytes:
    """Build a small valid RGB PNG: a simple horizontal gradient."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for each scanline
        for x in range(width):
            raw.append(int(255 * x / max(1, width - 1)))   # R gradient
            raw.append(int(255 * y / max(1, height - 1)))  # G gradient
            raw.append(128)                                # B constant
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(bytes(raw)))
        + _png_chunk(b"IEND", b"")
    )


PLACEHOLDER_PNG = make_png()


# ---------------------------------------------------------------------------
# Job store + deterministic worker
# ---------------------------------------------------------------------------

class RenderJob:
    def __init__(self, job_id: str, payload: dict):
        self.id = job_id
        self.payload = payload
        self.status = STATUS_QUEUED
        self.progress = 0
        self.step = 0  # per-job deterministic step counter
        self.result_url = None
        self.error_message = None
        # Resolved render preset (validated by RenderQueue.submit before the
        # job is constructed). Public metadata drops the internal "_ticks".
        self.preset = resolve_preset(payload)
        self.ticks_required = self.preset["_ticks"]
        scene = payload.get("scene") if isinstance(payload, dict) else None
        self.force_fail = isinstance(scene, dict) and scene.get("forceFail") is True

    def preset_metadata(self) -> dict:
        return {k: v for k, v in self.preset.items() if not k.startswith("_")}

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "status": self.status,
            "progress": self.progress,
            "resultUrl": self.result_url,
            "errorMessage": self.error_message,
            # Flat convenience field for the Kotlin client's simple parser,
            # plus the full preset metadata block.
            "preset": self.preset["name"],
            "renderPreset": self.preset_metadata(),
        }


class RenderQueue:
    """In-memory job store with a lock, plus one deterministic advance step.

    The HTTP server runs a background worker thread calling advance_all()
    every TICK_SECONDS; tests can call advance_all() directly.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._jobs = {}
        self._counter = 0

    def submit(self, payload: dict) -> RenderJob:
        """Queue a job. Raises ValueError (client-facing message) when the
        payload's preset fields are invalid; no job is created in that case
        and the id counter is not advanced."""
        resolve_preset(payload)  # validation only; RenderJob resolves again
        with self._lock:
            self._counter += 1
            job = RenderJob("job-%04d" % self._counter, payload)
            self._jobs[job.id] = job
            return job

    def get(self, job_id: str):
        with self._lock:
            return self._jobs.get(job_id)

    def cancel(self, job_id: str) -> bool:
        """Mark the job FAILED/cancelled. Returns True if the job existed."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return False
            if job.status not in (STATUS_SUCCEEDED, STATUS_FAILED):
                job.status = STATUS_FAILED
                job.error_message = "cancelled by client"
            return True

    def advance_all(self):
        """One deterministic tick: QUEUED -> RUNNING, progress advances by
        100/ticks_required per tick, SUCCEEDED at 100. The STANDARD preset
        needs 4 ticks (≈1s at the default tick rate, +25 per tick as before);
        PREVIEW finishes in 2 ticks and ULTRA in 8, so the simulated duration
        scales with the preset tier."""
        with self._lock:
            for job in self._jobs.values():
                if job.status in (STATUS_SUCCEEDED, STATUS_FAILED):
                    continue
                if job.force_fail:
                    job.status = STATUS_FAILED
                    job.error_message = "scene validation failed (forced)"
                    continue
                job.step += 1
                job.progress = min(100, job.step * 100 // job.ticks_required)
                if job.progress >= 100:
                    job.status = STATUS_SUCCEEDED
                    job.result_url = "/v1/renders/%s/result.png" % job.id
                else:
                    job.status = STATUS_RUNNING


# ---------------------------------------------------------------------------
# HTTP layer
# ---------------------------------------------------------------------------

def make_handler(queue: RenderQueue):
    class RenderHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # minimal stderr logging
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

        def _send_json(self, code: int, obj: dict):
            body = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_png(self, data: bytes):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            parts = [p for p in self.path.split("/") if p]
            if self.path == "/health":
                self._send_json(200, {"ok": True})
                return
            if len(parts) == 4 and parts[0] == "v1" and parts[1] == "renders" and parts[3] == "result.png":
                job = queue.get(parts[2])
                if job is None:
                    self._send_json(404, {"error": "unknown job"})
                elif job.status != STATUS_SUCCEEDED:
                    self._send_json(409, {"error": "result not ready", "status": job.status})
                else:
                    self._send_png(PLACEHOLDER_PNG)
                return
            if len(parts) == 3 and parts[0] == "v1" and parts[1] == "renders":
                job = queue.get(parts[2])
                if job is None:
                    self._send_json(404, {"error": "unknown job"})
                else:
                    self._send_json(200, job.to_dict())
                return
            self._send_json(404, {"error": "not found"})

        def do_POST(self):
            parts = [p for p in self.path.split("/") if p]
            if parts == ["v1", "renders"]:
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b"{}"
                try:
                    payload = json.loads(raw.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    self._send_json(400, {"error": "invalid JSON body"})
                    return
                try:
                    job = queue.submit(payload)
                except ValueError as e:
                    # Invalid preset values (unknown preset name, resolution
                    # width or samples-per-pixel outside the allowed sets,
                    # non-boolean denoiser, non-object body).
                    self._send_json(400, {"error": str(e)})
                    return
                self._send_json(201, {"id": job.id, "status": job.status,
                                      "progress": job.progress,
                                      "preset": job.preset["name"],
                                      "renderPreset": job.preset_metadata()})
                return
            if len(parts) == 4 and parts[0] == "v1" and parts[1] == "renders" and parts[3] == "cancel":
                if queue.cancel(parts[2]):
                    job = queue.get(parts[2])
                    self._send_json(200, {"id": job.id, "status": job.status,
                                          "errorMessage": job.error_message})
                else:
                    self._send_json(404, {"error": "unknown job"})
                return
            self._send_json(404, {"error": "not found"})

    return RenderHandler


def _worker_loop(queue: RenderQueue, stop_event: threading.Event):
    while not stop_event.is_set():
        queue.advance_all()
        stop_event.wait(TICK_SECONDS)


def make_server(host: str, port: int, queue: RenderQueue = None):
    """Create (but do not start) the HTTP server + worker thread."""
    queue = queue if queue is not None else RenderQueue()
    server = ThreadingHTTPServer((host, port), make_handler(queue))
    stop_event = threading.Event()
    worker = threading.Thread(target=_worker_loop, args=(queue, stop_event), daemon=True)
    return server, worker, stop_event


def main():
    port = int(os.environ.get("PORT", "8080"))
    server, worker, _stop = make_server("0.0.0.0", port)
    worker.start()
    sys.stderr.write("render server listening on 0.0.0.0:%d\n" % port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
