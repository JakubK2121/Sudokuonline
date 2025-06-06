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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer

// Extension function to convert dp to pixels
val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

class SinglePlayer : AppCompatActivity() {

    private val viewModel: SinglePlayerViewModel by viewModels()
    private lateinit var cells: Array<Array<EditText?>>
    private lateinit var numberButtonsLayout: LinearLayout
    private var previouslySelectedCellUi: EditText? = null

    private val thinLinePx = 1.dp // Grubość cienkiej linii
    private val thickLinePx = 3.dp // Grubość grubej linii

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_single_player)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupBoardUI()
        setupNumberInputUI()
        observeViewModel()
    }

    private fun setupBoardUI() {
        cells = Array(9) { Array<EditText?>(9) { null } }
        val sudokuGrid = findViewById<GridLayout>(R.id.sudokuGrid)

        for (r in 0..8) {
            for (c in 0..8) {
                try {
                    val cellIdName = "cell_${r}_${c}"
                    val cellId = resources.getIdentifier(cellIdName, "id", packageName)
                    if (cellId == 0) {
                        Log.e("SinglePlayer", "Could not find cell ID: $cellIdName")
                        continue
                    }
                    val cell = sudokuGrid.findViewById<EditText>(cellId)
                    if (cell != null) {
                        cell.isFocusable = false
                        cell.isClickable = true
                        cell.isCursorVisible = false
                        // Domyślne tło komórki zostanie ustawione przez obserwatorów
                        // cell.setBackgroundResource(R.drawable.cell_background_default)

                        val params = cell.layoutParams as GridLayout.LayoutParams

                        // Ustawienie marginesów do tworzenia linii
                        // Linia po lewej stronie komórki
                        params.leftMargin = if (c == 0) 0 else (if (c % 3 == 0) thickLinePx else thinLinePx)
                        // Linia na górze komórki
                        params.topMargin = if (r == 0) 0 else (if (r % 3 == 0) thickLinePx else thinLinePx)

                        // Aby GridLayout pokazał linie po prawej i na dole siatki,
                        // GridLayout sam musi mieć paddingRight i paddingBottom.
                        // Alternatywnie, ostatnie komórki w rzędzie/kolumnie mogłyby mieć marginRight/Bottom,
                        // ale padding na GridLayout jest czystszy.

                        cell.layoutParams = params

                        cell.setOnClickListener {
                            viewModel.selectCell(r, c)
                        }
                        cells[r][c] = cell
                    } else {
                        Log.e("SinglePlayer", "Could not find cell EditText view for ID: $cellIdName (ID val: $cellId)")
                    }
                } catch (e: Exception) {
                    Log.e("SinglePlayer", "Error finding cell $r, $c: ${e.message}", e)
                }
            }
        }
    }

    private fun setupNumberInputUI() {
        numberButtonsLayout = findViewById(R.id.numberInputLayout)

        val buttonIds = listOf(
            R.id.button_1, R.id.button_2, R.id.button_3,
            R.id.button_4, R.id.button_5, R.id.button_6,
            R.id.button_7, R.id.button_8, R.id.button_9
        )

        buttonIds.forEachIndexed { index, buttonId ->
            findViewById<Button>(buttonId)?.setOnClickListener {
                viewModel.inputNumber(index + 1)
            }
        }

        findViewById<Button>(R.id.button_clear)?.setOnClickListener {
            viewModel.clearSelectedCell()
        }
    }


    private fun observeViewModel() {
        viewModel.currentBoard.observe(this, Observer { board ->
            board?.let {
                for (r in 0..8) {
                    for (c in 0..8) {
                        val cellUi = cells[r][c]
                        if (cellUi != null) {
                            val number = it[r][c]
                            cellUi.setText(if (number == 0) "" else number.toString())
                        } else {
                            Log.w("SinglePlayer", "Cell UI at [$r][$c] is null during currentBoard observation.")
                        }
                    }
                }
            }
        })

        viewModel.isCellFixed.observe(this, Observer { fixedMap ->
            fixedMap?.let {
                for (r in 0..8) {
                    for (c in 0..8) {
                        val cellUi = cells[r][c]
                        if (cellUi != null) {
                            if (it[r][c]) { // Komórka stała
                                cellUi.setTextColor(Color.BLACK)
                                cellUi.setTypeface(null, Typeface.BOLD)
                                cellUi.setBackgroundResource(R.drawable.cell_background_default) // Domyślne tło dla stałych
                            } else { // Komórka edytowalna
                                cellUi.setTextColor(ContextCompat.getColor(this, R.color.myBlue))
                                cellUi.setTypeface(null, Typeface.NORMAL)
                                // Tło dla edytowalnych komórek (niezaznaczonych)
                                cellUi.setBackgroundResource(R.drawable.cell_background_default)
                            }
                        } else {
                            Log.w("SinglePlayer", "Cell UI at [$r][$c] is null during isCellFixed observation.")
                        }
                    }
                }
                // Po ustawieniu stałych komórek, odśwież tło dla aktualnie wybranej (jeśli jest i nie jest stała)
                viewModel.selectedCell.value?.let { (row, col) ->
                    if (fixedMap[row][col] == false) {
                        cells[row][col]?.setBackgroundResource(R.drawable.cell_background_selected)
                        previouslySelectedCellUi = cells[row][col]
                    } else {
                        previouslySelectedCellUi = null // Nie powinna być zaznaczona, jeśli stała
                    }
                }
            }
        })

        viewModel.selectedCell.observe(this, Observer { selectedPair ->
            // Przywróć tło poprzednio zaznaczonej komórki, jeśli była edytowalna
            previouslySelectedCellUi?.let { prevCell ->
                val (prevRow, prevCol) = findCellPosition(prevCell)
                if(prevRow != -1 && viewModel.isCellFixed.value?.get(prevRow)?.get(prevCol) == false){
                    prevCell.setBackgroundResource(R.drawable.cell_background_default)
                }
            }

            selectedPair?.let { (row, col) ->
                val currentCellUi = cells[row][col]
                // Zaznaczaj tylko edytowalne komórki
                if (viewModel.isCellFixed.value?.get(row)?.get(col) == false) {
                    currentCellUi?.setBackgroundResource(R.drawable.cell_background_selected)
                    previouslySelectedCellUi = currentCellUi
                } else {
                    // Jeśli kliknięto na stałą komórkę, selectedCell w ViewModelu jest null,
                    // więc ten blok nie powinien być wywołany dla stałych.
                    // Jeśli jednak jakoś by był, upewnij się, że nie zmieniamy tła stałej na "selected".
                    previouslySelectedCellUi = null
                }
            } ?: run { // Nic nie jest zaznaczone
                previouslySelectedCellUi = null
            }
        })

        viewModel.gameStatusMessage.observe(this, Observer { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        })

        viewModel.isGameWon.observe(this, Observer { event ->
            event.getContentIfNotHandled()?.let { isWon ->
                if (isWon) {
                    Toast.makeText(this, "Wygrałeś! Gratulacje!", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun findCellPosition(cell: EditText): Pair<Int, Int> {
        for (r in 0..8) {
            for (c in 0..8) {
                if (cells[r][c] == cell) {
                    return Pair(r, c)
                }
            }
        }
        return Pair(-1, -1)
    }
}
