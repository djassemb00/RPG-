package com.arena.boardgames.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arena.boardgames.model.GameType
import com.arena.boardgames.ui.screens.game.GameScreen
import com.arena.boardgames.ui.screens.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val GAME = "game/{gameId}"

    fun game(gameId: String) = "game/$gameId"
}

@Composable
fun BoardGamesNavGraph(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onGameSelected = { game -> navController.navigate(Routes.game(game.id)) },
            )
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameType = GameType.fromId(backStackEntry.arguments?.getString("gameId"))
            GameScreen(
                gameType = gameType,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
