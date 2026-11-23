package com.kitchendesigner.persistence

import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.ProjectRoom
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType
import com.kitchendesigner.model.SwingDirection

/**
 * Hand-rolled JSON save/load for the full [Project] — no external
 * dependencies (the project deliberately avoids a JSON library; key names
 * follow the same conventions as unity/UnityBridge.kt: "widthMm", "xMm", …).
 *
 * Wire shape (format version "v":2):
 * ```
 * {"v":2,"id":"p1","name":"Home","schemaVersion":2,"activeRoomIndex":0,
 *  "rooms":[{"id":"room-1","name":"","type":"KITCHEN",
 *            "room":{"widthMm":4200,"depthMm":3600,"ceilingHeightMm":2700},
 *            "units":[{"catalogId":"base-600","type":"BASE","widthMm":600,"heightMm":900,
 *                      "depthMm":560,"materialId":"front-oak","appliance":"SINK",
 *                      "xMm":0,"yMm":0,"rotationDeg":0,"isIsland":false}],
 *            "openings":[{"kind":"DOOR","wallIndex":1,"offsetMm":300,"widthMm":900,
 *                         "heightMm":2100,"swing":"INWARD","sillHeightMm":0}]}]}
 * ```
 * "appliance" is omitted when null. Loading also accepts the legacy v1
 * single-room shape ({"room":{…},"units":[…]}) and wraps it as one
 * KITCHEN room, mirroring [Project]'s back-compat constructor.
 *
 * Parsing is a small strict recursive-descent JSON parser; malformed input
 * throws [IllegalArgumentException].
 */
object ProjectJson {

    const val FORMAT_VERSION = 2

    // ------------------------------------------------------------- writing

    fun toJson(project: Project): String = buildString {
        append("{\"v\":").append(FORMAT_VERSION)
        append(",\"id\":").append(quoted(project.id))
        append(",\"name\":").append(quoted(project.name))
        append(",\"schemaVersion\":").append(project.schemaVersion)
        append(",\"activeRoomIndex\":").append(project.activeRoomIndex)
        append(",\"rooms\":[")
        project.rooms.forEachIndexed { i, r ->
            if (i > 0) append(",")
            appendRoom(r)
        }
        append("]}")
    }

    private fun StringBuilder.appendRoom(r: ProjectRoom) {
        append("{\"id\":").append(quoted(r.id))
        append(",\"name\":").append(quoted(r.name))
        append(",\"type\":\"").append(r.type.name).append("\"")
        append(",\"room\":{\"widthMm\":").append(r.room.widthMm)
        append(",\"depthMm\":").append(r.room.depthMm)
        append(",\"ceilingHeightMm\":").append(r.room.ceilingHeightMm).append("}")
        append(",\"units\":[")
        r.units.forEachIndexed { i, u ->
            if (i > 0) append(",")
            appendUnit(u)
        }
        append("],\"openings\":[")
        r.openings.forEachIndexed { i, o ->
            if (i > 0) append(",")
            appendOpening(o)
        }
        append("]}")
    }

    private fun StringBuilder.appendUnit(u: PlacedUnit) {
        val c = u.unit
        append("{\"catalogId\":").append(quoted(c.catalogId))
        append(",\"type\":\"").append(c.type.name).append("\"")
        append(",\"widthMm\":").append(c.widthMm)
        append(",\"heightMm\":").append(c.heightMm)
        append(",\"depthMm\":").append(c.depthMm)
        append(",\"materialId\":").append(quoted(c.materialId))
        if (c.appliance != null) append(",\"appliance\":\"").append(c.appliance.name).append("\"")
        append(",\"xMm\":").append(u.position.xMm)
        append(",\"yMm\":").append(u.position.yMm)
        append(",\"rotationDeg\":").append(u.rotationDeg)
        append(",\"isIsland\":").append(u.isIsland)
        append("}")
    }

    private fun StringBuilder.appendOpening(o: Opening) {
        append("{\"kind\":\"").append(o.kind.name).append("\"")
        append(",\"wallIndex\":").append(o.wallIndex)
        append(",\"offsetMm\":").append(o.offsetMm)
        append(",\"widthMm\":").append(o.widthMm)
        append(",\"heightMm\":").append(o.heightMm)
        append(",\"swing\":\"").append(o.swing.name).append("\"")
        append(",\"sillHeightMm\":").append(o.sillHeightMm)
        append("}")
    }

    private fun quoted(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    // ------------------------------------------------------------- reading

    fun fromJson(json: String): Project {
        val root = JsonParser(json).parse() as? Map<*, *>
            ?: throw IllegalArgumentException("Project JSON must be an object")
        val id = root.str("id")
        val name = root.str("name")
        val schemaVersion = root.int("schemaVersion", 1)
        val roomsJson = root["rooms"] as? List<*>
        if (roomsJson != null) {
            val rooms = roomsJson.map { item ->
                val m = item as? Map<*, *> ?: throw IllegalArgumentException("rooms[] entries must be objects")
                parseRoom(m)
            }
            return Project(
                id = id, name = name, rooms = rooms,
                activeRoomIndex = root.int("activeRoomIndex", 0).coerceIn(0, maxOf(0, rooms.size - 1)),
                schemaVersion = schemaVersion,
            )
        }
        // Legacy v1 shape: {"room":{…},"units":[…]} at the top level.
        val roomMap = root["room"] as? Map<*, *>
            ?: throw IllegalArgumentException("Project JSON has neither \"rooms\" nor legacy \"room\"")
        val units = (root["units"] as? List<*>).orEmpty().map { parseUnit(it as Map<*, *>) }
        return Project(id = id, name = name, room = parseRoomDims(roomMap), units = units, schemaVersion = schemaVersion)
    }

    private fun parseRoom(m: Map<*, *>): ProjectRoom {
        val dims = m["room"] as? Map<*, *> ?: throw IllegalArgumentException("room entry missing \"room\" dimensions")
        return ProjectRoom(
            id = m.str("id"),
            type = enumOr<RoomType>(m.str("type", RoomType.KITCHEN.name), RoomType.KITCHEN),
            room = parseRoomDims(dims),
            name = m.str("name", ""),
            units = (m["units"] as? List<*>).orEmpty().map { parseUnit(it as Map<*, *>) },
            openings = (m["openings"] as? List<*>).orEmpty().map { parseOpening(it as Map<*, *>) },
        )
    }

    private fun parseRoomDims(m: Map<*, *>): Room = Room(
        widthMm = m.int("widthMm"),
        depthMm = m.int("depthMm"),
        ceilingHeightMm = m.int("ceilingHeightMm", 2_700),
    )

    private fun parseUnit(m: Map<*, *>): PlacedUnit = PlacedUnit(
        unit = CabinetUnit(
            catalogId = m.str("catalogId"),
            type = enumOr<CabinetType>(m.str("type", CabinetType.BASE.name), CabinetType.BASE),
            widthMm = m.int("widthMm"),
            heightMm = m.int("heightMm"),
            depthMm = m.int("depthMm"),
            materialId = m.str("materialId", "front-oak"),
            appliance = (m["appliance"] as? String)?.let { enumOr<ApplianceType>(it, ApplianceType.SINK) },
        ),
        position = PointMm(m.int("xMm"), m.int("yMm")),
        rotationDeg = m.int("rotationDeg", 0),
        isIsland = m["isIsland"] as? Boolean ?: false,
    )

    private fun parseOpening(m: Map<*, *>): Opening {
        val kind = enumOr<OpeningKind>(m.str("kind", OpeningKind.DOOR.name), OpeningKind.DOOR)
        return Opening(
            kind = kind,
            wallIndex = m.int("wallIndex"),
            offsetMm = m.int("offsetMm"),
            widthMm = m.int("widthMm"),
            swing = enumOr<SwingDirection>(
                m.str("swing", if (kind == OpeningKind.DOOR) SwingDirection.INWARD.name else SwingDirection.NONE.name),
                if (kind == OpeningKind.DOOR) SwingDirection.INWARD else SwingDirection.NONE,
            ),
            sillHeightMm = m.int("sillHeightMm", 0),
            heightMm = m.int("heightMm", if (kind == OpeningKind.DOOR) 2_100 else 1_200),
        )
    }

    // ------------------------------------------------- typed map accessors

    private fun Map<*, *>.str(key: String, default: String? = null): String =
        (this[key] as? String) ?: default ?: throw IllegalArgumentException("Missing string field \"$key\"")

    private fun Map<*, *>.int(key: String, default: Int? = null): Int =
        (this[key] as? Number)?.toInt() ?: default ?: throw IllegalArgumentException("Missing int field \"$key\"")

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    // ------------------------------------------- minimal strict JSON parser

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
