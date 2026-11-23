package com.kitchendesigner.catalog

/**
 * Free-text + filter search over the expansion catalog (Workstream A,
 * v0.4.0). Pure functions over a `List<ExpansionSku>` — the list normally
 * comes from [CatalogJson.parse] at runtime, but any list works, so this is
 * fully unit-testable without Android or the asset file.
 *
 * Text matching is a case-insensitive substring test against the SKU id,
 * kind, cabinet kind, furniture/fixture subtype and finish id — the fields
 * a user actually types ("sofa", "oak", "wardrobe", "bath-vanity").
 * Results are sorted by SKU id so the output order is deterministic
 * regardless of input order.
 */
data class CatalogQuery(
    /** Free text; blank matches everything. */
    val text: String = "",
    /** One of the CatalogExpansion.ROOM_* constants, or null for any room. */
    val roomType: String? = null,
    /** One of the CatalogExpansion.GROUP_* constants, or null for any group. */
    val categoryGroup: String? = null,
    /** One of the CatalogExpansion.KIND_* constants, or null for any kind. */
    val kind: String? = null,
    /** Inclusive upper bound on the ESTIMATE price, USD cents. */
    val maxPriceUsdCents: Long? = null,
    /** Keep only MENA-tagged SKUs. */
    val menaOnly: Boolean = false,
    /** Keep only heat-resistant SKUs. */
    val heatResistantOnly: Boolean = false,
    /** Keep only humidity-resistant SKUs. */
    val humidityResistantOnly: Boolean = false,
)

object CatalogSearch {

    fun search(skus: List<ExpansionSku>, query: CatalogQuery): List<ExpansionSku> {
        val needle = query.text.trim().lowercase()
        return skus.filter { sku ->
            (needle.isEmpty() || matchesText(sku, needle)) &&
                (query.roomType == null || CatalogExpansion.roomTypeOf(sku) == query.roomType ||
                    (query.roomType != CatalogExpansion.ROOM_ALL &&
                        CatalogExpansion.roomTypeOf(sku) == CatalogExpansion.ROOM_ALL)) &&
                (query.categoryGroup == null || CatalogExpansion.categoryGroupOf(sku) == query.categoryGroup) &&
                (query.kind == null || sku.kind == query.kind) &&
                (query.maxPriceUsdCents == null || sku.priceUsdCentsEstimate <= query.maxPriceUsdCents) &&
                (!query.menaOnly || sku.region == CatalogExpansion.REGION_MENA) &&
                (!query.heatResistantOnly || sku.heatResistant) &&
                (!query.humidityResistantOnly || sku.humidityResistant)
        }.sortedBy { it.sku }
    }

    private fun matchesText(sku: ExpansionSku, needleLowercase: String): Boolean {
        val fields = listOfNotNull(
            sku.sku, sku.kind, sku.cabinetKind, sku.finishId, sku.doorStyle,
            CatalogExpansion.subtypeOf(sku),
        )
        return fields.any { it.lowercase().contains(needleLowercase) }
    }
}
