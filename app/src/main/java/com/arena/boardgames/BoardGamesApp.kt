package com.arena.boardgames

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arena.boardgames.ui.navigation.BoardGamesNavGraph

@Composable
fun BoardGamesApp(modifier: Modifier = Modifier) {
    BoardGamesNavGraph(modifier = modifier)
}
