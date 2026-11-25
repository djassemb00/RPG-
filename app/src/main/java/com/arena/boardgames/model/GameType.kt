package com.arena.boardgames.model

import androidx.compose.ui.graphics.Color

/**
 * Supported board games in the skeleton.
 * Add rules, state, and AI per game as the project evolves.
 */
enum class GameType(
    val id: String,
    val displayName: String,
    val description: String,
    val players: String,
    val boardLabel: String,
    val accentColor: Color,
    val darkCell: Color,
    val lightCell: Color,
) {
    CHESS(
        id = "chess",
        displayName = "الشطرنج",
        description = "لعبة استراتيجية كلاسيكية على لوحة 8×8.",
        players = "لاعبان",
        boardLabel = "8 × 8",
        accentColor = Color(0xFF4E342E),
        darkCell = Color(0xFF769656),
        lightCell = Color(0xFFEEEED2),
    ),
    BACKGAMMON(
        id = "backgammon",
        displayName = "الطاولة",
        description = "لعبة نرد وتحريك قطع على 24 نقطة.",
        players = "لاعبان",
        boardLabel = "24 نقطة",
        accentColor = Color(0xFF6D4C41),
        darkCell = Color(0xFF8D6E63),
        lightCell = Color(0xFFD7CCC8),
    ),
    LUDO(
        id = "ludo",
        displayName = "لودو",
        description = "لعبة عائلية سباق القطع حول اللوحة بالنرد.",
        players = "2 - 4 لاعبين",
        boardLabel = "15 × 15",
        accentColor = Color(0xFF1565C0),
        darkCell = Color(0xFF1976D2),
        lightCell = Color(0xFFF5F5F5),
    ),
    CHECKERS(
        id = "checkers",
        displayName = "الداما",
        description = "لعبة قفز والتقاط قطع الخصم على لوحة 8×8.",
        players = "لاعبان",
        boardLabel = "8 × 8",
        accentColor = Color(0xFF2E7D32),
        darkCell = Color(0xFF424242),
        lightCell = Color(0xFFF5F5F5),
    );

    companion object {
        fun fromId(id: String?): GameType = entries.firstOrNull { it.id == id } ?: CHESS
    }
}
