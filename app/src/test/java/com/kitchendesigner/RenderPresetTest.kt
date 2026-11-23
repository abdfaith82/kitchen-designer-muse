package com.kitchendesigner

import com.kitchendesigner.model.Project
import com.kitchendesigner.model.Room
import com.kitchendesigner.render.HttpCloudRenderClient
import com.kitchendesigner.render.RenderPreset
import com.kitchendesigner.render.RenderPresetSpec
import com.kitchendesigner.render.RenderQuality
import com.kitchendesigner.render.RenderRequest
import com.kitchendesigner.render.StubCloudRenderClient
import com.kitchendesigner.render.RenderJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Render preset contract tests. The expected values are the shared contract
 * with the Python render server (server/render_server.py PRESETS): widths
 * 1280/1920/3840, spp tiers in {16, 64, 256, 1024}, invalid values rejected.
 */
class RenderPresetTest {

    @Test fun presetValuesMatchServerContract() {
        assertEquals(1280, RenderPreset.PREVIEW.resolutionWidth)
        assertEquals(720, RenderPreset.PREVIEW.resolutionHeight)
        assertEquals(64, RenderPreset.PREVIEW.samplesPerPixel)
        assertTrue(RenderPreset.PREVIEW.denoiser)

        assertEquals(1920, RenderPreset.STANDARD.resolutionWidth)
        assertEquals(1080, RenderPreset.STANDARD.resolutionHeight)
        assertEquals(256, RenderPreset.STANDARD.samplesPerPixel)
        assertTrue(RenderPreset.STANDARD.denoiser)

        assertEquals(3840, RenderPreset.ULTRA.resolutionWidth)
        assertEquals(2160, RenderPreset.ULTRA.resolutionHeight)
        assertEquals(1024, RenderPreset.ULTRA.samplesPerPixel)
        assertFalse(RenderPreset.ULTRA.denoiser)
    }

    @Test fun everyPresetValueIsInTheAllowedSets() {
        for (preset in RenderPreset.entries) {
            assertTrue(preset.resolutionWidth in RenderPreset.ALLOWED_RESOLUTION_WIDTHS)
            assertTrue(preset.samplesPerPixel in RenderPreset.ALLOWED_SAMPLES_PER_PIXEL)
        }
        assertEquals(listOf(1280, 1920, 3840), RenderPreset.ALLOWED_RESOLUTION_WIDTHS)
        assertEquals(listOf(16, 64, 256, 1024), RenderPreset.ALLOWED_SAMPLES_PER_PIXEL)
    }

    @Test fun fromNameIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(RenderPreset.ULTRA, RenderPreset.fromName("ULTRA"))
        assertEquals(RenderPreset.ULTRA, RenderPreset.fromName(" ultra "))
        assertEquals(RenderPreset.PREVIEW, RenderPreset.fromName("preview"))
        assertNull(RenderPreset.fromName("CINEMATIC"))
        assertNull(RenderPreset.fromName(null))
        assertNull(RenderPreset.fromName(""))
        val e = assertThrows(IllegalArgumentException::class.java) {
            RenderPreset.fromNameOrThrow("CINEMATIC")
        }
        assertTrue(e.message!!.contains("CINEMATIC"))
        assertTrue(e.message!!.contains("ULTRA"))
    }

    @Test fun resolveDefaultsToStandard() {
        val spec = RenderPresetSpec.resolve()
        assertEquals("STANDARD", spec.name)
        assertEquals(1920, spec.resolutionWidth)
        assertEquals(1080, spec.resolutionHeight)
        assertEquals(256, spec.samplesPerPixel)
        assertTrue(spec.denoiser)
    }

    @Test fun resolveNamedPreset() {
        val spec = RenderPresetSpec.resolve(presetName = "ULTRA")
        assertEquals(RenderPreset.ULTRA.toSpec(), spec)
    }

    @Test fun resolveOverridesBecomeCustom() {
        val spec = RenderPresetSpec.resolve(
            presetName = "STANDARD", resolutionWidth = 3840,
            samplesPerPixel = 1024, denoiser = false,
        )
        assertEquals("CUSTOM", spec.name)
        assertEquals(3840, spec.resolutionWidth)
        assertEquals(2160, spec.resolutionHeight)
        assertEquals(1024, spec.samplesPerPixel)
        assertFalse(spec.denoiser)
    }

    @Test fun resolveMatchingOverridesKeepPresetName() {
        val spec = RenderPresetSpec.resolve(presetName = "ULTRA", resolutionWidth = 3840)
        assertEquals("ULTRA", spec.name)
    }

    @Test fun resolveRejectsInvalidResolutionWidth() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RenderPresetSpec.resolve(resolutionWidth = 2560)
        }
        assertTrue(e.message!!.contains("resolutionWidth"))
        assertTrue(e.message!!.contains("2560"))
        assertThrows(IllegalArgumentException::class.java) {
            RenderPresetSpec.resolve(resolutionWidth = 0)
        }
    }

    @Test fun resolveRejectsInvalidSamplesPerPixel() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RenderPresetSpec.resolve(samplesPerPixel = 100)
        }
        assertTrue(e.message!!.contains("samplesPerPixel"))
        assertThrows(IllegalArgumentException::class.java) {
            RenderPresetSpec.resolve(samplesPerPixel = 2048)
        }
        // The lowest allowed tier is fine.
        assertEquals(16, RenderPresetSpec.resolve(samplesPerPixel = 16).samplesPerPixel)
    }

    @Test fun resolveRejectsUnknownPresetName() {
        assertThrows(IllegalArgumentException::class.java) {
            RenderPresetSpec.resolve(presetName = "CINEMATIC")
        }
    }

    private fun project() = Project(id = "p1", name = "K", room = Room(4_200, 3_600))

    @Test fun httpSubmitJsonCarriesPresetFields() {
        val client = HttpCloudRenderClient("http://localhost:8080/")
        val json = client.buildSubmitJson(
            RenderRequest(project(), RenderQuality.FINAL_4K, preset = RenderPreset.ULTRA)
        )
        assertTrue(json.contains("\"preset\":\"ULTRA\""))
        assertTrue(json.contains("\"resolutionWidth\":3840"))
        assertTrue(json.contains("\"samplesPerPixel\":1024"))
        assertTrue(json.contains("\"denoiser\":false"))
    }

    @Test fun httpSubmitJsonDefaultsToStandardPreset() {
        val client = HttpCloudRenderClient("http://localhost:8080")
        val json = client.buildSubmitJson(RenderRequest(project(), RenderQuality.PREVIEW_2K))
        assertTrue(json.contains("\"preset\":\"STANDARD\""))
        assertTrue(json.contains("\"resolutionWidth\":1920"))
        assertTrue(json.contains("\"samplesPerPixel\":256"))
        assertTrue(json.contains("\"denoiser\":true"))
    }

    @Test fun httpSubmitJsonCarriesCustomSpec() {
        val client = HttpCloudRenderClient("http://localhost:8080")
        val spec = RenderPresetSpec.resolve(resolutionWidth = 1280, samplesPerPixel = 16)
        val json = client.buildSubmitJson(
            RenderRequest(project(), RenderQuality.FINAL_4K, presetSpec = spec)
        )
        assertTrue(json.contains("\"preset\":\"CUSTOM\""))
        assertTrue(json.contains("\"resolutionWidth\":1280"))
        assertTrue(json.contains("\"samplesPerPixel\":16"))
    }

    @Test fun stubClientEchoesPresetThroughLifecycle() {
        val stub = StubCloudRenderClient()
        val submitted = stub.submit(
            RenderRequest(project(), RenderQuality.FINAL_4K, preset = RenderPreset.ULTRA)
        )
        assertEquals(RenderJobStatus.QUEUED, submitted.status)
        assertEquals("ULTRA", submitted.presetName)
        assertEquals(3840, submitted.resolutionWidth)
        val done = stub.status(submitted.id).let { stub.status(submitted.id) }
        assertEquals(RenderJobStatus.SUCCEEDED, done.status)
        assertEquals("ULTRA", done.presetName)
        assertEquals(1024, done.samplesPerPixel)
        assertEquals(false, done.denoiser)
    }

    @Test fun requestEffectivePresetPrefersSpecOverEnum() {
        val spec = RenderPresetSpec.resolve(presetName = "PREVIEW", samplesPerPixel = 1024)
        val request = RenderRequest(
            project(), RenderQuality.FINAL_4K,
            preset = RenderPreset.ULTRA, presetSpec = spec,
        )
        assertEquals("CUSTOM", request.effectivePreset.name)
        assertEquals(1280, request.effectivePreset.resolutionWidth)
        assertEquals(1024, request.effectivePreset.samplesPerPixel)
    }
}
