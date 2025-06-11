package com.example.sudokoonline

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.sudokoonline.multiplayer.model.GameStatus
import android.app.Application // Dodano import
import nl.dionsegijn.konfetti.KonfettiView
import nl.dionsegijn.konfetti.models.Shape
import nl.dionsegijn.konfetti.models.Size


class CompetitiveMultiplayerActivity : AppCompatActivity() {

    private lateinit var viewModel: MultiPlayerViewModel
    private lateinit var cells: Array<Array<EditText?>>
    private lateinit var numberButtonsLayout: LinearLayout
    private var previouslySelectedCellUi: EditText? = null

    // UI Components
    private lateinit var gameStatusText: TextView
    private lateinit var opponentInfoTextView: TextView
    private lateinit var myProgressBar: ProgressBar
    private lateinit var opponentProgressBar: ProgressBar
    private lateinit var myProgressTextView: TextView
    private lateinit var opponentProgressTextView: TextView
    private lateinit var konfettiView: KonfettiView // Dodana referencja do widoku konfetti

    private val thinLinePx = 1// Grubość cienkiej linii
    private val thickLinePx = 3 // Grubość grubej linii

    // Statyczna referencja do wspólnego ViewModelu
    companion object {
        private const val TAG = "CompetitiveMultiplayerActivity"
        // Zmieniam z private na internal, aby MultiPlayerActivity miała dostęp
        internal var sharedViewModel: MultiPlayerViewModel? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_competitive_multiplayer)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.multiplayer_game_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Pobieramy instancję ViewModelu współdzieloną z MultiPlayerActivity
        if (sharedViewModel == null) {
            Log.e(TAG, "ERROR: sharedViewModel is null! This should never happen.")
            Toast.makeText(this, "Błąd: Nie znaleziono danych gry. Spróbuj ponownie.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel = sharedViewModel!!

        Log.d(TAG, "ViewModel obtained, checking if it has board data")
        if (viewModel.sudokuBoard.value == null) {
            // Dodajemy więcej logów diagnostycznych
            Log.e(TAG, "ERROR: sudokuBoard is null in the shared ViewModel!")
            Log.e(TAG, "Is host: ${viewModel.isHost.value}, Game state: ${viewModel.gameState.value?.status}")
            Toast.makeText(this, "Błąd: Nie otrzymano danych planszy Sudoku", Toast.LENGTH_LONG).show()
            // Poczekaj krótko i spróbuj sprawdzić ponownie (może dane są w trakcie przesyłania)
            android.os.Handler().postDelayed({
                if (viewModel.sudokuBoard.value != null) {
                    Log.d(TAG, "Board received after delay. Re-initializing UI")
                    updateBoardUI(viewModel.sudokuBoard.value!!)
                    markFixedCells(viewModel.sudokuBoard.value!!)
                } else {
                    Log.e(TAG, "Board still null after delay. Finishing activity.")
                    finish()
                }
            }, 1000)
        } else {
            Log.d(TAG, "Board data present, first row: ${viewModel.sudokuBoard.value?.get(0)?.joinToString()}")
        }

        initializeUIComponents()
        setupBoardUI()
        setupNumberInputUI()
        observeViewModel()

        // Dodajemy logi diagnostyczne
        Log.d(TAG, "onCreate: ViewModel pozyskany, sudokuBoard null? ${viewModel.sudokuBoard.value == null}")

        // Inicjalizacja komunikacji multiplayer
        Log.d(TAG, "Initializing multiplayer communication")
        viewModel.initializeMultiplayerCommunication()

        // Dla klienta, wysyłamy informacje o graczu do hosta
        if (viewModel.isHost.value != true) {
            Log.d(TAG, "Client device detected - sending player info to host")
            viewModel.sendPlayerInfo()
        } else {
            Log.d(TAG, "Host device detected - waiting for client info")
        }
    }

    private fun initializeUIComponents() {
        gameStatusText = findViewById(R.id.gameStatusText)
        opponentInfoTextView = findViewById(R.id.opponentInfoTextView)
        myProgressBar = findViewById(R.id.myProgressBar)
        opponentProgressBar = findViewById(R.id.opponentProgressBar)
        myProgressTextView = findViewById(R.id.myProgressTextView)
        opponentProgressTextView = findViewById(R.id.opponentProgressTextView)
        konfettiView = findViewById(R.id.konfettiView) // Inicjalizacja widoku konfetti
    }

    private fun setupBoardUI() {
        cells = Array(9) { Array<EditText?>(9) { null } }
        val sudokuGrid = findViewById<GridLayout>(R.id.sudokuGridMultiplayer)

        for (r in 0..8) {
            for (c in 0..8) {
                try {
                    val cellIdName = "cell_mp_${r}_${c}"
                    val cellId = resources.getIdentifier(cellIdName, "id", packageName)
                    if (cellId == 0) {
                        Log.e(TAG, "Could not find cell ID: $cellIdName")
                        continue
                    }
                    val cell = sudokuGrid.findViewById<EditText>(cellId)
                    if (cell != null) {
                        cell.isFocusable = false
                        cell.isClickable = true
                        cell.isCursorVisible = false

                        val params = cell.layoutParams as GridLayout.LayoutParams

                        // Ustawienie marginesów do tworzenia linii
                        params.leftMargin = if (c == 0) 0 else (if (c % 3 == 0) thickLinePx else thinLinePx)
                        params.topMargin = if (r == 0) 0 else (if (r % 3 == 0) thickLinePx else thinLinePx)

                        cell.layoutParams = params

                        cell.setOnClickListener {
                            selectCell(r, c)
                        }
                        cells[r][c] = cell
                    } else {
                        Log.e(TAG, "Could not find cell EditText view for ID: $cellIdName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finding cell $r, $c: ${e.message}", e)
                }
            }
        }
    }

    private fun setupNumberInputUI() {
        numberButtonsLayout = findViewById(R.id.numberInputLayoutMultiplayer)

        val buttonIds = listOf(
            R.id.button_mp_1, R.id.button_mp_2, R.id.button_mp_3,
            R.id.button_mp_4, R.id.button_mp_5, R.id.button_mp_6,
            R.id.button_mp_7, R.id.button_mp_8, R.id.button_mp_9
        )

        buttonIds.forEachIndexed { index, buttonId ->
            findViewById<Button>(buttonId)?.setOnClickListener {
                inputNumber(index + 1)
            }
        }

        findViewById<Button>(R.id.button_mp_clear)?.setOnClickListener {
            clearSelectedCell()
        }
    }

    private fun selectCell(row: Int, col: Int) {
        // Debugowanie dla weryfikacji wywołania metody
        Log.d(TAG, "selectCell($row, $col) called")

        // Sprawdzamy, czy plansza jest dostępna
        val board = viewModel.sudokuBoard.value
        if (board == null) {
            Log.e(TAG, "Cannot select cell - board is null!")
            Toast.makeText(this, "Plansza nie jest jeszcze gotowa, poczekaj chwilę...", Toast.LENGTH_SHORT).show()
            return
        }

        // Sprawdzamy, czy komórka istnieje w zakresie planszy
        if (row < 0 || row >= 9 || col < 0 || col >= 9) {
            Log.e(TAG, "Cell coordinates out of range: ($row, $col)")
            return
        }

        // Sprawdzamy, czy komórka jest stała (nie można jej zmieniać)
        val isFixed = board[row][col] != 0
        Log.d(TAG, "Cell [$row][$col] value=${board[row][col]}, isFixed=$isFixed")

        // Resetujemy podświetlenie poprzedniej komórki
        previouslySelectedCellUi?.setBackgroundResource(R.drawable.cell_background_default)

        if (!isFixed) {
            // Sprawdzamy czy komórka UI istnieje
            val cellUi = cells[row][col]
            if (cellUi == null) {
                Log.e(TAG, "Cell UI at [$row][$col] is null!")
                return
            }

            // Podświetlamy nowo wybraną komórkę
            cellUi.setBackgroundResource(R.drawable.cell_background_selected)
            previouslySelectedCellUi = cellUi

            // Zapisujemy ostatnio wybraną pozycję, aby móc później wpisać tam liczbę
            viewModel.selectedCellPosition = Pair(row, col)
            Log.d(TAG, "Cell selected: position set to ($row,$col)")
        } else {
            // Jeśli komórka jest stała, nie pozwalamy jej wybrać
            viewModel.selectedCellPosition = null
            previouslySelectedCellUi = null
            Log.d(TAG, "Fixed cell - selection cleared")
            Toast.makeText(this, "Ta komórka jest stała i nie może być zmieniona", Toast.LENGTH_SHORT).show()
        }
    }

    private fun inputNumber(number: Int) {
        val position = viewModel.selectedCellPosition
        if (position == null) {
            Log.d(TAG, "inputNumber($number): No cell selected")
            return
        }

        val (row, col) = position
        Log.d(TAG, "inputNumber($number) at position ($row,$col)")

        // Aktualizujemy planszę w ViewModel
        viewModel.onCellValueUpdated(row, col, number)

        // Aktualizujemy UI
        cells[row][col]?.setText(number.toString())
        Log.d(TAG, "Number $number set in cell ($row,$col)")
    }

    private fun clearSelectedCell() {
        val position = viewModel.selectedCellPosition
        if (position == null) {
            Log.d(TAG, "clearSelectedCell(): No cell selected")
            return
        }

        val (row, col) = position
        Log.d(TAG, "clearSelectedCell() at position ($row,$col)")

        // Aktualizujemy planszę w ViewModel
        viewModel.onCellValueUpdated(row, col, 0)

        // Aktualizujemy UI
        cells[row][col]?.setText("")
        Log.d(TAG, "Cell ($row,$col) cleared")
    }

    private fun observeViewModel() {
        // Obserwuj zmiany planszy Sudoku
        viewModel.sudokuBoard.observe(this, Observer { board ->
            // Dodajemy rozszerzone logi diagnostyczne
            if (board == null) {
                Log.e(TAG, "observeViewModel: Received NULL sudokuBoard!")
                Toast.makeText(this, "Błąd: Otrzymano pustą planszę", Toast.LENGTH_SHORT).show()
                return@Observer
            }

            Log.d(TAG, "observeViewModel: Received sudokuBoard! Size: ${board.size}x${board[0].size}")

            // Logujemy pierwszy wiersz dla diagnostyki
            val firstRowValues = board[0].joinToString()
            Log.d(TAG, "SudokuBoard first row values: $firstRowValues")

            // Liczymy niepuste komórki dla weryfikacji
            val nonEmptyCells = board.sumOf { row -> row.count { it != 0 } }
            Log.d(TAG, "SudokuBoard has $nonEmptyCells non-empty cells")

            // Aktualizuj UI planszy
            updateBoardUI(board)

            // Znajdź i oznacz stałe komórki (wypełnione na początku)
            markFixedCells(board)

            Toast.makeText(this, "Plansza Sudoku załadowana!", Toast.LENGTH_SHORT).show()

            // Aktualizuj status gry na "Gra w toku!" gdy plansza zostanie załadowana
            gameStatusText.text = "Gra w toku!"
        })

        // Obserwuj postęp gracza
        viewModel.myProgress.observe(this, Observer { progress ->
            myProgressBar.progress = progress
            myProgressTextView.text = "$progress%"

            // Uruchom konfetti, gdy osiągniemy 100%
            if (progress == 100) {
                celebrateWithConfetti()
            }
        })

        // Obserwuj postęp przeciwnika
        viewModel.opponentProgress.observe(this, Observer { progress ->
            opponentProgressBar.progress = progress
            opponentProgressTextView.text = "$progress%"
        })

        // Obserwuj nazwę przeciwnika
        viewModel.opponentName.observe(this, Observer { name ->
            Log.d(TAG, "Otrzymano nazwę przeciwnika: $name")
            if (name.isNotEmpty() && name != "Przeciwnik") {
                opponentInfoTextView.text = "Przeciwnik: $name"
            }
        })

        // Obserwuj wynik gry (wygrana/przegrana)
        viewModel.gameResultEvent.observe(this, Observer { event ->
            event.getContentIfNotHandled()?.let { isWinner ->
                if (isWinner) {
                    gameStatusText.text = "Wygrałeś!"
                    Toast.makeText(this, "Wygrałeś! Gratulacje!", Toast.LENGTH_LONG).show()
                    celebrateWithConfetti() // Dodane wywołanie konfetti przy wygranej
                } else {
                    gameStatusText.text = "Przegrałeś!"
                    Toast.makeText(this, "Tym razem przeciwnik był szybszy. Spróbuj ponownie!", Toast.LENGTH_LONG).show()
                }
                // Zablokuj dalsze wprowadzanie liczb
                disableInput()
            }
        })

        // Obserwuj komunikaty o statusie gry
        viewModel.toastMessage.observe(this, Observer { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        })

        // Stan gry (status, rozpoczęcie, itp.)
        viewModel.gameState.observe(this, Observer { gameState ->
            Log.d(TAG, "Zmiana stanu gry na: ${gameState.status}")
            when (gameState.status) {
                GameStatus.WAITING_FOR_PLAYERS, GameStatus.STARTING, GameStatus.IN_PROGRESS -> {
                    // Zawsze pokazujemy tylko "Gra w toku!" niezależnie od faktycznego stanu
                    gameStatusText.text = "Gra w toku!"

                    // Sprawdź czy mamy nazwę przeciwnika
                    if (viewModel.opponentName.value != null && viewModel.opponentName.value != "Przeciwnik") {
                        opponentInfoTextView.text = "Przeciwnik: ${viewModel.opponentName.value}"
                    }
                }
                GameStatus.FINISHED -> {
                    val winner = gameState.players.find { it.deviceId == gameState.winnerDeviceId }
                    if (winner != null) {
                        gameStatusText.text = "Gra zakończona! Zwycięzca: ${winner.deviceName}"
                    } else {
                        gameStatusText.text = "Gra zakończona!"
                    }
                    disableInput()
                }
            }
        })
    }

    private fun updateBoardUI(board: Array<IntArray>) {
        for (r in 0..8) {
            for (c in 0..8) {
                val cellUi = cells[r][c]
                if (cellUi != null) {
                    val number = board[r][c]
                    cellUi.setText(if (number == 0) "" else number.toString())
                } else {
                    Log.w(TAG, "Cell UI at [$r][$c] is null during board update.")
                }
            }
        }
    }

    private fun markFixedCells(board: Array<IntArray>) {
        // Oznaczamy stałe komórki na podstawie początkowego stanu planszy
        // W tym przypadku, wszystkie niepuste komórki na początku są stałe
        for (r in 0..8) {
            for (c in 0..8) {
                val value = board[r][c]
                val cellUi = cells[r][c]

                if (value != 0) { // Stała komórka
                    cellUi?.setTextColor(Color.BLACK)
                    cellUi?.setTypeface(null, Typeface.BOLD)
                } else { // Edytowalna komórka
                    cellUi?.setTextColor(ContextCompat.getColor(this, R.color.myBlue))
                    cellUi?.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }

    private fun disableInput() {
        // Wyłącz przyciski z cyframi
        val buttonIds = listOf(
            R.id.button_mp_1, R.id.button_mp_2, R.id.button_mp_3,
            R.id.button_mp_4, R.id.button_mp_5, R.id.button_mp_6,
            R.id.button_mp_7, R.id.button_mp_8, R.id.button_mp_9,
            R.id.button_mp_clear
        )

        buttonIds.forEach { buttonId ->
            findViewById<Button>(buttonId)?.isEnabled = false
        }

        // Resetujemy zaznaczenie komórki
        previouslySelectedCellUi?.setBackgroundResource(R.drawable.cell_background_default)
        previouslySelectedCellUi = null
        viewModel.selectedCellPosition = null

        // Wyłącz klikanie w komórki
        for (r in 0..8) {
            for (c in 0..8) {
                cells[r][c]?.isClickable = false
            }
        }
    }

    /**
     * Metoda uruchamiająca animację konfetti jako świętowanie wygranej lub ukończenia planszy
     */
    private fun celebrateWithConfetti() {
        konfettiView.build()
            .addColors(Color.YELLOW, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.RED, Color.BLUE)
            .setDirection(0.0, 359.0) // wszystkie kierunki
            .setSpeed(1f, 5f)
            .setFadeOutEnabled(true)
            .setTimeToLive(2000L) // czas trwania w milisekundach
            .addShapes(Shape.Square, Shape.Circle)
            .addSizes(Size(12))
            .setPosition(konfettiView.width / 2f, konfettiView.height / 3f) // Pozycja startowa (środek-góra)
            .streamFor(300, 2000L) // ilość cząsteczek i czas trwania emisji
    }
}

// Extension function do konwersji dp na piksele - taka sama jak w SinglePlayer