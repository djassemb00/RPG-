package com.arena.boardgames.ui.screens.game

import androidx.lifecycle.ViewModel
import com.arena.boardgames.model.BoardMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameViewModel : ViewModel() {

    private val _boardMode = MutableStateFlow(BoardMode.TWO_D)
    val boardMode: StateFlow<BoardMode> = _boardMode.asStateFlow()

    fun setBoardMode(mode: BoardMode) {
        _boardMode.value = mode
    }
}
