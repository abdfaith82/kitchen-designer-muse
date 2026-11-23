package com.kitchendesigner.render

/**
 * On-device preview quality tiers. The cloud render presets (see
 * [RenderPreset]) decide final-image quality; the device tier decides how
 * heavy the *real-time Unity preview* may be on this phone, so low-end
 * devices stay fluid while editing.
 */
enum class DeviceTier { LOW, MEDIUM, HIGH }

/** Coarse GPU capability bucket derived from the GL renderer string. */
enum class GpuTier { LOW, MEDIUM, HIGH, UNKNOWN }

/** Raw inputs for [DeviceTierClassifier]. [gpuRenderer] is the OpenGL/Vulkan
 *  renderer string (e.g. "Adreno (TM) 740", "Mali-G78"); null when the
 *  platform did not report one. */
data class DeviceProfile(
    val ramMb: Int,
    val gpuRenderer: String?,
    val androidApiLevel: Int,
)

/** Real-time preview limits for a [DeviceTier]. */
data class PreviewSettings(
    /** Cap for preview texture resolution, in pixels on the long edge. */
    val textureResolutionCap: Int,
    /** Whether real-time shadows are enabled in the preview. */
    val shadowsEnabled: Boolean,
    /** Frame-rate the preview should target. */
    val targetFps: Int,
)

/**
 * Classifies a device as LOW / MEDIUM / HIGH from RAM, GPU renderer string
 * heuristics and Android API level, and maps each tier to preview settings.
 *
 * Scoring (after the hard floor below): RAM, GPU and API level contribute
 * 0–2 points each; a total of 5–6 is HIGH, 3–4 MEDIUM, 0–2 LOW. An unknown
 * GPU scores a neutral 1, so the decision falls back to RAM + API level.
 *
 * Hard floor: under 2048 MB of RAM the device is LOW regardless of the other
 * inputs — a strong GPU cannot compensate for the texture/scene memory the
 * preview needs.
 *
 * The GPU heuristics are deliberately simple string rules (vendor + model
 * number); they are a preview-quality hint, not a benchmark.
 */
object DeviceTierClassifier {

    const val MIN_RAM_MB_FOR_NON_LOW: Int = 2048

    fun classify(profile: DeviceProfile): DeviceTier {
        if (profile.ramMb < MIN_RAM_MB_FOR_NON_LOW) return DeviceTier.LOW
        val score = ramScore(profile.ramMb) +
            gpuScore(gpuTier(profile.gpuRenderer)) +
            apiScore(profile.androidApiLevel)
        return when {
            score >= 5 -> DeviceTier.HIGH
            score >= 3 -> DeviceTier.MEDIUM
            else -> DeviceTier.LOW
        }
    }

    fun classify(ramMb: Int, gpuRenderer: String?, androidApiLevel: Int): DeviceTier =
        classify(DeviceProfile(ramMb, gpuRenderer, androidApiLevel))

    /** Preview limits per tier: LOW saves memory and fill-rate (small
     *  textures, no shadows, 30 fps), HIGH unlocks 2K textures and 120 fps. */
    fun previewSettings(tier: DeviceTier): PreviewSettings = when (tier) {
        DeviceTier.LOW -> PreviewSettings(textureResolutionCap = 512, shadowsEnabled = false, targetFps = 30)
        DeviceTier.MEDIUM -> PreviewSettings(textureResolutionCap = 1024, shadowsEnabled = true, targetFps = 60)
        DeviceTier.HIGH -> PreviewSettings(textureResolutionCap = 2048, shadowsEnabled = true, targetFps = 120)
    }

    fun previewSettings(profile: DeviceProfile): PreviewSettings = previewSettings(classify(profile))

    // -- Scoring ------------------------------------------------------------

    /** 2048–4095 MB -> 0, 4096–6143 MB -> 1, >= 6144 MB -> 2. */
    fun ramScore(ramMb: Int): Int = when {
        ramMb >= 6144 -> 2
        ramMb >= 4096 -> 1
        else -> 0
    }

    /** API < 29 -> 0, 29–32 -> 1, >= 33 -> 2. (The app's minSdk is 29, so
     *  the 0 band only matters for defensive use below the supported floor.) */
    fun apiScore(androidApiLevel: Int): Int = when {
        androidApiLevel >= 33 -> 2
        androidApiLevel >= 29 -> 1
        else -> 0
    }

    fun gpuScore(tier: GpuTier): Int = when (tier) {
        GpuTier.HIGH -> 2
        GpuTier.MEDIUM -> 1
        GpuTier.UNKNOWN -> 1 // neutral: let RAM + API decide
        GpuTier.LOW -> 0
    }

    // -- GPU renderer string heuristics --------------------------------------

    private val ADRENO_MODEL = Regex("""adreno\D*(\d{3})""")
    private val MALI_G_MODEL = Regex("""mali[- ]?g(\d+)""")
    private val XCLIPSE_MODEL = Regex("""xclipse\D*(\d+)""")

    /**
     * Bucket a GL renderer string. Rules (first match wins):
     *  - Adreno NNN: >= 700 HIGH, 600–699 MEDIUM, < 600 LOW; an Adreno string
     *    with no parseable model is MEDIUM.
     *  - Mali-G N: two-digit 70–99 (G71–G78…) and 600+ (G610, G710…) HIGH;
     *    50–69 and 100–599 MEDIUM; below 50 LOW.
     *  - Older Mali (Mali-T…, Mali-400…) LOW.
     *  - Samsung Xclipse NNN: >= 900 HIGH, otherwise MEDIUM.
     *  - PowerVR LOW (the Android PowerVR parts in circulation are entry-level).
     *  - Anything else (including null/blank) UNKNOWN.
     */
    fun gpuTier(gpuRenderer: String?): GpuTier {
        val s = gpuRenderer?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return GpuTier.UNKNOWN

        ADRENO_MODEL.find(s)?.let { m ->
            val model = m.groupValues[1].toInt()
            return when {
                model >= 700 -> GpuTier.HIGH
                model >= 600 -> GpuTier.MEDIUM
                else -> GpuTier.LOW
            }
        }
        if ("adreno" in s) return GpuTier.MEDIUM

        MALI_G_MODEL.find(s)?.let { m ->
            val model = m.groupValues[1].toInt()
            return when {
                model >= 600 -> GpuTier.HIGH
                model in 100..599 -> GpuTier.MEDIUM
                model >= 70 -> GpuTier.HIGH
                model >= 50 -> GpuTier.MEDIUM
                else -> GpuTier.LOW
            }
        }
        if ("mali" in s) return GpuTier.LOW // Mali-T / Mali-400 era parts

        XCLIPSE_MODEL.find(s)?.let { m ->
            return if (m.groupValues[1].toInt() >= 900) GpuTier.HIGH else GpuTier.MEDIUM
        }
        if ("powervr" in s) return GpuTier.LOW

        return GpuTier.UNKNOWN
    }
}
