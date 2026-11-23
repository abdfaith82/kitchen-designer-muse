package com.kitchendesigner.catalog

import com.kitchendesigner.model.Appliance
import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType

/** Starter catalog — real standard metric sizes. Prices are USD cents and are
 *  PLACEHOLDER starter prices for estimation only, not supplier quotes. */
data class CatalogCabinet(
    val id: String, val type: CabinetType, val widthMm: Int, val heightMm: Int, val depthMm: Int,
    val nameEn: String, val nameAr: String, val priceUsdCents: Long,
)
data class MaterialDef(
    val id: String, val category: String, // COUNTERTOP | CABINET_FRONT | FLOOR
    val nameEn: String, val nameAr: String,
    val pricePerLinearMetreUsdCents: Long = 0, // countertops
    val pbrNotes: String = "PBR albedo/normal/roughness, ASTC/KTX2 on device",
)

object Catalog {
    const val MODULE_MM = 600
    val cabinets: List<CatalogCabinet> = listOf(
        CatalogCabinet("base-600", CabinetType.BASE, 600, 860, 560, "Base cabinet 60 cm", "خزانة أرضية 60 سم", 18_000),
        CatalogCabinet("base-300", CabinetType.BASE, 300, 860, 560, "Base cabinet 30 cm", "خزانة أرضية 30 سم", 11_000),
        CatalogCabinet("base-900", CabinetType.BASE, 900, 860, 560, "Base cabinet 90 cm", "خزانة أرضية 90 سم", 24_000),
        CatalogCabinet("base-sink-600", CabinetType.BASE, 600, 860, 560, "Sink base 60 cm", "خزانة حوض 60 سم", 16_000),
        CatalogCabinet("base-cooktop-600", CabinetType.BASE, 600, 860, 560, "Cooktop base 60 cm", "خزانة موقد 60 سم", 15_000),
        CatalogCabinet("wall-600", CabinetType.WALL, 600, 720, 320, "Wall cabinet 60 cm", "خزانة علوية 60 سم", 12_000),
        CatalogCabinet("wall-300", CabinetType.WALL, 300, 720, 320, "Wall cabinet 30 cm", "خزانة علوية 30 سم", 8_000),
        CatalogCabinet("tall-fridge-700", CabinetType.TALL, 700, 2_100, 650, "Tall fridge housing 70 cm", "وحدة ثلاجة طويلة 70 سم", 30_000),
        CatalogCabinet("tall-oven-600", CabinetType.TALL, 600, 2_100, 580, "Tall oven housing 60 cm", "وحدة فرن طويلة 60 سم", 28_000),
    )
    fun cabinet(id: String): CatalogCabinet = cabinets.first { it.id == id }

    val appliances: List<Appliance> = listOf(
        Appliance(ApplianceType.SINK, 560, 200, 460, "sink-560"),
        Appliance(ApplianceType.COOKTOP, 580, 50, 520, "cooktop-580"),
        Appliance(ApplianceType.OVEN, 595, 595, 567, "oven-595"),
        Appliance(ApplianceType.FRIDGE, 700, 1_900, 650, "fridge-700"),
        Appliance(ApplianceType.DISHWASHER, 600, 820, 550, "dishwasher-600"),
        Appliance(ApplianceType.HOOD, 600, 500, 500, "hood-600"),
    )
    val materials: List<MaterialDef> = listOf(
        MaterialDef("counter-quartz", "COUNTERTOP", "Quartz worktop", "سطح كوارتز", 32_000),
        MaterialDef("counter-granite", "COUNTERTOP", "Granite worktop", "سطح جرانيت", 28_000),
        MaterialDef("counter-laminate", "COUNTERTOP", "Laminate worktop", "سطح لامينيت", 9_000),
        MaterialDef("front-oak", "CABINET_FRONT", "Oak fronts", "واجهات بلوط"),
        MaterialDef("front-acrylic-white", "CABINET_FRONT", "White acrylic fronts", "واجهات أكريليك أبيض"),
        MaterialDef("front-hpl-grey", "CABINET_FRONT", "Grey HPL fronts", "واجهات HPL رمادي"),
        MaterialDef("floor-porcelain", "FLOOR", "Porcelain tiles", "أرضية بورسلين"),
        MaterialDef("floor-oak-laminate", "FLOOR", "Oak laminate", "أرضية لامينيت بلوط"),
    )
    fun material(id: String): MaterialDef = materials.first { it.id == id }
}
