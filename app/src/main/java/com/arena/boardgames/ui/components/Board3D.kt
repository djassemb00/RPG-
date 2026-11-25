package com.arena.boardgames.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.arena.boardgames.model.GameType
import kotlin.math.min

/**
 * 3D board demo (isometric projection).
 *
 * This skeleton uses a Compose Canvas isometric renderer so it builds reliably
 * without requiring device-specific 3D assets. It can be replaced with a
 * SceneView/Filament implementation for real-time 3D rendering.
 */
@Composable
fun Board3D(
    gameType: GameType,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF101418)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIsometricBoard(gameType)
        }

        Text(
            text = "3D • ${gameType.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun DrawScope.drawIsometricBoard(gameType: GameType) {
    val cx = size.width / 2f
    val cy = size.height / 2f + size.height * 0.05f
    val board = min(size.width, size.height) * 0.62f
    val tile = board / 4f
    val depth = tile * 0.55f

    fun iso(x: Float, y: Float, z: Float = 0f): Offset {
        // x,y on board plane; z up
        val ix = (x - y) * 0.82f
        val iy = (x + y) * 0.48f - z
        return Offset(cx + ix, cy + iy)
    }

    // Board top
    val top = Path().apply {
        moveTo(iso(-board / 2, -board / 2).x, iso(-board / 2, -board / 2).y)
        lineTo(iso(board / 2, -board / 2).x, iso(board / 2, -board / 2).y)
        lineTo(iso(board / 2, board / 2).x, iso(board / 2, board / 2).y)
        lineTo(iso(-board / 2, board / 2).x, iso(-board / 2, board / 2).y)
        close()
    }

    // Sides for depth
    val rightSide = Path().apply {
        val p1 = iso(board / 2, -board / 2)
        val p2 = iso(board / 2, board / 2)
        val p3 = iso(board / 2, board / 2, -depth)
        val p4 = iso(board / 2, -board / 2, -depth)
        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
    }
    val leftSide = Path().apply {
        val p1 = iso(-board / 2, board / 2)
        val p2 = iso(board / 2, board / 2)
        val p3 = iso(board / 2, board / 2, -depth)
        val p4 = iso(-board / 2, board / 2, -depth)
        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
    }

    drawPath(rightSide, gameType.accentColor.copy(alpha = 0.55f))
    drawPath(leftSide, gameType.accentColor.copy(alpha = 0.35f))
    drawPath(top, Color(0xFF2A211B))
    drawPath(top, Color.White.copy(alpha = 0.12f), style = Stroke(2f))

    // Tiles
    for (r in 0 until 4) {
        for (c in 0 until 4) {
            val x0 = -board / 2 + c * tile
            val y0 = -board / 2 + r * tile
            val cell = Path().apply {
                moveTo(iso(x0, y0).x, iso(x0, y0).y)
                lineTo(iso(x0 + tile, y0).x, iso(x0 + tile, y0).y)
                lineTo(iso(x0 + tile, y0 + tile).x, iso(x0 + tile, y0 + tile).y)
                lineTo(iso(x0, y0 + tile).x, iso(x0, y0 + tile).y)
                close()
            }
            val isDark = (r + c) % 2 == 0
            val base = if (isDark) gameType.darkCell else gameType.lightCell
            drawPath(cell, base.copy(alpha = 0.92f))
            drawPath(cell, Color.Black.copy(alpha = 0.12f), style = Stroke(1.5f))
        }
    }

    // Demo pieces
    val piecePositions = when (gameType) {
        GameType.CHESS, GameType.CHECKERS -> listOf(
            -1.5f to -1.5f, -0.5f to -1.5f, 0.5f to -1.5f, 1.5f to -1.5f,
            -1.5f to 1.5f, -0.5f to 1.5f, 0.5f to 1.5f, 1.5f to 1.5f,
        )
        GameType.BACKGAMMON -> listOf(
            -1.5f to -1.2f, -0.5f to -1.2f, 0.5f to -1.2f, 1.5f to -1.2f,
            -1.5f to 1.2f, -0.5f to 1.2f, 0.5f to 1.2f, 1.5f to 1.2f,
        )
        GameType.LUDO -> listOf(-1.2f to -1.2f, 1.2f to -1.2f, 1.2f to 1.2f, -1.2f to 1.2f)
    }

    piecePositions.forEachIndexed { index, (gx, gy) ->
        val px = gx * (tile / 2f)
        val py = gy * (tile / 2f)
        val color = when (gameType) {
            GameType.LUDO -> listOf(
                Color(0xFFD32F2F), Color(0xFF388E3C), Color(0xFFFBC02D), Color(0xFF1976D2)
            )[index % 4]
            else -> if (index < piecePositions.size / 2) Color(0xFFF5F5F5) else Color(0xFF263238)
        }
        drawPiece(iso(px, py), tile * 0.18f, tile * 0.22f, color)
    }
}

private fun DrawScope.drawPiece(center: Offset, radius: Float, height: Float, color: Color) {
    // Shadow
    drawOval(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(center.x - radius, center.y - radius * 0.45f + 3f),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 0.9f),
    )
    // Body
    drawRect(
        color = color.copy(alpha = 0.85f),
        topLeft = Offset(center.x - radius, center.y - height),
        size = androidx.compose.ui.geometry.Size(radius * 2, height),
    )
    // Top
    drawOval(
        color = color,
        topLeft = Offset(center.x - radius, center.y - height - radius * 0.45f),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 0.9f),
    )
    // Bottom rim
    drawOval(
        color = Color.Black.copy(alpha = 0.15f),
        topLeft = Offset(center.x - radius, center.y - radius * 0.45f),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 0.9f),
        style = Stroke(1.5f),
    )
}
