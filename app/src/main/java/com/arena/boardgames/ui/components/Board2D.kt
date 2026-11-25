package com.arena.boardgames.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.arena.boardgames.model.GameType
import kotlin.math.min

@Composable
fun Board2D(
    gameType: GameType,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A120E))
            .padding(12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (gameType) {
                GameType.CHESS -> drawCheckerBoard(gameType, rows = 8, cols = 8, drawPieces = true)
                GameType.CHECKERS -> drawCheckerBoard(gameType, rows = 8, cols = 8, drawPieces = true, checkersOnly = true)
                GameType.BACKGAMMON -> drawBackgammonBoard(gameType)
                GameType.LUDO -> drawLudoBoard()
            }
        }
    }
}

private fun DrawScope.drawCheckerBoard(
    gameType: GameType,
    rows: Int,
    cols: Int,
    drawPieces: Boolean,
    checkersOnly: Boolean = false,
) {
    val boardSize = min(size.width, size.height)
    val cell = boardSize / cols
    val origin = Offset((size.width - boardSize) / 2f, (size.height - boardSize) / 2f)

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val isDark = (r + c) % 2 == 1
            drawRect(
                color = if (isDark) gameType.darkCell else gameType.lightCell,
                topLeft = Offset(origin.x + c * cell, origin.y + r * cell),
                size = Size(cell, cell),
            )
        }
    }

    drawRect(
        color = Color(0xFF5D4037),
        topLeft = origin,
        size = Size(boardSize, boardSize),
        style = Stroke(width = 6f),
    )

    if (!drawPieces) return

    val radius = cell * 0.35f
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val isDark = (r + c) % 2 == 1
            val center = Offset(origin.x + c * cell + cell / 2f, origin.y + r * cell + cell / 2f)

            val pieceColor: Color? = when {
                checkersOnly && isDark && r < 3 -> Color(0xFFE53935)
                checkersOnly && isDark && r > 4 -> Color(0xFFF5F5F5)
                !checkersOnly && r < 2 -> Color(0xFF263238)
                !checkersOnly && r > 5 -> Color(0xFFFAFAFA)
                else -> null
            }

            if (pieceColor != null) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = radius,
                    center = center + Offset(2f, 3f),
                )
                drawCircle(color = pieceColor, radius = radius, center = center)
                drawCircle(
                    color = if (pieceColor == Color(0xFF263238) || pieceColor == Color(0xFFE53935))
                        Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.2f),
                    radius = radius * 0.7f,
                    center = center,
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

private fun DrawScope.drawBackgammonBoard(gameType: GameType) {
    val boardW = min(size.width, size.height * 1.2f)
    val boardH = boardW * 0.75f
    val origin = Offset((size.width - boardW) / 2f, (size.height - boardH) / 2f)

    drawRoundRect(
        color = Color(0xFF5D4037),
        topLeft = origin,
        size = Size(boardW, boardH),
        cornerRadius = CornerRadius(16f, 16f),
    )
    drawRoundRect(
        color = Color(0xFFEEDCC6),
        topLeft = origin + Offset(10f, 10f),
        size = Size(boardW - 20f, boardH - 20f),
        cornerRadius = CornerRadius(12f, 12f),
    )

    val barW = boardW * 0.06f
    val halfW = (boardW - 20f - barW) / 2f
    val triangleW = halfW / 6f
    val triangleH = (boardH - 20f) * 0.42f
    val topY = origin.y + 10f
    val bottomY = origin.y + boardH - 10f

    fun drawTriangle(x: Float, top: Boolean, index: Int) {
        val color = if (index % 2 == 0) gameType.darkCell else Color(0xFFF5EFE6)
        val path = Path().apply {
            if (top) {
                moveTo(x, topY)
                lineTo(x + triangleW, topY)
                lineTo(x + triangleW / 2f, topY + triangleH)
            } else {
                moveTo(x, bottomY)
                lineTo(x + triangleW, bottomY)
                lineTo(x + triangleW / 2f, bottomY - triangleH)
            }
            close()
        }
        drawPath(path, color)
    }

    for (i in 0 until 6) {
        val xLeft = origin.x + 10f + i * triangleW
        val xRight = origin.x + 10f + halfW + barW + i * triangleW
        drawTriangle(xLeft, top = true, index = i)
        drawTriangle(xRight, top = true, index = i + 1)
        drawTriangle(xLeft, top = false, index = i + 1)
        drawTriangle(xRight, top = false, index = i)
    }

    // Center bar
    drawRect(
        color = Color(0xFF6D4C41),
        topLeft = Offset(origin.x + 10f + halfW, topY),
        size = Size(barW, boardH - 20f),
    )

    // Demo checkers
    fun drawChecker(x: Float, y: Float, color: Color) {
        drawCircle(color = Color.Black.copy(alpha = 0.2f), radius = triangleW * 0.28f, center = Offset(x + 1f, y + 2f))
        drawCircle(color = color, radius = triangleW * 0.28f, center = Offset(x, y))
    }

    val r = triangleW * 0.28f
    for (i in 0 until 5) {
        drawChecker(origin.x + 10f + triangleW / 2f, topY + r + i * r * 2.1f, Color(0xFFF5F5F5))
        drawChecker(origin.x + 10f + triangleW * 5.5f, bottomY - r - i * r * 2.1f, Color(0xFFF5F5F5))
        drawChecker(origin.x + 10f + triangleW * 5.5f, topY + r + i * r * 2.1f, Color(0xFF3E2723))
        drawChecker(origin.x + 10f + triangleW / 2f, bottomY - r - i * r * 2.1f, Color(0xFF3E2723))
    }
}

private fun DrawScope.drawLudoBoard() {
    val boardSize = min(size.width, size.height)
    val cell = boardSize / 15f
    val origin = Offset((size.width - boardSize) / 2f, (size.height - boardSize) / 2f)

    fun cellRect(r: Int, c: Int) = Offset(origin.x + c * cell, origin.y + r * cell)

    val red = Color(0xFFD32F2F)
    val green = Color(0xFF388E3C)
    val yellow = Color(0xFFFBC02D)
    val blue = Color(0xFF1976D2)

    // Home bases
    drawRect(red, cellRect(0, 0), Size(cell * 6, cell * 6))
    drawRect(green, cellRect(0, 9), Size(cell * 6, cell * 6))
    drawRect(blue, cellRect(9, 0), Size(cell * 6, cell * 6))
    drawRect(yellow, cellRect(9, 9), Size(cell * 6, cell * 6))

    fun drawBase(r0: Int, c0: Int, color: Color) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = cellRect(r0 + 1, c0 + 1),
            size = Size(cell * 4, cell * 4),
            cornerRadius = CornerRadius(12f, 12f),
        )
        val spots = listOf(r0 + 2 to c0 + 2, r0 + 2 to c0 + 3, r0 + 3 to c0 + 2, r0 + 3 to c0 + 3)
        spots.forEach { (r, c) ->
            drawCircle(color, cell * 0.45f, cellRect(r, c) + Offset(cell / 2, cell / 2))
        }
    }
    drawBase(0, 0, red)
    drawBase(0, 9, green)
    drawBase(9, 0, blue)
    drawBase(9, 9, yellow)

    // Grid
    for (r in 0 until 15) for (c in 0 until 15) {
        val inBase = (r < 6 && c < 6) || (r < 6 && c > 8) || (r > 8 && c < 6) || (r > 8 && c > 8)
        val inCenter = r in 6..8 && c in 6..8
        if (inBase || inCenter) continue
        val color = when {
            c == 7 && r in 1..5 -> green
            r == 7 && c in 9..13 -> yellow
            c == 7 && r in 9..13 -> blue
            r == 7 && c in 1..5 -> red
            r == 6 && c == 1 -> red
            r == 1 && c == 8 -> green
            r == 8 && c == 13 -> yellow
            r == 13 && c == 6 -> blue
            else -> Color.White
        }
        drawRect(color, cellRect(r, c), Size(cell, cell))
        drawRect(Color(0xFFBDBDBD), cellRect(r, c), Size(cell, cell), style = Stroke(1.5f))
    }

    // Center
    val center = cellRect(6, 6)
    val cSize = cell * 3
    val mid = center + Offset(cSize / 2, cSize / 2)
    drawPath(Path().apply { moveTo(center.x, center.y); lineTo(mid.x, mid.y); lineTo(center.x, center.y + cSize); close() }, red)
    drawPath(Path().apply { moveTo(center.x, center.y); lineTo(mid.x, mid.y); lineTo(center.x + cSize, center.y); close() }, green)
    drawPath(Path().apply { moveTo(center.x + cSize, center.y); lineTo(mid.x, mid.y); lineTo(center.x + cSize, center.y + cSize); close() }, yellow)
    drawPath(Path().apply { moveTo(center.x, center.y + cSize); lineTo(mid.x, mid.y); lineTo(center.x + cSize, center.y + cSize); close() }, blue)
}
