package com.kitchendesigner

import com.kitchendesigner.render.DeviceProfile
import com.kitchendesigner.render.DeviceTier
import com.kitchendesigner.render.DeviceTierClassifier
import com.kitchendesigner.render.GpuTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceTier classifier tests: GPU string heuristics, the scoring bands and
 * their exact boundaries, the low-RAM hard floor, and the preview-settings
 * mapping. Scoring recap: RAM 0–2 + GPU 0–2 (unknown = neutral 1) + API 0–2;
 * >= 5 HIGH, 3–4 MEDIUM, <= 2 LOW; RAM < 2048 MB is always LOW.
 */
class DeviceTierTest {

    // -- GPU heuristics ------------------------------------------------------

    @Test fun adrenoHeuristics() {
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Adreno (TM) 740"))
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Adreno 730"))
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Adreno (TM) 700"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Adreno (TM) 660"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Adreno 619"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Adreno (TM) 600"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("Adreno (TM) 530"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("Adreno 308"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Adreno")) // no model -> neutral medium
    }

    @Test fun maliHeuristics() {
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Mali-G78"))
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Mali-G710 MC10"))
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Mali-G610"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Mali-G57"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Mali-G52 MC2"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Mali-G310"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("Mali-G31"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("Mali-T880"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("Mali-400 MP"))
    }

    @Test fun otherVendorHeuristics() {
        assertEquals(GpuTier.HIGH, DeviceTierClassifier.gpuTier("Xclipse 920"))
        assertEquals(GpuTier.MEDIUM, DeviceTierClassifier.gpuTier("Xclipse 530"))
        assertEquals(GpuTier.LOW, DeviceTierClassifier.gpuTier("PowerVR GE8320"))
        assertEquals(GpuTier.UNKNOWN, DeviceTierClassifier.gpuTier("SomeFuture GPU 9000"))
        assertEquals(GpuTier.UNKNOWN, DeviceTierClassifier.gpuTier(null))
        assertEquals(GpuTier.UNKNOWN, DeviceTierClassifier.gpuTier(""))
        assertEquals(GpuTier.UNKNOWN, DeviceTierClassifier.gpuTier("   "))
    }

    // -- Score bands ----------------------------------------------------------

    @Test fun ramAndApiScoreBands() {
        assertEquals(0, DeviceTierClassifier.ramScore(2048))
        assertEquals(0, DeviceTierClassifier.ramScore(4095))
        assertEquals(1, DeviceTierClassifier.ramScore(4096))
        assertEquals(1, DeviceTierClassifier.ramScore(6143))
        assertEquals(2, DeviceTierClassifier.ramScore(6144))
        assertEquals(2, DeviceTierClassifier.ramScore(16384))

        assertEquals(0, DeviceTierClassifier.apiScore(28))
        assertEquals(1, DeviceTierClassifier.apiScore(29))
        assertEquals(1, DeviceTierClassifier.apiScore(32))
        assertEquals(2, DeviceTierClassifier.apiScore(33))
        assertEquals(2, DeviceTierClassifier.apiScore(35))
    }

    // -- Classification --------------------------------------------------------

    @Test fun flagshipClassifiesHigh() {
        assertEquals(
            DeviceTier.HIGH,
            DeviceTierClassifier.classify(8192, "Adreno (TM) 740", 34),
        )
        assertEquals(
            DeviceTier.HIGH,
            DeviceTierClassifier.classify(DeviceProfile(12288, "Mali-G710", 35)),
        )
    }

    @Test fun midRangeClassifiesMedium() {
        // 2 (RAM 6144) + 1 (Adreno 619) + 1 (API 31) = 4
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(6144, "Adreno 619", 31))
        // 1 (RAM 4096) + 1 (Mali-G57) + 1 (API 30) = 3
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(4096, "Mali-G57", 30))
    }

    @Test fun budgetClassifiesLow() {
        // 0 (RAM 3072) + 1 (Mali-G52) + 1 (API 30) = 2
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(3072, "Mali-G52", 30))
        // 0 + 0 (Adreno 530) + 1 (API 29) = 1
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(2048, "Adreno 530", 29))
    }

    @Test fun lowRamHardFloorOverridesEverything() {
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(2047, "Adreno 740", 35))
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(1024, "Mali-G710", 35))
        // Just above the floor the same GPU/API classify normally:
        // 0 (RAM 2048) + 2 (Adreno 740) + 2 (API 35) = 4 -> MEDIUM
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(2048, "Adreno 740", 35))
    }

    @Test fun ramBoundary6144FlipsMediumToHigh() {
        // Unknown GPU is neutral (1); API 33 scores 2.
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(6143, null, 33)) // 1+1+2 = 4
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.classify(6144, null, 33))   // 2+1+2 = 5
    }

    @Test fun apiBoundary33FlipsMediumToHigh() {
        // RAM 6144 scores 2; unknown GPU neutral 1.
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(6144, "???", 32)) // 2+1+1 = 4
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.classify(6144, "???", 33))   // 2+1+2 = 5
    }

    @Test fun unknownGpuFallsBackToRamAndApi() {
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.classify(8192, null, 34))     // 2+1+2 = 5
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(3072, null, 30))      // 0+1+1 = 2
    }

    @Test fun weakGpuCapsTierDespiteStrongRamAndApi() {
        // 2 + 0 (PowerVR) + 2 = 4 -> MEDIUM, never HIGH on the GPU's account.
        assertEquals(DeviceTier.MEDIUM, DeviceTierClassifier.classify(8192, "PowerVR GE8320", 35))
    }

    // -- Preview settings -------------------------------------------------------

    @Test fun previewSettingsPerTier() {
        val low = DeviceTierClassifier.previewSettings(DeviceTier.LOW)
        assertEquals(512, low.textureResolutionCap)
        assertFalse(low.shadowsEnabled)
        assertEquals(30, low.targetFps)

        val medium = DeviceTierClassifier.previewSettings(DeviceTier.MEDIUM)
        assertEquals(1024, medium.textureResolutionCap)
        assertTrue(medium.shadowsEnabled)
        assertEquals(60, medium.targetFps)

        val high = DeviceTierClassifier.previewSettings(DeviceTier.HIGH)
        assertEquals(2048, high.textureResolutionCap)
        assertTrue(high.shadowsEnabled)
        assertEquals(120, high.targetFps)
    }

    @Test fun previewSettingsAreMonotonicInTier() {
        val caps = DeviceTier.entries.map { DeviceTierClassifier.previewSettings(it).textureResolutionCap }
        assertEquals(caps.sorted(), caps)
        val fps = DeviceTier.entries.map { DeviceTierClassifier.previewSettings(it).targetFps }
        assertEquals(fps.sorted(), fps)
    }

    @Test fun previewSettingsFromProfile() {
        val settings = DeviceTierClassifier.previewSettings(DeviceProfile(8192, "Adreno 740", 34))
        assertEquals(2048, settings.textureResolutionCap)
        assertTrue(settings.shadowsEnabled)
    }
}
