package com.example.sudokoonline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class WyborGry : AppCompatActivity() {

    private val viewModel: WyborGryViewModel by viewModels()
    private lateinit var wifiManager: WifiManager

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_wybor_gry)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Log.d("WyborGry", "Permission result received: $permissions")
            viewModel.onPermissionsResult(permissions)
        }


        val singleGraButton = findViewById<Button>(R.id.SinglePlayer)
        singleGraButton.setOnClickListener {
            viewModel.onSinglePlayerClicked()
        }

        val multiGraButton = findViewById<Button>(R.id.MultiPlayer)
        multiGraButton.setOnClickListener {
            handleMultiplayerClick()
        }

        observeViewModel()
    }

    private fun handleMultiplayerClick() {
        val isWifiEnabled = wifiManager.isWifiEnabled
        Log.d("WyborGry", "MultiPlayer clicked. Wi-Fi enabled: $isWifiEnabled")

        val requiredPermissions = getRequiredPermissions()
        Log.d("WyborGry", "Required permissions: ${requiredPermissions.joinToString()}")

        val arePermissionsGranted = checkPermissions(requiredPermissions)
        Log.d("WyborGry", "Are permissions already granted: $arePermissionsGranted")

        viewModel.onMultiPlayerClicked(isWifiEnabled, requiredPermissions, arePermissionsGranted)
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun checkPermissions(permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }


    private fun observeViewModel() {
        viewModel.navigateToSinglePlayer.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d("WyborGry", "Navigating to SinglePlayer")
                startActivity(Intent(this, SinglePlayer::class.java))
            }
        }

        viewModel.navigateToMultiPlayer.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d("WyborGry", "Navigating to MultiPlayer")
                startActivity(Intent(this, MultiPlayerActivity::class.java))
            }
        }

        viewModel.promptEnableWifi.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d("WyborGry", "Received prompt to enable Wi-Fi.")
                promptUserToEnableWifi()
            }
        }

        // --- OBSERWACJA PROŚBY O UPRAWNIENIA Z DODANYM TOASTEM DIAGNOSTYCZNYM ---
        viewModel.requestPermissionsEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { permissionsToRequest ->
                Log.d("WyborGry", "Received request to ask for permissions: ${permissionsToRequest.joinToString()}")

                // <<< === ZMIANA ZNAJDUJE SIĘ TUTAJ === >>>
                // Ten Toast pokaże, czy kod w ogóle próbuje uruchomić prośbę o uprawnienia.
                Toast.makeText(this, "TEST: Próba uruchomienia prośby o uprawnienia...", Toast.LENGTH_LONG).show()

                // Uruchom systemowy dialog proszący o uprawnienia
                requestPermissionLauncher.launch(permissionsToRequest)
            }
        }

        viewModel.showPermissionDeniedMessage.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d("WyborGry", "Received message to show permission denied.")
                Toast.makeText(this, "Uprawnienia są wymagane do gry Multiplayer.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptUserToEnableWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d("WyborGry", "Using Settings Panel to enable Wi-Fi (Android Q+)")
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            startActivity(panelIntent)
            Toast.makeText(this, "Proszę włączyć Wi-Fi i spróbować ponownie", Toast.LENGTH_LONG).show()
        } else {
            Log.d("WyborGry", "Redirecting to Wi-Fi Settings (pre-Android Q)")
            @Suppress("deprecation")
            val settingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            if (settingsIntent.resolveActivity(packageManager) != null) {
                startActivity(settingsIntent)
                Toast.makeText(this, "Proszę włączyć Wi-Fi w ustawieniach i spróbować ponownie", Toast.LENGTH_LONG).show()
            } else {
                Log.e("WyborGry", "Could not resolve Wi-Fi settings intent!")
                Toast.makeText(this, "Nie można otworzyć ustawień Wi-Fi", Toast.LENGTH_SHORT).show()
            }
        }
    }
}