package com.example.sudokoonline

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.sudokoonline.util.Event
import com.example.sudokoonline.util.SudokuLogic

class SinglePlayerViewModel : ViewModel() {

    private val _initialBoard = MutableLiveData<Array<IntArray>>()
    // val initialBoard: LiveData<Array<IntArray>> get() = _initialBoard

    private val _currentBoard = MutableLiveData<Array<IntArray>>()
    val currentBoard: LiveData<Array<IntArray>> get() = _currentBoard

    private val _solutionBoard = MutableLiveData<Array<IntArray>>()

    private val _selectedCell = MutableLiveData<Pair<Int, Int>?>(null)
    val selectedCell: LiveData<Pair<Int, Int>?> get() = _selectedCell

    private val _isCellFixed = MutableLiveData<Array<BooleanArray>>()
    val isCellFixed: LiveData<Array<BooleanArray>> get() = _isCellFixed

    private val _gameStatusMessage = MutableLiveData<Event<String>>()
    val gameStatusMessage: LiveData<Event<String>> get() = _gameStatusMessage

    private val _isGameWon = MutableLiveData<Event<Boolean>>()
    val isGameWon: LiveData<Event<Boolean>> get() = _isGameWon

    init {
        startNewGame()
    }

    fun startNewGame(difficulty: Int = 40) {
        try {
            val (puzzle, solution) = SudokuLogic.generateNewGame(difficulty)
            _initialBoard.value = puzzle
            _currentBoard.value = puzzle.map { it.clone() }.toTypedArray()
            _solutionBoard.value = solution

            val fixedCells = Array(9) { BooleanArray(9) }
            for (r in 0..8) {
                for (c in 0..8) {
                    fixedCells[r][c] = puzzle[r][c] != 0
                }
            }
            _isCellFixed.value = fixedCells
            _selectedCell.value = null
            _isGameWon.value = Event(false)
            _gameStatusMessage.value = Event("Nowa gra rozpoczęta!")
        } catch (e: IllegalStateException) {
            _gameStatusMessage.value = Event("Błąd generowania planszy: ${e.message}")
            Log.e("SinglePlayerViewModel", "Błąd podczas generowania nowej gry", e)
        }
    }

    fun selectCell(row: Int, col: Int) {
        if (_isCellFixed.value?.get(row)?.get(col) == true) {
            _selectedCell.value = null
        } else {
            _selectedCell.value = Pair(row, col)
        }
    }

    fun inputNumber(number: Int) {
        val (row, col) = _selectedCell.value ?: return
        val currentFixed = _isCellFixed.value ?: return
        val currentBoardCloned = _currentBoard.value?.map { it.clone() }?.toTypedArray() ?: return

        if (currentFixed[row][col]) {
            _gameStatusMessage.value = Event("Nie można zmienić tej komórki.")
            return
        }

        val boardForValidation = _currentBoard.value?.map { it.clone() }?.toTypedArray() ?: return
        // Usuń starą wartość z komórki przed walidacją, aby nie kolidowała sama ze sobą
        boardForValidation[row][col] = 0

        if (number != 0 && !SudokuLogic.isValidPlayerMove(boardForValidation, row, col, number)) {
            _gameStatusMessage.value = Event("Niepoprawny ruch! Cyfra $number już istnieje.")
            return
        }

        currentBoardCloned[row][col] = number
        _currentBoard.value = currentBoardCloned

        if (number != 0) {
            val solution = _solutionBoard.value ?: return
            if (SudokuLogic.isBoardSolved(currentBoardCloned, solution)) {
                _gameStatusMessage.value = Event("Gratulacje! Wygrałeś!")
                _isGameWon.value = Event(true)
                _selectedCell.value = null
            }
        }
    }

    fun clearSelectedCell() {
        inputNumber(0)
    }
}
