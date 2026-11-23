package com.kitchendesigner.render

import com.kitchendesigner.model.Project

/** Cloud Path Tracing for final photoreal renders (research decision: real-time
 *  PBR on device for editing, path tracing in the cloud for final images —
 *  mobile ray tracing thermally throttles after a few minutes). */
enum class RenderQuality { PREVIEW_2K, FINAL_4K, PANORAMA_360 }
enum class RenderJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

data class RenderRequest(
    val project: Project,
    val quality: RenderQuality,
    val cameraName: String = "main",
    val lightingMode: String = "DAY",
    /** Named cloud render preset (see [RenderPreset]); ignored when
     *  [presetSpec] is set. */
    val preset: RenderPreset = RenderPreset.STANDARD,
    /** Fully resolved preset (e.g. a CUSTOM override combination built by
     *  [RenderPresetSpec.resolve]); takes precedence over [preset]. */
    val presetSpec: RenderPresetSpec? = null,
) {
    /** The preset actually submitted: [presetSpec] if present, else [preset]. */
    val effectivePreset: RenderPresetSpec get() = presetSpec ?: preset.toSpec()
}

data class RenderJob(
    val id: String,
    val status: RenderJobStatus,
    val resultUrl: String? = null,
    val errorMessage: String? = null,
    /** Resolved preset metadata echoed back by the server (its
     *  `renderPreset` block): name is PREVIEW/STANDARD/ULTRA/CUSTOM. All
     *  null when the server did not report them (e.g. an error job). */
    val presetName: String? = null,
    val resolutionWidth: Int? = null,
    val samplesPerPixel: Int? = null,
    val denoiser: Boolean? = null,
)

interface CloudRenderClient {
    /** Uploads the parametric project (not a mesh) and queues a render. */
    fun submit(request: RenderRequest): RenderJob
    fun status(jobId: String): RenderJob
    fun cancel(jobId: String): Boolean
}

/** STUB — no server exists yet. Deterministic fake lifecycle: submit -> QUEUED,
 *  first status poll -> RUNNING, second -> SUCCEEDED with a placeholder URL.
 *  TODO(backend): replace with HTTPS calls to the render farm queue. */
class StubCloudRenderClient : CloudRenderClient {
    private var counter = 0
    private val polls = mutableMapOf<String, Int>()
    private val specs = mutableMapOf<String, RenderPresetSpec>()
    override fun submit(request: RenderRequest): RenderJob {
        val id = "stub-job-${++counter}"; polls[id] = 0
        val spec = request.effectivePreset; specs[id] = spec
        return RenderJob(id, RenderJobStatus.QUEUED, presetName = spec.name,
            resolutionWidth = spec.resolutionWidth, samplesPerPixel = spec.samplesPerPixel,
            denoiser = spec.denoiser)
    }
    override fun status(jobId: String): RenderJob {
        val n = (polls[jobId] ?: return RenderJob(jobId, RenderJobStatus.FAILED, errorMessage = "unknown job"))
        polls[jobId] = n + 1
        val spec = specs[jobId]
        return when (n) {
            0 -> RenderJob(jobId, RenderJobStatus.RUNNING, presetName = spec?.name,
                resolutionWidth = spec?.resolutionWidth, samplesPerPixel = spec?.samplesPerPixel,
                denoiser = spec?.denoiser)
            else -> RenderJob(jobId, RenderJobStatus.SUCCEEDED, resultUrl = "https://example.invalid/renders/$jobId.png", // STUB url
                presetName = spec?.name, resolutionWidth = spec?.resolutionWidth,
                samplesPerPixel = spec?.samplesPerPixel, denoiser = spec?.denoiser)
        }
    }
    override fun cancel(jobId: String): Boolean { specs.remove(jobId); return polls.remove(jobId) != null }
}
