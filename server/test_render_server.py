#!/usr/bin/env python3
"""Tests for render_server: unit tests of RenderQueue + a full HTTP
integration test on an ephemeral port. Stdlib only (unittest, urllib)."""

import json
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

import render_server
from render_server import RenderQueue, make_png, make_server


def sample_payload(**scene_extra):
    scene = {"units": []}
    scene.update(scene_extra)
    return {
        "projectId": "p1",
        "quality": "FINAL_4K",
        "cameraName": "main",
        "lightingMode": "DAY",
        "scene": scene,
    }


class RenderQueueTest(unittest.TestCase):
    def test_submit_assigns_sequential_ids_and_queued(self):
        q = RenderQueue()
        j1 = q.submit(sample_payload())
        j2 = q.submit(sample_payload())
        self.assertEqual(j1.id, "job-0001")
        self.assertEqual(j2.id, "job-0002")
        self.assertEqual(j1.status, "QUEUED")
        self.assertEqual(j1.progress, 0)

    def test_advance_reaches_succeeded_at_100(self):
        q = RenderQueue()
        job = q.submit(sample_payload())
        q.advance_all()
        self.assertEqual(job.status, "RUNNING")
        self.assertEqual(job.progress, 25)
        for _ in range(3):
            q.advance_all()
        self.assertEqual(job.status, "SUCCEEDED")
        self.assertEqual(job.progress, 100)
        self.assertEqual(job.result_url, "/v1/renders/job-0001/result.png")
        # Further ticks are no-ops for a finished job.
        q.advance_all()
        self.assertEqual(job.status, "SUCCEEDED")

    def test_force_fail(self):
        q = RenderQueue()
        job = q.submit(sample_payload(forceFail=True))
        q.advance_all()
        self.assertEqual(job.status, "FAILED")
        self.assertEqual(job.error_message, "scene validation failed (forced)")

    def test_cancel_existing_and_unknown(self):
        q = RenderQueue()
        job = q.submit(sample_payload())
        self.assertTrue(q.cancel(job.id))
        self.assertEqual(job.status, "FAILED")
        self.assertEqual(job.error_message, "cancelled by client")
        self.assertFalse(q.cancel("job-9999"))

    def test_get_unknown_returns_none(self):
        self.assertIsNone(RenderQueue().get("nope"))


class PngTest(unittest.TestCase):
    def test_png_magic_and_chunks(self):
        data = make_png()
        self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertIn(b"IHDR", data)
        self.assertIn(b"IDAT", data)
        self.assertTrue(data.rstrip().endswith(b"IEND\xaeB`\x82"))


class HttpIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server, cls.worker, cls.stop = make_server("127.0.0.1", 0)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.worker.start()
        cls.base = "http://127.0.0.1:%d" % cls.port

    @classmethod
    def tearDownClass(cls):
        cls.stop.set()
        cls.server.shutdown()
        cls.server.server_close()

    def _req(self, method, path, payload=None):
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.headers, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read()

    def test_health(self):
        code, _, body = self._req("GET", "/health")
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body), {"ok": True})

    def test_full_flow_submit_poll_result(self):
        code, _, body = self._req("POST", "/v1/renders", sample_payload())
        self.assertEqual(code, 201)
        created = json.loads(body)
        self.assertEqual(created["status"], "QUEUED")
        job_id = created["id"]

        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            code, _, body = self._req("GET", "/v1/renders/%s" % job_id)
            self.assertEqual(code, 200)
            status = json.loads(body)
            if status["status"] == "SUCCEEDED":
                break
            time.sleep(0.1)
        self.assertIsNotNone(status)
        self.assertEqual(status["status"], "SUCCEEDED")
        self.assertEqual(status["progress"], 100)
        self.assertEqual(status["resultUrl"], "/v1/renders/%s/result.png" % job_id)

        code, headers, body = self._req("GET", status["resultUrl"])
        self.assertEqual(code, 200)
        self.assertEqual(headers.get_content_type(), "image/png")
        self.assertTrue(body.startswith(b"\x89PNG\r\n\x1a\n"))

    def test_unknown_job_404(self):
        code, _, body = self._req("GET", "/v1/renders/job-9999")
        self.assertEqual(code, 404)
        self.assertEqual(json.loads(body), {"error": "unknown job"})

    def test_cancel_over_http(self):
        code, _, body = self._req("POST", "/v1/renders", sample_payload())
        job_id = json.loads(body)["id"]
        code, _, body = self._req("POST", "/v1/renders/%s/cancel" % job_id)
        self.assertEqual(code, 200)
        cancelled = json.loads(body)
        self.assertEqual(cancelled["status"], "FAILED")
        self.assertEqual(cancelled["errorMessage"], "cancelled by client")
        code, _, _ = self._req("POST", "/v1/renders/job-9999/cancel")
        self.assertEqual(code, 404)


def preset_payload(**overrides):
    payload = sample_payload()
    payload.update(overrides)
    return payload


class PresetResolutionTest(unittest.TestCase):
    """Queue-level preset validation: resolve_preset / RenderQueue.submit."""

    def test_default_preset_is_standard(self):
        job = RenderQueue().submit(sample_payload())
        meta = job.preset_metadata()
        self.assertEqual(meta["name"], "STANDARD")
        self.assertEqual(meta["resolutionWidth"], 1920)
        self.assertEqual(meta["resolutionHeight"], 1080)
        self.assertEqual(meta["samplesPerPixel"], 256)
        self.assertTrue(meta["denoiser"])
        self.assertEqual(meta["engine"], "SIMULATED")
        self.assertNotIn("_ticks", meta)

    def test_named_presets_resolve(self):
        q = RenderQueue()
        preview = q.submit(preset_payload(preset="PREVIEW")).preset_metadata()
        self.assertEqual((preview["resolutionWidth"], preview["samplesPerPixel"]), (1280, 64))
        self.assertTrue(preview["denoiser"])
        ultra = q.submit(preset_payload(preset="ULTRA")).preset_metadata()
        self.assertEqual((ultra["resolutionWidth"], ultra["resolutionHeight"],
                          ultra["samplesPerPixel"]), (3840, 2160, 1024))
        self.assertFalse(ultra["denoiser"])

    def test_explicit_fields_override_and_become_custom(self):
        job = RenderQueue().submit(preset_payload(
            preset="STANDARD", resolutionWidth=3840, samplesPerPixel=1024, denoiser=False))
        meta = job.preset_metadata()
        self.assertEqual(meta["name"], "CUSTOM")
        self.assertEqual(meta["resolutionWidth"], 3840)
        self.assertEqual(meta["samplesPerPixel"], 1024)
        self.assertFalse(meta["denoiser"])
        # Explicit fields that match the named preset keep its name.
        same = RenderQueue().submit(preset_payload(preset="ULTRA", resolutionWidth=3840))
        self.assertEqual(same.preset_metadata()["name"], "ULTRA")

    def test_custom_name_accepted_with_explicit_fields(self):
        job = RenderQueue().submit(preset_payload(
            preset="CUSTOM", resolutionWidth=3840, samplesPerPixel=16))
        meta = job.preset_metadata()
        self.assertEqual(meta["name"], "CUSTOM")
        self.assertEqual(meta["resolutionWidth"], 3840)
        self.assertEqual(meta["samplesPerPixel"], 16)
        self.assertTrue(meta["denoiser"])  # STANDARD base value, not overridden

    def test_invalid_preset_values_raise(self):
        q = RenderQueue()
        for bad in ({"preset": "CINEMATIC"},
                    {"preset": 4},
                    {"resolutionWidth": 2560},
                    {"resolutionWidth": "3840"},
                    {"resolutionWidth": True},
                    {"samplesPerPixel": 100},
                    {"samplesPerPixel": 2048},
                    {"denoiser": "yes"},
                    {"denoiser": 1}):
            with self.assertRaises(ValueError, msg=repr(bad)):
                q.submit(preset_payload(**bad))
        # Rejected submissions must not consume job ids.
        self.assertEqual(q.submit(sample_payload()).id, "job-0001")

    def test_higher_tier_takes_more_ticks(self):
        q = RenderQueue()
        preview = q.submit(preset_payload(preset="PREVIEW"))
        ultra = q.submit(preset_payload(preset="ULTRA"))
        q.advance_all()
        q.advance_all()
        self.assertEqual(preview.status, "SUCCEEDED")
        self.assertEqual(ultra.status, "RUNNING")
        self.assertEqual(ultra.progress, 25)
        for _ in range(6):
            q.advance_all()
        self.assertEqual(ultra.status, "SUCCEEDED")
        self.assertEqual(ultra.progress, 100)


class PresetHttpTest(HttpIntegrationTest):
    """HTTP-level preset checks, reusing the live-server fixture. (The
    inherited flow tests run again against this class's own server.)"""

    def test_valid_preset_accepted_and_reflected(self):
        code, _, body = self._req("POST", "/v1/renders",
                                  preset_payload(preset="ULTRA"))
        self.assertEqual(code, 201)
        created = json.loads(body)
        self.assertEqual(created["preset"], "ULTRA")
        self.assertEqual(created["renderPreset"]["resolutionWidth"], 3840)
        self.assertEqual(created["renderPreset"]["samplesPerPixel"], 1024)

        deadline = time.time() + 10
        status = None
        while time.time() < deadline:
            code, _, body = self._req("GET", "/v1/renders/%s" % created["id"])
            self.assertEqual(code, 200)
            status = json.loads(body)
            if status["status"] == "SUCCEEDED":
                break
            time.sleep(0.1)
        self.assertEqual(status["status"], "SUCCEEDED")
        self.assertEqual(status["preset"], "ULTRA")
        self.assertEqual(status["renderPreset"]["name"], "ULTRA")
        self.assertEqual(status["renderPreset"]["resolutionWidth"], 3840)
        self.assertEqual(status["renderPreset"]["resolutionHeight"], 2160)
        self.assertEqual(status["renderPreset"]["samplesPerPixel"], 1024)
        self.assertFalse(status["renderPreset"]["denoiser"])
        self.assertEqual(status["renderPreset"]["engine"], "SIMULATED")

    def test_invalid_resolution_rejected_400(self):
        code, _, body = self._req("POST", "/v1/renders",
                                  preset_payload(resolutionWidth=2560))
        self.assertEqual(code, 400)
        error = json.loads(body)["error"]
        self.assertIn("resolutionWidth", error)
        self.assertIn("1280", error)

    def test_invalid_spp_rejected_400(self):
        code, _, body = self._req("POST", "/v1/renders",
                                  preset_payload(samplesPerPixel=100))
        self.assertEqual(code, 400)
        self.assertIn("samplesPerPixel", json.loads(body)["error"])

    def test_invalid_preset_name_and_denoiser_rejected_400(self):
        code, _, body = self._req("POST", "/v1/renders",
                                  preset_payload(preset="CINEMATIC"))
        self.assertEqual(code, 400)
        self.assertIn("preset", json.loads(body)["error"])
        code, _, body = self._req("POST", "/v1/renders",
                                  preset_payload(denoiser="yes"))
        self.assertEqual(code, 400)
        self.assertIn("denoiser", json.loads(body)["error"])


class SceneCustomizationTest(unittest.TestCase):
    """v0.5.0: per-unit customization + finishes ride in the scene payload;
    the server validates the enum fields and echoes a scene summary."""

    def styled_payload(self):
        return sample_payload(
            units=[
                {"catalogId": "base-600", "doorStyle": "SHAKER", "handleStyle": "BAR", "interiorLed": False},
                {"catalogId": "tall-600", "doorStyle": "GLASS", "handleStyle": "BAR", "interiorLed": True},
                {"catalogId": "wall-600", "doorStyle": "GLASS", "handleStyle": "NONE", "interiorLed": True},
            ],
            finishes={"countertopMaterialId": "counter-granite-black",
                      "backsplashId": "backsplash-greek-key"},
            lighting={"underCabinetLed": True, "coveLed": True},
        )

    def test_scene_summary_counts_units_led_and_glass(self):
        job = RenderQueue().submit(self.styled_payload())
        summary = job.to_dict()["sceneSummary"]
        self.assertEqual(summary["unitCount"], 3)
        self.assertEqual(summary["interiorLedUnits"], 2)
        self.assertEqual(summary["glassUnits"], 2)
        self.assertTrue(summary["underCabinetLed"])
        self.assertTrue(summary["coveLed"])
        self.assertEqual(summary["countertopMaterialId"], "counter-granite-black")
        self.assertEqual(summary["backsplashId"], "backsplash-greek-key")

    def test_scene_summary_defaults_without_scene(self):
        job = RenderQueue().submit({"projectId": "p1"})
        summary = job.to_dict()["sceneSummary"]
        self.assertEqual(summary["unitCount"], 0)
        self.assertEqual(summary["interiorLedUnits"], 0)
        self.assertIsNone(summary["countertopMaterialId"])

    def test_invalid_door_style_rejected(self):
        payload = sample_payload(units=[{"catalogId": "base-600", "doorStyle": "FRENCH"}])
        with self.assertRaises(ValueError) as ctx:
            RenderQueue().submit(payload)
        self.assertIn("doorStyle", str(ctx.exception))

    def test_invalid_handle_style_rejected(self):
        payload = sample_payload(units=[{"catalogId": "base-600", "handleStyle": "LEVER"}])
        with self.assertRaises(ValueError) as ctx:
            RenderQueue().submit(payload)
        self.assertIn("handleStyle", str(ctx.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
