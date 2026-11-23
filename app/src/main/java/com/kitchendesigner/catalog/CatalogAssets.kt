package com.kitchendesigner.catalog

import android.content.Context

/**
 * Android-side loader for the expansion catalog (Workstream A, v0.4.0):
 * reads `assets/catalog_expansion.json` and parses it with the pure-Kotlin
 * [CatalogJson] parser — no JSON library needed on the Android side either.
 *
 * NOTE: this is the one catalog file with an Android dependency, so it is
 * excluded from the standalone kotlinc/JUnit verification run (same as the
 * ui/ files). It is written against the standard Context.assets API and
 * compiles under Gradle in Android Studio.
 */
object CatalogAssets {

    const val ASSET_NAME = "catalog_expansion.json"

    /** Loads and parses the full expansion catalog (3,169 SKUs in v0.4.0).
     *  Call from a background dispatcher — the file is ~600 KB. */
    fun loadExpansionCatalog(context: Context): List<ExpansionSku> {
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return CatalogJson.parse(json)
    }
}
