package com.arena.boardgames.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arena.boardgames.model.GameType
import com.google.android.filament.Engine
import io.github.sceneview.Scene
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import io.github.sceneview.math.Color as SceneColor

/**
 * Real-time 3D board rendered with SceneView (Filament).
 * The user can orbit/pan/zoom with touch gestures via [rememberCameraManipulator].
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
            .background(Color(0xFF0F141A)),
    ) {
        val engine = rememberEngine()
        val materialLoader = rememberMaterialLoader(engine)
        val view = rememberView(engine)
        val renderer = rememberRenderer(engine)
        val scene = rememberScene(engine)
        val cameraNode = rememberCameraNode(engine) {
            position = Position(x = 0f, y = 3.8f, z = 5.2f)
            lookAt(Position(x = 0f, y = 0f, z = 0f))
        }
        val childNodes = rememberNodes {
            add(boardBase(engine, materialLoader, gameType))
            boardTiles(engine, materialLoader, gameType).forEach { add(it) }
            demoPieces(engine, materialLoader, gameType).forEach { add(it) }
        }

        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            renderer = renderer,
            scene = scene,
            materialLoader = materialLoader,
            collisionSystem = rememberCollisionSystem(view),
            mainLightNode = rememberMainLightNode(engine) {
                intensity = 110_000f
            },
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(),
            childNodes = childNodes,
            onGestureListener = rememberOnGestureListener(),
        )

        Text(
            text = "3D • ${gameType.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun boardBase(
    engine: Engine,
    materialLoader: MaterialLoader,
    gameType: GameType,
): Node = CubeNode(
    engine = engine,
    size = Size(x = 4.4f, y = 0.28f, z = 4.4f),
    materialInstance = materialLoader.createColorInstance(
        color = gameType.accentColor.toSceneColor(),
        metallic = 0.1f,
        roughness = 0.75f,
        reflectance = 0.25f,
    ),
).apply {
    position = Position(y = -0.14f)
}

private fun boardTiles(
    engine: Engine,
    materialLoader: MaterialLoader,
    gameType: GameType,
): List<Node> {
    val nodes = mutableListOf<Node>()
    val boardSize = 4f
    val divisions = when (gameType) {
        GameType.CHESS, GameType.CHECKERS -> 8
        GameType.LUDO -> 6
        GameType.BACKGAMMON -> 8
    }
    val tileSize = boardSize / divisions
    val start = -boardSize / 2f + tileSize / 2f

    for (r in 0 until divisions) {
        for (c in 0 until divisions) {
            val dark = (r + c) % 2 == 0
            val color = if (dark) gameType.darkCell.toSceneColor() else gameType.lightCell.toSceneColor()
            nodes += CubeNode(
                engine = engine,
                size = Size(x = tileSize * 0.96f, y = 0.04f, z = tileSize * 0.96f),
                materialInstance = materialLoader.createColorInstance(
                    color = color,
                    metallic = 0.0f,
                    roughness = 0.85f,
                    reflectance = 0.1f,
                ),
            ).apply {
                position = Position(
                    x = start + c * tileSize,
                    y = 0.02f,
                    z = start + r * tileSize,
                )
            }
        }
    }
    return nodes
}

private fun demoPieces(
    engine: Engine,
    materialLoader: MaterialLoader,
    gameType: GameType,
): List<Node> = when (gameType) {
    GameType.CHESS, GameType.CHECKERS -> checkerPieces(engine, materialLoader, gameType)
    GameType.BACKGAMMON -> backgammonPieces(engine, materialLoader)
    GameType.LUDO -> ludoPieces(engine, materialLoader)
}

private fun checkerPieces(
    engine: Engine,
    materialLoader: MaterialLoader,
    gameType: GameType,
): List<Node> {
    val nodes = mutableListOf<Node>()
    val coords = listOf(-1.5f, -0.5f, 0.5f, 1.5f)
    val light = SceneColor(0.96f, 0.96f, 0.96f, 1f)
    val dark = if (gameType == GameType.CHECKERS) {
        SceneColor(0.90f, 0.22f, 0.21f, 1f)
    } else {
        SceneColor(0.15f, 0.17f, 0.20f, 1f)
    }

    coords.forEachIndexed { rIdx, z ->
        coords.forEach { x ->
            val pieceColor = if (rIdx < 2) dark else light
            val radius = if (gameType == GameType.CHECKERS) 0.18f else 0.15f
            val height = if (gameType == GameType.CHECKERS) 0.08f else 0.22f
            nodes += CylinderNode(
                engine = engine,
                radius = radius,
                height = height,
                materialInstance = materialLoader.createColorInstance(
                    color = pieceColor,
                    metallic = 0.05f,
                    roughness = 0.45f,
                    reflectance = 0.2f,
                ),
            ).apply {
                position = Position(x = x, y = 0.04f + height / 2f, z = z)
            }
        }
    }
    return nodes
}

private fun backgammonPieces(engine: Engine, materialLoader: MaterialLoader): List<Node> {
    val nodes = mutableListOf<Node>()
    val white = SceneColor(0.96f, 0.96f, 0.96f, 1f)
    val brown = SceneColor(0.30f, 0.20f, 0.15f, 1f)
    val xs = listOf(-1.5f, -0.9f, -0.3f, 0.3f, 0.9f, 1.5f)

    xs.forEach { x ->
        for (i in 0 until 3) {
            val y = 0.04f + 0.035f + i * 0.075f
            nodes += CylinderNode(
                engine = engine,
                radius = 0.13f,
                height = 0.06f,
                materialInstance = materialLoader.createColorInstance(white, 0f, 0.55f, 0.2f),
            ).apply { position = Position(x = x, y = y, z = -1.3f + i * 0.12f) }

            nodes += CylinderNode(
                engine = engine,
                radius = 0.13f,
                height = 0.06f,
                materialInstance = materialLoader.createColorInstance(brown, 0f, 0.55f, 0.2f),
            ).apply { position = Position(x = x, y = y, z = 1.3f - i * 0.12f) }
        }
    }
    return nodes
}

private fun ludoPieces(engine: Engine, materialLoader: MaterialLoader): List<Node> {
    val colors = listOf(
        SceneColor(0.83f, 0.18f, 0.18f, 1f),
        SceneColor(0.22f, 0.56f, 0.24f, 1f),
        SceneColor(0.98f, 0.75f, 0.18f, 1f),
        SceneColor(0.10f, 0.46f, 0.82f, 1f),
    )
    val positions = listOf(
        Position(-1.2f, 0.22f, -1.2f),
        Position(1.2f, 0.22f, -1.2f),
        Position(1.2f, 0.22f, 1.2f),
        Position(-1.2f, 0.22f, 1.2f),
    )
    return positions.zip(colors).map { (pos, color) ->
        CylinderNode(
            engine = engine,
            radius = 0.16f,
            height = 0.36f,
            materialInstance = materialLoader.createColorInstance(
                color = color,
                metallic = 0.05f,
                roughness = 0.45f,
                reflectance = 0.2f,
            ),
        ).apply { position = pos }
    }
}

private fun Color.toSceneColor(): SceneColor = SceneColor(red, green, blue, alpha)
