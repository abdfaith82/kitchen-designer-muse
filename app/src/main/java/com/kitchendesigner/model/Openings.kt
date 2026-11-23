package com.kitchendesigner.model

/** Doors and windows placed on a room's walls. All dimensions in mm.
 *
 * A wall is addressed by [Opening.wallIndex], the index into [Room.walls]
 * (0 = NORTH, 1 = SOUTH, 2 = EAST, 3 = WEST — the order [Room.walls] returns).
 * [Opening.offsetMm] is measured along the wall from its start corner:
 * for NORTH/SOUTH walls from the west end (x = 0) eastwards; for EAST/WEST
 * walls from the south end (y = 0) northwards. The opening occupies the
 * half-open range [offsetMm, offsetMm + widthMm) along the wall.
 */

enum class OpeningKind { DOOR, WINDOW }

/** How a door leaf moves. Meaningful for doors; windows use [NONE]. */
enum class SwingDirection { INWARD, OUTWARD, SLIDING, NONE }

data class Opening(
    val kind: OpeningKind,
    val wallIndex: Int,
    val offsetMm: Int,
    val widthMm: Int,
    /** Door leaf swing (doors only). Defaults to a plain inward swing for doors. */
    val swing: SwingDirection = if (kind == OpeningKind.DOOR) SwingDirection.INWARD else SwingDirection.NONE,
    /** Height of the window sill above the floor (windows only; 0 for doors). */
    val sillHeightMm: Int = 0,
    /** Leaf/sash height: 2 100 mm door, 1 200 mm window by default. */
    val heightMm: Int = if (kind == OpeningKind.DOOR) 2_100 else 1_200,
) {
    /** End of the opening along its wall (exclusive). */
    val endMm: Int get() = offsetMm + widthMm

    /** The wall side this opening sits on, or null for an out-of-range index. */
    val wallSide: WallSide? get() = WallSide.entries.getOrNull(wallIndex)

    /** Length of the wall this opening sits on in [room]; null if [wallIndex] is invalid. */
    fun wallLengthMm(room: Room): Int? = room.walls.getOrNull(wallIndex)?.lengthMm
}
