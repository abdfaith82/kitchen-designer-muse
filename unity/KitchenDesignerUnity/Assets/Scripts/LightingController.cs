// LightingController.cs
// DAY / EVENING / NIGHT lighting presets for the kitchen scene, plus
// PREVIEW / REALISTIC / CINEMATIC render quality presets (shadow
// resolution/softness, ambient occlusion toggle, exposure) whose
// post-processing half lives in PostProcessingSettings as data.
//
// Apply(mode, intensity) retunes:
//   * the directional "sun" light (colour, intensity, angle),
//   * the scene ambient light (RenderSettings.ambientLight),
//   * a warm point light ("ceiling light") that is off during the day and
//     on for EVENING / NIGHT.
// `intensity` is the 0..1 master scale from the SetLighting command; it
// multiplies each preset's light intensities.
//
// Light references are auto-found when not wired in the Inspector: the
// first Directional Light and the first Point Light in the scene are used,
// and a point light is created (as a child named "WarmPointLight") if the
// scene has none.

using UnityEngine;

namespace KitchenDesigner
{
    /// <summary>
    /// Render quality presets for the real-time preview, independent of the
    /// DAY/EVENING/NIGHT lighting modes: they trade shadow fidelity, ambient
    /// occlusion and post-processing against frame rate.
    /// </summary>
    public enum RenderQualityPreset
    {
        /// <summary>Fast editing preview: 1024 shadow map, no AO, no tonemapping/bloom.</summary>
        Preview,
        /// <summary>Balanced default: 2048 shadows, SSAO on, ACES + gentle bloom.</summary>
        Realistic,
        /// <summary>Hero-shot look: 4096 soft shadows, strong AO/bloom/vignette, hotter exposure.</summary>
        Cinematic,
    }

    /// <summary>The concrete engine settings one <see cref="RenderQualityPreset"/> stands for.</summary>
    public struct LightingQualitySettings
    {
        public int ShadowMapResolution;       // directional shadow map size (px)
        public ShadowResolution ShadowQuality; // QualitySettings.shadowResolution band
        public float ShadowSoftness;          // applied as the sun's shadow normal bias (larger = softer edge bleed)
        public float ShadowDistance;          // QualitySettings.shadowDistance (m)
        public PostProcessingSettings PostProcessing; // includes AO toggle/intensity + exposure

        public bool AmbientOcclusionEnabled => PostProcessing != null && PostProcessing.AmbientOcclusionEnabled;
        public float Exposure => PostProcessing != null ? PostProcessing.Exposure : 1f;
    }

    public class LightingController : MonoBehaviour
    {
        [Header("Lights (auto-found if left empty)")]
        [SerializeField] private Light directionalLight;
        [SerializeField] private Light warmPointLight;

        [Header("Quality")]
        [SerializeField] private RenderQualityPreset qualityPreset = RenderQualityPreset.Realistic;

        /// <summary>The currently applied quality preset.</summary>
        public RenderQualityPreset CurrentQuality => qualityPreset;

        /// <summary>
        /// Post-processing settings of the current quality preset (data only —
        /// the scene's Volume wiring reads this; see PostProcessingSettings).
        /// </summary>
        public PostProcessingSettings CurrentPostProcessing { get; private set; }

        private void Awake()
        {
            ResolveLights();
            ApplyQuality(qualityPreset);
        }

        // ------------------------------------------------------------------
        // Quality presets
        // ------------------------------------------------------------------

        /// <summary>The settings table behind the three quality presets.</summary>
        public static LightingQualitySettings SettingsFor(RenderQualityPreset preset)
        {
            switch (preset)
            {
                case RenderQualityPreset.Preview:
                    return new LightingQualitySettings
                    {
                        ShadowMapResolution = 1024,
                        ShadowQuality = ShadowResolution.Low,
                        ShadowSoftness = 0.02f,
                        ShadowDistance = 25f,
                        PostProcessing = PostProcessingSettings.Preview(),
                    };
                case RenderQualityPreset.Cinematic:
                    return new LightingQualitySettings
                    {
                        ShadowMapResolution = 4096,
                        ShadowQuality = ShadowResolution.VeryHigh,
                        ShadowSoftness = 0.06f,
                        ShadowDistance = 80f,
                        PostProcessing = PostProcessingSettings.Cinematic(),
                    };
                case RenderQualityPreset.Realistic:
                default:
                    return new LightingQualitySettings
                    {
                        ShadowMapResolution = 2048,
                        ShadowQuality = ShadowResolution.High,
                        ShadowSoftness = 0.03f,
                        ShadowDistance = 50f,
                        PostProcessing = PostProcessingSettings.Realistic(),
                    };
            }
        }

        /// <summary>Parse "PREVIEW" | "REALISTIC" | "CINEMATIC" (any case); unknown names keep the current preset.</summary>
        public void ApplyQuality(string presetName)
        {
            if (System.Enum.TryParse(presetName ?? string.Empty, ignoreCase: true, out RenderQualityPreset parsed))
                ApplyQuality(parsed);
        }

        /// <summary>
        /// Apply a quality preset: shadow resolution/softness/distance on the
        /// sun + QualitySettings, and store the preset's post-processing
        /// settings (bloom/vignette/tonemapping/exposure/AO) as
        /// <see cref="CurrentPostProcessing"/> for the scene's Volume wiring.
        /// </summary>
        public void ApplyQuality(RenderQualityPreset preset)
        {
            ResolveLights();
            qualityPreset = preset;
            LightingQualitySettings settings = SettingsFor(preset);
            CurrentPostProcessing = settings.PostProcessing;

            QualitySettings.shadowResolution = settings.ShadowQuality;
            QualitySettings.shadowDistance = settings.ShadowDistance;

            if (directionalLight != null)
            {
                directionalLight.shadows = LightShadows.Soft;
                directionalLight.shadowCustomResolution = settings.ShadowMapResolution;
                // Softness approximation on the built-in/URP shadow path:
                // a larger normal bias softens shadow edge acne/bleed. (PCSS
                // soft shadows are not exposed by URP Lit on mobile.)
                directionalLight.shadowNormalBias = settings.ShadowSoftness;
                directionalLight.shadowBias = 0.02f;
            }
        }

        /// <summary>Apply a "DAY" | "EVENING" | "NIGHT" preset scaled by intensity (0..1).</summary>
        public void Apply(string mode, float intensity)
        {
            ResolveLights();
            intensity = Mathf.Clamp01(intensity);

            switch ((mode ?? "DAY").ToUpperInvariant())
            {
                case "EVENING":
                    SetSun(new Color(1.00f, 0.72f, 0.48f), 1.2f * intensity, new Vector3(18f, -60f, 0f));
                    RenderSettings.ambientLight = new Color(0.45f, 0.38f, 0.32f);
                    SetWarmLight(true, 30f * intensity);
                    break;

                case "NIGHT":
                    SetSun(new Color(0.40f, 0.52f, 0.85f), 0.25f * intensity, new Vector3(70f, 20f, 0f));
                    RenderSettings.ambientLight = new Color(0.16f, 0.18f, 0.24f);
                    SetWarmLight(true, 40f * intensity);
                    break;

                case "DAY":
                default:
                    SetSun(new Color(1.00f, 0.97f, 0.90f), 3.0f * intensity, new Vector3(50f, -30f, 0f));
                    RenderSettings.ambientLight = new Color(0.65f, 0.68f, 0.72f);
                    SetWarmLight(false, 0f);
                    break;
            }
        }

        // ------------------------------------------------------------------

        private void SetSun(Color color, float intensity, Vector3 eulerAngles)
        {
            if (directionalLight == null) return;
            directionalLight.color = color;
            directionalLight.intensity = intensity;
            directionalLight.transform.rotation = Quaternion.Euler(eulerAngles);
        }

        private void SetWarmLight(bool on, float intensity)
        {
            if (warmPointLight == null) return;
            warmPointLight.gameObject.SetActive(on);
            warmPointLight.intensity = intensity;
        }

        private void ResolveLights()
        {
            if (directionalLight == null || warmPointLight == null)
            {
                foreach (var light in FindObjectsOfType<Light>())
                {
                    if (directionalLight == null && light.type == LightType.Directional)
                        directionalLight = light;
                    else if (warmPointLight == null && light.type == LightType.Point)
                        warmPointLight = light;
                }
            }

            if (warmPointLight == null)
            {
                // No point light in the scene: create the ceiling light so
                // EVENING/NIGHT presets always have something to switch on.
                var go = new GameObject("WarmPointLight");
                go.transform.SetParent(transform, false);
                go.transform.position = new Vector3(2f, 2.5f, 2f);
                warmPointLight = go.AddComponent<Light>();
                warmPointLight.type = LightType.Point;
                warmPointLight.color = new Color(1.00f, 0.80f, 0.58f);
                warmPointLight.range = 8f;
                go.SetActive(false);
            }
        }
    }
}
