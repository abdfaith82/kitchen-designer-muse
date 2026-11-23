package com.kitchendesigner.model

/**
 * Full per-unit and per-design customization (v0.5.0) — the layer that turns
 * a generated layout into a bespoke kitchen like the reference photos:
 * every unit's dimensions already live on [CabinetUnit]; this file adds its
 * door style, handle, finish hardware and interior lighting, plus the
 * design-wide surfaces (worktop, backsplash, floor) and LED lighting that
 * the photoreal renderers (Unity scene + cloud render) consume.
 */

/** Cabinet front construction. GLASS is the framed glass front used by
 *  illuminated display cabinets. HANDLELESS is a slab front with a routed
 *  channel instead of a visible handle. */
enum class DoorStyle { SHAKER, SLAB, GLASS, HANDLELESS }

/** How a front opens. INTEGRATED = finger pull routed into the front edge,
 *  NONE = push-to-open (only sensible with HANDLELESS/SLAB fronts). */
enum class HandleStyle { BAR, KNOB, INTEGRATED, NONE }

/** Everything editable on ONE unit beyond its catalogue identity and size. */
data class UnitCustomization(
    val doorStyle: DoorStyle = DoorStyle.SLAB,
    val handleStyle: HandleStyle = HandleStyle.BAR,
    val handleFinishId: String = "handle-chrome",
    /** LED strip inside the unit — the lit glass display cabinets. */
    val interiorLed: Boolean = false,
)

/** Design-wide surfaces and lighting for one room. Ids resolve through
 *  [Materials]; the render payload carries them verbatim. */
data class KitchenFinishes(
    val countertopMaterialId: String = "counter-quartz",
    val backsplashId: String = "backsplash-subway-white",
    val floorFinishId: String = "floor-marble-white",
    val wallFinishId: String = "wall-white-matte",
    val plinthFinishId: String = "plinth-black",
    val corniceFinishId: String = "cornice-black",
    /** LED strip under the wall cabinets, washing the backsplash. */
    val underCabinetLed: Boolean = false,
    /** LED strip hidden in the ceiling cornice/pelmet. */
    val coveLed: Boolean = false,
    /** Pendant light fixture id over an island/peninsula, or null. */
    val pendantId: String? = null,
)

/** One selectable finish: a stable id plus the flat colour the 2D plan and
 *  material pickers preview it with (the 3D/cloud renderers map the same id
 *  to full PBR maps on their side). */
data class FinishOption(val id: String, val argb: Long)

/** The finish library for the customization UI and the render spec.
 *  Cabinet ids intentionally overlap the expansion-catalog finish ids
 *  (oak / walnut / matte-black / …) so assistant palettes and manual edits
 *  speak the same language. */
object Materials {

    val CABINET_FINISHES: List<FinishOption> = listOf(
        FinishOption("cabinet-cream", 0xFFEDE4D3),
        FinishOption("acrylic-white", 0xFFF5F5F2),
        FinishOption("oak", 0xFFC8A165),
        FinishOption("walnut", 0xFF6E4B2A),
        FinishOption("cabinet-charcoal", 0xFF3B3F45),
        FinishOption("matte-black", 0xFF1E1E22),
        FinishOption("hpl-grey", 0xFF9AA0A3),
    )

    val COUNTERTOPS: List<FinishOption> = listOf(
        FinishOption("counter-granite-black", 0xFF17181A),
        FinishOption("counter-marble-black-gold", 0xFF232022),
        FinishOption("counter-quartz", 0xFFEDEEF0),
        FinishOption("counter-granite", 0xFF5A5A5E),
        FinishOption("counter-walnut-block", 0xFF7A5230),
        FinishOption("counter-laminate", 0xFFD8CBB2),
    )

    val BACKSPLASHES: List<FinishOption> = listOf(
        FinishOption("backsplash-greek-key", 0xFFE8E0CC),
        FinishOption("backsplash-marble-black", 0xFF201D20),
        FinishOption("backsplash-wood-oak", 0xFFC8A165),
        FinishOption("backsplash-subway-white", 0xFFF2F2EE),
        FinishOption("backsplash-microcement", 0xFFB9B4AA),
    )

    val FLOORS: List<FinishOption> = listOf(
        FinishOption("floor-marble-white", 0xFFEFEDE8),
        FinishOption("floor-porcelain-grey", 0xFFC9C9C7),
        FinishOption("floor-herringbone-oak", 0xFFB98D55),
    )

    val HANDLE_FINISHES: List<FinishOption> = listOf(
        FinishOption("handle-black", 0xFF17181A),
        FinishOption("handle-chrome", 0xFFC0C4C8),
        FinishOption("handle-brass", 0xFFB08D3E),
        FinishOption("handle-copper", 0xFFB0663A),
    )

    val PENDANTS: List<FinishOption> = listOf(
        FinishOption("pendant-copper-globe", 0xFFB0663A),
        FinishOption("pendant-black-cone", 0xFF1E1E22),
        FinishOption("pendant-glass-globe", 0xFFE8E4DA),
    )

    private val ALL: List<FinishOption> =
        CABINET_FINISHES + COUNTERTOPS + BACKSPLASHES + FLOORS + HANDLE_FINISHES + PENDANTS

    /** Flat preview colour for any finish/material id. Exact library ids win;
     *  legacy ids (front-oak, counter-granite, …) fall back to keyword rules
     *  so older projects and assistant palettes still preview sensibly.
     *  Unknown ids get a neutral mid-grey — never a crash, never random. */
    fun previewColorArgb(id: String): Long {
        ALL.firstOrNull { it.id == id }?.let { return it.argb }
        val s = id.lowercase()
        return when {
            "walnut" in s -> 0xFF6E4B2A
            "oak" in s -> 0xFFC8A165
            "cream" in s || "ivory" in s -> 0xFFEDE4D3
            "white" in s || "acrylic" in s -> 0xFFF5F5F2
            "charcoal" in s -> 0xFF3B3F45
            "black" in s -> 0xFF1E1E22
            "grey" in s || "gray" in s -> 0xFF9AA0A3
            "marble" in s -> 0xFFEFEDE8
            "granite" in s -> 0xFF5A5A5E
            "quartz" in s -> 0xFFEDEEF0
            "copper" in s -> 0xFFB0663A
            "brass" in s || "gold" in s -> 0xFFB08D3E
            "chrome" in s || "steel" in s || "stainless" in s -> 0xFFC0C4C8
            "terracotta" in s -> 0xFFB4664A
            "wood" in s -> 0xFFB98D55
            else -> 0xFF8D8D93
        }
    }
}
