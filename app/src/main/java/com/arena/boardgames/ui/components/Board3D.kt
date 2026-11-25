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
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView

/**
 * 3D board demo rendered with SceneView (Filament).
 *
 * Skeleton implementation: renders a procedural board and sample pieces for the selected
 * game type. Extend with real models (.glb), animations, hit-testing, and game rules.
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
        val engine = rememberEngine()
        val materialLoader = rememberMaterialLoader(engine)
        val view = rememberView(engine)
        val renderer = rememberRenderer(engine)
        val scene = rememberScene(engine)
        val cameraNode = rememberCameraNode(engine) {
            position = Position(x = 0f, y = 3.2f, z = 4.2f)
            lookAt(Position(x = 0f, y = 0f, z = 0f))
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
                intensity = 90_000f
            },
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(),
            childNodes = rememberNodes {
                add(
                    CubeNode(
                        engine = engine,
                        size = Size(x = 3.2f, y = 0.18f, z = 3.2f),
                        materialInstance = materialLoader.createColorInstance(
                            color = gameType.accentColor.toSceneColor(),
                            metallic = 0.15f,
                            roughness = 0.7f,
                            reflectance = 0.3f,
                        ),
                    ).apply {
                        position = Position(y = -0.1f)
                    }
                )

                demoPieces(gameType, engine, materialLoader).forEach { add(it) }
            },
        )

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

private fun demoPieces(
    gameType: GameType,
    engine: Engine,
    materialLoader: MaterialLoader,
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
    val rows = listOf(-1.2f, -0.8f, 0.8f, 1.2f)
    val cols = listOf(-1.2f, -0.4f, 0.4f, 1.2f)
    val lightColor = Color(0xFFF5F5F5).toSceneColor()
    val darkColor =
        if (gameType == GameType.CHECKERS) Color(0xFFE53935).toSceneColor()
        else Color(0xFF263238).toSceneColor()

    rows.forEachIndexed { rIndex, z ->
        cols.forEachIndexed { _, x ->
            val isDarkPiece = rIndex < 2
            val pieceColor = if (isDarkPiece) darkColor else lightColor
            val radius = if (gameType == GameType.CHECKERS) 0.16f else 0.13f
            val height = if (gameType == GameType.CHECKERS) 0.07f else 0.18f
            nodes += CylinderNode(
                engine = engine,
                radius = radius,
                height = height,
                materialInstance = materialLoader.createColorInstance(
                    color = pieceColor,
                    metallic = 0.1f,
                    roughness = 0.45f,
                    reflectance = 0.2f,
                ),
            ).apply {
                position = Position(x = x, y = height / 2f, z = z)
                rotation = Rotation()
            }
        }
    }
    return nodes
}

private fun backgammonPieces(engine: Engine, materialLoader: MaterialLoader): List<Node> {
    val nodes = mutableListOf<Node>()
    val white = Color(0xFFF5F5F5).toSceneColor()
    val black = Color(0xFF3E2723).toSceneColor()
    val xPositions = listOf(-1.2f, -0.6f, 0.6f, 1.2f)

    xPositions.forEach { x ->
        for (i in 0 until 3) {
            val zWhite = -1.2f + i * 0.22f
            val zBlack = 1.2f - i * 0.22f
            nodes += CylinderNode(
                engine = engine,
                radius = 0.12f,
                height = 0.05f,
                materialInstance = materialLoader.createColorInstance(white, 0f, 0.6f, 0.2f),
            ).apply { position = Position(x = x, y = 0.025f + i * 0.055f, z = zWhite) }

            nodes += CylinderNode(
                engine = engine,
                radius = 0.12f,
                height = 0.05f,
                materialInstance = materialLoader.createColorInstance(black, 0f, 0.6f, 0.2f),
            ).apply { position = Position(x = x, y = 0.025f + i * 0.055f, z = zBlack) }
        }
    }
    return nodes
}

private fun ludoPieces(engine: Engine, materialLoader: MaterialLoader): List<Node> {
    val colors = listOf(
        Color(0xFFD32F2F),
        Color(0xFF388E3C),
        Color(0xFFFBC02D),
        Color(0xFF1976D2),
    )
    val positions = listOf(
        Position(-1.0f, 0.16f, -1.0f),
        Position(1.0f, 0.16f, -1.0f),
        Position(1.0f, 0.16f, 1.0f),
        Position(-1.0f, 0.16f, 1.0f),
    )

    return positions.zip(colors).map { (pos, color) ->
        CylinderNode(
            engine = engine,
            radius = 0.14f,
            height = 0.32f,
            materialInstance = materialLoader.createColorInstance(
                color = color.toSceneColor(),
                metallic = 0.05f,
                roughness = 0.5f,
                reflectance = 0.2f,
            ),
        ).apply { position = pos }
    }
}

private fun Color.toSceneColor(): io.github.sceneview.math.Color = io.github.sceneview.math.Color(
    red = red,
    green = green,
    blue = blue,
    alpha = alpha,
)
