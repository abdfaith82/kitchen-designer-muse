// PostProcessingSettings.cs
// Post-processing look settings as plain serializable DATA.
//
// This class deliberately holds values only — bloom, vignette, tonemapping,
// exposure and ambient occlusion — and applies nothing itself. The concrete
// URP types (VolumeProfile overrides: Bloom, Vignette, Tonemapping, and the
// SSAO renderer feature) live in the Universal pipeline assemblies, so the
// wiring from these values to a scene Volume belongs to the scene setup
// step (see unity/README.md), which reads the active settings from
// LightingController.CurrentPostProcessing. Keeping the look as data means
// quality presets (LightingController) and the Kotlin side can reason about
// it without a dependency on pipeline internals.

using System;
using UnityEngine;

namespace KitchenDesigner
{
    /// <summary>Tonemapping curve selection (mirrors URP's TonemappingMode names).</summary>
    public enum TonemappingMode
    {
        None,
        Neutral,
        Aces,
    }

    [Serializable]
    public class PostProcessingSettings
    {
        [Header("Bloom")]
        public bool BloomEnabled = false;
        [Range(0f, 2f)] public float BloomIntensity = 0f;
        [Range(0f, 2f)] public float BloomThreshold = 0.85f;

        [Header("Vignette")]
        public bool VignetteEnabled = false;
        [Range(0f, 1f)] public float VignetteIntensity = 0f;
        [Range(0f, 1f)] public float VignetteSmoothness = 0.4f;

        [Header("Colour")]
        public TonemappingMode Tonemapping = TonemappingMode.None;
        /// <summary>Post exposure multiplier (1 = neutral), applied as the
        /// colour-adjustments post exposure in the Volume wiring.</summary>
        public float Exposure = 1.0f;

        [Header("Ambient occlusion (SSAO)")]
        public bool AmbientOcclusionEnabled = false;
        [Range(0f, 2f)] public float AmbientOcclusionIntensity = 0f;

        public PostProcessingSettings Clone() => (PostProcessingSettings)MemberwiseClone();

        // -- Quality preset factories (used by LightingController) -----------

        /// <summary>Fast preview look: no bloom/AO, no tonemapping, faint vignette.</summary>
        public static PostProcessingSettings Preview() => new PostProcessingSettings
        {
            BloomEnabled = false, BloomIntensity = 0f, BloomThreshold = 1.0f,
            VignetteEnabled = true, VignetteIntensity = 0.10f, VignetteSmoothness = 0.5f,
            Tonemapping = TonemappingMode.None, Exposure = 1.00f,
            AmbientOcclusionEnabled = false, AmbientOcclusionIntensity = 0f,
        };

        /// <summary>Balanced realistic look: ACES, gentle bloom and AO.</summary>
        public static PostProcessingSettings Realistic() => new PostProcessingSettings
        {
            BloomEnabled = true, BloomIntensity = 0.25f, BloomThreshold = 0.85f,
            VignetteEnabled = true, VignetteIntensity = 0.25f, VignetteSmoothness = 0.45f,
            Tonemapping = TonemappingMode.Aces, Exposure = 1.00f,
            AmbientOcclusionEnabled = true, AmbientOcclusionIntensity = 0.6f,
        };

        /// <summary>Film look: stronger bloom/vignette/AO and a hotter exposure.</summary>
        public static PostProcessingSettings Cinematic() => new PostProcessingSettings
        {
            BloomEnabled = true, BloomIntensity = 0.50f, BloomThreshold = 0.75f,
            VignetteEnabled = true, VignetteIntensity = 0.40f, VignetteSmoothness = 0.40f,
            Tonemapping = TonemappingMode.Aces, Exposure = 1.15f,
            AmbientOcclusionEnabled = true, AmbientOcclusionIntensity = 1.0f,
        };
    }
}
