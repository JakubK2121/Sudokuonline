package com.example.sudokoonline

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.sudokoonline.util.Event // Upewnij się, że ten plik istnieje w com.example.sudokoonline.util
import kotlin.random.Random

object SudokuLogic {

    // Sprawdza, czy bezpiecznie jest umieścić 'num' w danej komórce siatki (dla algorytmu generującego/rozwiązującego)
    private fun isSafeToPlace(grid: Array<IntArray>, row: Int, col: Int, num: Int): Boolean {
        // Sprawdź wiersz
        for (c in 0..8) {
            if (grid[row][c] == num) return false
        }
        // Sprawdź kolumnę
        for (r in 0..8) {
            if (grid[r][col] == num) return false
        }
        // Sprawdź blok 3x3
        val boxRowStart = row - row % 3
        val boxColStart = col - col % 3
        for (r in boxRowStart until boxRowStart + 3) {
            for (c in boxColStart until boxColStart + 3) {
                if (grid[r][c] == num) return false
            }
        }
        return true // Bezpiecznie umieścić cyfrę
    }

    // Funkcja pomocnicza do znalezienia następnej pustej komórki (0)
    private fun findUnassignedLocation(grid: Array<IntArray>): Pair<Int, Int>? {
        for (row in 0..8) {
            for (col in 0..8) {
                if (grid[row][col] == 0) {
                    return Pair(row, col)
                }
            }
        }
        return null // Wszystkie komórki są wypełnione
    }

    // Główna funkcja rekurencyjna do rozwiązania Sudoku (wypełnienia siatki)
    private fun solveSudoku(grid: Array<IntArray>): Boolean {
        val unassigned = findUnassignedLocation(grid)
        if (unassigned == null) {
            return true // Plansza rozwiązana
        }

        val row = unassigned.first
        val col = unassigned.second

        // Próbuj umieścić cyfry od 1 do 9 (w losowej kolejności dla generowania różnych plansz)
        for (num in (1..9).shuffled(Random)) { // Losowa kolejność dla różnorodności
            if (isSafeToPlace(grid, row, col, num)) {
                grid[row][col] = num // Spróbuj umieścić cyfrę

                if (solveSudoku(grid)) { // Rekurencyjnie próbuj rozwiązać resztę
                    return true
                }

                grid[row][col] = 0 // Backtrack: usuń cyfrę, jeśli nie prowadzi do rozwiązania
            }
        }
        return false // Żadna cyfra nie pasuje, cofnij się w rekurencji
    }

    fun generateNewGame(difficultyCellsToRemove: Int = 40): Pair<Array<IntArray>, Array<IntArray>> {
        val solutionGrid = Array(9) { IntArray(9) { 0 } }

        if (!solveSudoku(solutionGrid)) {
            Log.e("SudokuLogic", "Nie udało się wygenerować pełnego rozwiązania Sudoku.")
            throw IllegalStateException("Nie można wygenerować planszy Sudoku.")
        }

        val finalSolutionBoard = solutionGrid.map { it.clone() }.toTypedArray()
        val puzzleBoard = solutionGrid.map { it.clone() }.toTypedArray()

        var cellsRemoved = 0
        var attempts = 0
        val maxAttempts = 81 * 3

        while (cellsRemoved < difficultyCellsToRemove && attempts < maxAttempts) {
            val row = Random.nextInt(9)
            val col = Random.nextInt(9)

            if (puzzleBoard[row][col] != 0) {
                puzzleBoard[row][col] = 0
                cellsRemoved++
            }
            attempts++
        }
        if (cellsRemoved < difficultyCellsToRemove) {
            Log.w("SudokuLogic", "Nie udało się usunąć żądanej liczby komórek. Usunięto: $cellsRemoved")
        }

        return Pair(puzzleBoard, finalSolutionBoard)
    }

    fun isValidPlayerMove(currentBoard: Array<IntArray>, row: Int, col: Int, number: Int): Boolean {
        if (number == 0) return true

        for (c in 0..8) {
            if (c != col && currentBoard[row][c] == number) {
                Log.d("SudokuLogic", "isValidPlayerMove: Konflikt w wierszu $row dla liczby $number (komórka [$row,$c])")
                return false
            }
        }

        for (r in 0..8) {
            if (r != row && currentBoard[r][col] == number) {
                Log.d("SudokuLogic", "isValidPlayerMove: Konflikt w kolumnie $col dla liczby $number (komórka [$r,$col])")
                return false
            }
        }

        val boxRowStart = row - row % 3
        val boxColStart = col - col % 3
        for (r in boxRowStart until boxRowStart + 3) {
            for (c in boxColStart until boxColStart + 3) {
                if ((r != row || c != col) && currentBoard[r][c] == number) {
                    Log.d("SudokuLogic", "isValidPlayerMove: Konflikt w bloku 3x3 dla liczby $number (komórka [$r,$c])")
                    return false
                }
            }
        }
        return true
    }

    fun isBoardSolved(currentBoard: Array<IntArray>, solution: Array<IntArray>): Boolean {
        for (i in 0..8) {
            for (j in 0..8) {
                if (currentBoard[i][j] == 0 || currentBoard[i][j] != solution[i][j]) {
                    return false
                }
            }
        }
        return true
    }
}

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
