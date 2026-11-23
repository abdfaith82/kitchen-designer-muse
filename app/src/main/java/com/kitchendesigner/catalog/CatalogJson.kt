package com.kitchendesigner.catalog

/**
 * Runtime parser for `assets/catalog_expansion.json` (Workstream A, v0.4.0) —
 * resolves the TODO(runtime parsing) that [CatalogExpansion] carried: the
 * generated catalog can now actually be LOADED, not just filtered in tests.
 *
 * Hand-rolled, no external dependencies, deliberately mirroring the
 * conventions of `persistence/ProjectJson.kt`: the same strict
 * recursive-descent JSON parser shape and the same typed-map accessors, so
 * the project keeps exactly one JSON style. Malformed input throws
 * [IllegalArgumentException].
 *
 * Wire shape (generator schemaVersion 1):
 * ```
 * {"schemaVersion":1,"generatedBy":"scripts/generate_catalog.py",
 *  "priceDisclaimer":"…","skus":[
 *   {"sku":"CAB-BASE-600-…","kind":"CABINET","cabinetKind":"base",
 *    "widthMm":600,"heightMm":860,"depthMm":560,"doorStyle":"slab",
 *    "finishId":"oak","priceUsdCentsEstimate":18400,"region":"GLOBAL",
 *    "heatResistant":false,"humidityResistant":false,"supplier":null}, …]}
 * ```
 * `cabinetKind` / `doorStyle` / `supplier` are JSON null when absent;
 * `supplier` is read-and-ignored (no supplier quotes exist yet — see
 * [ExpansionSku]). Prices remain ESTIMATE placeholders, not quotes.
 */
object CatalogJson {

    /** Parses the full catalog file text into [ExpansionSku] entries,
     *  preserving file order. */
    fun parse(json: String): List<ExpansionSku> {
        val root = JsonParser(json).parse() as? Map<*, *>
            ?: throw IllegalArgumentException("Catalog JSON must be an object")
        val skusJson = root["skus"] as? List<*>
            ?: throw IllegalArgumentException("Catalog JSON is missing the \"skus\" array")
        return skusJson.map { item ->
            val m = item as? Map<*, *> ?: throw IllegalArgumentException("skus[] entries must be objects")
            ExpansionSku(
                sku = m.str("sku"),
                kind = m.str("kind"),
                cabinetKind = m["cabinetKind"] as? String,
                widthMm = m.int("widthMm"),
                heightMm = m.int("heightMm"),
                depthMm = m.int("depthMm"),
                doorStyle = m["doorStyle"] as? String,
                finishId = m.str("finishId"),
                priceUsdCentsEstimate = m.long("priceUsdCentsEstimate"),
                region = m.str("region"),
                heatResistant = m["heatResistant"] as? Boolean ?: false,
                humidityResistant = m["humidityResistant"] as? Boolean ?: false,
            )
        }
    }

    // ------------------------------------------------- typed map accessors
    // (Same conventions as persistence/ProjectJson.kt.)

    private fun Map<*, *>.str(key: String): String =
        (this[key] as? String) ?: throw IllegalArgumentException("Missing string field \"$key\"")

    private fun Map<*, *>.int(key: String): Int =
        (this[key] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing int field \"$key\"")

    private fun Map<*, *>.long(key: String): Long =
        (this[key] as? Number)?.toLong() ?: throw IllegalArgumentException("Missing long field \"$key\"")

    // ------------------------------------------- minimal strict JSON parser
    // Mirrors the parser in persistence/ProjectJson.kt (kept separate so the
    // catalog module has no dependency on the persistence module).

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            val value = readValue()
            skipWs()
            if (pos != text.length) fail("Trailing characters")
            return value
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("Invalid JSON at $pos: $msg")

        private fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }

        private fun readValue(): Any? {
            skipWs()
            if (pos >= text.length) fail("Unexpected end of input")
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) fail("Expected $literal")
            pos += literal.length
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') fail("Expected ':'")
                pos++
                map[key] = readValue()
                skipWs()
                if (pos >= text.length) fail("Unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                list += readValue()
                skipWs()
                if (pos >= text.length) fail("Unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            if (pos >= text.length || text[pos] != '"') fail("Expected string")
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("Unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("Bad \\u escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                                sb.append(code.toChar()); pos += 4
                            }
                            else -> fail("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            fail("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            if (raw.isEmpty() || raw == "-") fail("Invalid number")
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("Invalid number '$raw'")
        }
    }
}
