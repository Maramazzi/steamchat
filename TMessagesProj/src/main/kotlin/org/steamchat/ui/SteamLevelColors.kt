package org.steamchat.ui

/**
 * Colour keyed to a Steam level's ten-bracket (0-9, 10-19, ...), matching real Steam's own
 * "friendPlayerLevel lvl_NN" CSS classes - colours verified against
 * https://github.com/Luc4sguilherme/react-steam-level (a project that reproduces Steam's actual
 * community CSS) rather than guessed. Levels 100+ get a single gold colour instead of Steam's real
 * per-hundred sprite sheets (levels_hexagons.png, levels_shields.png, ...) - those are a fetchable
 * but purely cosmetic extra we skipped for now.
 */
internal object SteamLevelColors {
    fun forLevel(level: Int): Int {
        if (level >= 100) return GOLD
        val bracket = (level.coerceAtLeast(0) / 10) * 10
        return BRACKET_COLORS.getValue(bracket)
    }

    private val GOLD = 0xFFCFA935.toInt()
    private val BRACKET_COLORS = mapOf(
        0 to 0xFF9B9B9B.toInt(),
        10 to 0xFFC02942.toInt(),
        20 to 0xFFD95B43.toInt(),
        30 to 0xFFFECC23.toInt(),
        40 to 0xFF467A3C.toInt(),
        50 to 0xFF4E8DDB.toInt(),
        60 to 0xFF7652C9.toInt(),
        70 to 0xFFC252C9.toInt(),
        80 to 0xFF542437.toInt(),
        90 to 0xFF997C52.toInt(),
    )
}
