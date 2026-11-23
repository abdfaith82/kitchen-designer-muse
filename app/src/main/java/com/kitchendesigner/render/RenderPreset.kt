package com.kitchendesigner.render

/**
 * Cloud render quality presets — the Kotlin mirror of the render server's
 * preset contract (see `server/render_server.py` PRESETS and
 * `server/openapi.yaml`). Keep the two in sync: the server validates every
 * submitted job against the same allowed sets and rejects invalid values
 * with HTTP 400.
 *
 * A job payload carries the preset as a name plus its explicit fields
 * (`resolutionWidth` / `samplesPerPixel` / `denoiser`), so the server can
 * resolve named presets, field-by-field overrides (reported back as
 * "CUSTOM"), and hand-written payloads uniformly.
 *
 * Note the output aspect is fixed at 16:9, so only the width is a preset
 * parameter; the height is derived.
 */
enum class RenderPreset(
    val resolutionWidth: Int,
    val samplesPerPixel: Int,
    val denoiser: Boolean,
) {
    /** Fast draft: 1280x720, low sample count, denoiser does the heavy lifting. */
    PREVIEW(1280, 64, true),

    /** Default final quality: 1920x1080, mid sample count, denoiser on. */
    STANDARD(1920, 256, true),

    /** 3840x2160 at 1024 spp — clean enough that the denoiser stays off to
     *  preserve fine detail (wood grain, marble veining). Slowest tier. */
    ULTRA(3840, 1024, false),
    ;

    /** Derived output height at the fixed 16:9 aspect (720 / 1080 / 2160). */
    val resolutionHeight: Int get() = resolutionWidth * 9 / 16

    /** This preset as a fully explicit, validated spec. */
    fun toSpec(): RenderPresetSpec =
        RenderPresetSpec(name, resolutionWidth, samplesPerPixel, denoiser)

    companion object {
        /** The only resolution widths the server accepts. */
        val ALLOWED_RESOLUTION_WIDTHS: List<Int> = listOf(1280, 1920, 3840)

        /** The only samples-per-pixel tiers the server accepts. */
        val ALLOWED_SAMPLES_PER_PIXEL: List<Int> = listOf(16, 64, 256, 1024)

        /** Preset by wire name (case-insensitive, surrounding whitespace
         *  ignored), or null for an unknown name. */
        fun fromName(name: String?): RenderPreset? {
            val key = name?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == key }
        }

        /** Like [fromName] but throws [IllegalArgumentException] naming the
         *  bad value and the valid names — the client-side mirror of the
         *  server's HTTP 400 error. */
        fun fromNameOrThrow(name: String?): RenderPreset =
            fromName(name) ?: throw IllegalArgumentException(
                "invalid preset '$name': must be one of ${entries.joinToString(", ") { it.name }}"
            )
    }
}

/**
 * A fully resolved render preset: a named [RenderPreset], or a "CUSTOM"
 * combination produced by overriding individual fields. Instances are only
 * created through [resolve], which validates every field against the
 * server's allowed sets, so a constructed spec is always submittable.
 */
data class RenderPresetSpec(
    val name: String,
    val resolutionWidth: Int,
    val samplesPerPixel: Int,
    val denoiser: Boolean,
) {
    /** Derived output height at the fixed 16:9 aspect. */
    val resolutionHeight: Int get() = resolutionWidth * 9 / 16

    companion object {
        /**
         * Resolve a preset the same way the server does: start from the
         * named preset (STANDARD when [presetName] is null) and apply each
         * non-null explicit field as an override. When an override changes a
         * value, the resolved name becomes "CUSTOM"; overrides that merely
         * restate the preset's own values keep its name.
         *
         * @throws IllegalArgumentException with a field-naming message when
         * the preset name is unknown, the width is not in
         * [RenderPreset.ALLOWED_RESOLUTION_WIDTHS], or the sample count is
         * not in [RenderPreset.ALLOWED_SAMPLES_PER_PIXEL].
         */
        fun resolve(
            presetName: String? = null,
            resolutionWidth: Int? = null,
            samplesPerPixel: Int? = null,
            denoiser: Boolean? = null,
        ): RenderPresetSpec {
            val base = if (presetName == null) RenderPreset.STANDARD
                else RenderPreset.fromNameOrThrow(presetName)

            if (resolutionWidth != null && resolutionWidth !in RenderPreset.ALLOWED_RESOLUTION_WIDTHS) {
                throw IllegalArgumentException(
                    "invalid resolutionWidth $resolutionWidth: must be one of " +
                        RenderPreset.ALLOWED_RESOLUTION_WIDTHS.joinToString(", ")
                )
            }
            if (samplesPerPixel != null && samplesPerPixel !in RenderPreset.ALLOWED_SAMPLES_PER_PIXEL) {
                throw IllegalArgumentException(
                    "invalid samplesPerPixel $samplesPerPixel: must be one of " +
                        RenderPreset.ALLOWED_SAMPLES_PER_PIXEL.joinToString(", ")
                )
            }

            val width = resolutionWidth ?: base.resolutionWidth
            val spp = samplesPerPixel ?: base.samplesPerPixel
            val denoise = denoiser ?: base.denoiser
            val overridden = width != base.resolutionWidth ||
                spp != base.samplesPerPixel || denoise != base.denoiser
            return RenderPresetSpec(
                name = if (overridden) "CUSTOM" else base.name,
                resolutionWidth = width,
                samplesPerPixel = spp,
                denoiser = denoise,
            )
        }
    }
}
