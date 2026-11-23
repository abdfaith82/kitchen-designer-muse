package com.kitchendesigner.assistant

import com.kitchendesigner.bom.Bom
import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.catalog.CatalogExpansion
import com.kitchendesigner.catalog.ExpansionSku
import com.kitchendesigner.engine.KitchenPlanInput
import com.kitchendesigner.engine.LayoutGenerator
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.engine.NkbaValidator
import com.kitchendesigner.engine.ValidationReport
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType

/**
 * Smart design assistant (Workstream C, v0.4.0) — a DETERMINISTIC,
 * RULE-BASED recommender. It is not ML and makes no learning claims: every
 * output follows from the explicit rules below, and [DesignRecommendation.notes]
 * carries the rule codes that fired so the UI (and tests) can explain *why*.
 *
 * Input: room size + type, a [StylePreference] and a [BudgetTier].
 * Output:
 *  - Kitchens: a recommended [LayoutType] (room size/shape rules), a real
 *    generated [Project] via LayoutGenerator with the palette's cabinet
 *    finish applied, an NKBA [ValidationReport] for that layout, and a
 *    [Bom] cost estimate priced with the palette's worktop.
 *  - Other rooms: a furniture set picked from the expansion catalog
 *    (widest item of each wanted subtype that fits the room width;
 *    cheapest fitting item on the ECONOMY tier).
 *
 * Layout rules for kitchens (width W, depth D, mm), evaluated in order:
 *  1. W < 2400                       -> SINGLE_WALL (too narrow for runs facing anything)
 *  2. W >= 3600 and D >= 3400        -> ISLAND (run + 1070 aisle + 600 island + walkway fits)
 *  3. W >= 3200 and D >= 3200        -> U_SHAPED (three runs with a workable aisle)
 *  4. D <= 3000 and W >= 3000        -> GALLEY (long, shallow room: two facing runs)
 *  5. otherwise                      -> L_SHAPED
 */

enum class StylePreference { MODERN, CLASSIC, SCANDINAVIAN, MENA_TRADITIONAL, INDUSTRIAL }

enum class BudgetTier { ECONOMY, STANDARD, PREMIUM }

/** A finish palette. All ids are finish ids that exist in the generated
 *  expansion catalog (cabinet finishes: oak/walnut/acrylic-white/hpl-* /
 *  matte-black; worktop finishes: quartz/granite/marble-look/laminate/…;
 *  floor finishes: porcelain/herringbone-oak/marble-tile/…; wall finishes:
 *  ivory/terracotta/charcoal/…). */
data class StylePalette(
    val cabinetFinishId: String,
    val doorStyle: String,
    val worktopFinishId: String,
    val floorFinishId: String,
    val wallFinishId: String,
)

data class AssistantRequest(
    val roomWidthMm: Int,
    val roomDepthMm: Int,
    val roomType: RoomType,
    val style: StylePreference,
    val budget: BudgetTier,
    val twoCooks: Boolean = false,
)

data class DesignRecommendation(
    val roomType: RoomType,
    val style: StylePreference,
    val budget: BudgetTier,
    val palette: StylePalette,
    /** Kitchens only: the recommended layout. */
    val layoutType: LayoutType?,
    /** Kitchens only: the generated project (palette finish applied). */
    val project: Project?,
    /** Kitchens only: NKBA validation of the generated layout. */
    val nkbaReport: ValidationReport?,
    /** Kitchens only: BOM estimate for the generated project. */
    val bom: Bom?,
    /** Non-kitchen rooms: the picked furniture/fixture set. */
    val furniturePicks: List<ExpansionSku>,
    /** Rule codes that fired, e.g. "LAYOUT_ISLAND", "PALETTE_BUDGET_ECONOMY". */
    val notes: List<String>,
)

object DesignAssistant {

    // ------------------------------------------------------------ palettes

    private fun basePalette(style: StylePreference): StylePalette = when (style) {
        StylePreference.MODERN -> StylePalette("acrylic-white", "handleless", "quartz", "microcement", "white-matte")
        StylePreference.CLASSIC -> StylePalette("walnut", "shaker", "granite", "herringbone-oak", "ivory")
        StylePreference.SCANDINAVIAN -> StylePalette("oak", "slab", "laminate", "oak-laminate", "white-painted")
        StylePreference.MENA_TRADITIONAL -> StylePalette("oak", "shaker", "marble-look", "marble-tile", "terracotta")
        StylePreference.INDUSTRIAL -> StylePalette("matte-black", "slab", "stainless", "microcement", "charcoal")
    }

    /** The palette for [style] after the [budget] tier is applied:
     *  ECONOMY downgrades cabinet/worktop/floor finishes to the cheapest
     *  catalog families; PREMIUM upgrades worktop and floor to marble
     *  finishes; STANDARD keeps the style palette untouched. */
    fun paletteFor(style: StylePreference, budget: BudgetTier): StylePalette {
        val base = basePalette(style)
        return when (budget) {
            BudgetTier.STANDARD -> base
            BudgetTier.ECONOMY -> base.copy(
                cabinetFinishId = "hpl-grey", worktopFinishId = "laminate", floorFinishId = "vinyl",
            )
            BudgetTier.PREMIUM -> base.copy(
                worktopFinishId = "marble-look", floorFinishId = "marble-tile",
            )
        }
    }

    /** Starter-catalog countertop material id for a palette worktop finish
     *  (the BOM prices countertops from the starter catalog). */
    private fun countertopMaterialId(worktopFinishId: String): String = when (worktopFinishId) {
        "quartz", "marble-look", "solid-surface" -> "counter-quartz"
        "granite" -> "counter-granite"
        else -> "counter-laminate"
    }

    // --------------------------------------------------------- layout rules

    /** The kitchen layout the size/shape rules recommend. */
    fun recommendLayout(roomWidthMm: Int, roomDepthMm: Int): LayoutType = when {
        roomWidthMm < 2_400 -> LayoutType.SINGLE_WALL
        roomWidthMm >= 3_600 && roomDepthMm >= 3_400 -> LayoutType.ISLAND
        roomWidthMm >= 3_200 && roomDepthMm >= 3_200 -> LayoutType.U_SHAPED
        roomDepthMm <= 3_000 && roomWidthMm >= 3_000 -> LayoutType.GALLEY
        else -> LayoutType.L_SHAPED
    }

    // ------------------------------------------------------------- recommend

    fun recommend(request: AssistantRequest, catalog: List<ExpansionSku> = emptyList()): DesignRecommendation {
        val palette = paletteFor(request.style, request.budget)
        val notes = mutableListOf("PALETTE_BUDGET_${request.budget.name}")
        if (request.roomType != RoomType.KITCHEN) {
            val picks = pickFurniture(request, catalog)
            notes += "FURNITURE_SET_${request.roomType.name}"
            return DesignRecommendation(
                roomType = request.roomType, style = request.style, budget = request.budget,
                palette = palette, layoutType = null, project = null, nkbaReport = null,
                bom = null, furniturePicks = picks, notes = notes,
            )
        }
        val layoutType = recommendLayout(request.roomWidthMm, request.roomDepthMm)
        notes += "LAYOUT_${layoutType.name}"
        val room = Room(request.roomWidthMm, request.roomDepthMm)
        val generated = LayoutGenerator.generate(room, layoutType)
        // Apply the palette's cabinet finish so the project, the 3D scene
        // and the BOM all speak about the same fronts.
        val units = generated.units.map { placed ->
            placed.copy(unit = placed.unit.copy(materialId = palette.cabinetFinishId))
        }
        val project = Project(
            id = "assistant-${layoutType.name.lowercase()}",
            name = "Assistant design",
            room = room, units = units, schemaVersion = 2,
        )
        val nkba = NkbaValidator.validate(
            KitchenPlanInput(triangle = generated.triangle, workAisleMm = generated.workAisleMm, twoCooks = request.twoCooks),
        )
        if (!nkba.allPassed) notes += "NKBA_HAS_CHECKS"
        val bom = BomCalculator.calculate(units, countertopMaterialId(palette.worktopFinishId))
        return DesignRecommendation(
            roomType = request.roomType, style = request.style, budget = request.budget,
            palette = palette, layoutType = layoutType, project = project, nkbaReport = nkba,
            bom = bom, furniturePicks = emptyList(), notes = notes,
        )
    }

    // ------------------------------------------------------ furniture picking

    /** Wanted pieces per room type: furniture/fixture subtypes, plus
     *  cabinet kinds for vanities and wardrobes. */
    private fun wantedSubtypes(roomType: RoomType): List<String> = when (roomType) {
        RoomType.LIVING -> listOf("SOFA", "TVUNIT", "COFFEE")
        RoomType.BEDROOM -> listOf("BED", "BEDSIDE")
        RoomType.BATHROOM -> listOf("MIRROR")
        RoomType.OFFICE -> listOf("DESK", "BOOKCASE", "CHAIR")
        else -> emptyList()
    }

    private fun wantedCabinetKinds(roomType: RoomType): List<String> = when (roomType) {
        RoomType.BEDROOM -> listOf("wardrobe")
        RoomType.BATHROOM -> listOf("bath-vanity")
        else -> emptyList()
    }

    private fun pickFurniture(request: AssistantRequest, catalog: List<ExpansionSku>): List<ExpansionSku> {
        if (catalog.isEmpty()) return emptyList()
        val inRoom = CatalogExpansion.filterByRoomType(catalog, request.roomType.name)
        val picks = mutableListOf<ExpansionSku>()
        for (subtype in wantedSubtypes(request.roomType)) {
            pickOne(inRoom.filter { CatalogExpansion.subtypeOf(it) == subtype }, request)?.let { picks += it }
        }
        for (kind in wantedCabinetKinds(request.roomType)) {
            pickOne(inRoom.filter { it.cabinetKind == kind }, request)?.let { picks += it }
        }
        return picks.sortedBy { it.sku }
    }

    /** One deterministic pick: among items that fit the room width, the
     *  widest (STANDARD/PREMIUM) or the cheapest (ECONOMY); if nothing fits,
     *  the narrowest item overall. Ties break by SKU id. */
    private fun pickOne(candidates: List<ExpansionSku>, request: AssistantRequest): ExpansionSku? {
        if (candidates.isEmpty()) return null
        val fitting = candidates.filter { it.widthMm <= request.roomWidthMm }
        return when {
            fitting.isEmpty() -> candidates.minWithOrNull(compareBy<ExpansionSku> { it.widthMm }.thenBy { it.sku })
            request.budget == BudgetTier.ECONOMY ->
                fitting.minWithOrNull(compareBy<ExpansionSku> { it.priceUsdCentsEstimate }.thenBy { it.sku })
            else -> fitting.maxWithOrNull(compareBy<ExpansionSku> { it.widthMm }.thenBy { it.sku })
        }
    }
}
