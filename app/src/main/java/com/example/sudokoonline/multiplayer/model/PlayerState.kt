package com.example.sudokoonline.multiplayer.model

/**
 * Klasa reprezentująca stan gracza w grze wieloosobowej.
 *
 * @property deviceId Unikalny identyfikator urządzenia gracza
 * @property deviceName Nazwa urządzenia/gracza
 * @property completionPercentage Procent ukończenia planszy sudoku (0-100)
 * @property isReady Czy gracz jest gotowy do rozpoczęcia gry
 * @property lastUpdateTime Czas ostatniej aktualizacji stanu gracza
 * @property totalCellsFilled Liczba wypełnionych komórek
 * @property totalCells Całkowita liczba komórek do wypełnienia
 */
data class PlayerState(
    val deviceId: String,
    val deviceName: String,
    var completionPercentage: Int = 0,
    var isReady: Boolean = false,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    var totalCellsFilled: Int = 0,
    var totalCells: Int = 81 // Domyślnie pełna plansza Sudoku
) {
    /**
     * Aktualizuje postęp gracza na podstawie wypełnionych komórek
     *
     * @param filledCells Liczba wypełnionych komórek
     * @param allCells Całkowita liczba komórek do wypełnienia (opcjonalnie)
     */
    fun updateProgress(filledCells: Int, allCells: Int = totalCells) {
        totalCellsFilled = filledCells
        totalCells = allCells
        completionPercentage = if (allCells > 0) {
            (filledCells * 100) / allCells
        } else {
            0
        }
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * Oblicza i aktualizuje procent ukończenia na podstawie planszy sudoku
     *
     * @param board Aktualna plansza sudoku
     * @param initialFixedCells Liczba początkowo wypełnionych komórek (stałych)
     */
    fun updateProgressFromBoard(board: Array<IntArray>, initialFixedCells: Int) {
        val totalToFill = 81 - initialFixedCells
        var filled = 0

        // Liczymy wypełnione komórki (ale tylko te, które nie były początkowo wypełnione)
        for (row in board) {
            for (value in row) {
                if (value != 0) {
                    filled++
                }
            }
        }

        filled -= initialFixedCells // Odejmujemy początkowe (stałe) komórki
        if (filled < 0) filled = 0 // Bezpieczeństwo

        updateProgress(filled, totalToFill)
    }

    /**
     * Oznacza gracza jako gotowego do gry
     */
    fun markAsReady() {
        isReady = true
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * Resetuje postęp gracza do wartości początkowych
     */
    fun resetProgress() {
        completionPercentage = 0
        totalCellsFilled = 0
        lastUpdateTime = System.currentTimeMillis()
    }
}
