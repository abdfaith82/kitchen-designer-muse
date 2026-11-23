package com.kitchendesigner.assistant

import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.DoorStyle
import com.kitchendesigner.model.HandleStyle
import com.kitchendesigner.model.KitchenFinishes
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.UnitCustomization

/**
 * Reference-photo styles (v0.5.0): three complete, deterministic looks taken
 * from real finished kitchens the user supplied, applied to an existing
 * layout's units. Applying a style rewrites ONLY finishes and per-unit
 * customization — positions, sizes, catalogue ids and appliances are never
 * touched, so NKBA geometry and the BOM structure stay valid.
 *
 * The looks:
 *  - CLASSIC_CREAM_SHAKER — cream shaker fronts, black bar handles, black
 *    granite worktop, Greek-key tile backsplash border, white marble floor,
 *    black cornice with cove LED, under-cabinet LED, and the last tall
 *    larder unit turned into a glass display cabinet with interior LED.
 *  - LUXE_WALNUT_CHARCOAL — walnut slab base units with integrated pulls,
 *    charcoal slab wall/tall units (push-to-open), alternating wide wall
 *    units in glass with interior LED, black-and-gold marble worktop and
 *    backsplash, copper globe pendants, under-cabinet LED.
 *  - MINIMAL_CREAM — flat cream slab fronts everywhere, integrated pulls,
 *    light quartz worktop, oak-wood backsplash panel, white marble floor,
 *    under-cabinet LED.
 */
enum class ReferenceStyle { CLASSIC_CREAM_SHAKER, LUXE_WALNUT_CHARCOAL, MINIMAL_CREAM }

data class StyledDesign(val units: List<PlacedUnit>, val finishes: KitchenFinishes)

object ReferenceStyles {

    fun finishesFor(style: ReferenceStyle): KitchenFinishes = when (style) {
        ReferenceStyle.CLASSIC_CREAM_SHAKER -> KitchenFinishes(
            countertopMaterialId = "counter-granite-black",
            backsplashId = "backsplash-greek-key",
            floorFinishId = "floor-marble-white",
            wallFinishId = "ivory",
            plinthFinishId = "plinth-black",
            corniceFinishId = "cornice-black",
            underCabinetLed = true,
            coveLed = true,
            pendantId = null,
        )
        ReferenceStyle.LUXE_WALNUT_CHARCOAL -> KitchenFinishes(
            countertopMaterialId = "counter-marble-black-gold",
            backsplashId = "backsplash-marble-black",
            floorFinishId = "floor-marble-white",
            wallFinishId = "wall-white-matte",
            plinthFinishId = "plinth-black",
            corniceFinishId = "cornice-walnut",
            underCabinetLed = true,
            coveLed = false,
            pendantId = "pendant-copper-globe",
        )
        ReferenceStyle.MINIMAL_CREAM -> KitchenFinishes(
            countertopMaterialId = "counter-quartz",
            backsplashId = "backsplash-wood-oak",
            floorFinishId = "floor-marble-white",
            wallFinishId = "wall-white-matte",
            plinthFinishId = "plinth-black",
            corniceFinishId = "cornice-cream",
            underCabinetLed = true,
            coveLed = false,
            pendantId = null,
        )
    }

    /** Style [units] (positions preserved) and return them with the
     *  design-wide [KitchenFinishes] for the same look. */
    fun apply(units: List<PlacedUnit>, style: ReferenceStyle): StyledDesign {
        val glassIndices = glassDisplayIndices(units, style)
        val styled = units.mapIndexed { index, placed ->
            placed.copy(unit = styleUnit(placed.unit, style, isGlassDisplay = index in glassIndices))
        }
        return StyledDesign(units = styled, finishes = finishesFor(style))
    }

    /** Which list positions become glass-front display units:
     *  - CLASSIC: the last TALL unit without an appliance (the larder).
     *  - LUXE: alternating wide WALL units without an appliance, starting
     *    with the first one.
     *  - MINIMAL: none. */
    private fun glassDisplayIndices(units: List<PlacedUnit>, style: ReferenceStyle): Set<Int> = when (style) {
        ReferenceStyle.CLASSIC_CREAM_SHAKER ->
            units.indices.filter { units[it].unit.type == CabinetType.TALL && units[it].unit.appliance == null }
                .lastOrNull()?.let { setOf(it) } ?: emptySet()
        ReferenceStyle.LUXE_WALNUT_CHARCOAL ->
            units.indices.filter {
                val u = units[it].unit
                u.type == CabinetType.WALL && u.appliance == null && u.widthMm >= 600
            }.filterIndexed { order, _ -> order % 2 == 0 }.toSet()
        ReferenceStyle.MINIMAL_CREAM -> emptySet()
    }

    private fun styleUnit(unit: CabinetUnit, style: ReferenceStyle, isGlassDisplay: Boolean): CabinetUnit {
        val custom = when (style) {
            ReferenceStyle.CLASSIC_CREAM_SHAKER -> UnitCustomization(
                doorStyle = if (isGlassDisplay) DoorStyle.GLASS else DoorStyle.SHAKER,
                handleStyle = HandleStyle.BAR,
                handleFinishId = "handle-black",
                interiorLed = isGlassDisplay,
            )
            ReferenceStyle.LUXE_WALNUT_CHARCOAL -> when (unit.type) {
                CabinetType.WALL -> UnitCustomization(
                    doorStyle = if (isGlassDisplay) DoorStyle.GLASS else DoorStyle.SLAB,
                    handleStyle = HandleStyle.NONE,
                    handleFinishId = "handle-black",
                    interiorLed = isGlassDisplay,
                )
                CabinetType.BASE, CabinetType.TALL -> UnitCustomization(
                    doorStyle = DoorStyle.SLAB,
                    handleStyle = HandleStyle.INTEGRATED,
                    handleFinishId = "handle-black",
                    interiorLed = false,
                )
            }
            ReferenceStyle.MINIMAL_CREAM -> UnitCustomization(
                doorStyle = DoorStyle.SLAB,
                handleStyle = HandleStyle.INTEGRATED,
                handleFinishId = "handle-chrome",
                interiorLed = false,
            )
        }
        val materialId = when (style) {
            ReferenceStyle.CLASSIC_CREAM_SHAKER -> "cabinet-cream"
            ReferenceStyle.LUXE_WALNUT_CHARCOAL -> when (unit.type) {
                CabinetType.BASE -> "walnut"
                CabinetType.WALL, CabinetType.TALL -> "cabinet-charcoal"
            }
            ReferenceStyle.MINIMAL_CREAM -> "cabinet-cream"
        }
        return unit.copy(materialId = materialId, custom = custom)
    }
}
