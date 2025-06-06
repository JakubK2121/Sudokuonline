package com.example.sudokoonline

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.viewModels // <-- Dodaj ten import

// Jeśli Event jest w tym samym pliku co ViewModel, import nie jest potrzebny
// Jeśli jest w osobnym pliku, np. w pakiecie 'util':
// import com.example.sudokoonline.util.Event

class MainActivity : AppCompatActivity() {

    // Pobierz instancję ViewModelu używając delegata KTX
    // ViewModel zostanie automatycznie powiązany z cyklem życia tej Activity
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val nowaGraButton = findViewById<Button>(R.id.NowaGra)

        nowaGraButton.setOnClickListener {
            viewModel.onNewGameButtonClicked()
        }
        observeNavigation()
    }

    private fun observeNavigation() {
        viewModel.navigateToWyborGry.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                val intent = Intent(this, WyborGry::class.java)
                startActivity(intent)
            }
        }
    }
}