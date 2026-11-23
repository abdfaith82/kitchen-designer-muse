package com.kitchendesigner.model

/** All dimensions in this project are millimetres (mm), origin at the room's
 *  south-west floor corner, x = east (width), y = north (depth). */

data class PointMm(val xMm: Int, val yMm: Int) {
    fun distanceTo(other: PointMm): Int {
        val dx = (xMm - other.xMm).toDouble(); val dy = (yMm - other.yMm).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy).toInt()
    }
}

enum class WallSide { NORTH, SOUTH, EAST, WEST }

data class Wall(val side: WallSide, val lengthMm: Int, val heightMm: Int = 2_700)

data class Room(val widthMm: Int, val depthMm: Int, val ceilingHeightMm: Int = 2_700) {
    init { require(widthMm > 0 && depthMm > 0) { "Room dimensions must be positive" } }
    val walls: List<Wall> get() = listOf(
        Wall(WallSide.NORTH, widthMm, ceilingHeightMm), Wall(WallSide.SOUTH, widthMm, ceilingHeightMm),
        Wall(WallSide.EAST, depthMm, ceilingHeightMm), Wall(WallSide.WEST, depthMm, ceilingHeightMm))
}

enum class CabinetType { BASE, WALL, TALL }

/** A parametric cabinet unit: change the numbers and the 3D scene, BOM and
 *  drawings all follow — the model never stores a fixed mesh. */
data class CabinetUnit(
    val catalogId: String,
    val type: CabinetType,
    val widthMm: Int,
    val heightMm: Int,
    val depthMm: Int,
    val materialId: String = "front-oak",
    val appliance: ApplianceType? = null,
)

enum class ApplianceType { SINK, COOKTOP, OVEN, FRIDGE, DISHWASHER, HOOD }

data class Appliance(val type: ApplianceType, val widthMm: Int, val heightMm: Int, val depthMm: Int, val catalogId: String)

/** The three NKBA work points. */
data class WorkTriangle(val sink: PointMm, val cooktop: PointMm, val fridge: PointMm) {
    val legSinkCooktopMm: Int get() = sink.distanceTo(cooktop)
    val legCooktopFridgeMm: Int get() = cooktop.distanceTo(fridge)
    val legFridgeSinkMm: Int get() = fridge.distanceTo(sink)
    val totalMm: Int get() = legSinkCooktopMm + legCooktopFridgeMm + legFridgeSinkMm
}

/** A cabinet placed in the room (position = its south-west corner). */
data class PlacedUnit(val unit: CabinetUnit, val position: PointMm, val rotationDeg: Int = 0, val isIsland: Boolean = false)

enum class RoomType { KITCHEN, BATHROOM, LIVING, BEDROOM, OFFICE, OTHER }

/** One room of a (possibly multi-room) project: its own dimensions, type,
 *  placed units and wall openings. Rooms never share units or openings. */
data class ProjectRoom(
    val id: String,
    val type: RoomType,
    val room: Room,
    val name: String = "",
    val units: List<PlacedUnit> = emptyList(),
    val openings: List<Opening> = emptyList(),
) {
    init { require(id.isNotBlank()) { "ProjectRoom id must not be blank" } }
    /** Display name: the explicit [name], or empty (UI falls back to the type label). */
    val displayName: String get() = name
}

/**
 * A project: one or more rooms, exactly one of them active at a time.
 *
 * Backward compatibility (v0.1/v0.2 single-room shape): the secondary
 * constructor keeps the old `Project(id, name, room, units, schemaVersion)`
 * signature and wraps the room as a single KITCHEN [ProjectRoom] with id
 * "room-1". The [room] and [units] properties delegate to the ACTIVE room,
 * so existing readers (UnityBridge, BOM call sites, …) keep working and now
 * transparently see whichever room is active.
 */
data class Project(
    val id: String,
    val name: String,
    val rooms: List<ProjectRoom>,
    val activeRoomIndex: Int = 0,
    val schemaVersion: Int = 2,
) {
    init {
        require(rooms.isNotEmpty()) { "A project needs at least one room" }
        require(activeRoomIndex in rooms.indices) { "activeRoomIndex out of range" }
    }

    /** Old single-room constructor: wraps [room]/[units] as one KITCHEN room. */
    constructor(
        id: String,
        name: String,
        room: Room,
        units: List<PlacedUnit> = emptyList(),
        schemaVersion: Int = 1,
    ) : this(
        id = id,
        name = name,
        rooms = listOf(ProjectRoom(id = "room-1", type = RoomType.KITCHEN, room = room, units = units)),
        activeRoomIndex = 0,
        schemaVersion = schemaVersion,
    )

    /** The currently active room. */
    val activeRoom: ProjectRoom get() = rooms[activeRoomIndex]

    /** Back-compat view: the active room's dimensions. */
    val room: Room get() = activeRoom.room

    /** Back-compat view: the active room's placed units. */
    val units: List<PlacedUnit> get() = activeRoom.units

    fun roomById(roomId: String): ProjectRoom? = rooms.firstOrNull { it.id == roomId }

    /** Returns a copy with [index] as the active room (out-of-range = unchanged). */
    fun withActiveRoom(index: Int): Project =
        if (index in rooms.indices) copy(activeRoomIndex = index) else this

    /** Returns a copy where the room with [ProjectRoom.id] == room.id is replaced. */
    fun withRoom(room: ProjectRoom): Project =
        copy(rooms = rooms.map { if (it.id == room.id) room else it })

    /** Returns a copy with [room] appended (ids must stay unique; duplicate id replaces). */
    fun withAddedRoom(room: ProjectRoom): Project =
        if (rooms.any { it.id == room.id }) withRoom(room) else copy(rooms = rooms + room)
}
