// MultiPlayerActivity.kt
package com.example.sudokoonline

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.sudokoonline.util.Event


// Funkcja rozszerzająca przeniesiona na najwyższy poziom pliku dla lepszej widoczności
fun WifiP2pDevice.statusToString(): String {
    return when (status) {
        WifiP2pDevice.AVAILABLE -> "Dostępny"
        WifiP2pDevice.INVITED -> "Zaproszony"
        WifiP2pDevice.CONNECTED -> "Połączony"
        WifiP2pDevice.FAILED -> "Błąd"
        WifiP2pDevice.UNAVAILABLE -> "Niedostępny"
        else -> "Nieznany"
    }
}

class MultiPlayerActivity : AppCompatActivity(), WifiP2pManager.ChannelListener,
    WifiP2pManager.ConnectionInfoListener, WifiP2pManager.PeerListListener {

    // Używamy tej samej referencji do ViewModelu co CompetitiveMultiplayerActivity
    private val viewModel: MultiPlayerViewModel by lazy {
        // Najpierw sprawdzamy, czy CompetitiveMultiplayerActivity już utworzyło ViewModel
        if (CompetitiveMultiplayerActivity.sharedViewModel == null) {
            Log.d(TAG, "Creating new shared MultiPlayerViewModel")
            CompetitiveMultiplayerActivity.sharedViewModel = ViewModelProvider(this)[MultiPlayerViewModel::class.java]
        } else {
            Log.d(TAG, "Reusing existing shared MultiPlayerViewModel")
        }
        CompetitiveMultiplayerActivity.sharedViewModel!!
    }

    private lateinit var statusText: TextView
    private lateinit var hostButton: Button
    private lateinit var discoverButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var disconnectButton: Button
    private lateinit var sendMsgButton: Button
    private lateinit var receivedMsgText: TextView
    private lateinit var startGameButton: Button  // Nowy przycisk "Grajmy!"


    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private var discoveredDevices: List<WifiP2pDevice> = emptyList()

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private lateinit var intentFilter: IntentFilter

    // private val activityScope = CoroutineScope(Dispatchers.Main + Job()) // Jeśli nie używasz, można usunąć


    // === Uprawnienia ===
    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                when {
                    permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                        Log.d(TAG, "ACCESS_FINE_LOCATION permission granted for Android < 12")
                        viewModel.lastPermissionAction?.let { action ->
                            when(action) {
                                PermissionAction.DISCOVER_PEERS -> actualDiscoverPeers()
                                PermissionAction.CREATE_GROUP -> actualCreateGroup()
                            }
                            viewModel.clearLastPermissionAction()
                        }
                    }
                    else -> {
                        Log.e(TAG, "ACCESS_FINE_LOCATION permission denied for Android < 12")
                        Toast.makeText(this, "Uprawnienie do lokalizacji jest wymagane do Wi-Fi P2P.", Toast.LENGTH_LONG).show()
                        viewModel.clearLastPermissionAction()
                    }
                }
            }
        }

    private val nearbyDevicesPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val actionInProgress = viewModel.lastPermissionAction
                if (actionInProgress == null) {
                    Log.w(TAG, "Permission result received for S+ but no action was pending.")
                    return@registerForActivityResult
                }

                val nearbyDevicesGranted = permissions.getOrDefault(Manifest.permission.NEARBY_WIFI_DEVICES, false)
                // Dla DISCOVER_PEERS, ACCESS_FINE_LOCATION jest również wymagane na S+
                val fineLocationNeededForDiscover = actionInProgress == PermissionAction.DISCOVER_PEERS
                val fineLocationGranted = if (fineLocationNeededForDiscover) {
                    permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
                } else {
                    true // Nie jest potrzebne dla innych akcji jak CREATE_GROUP na S+, zakładając, że tylko NEARBY_WIFI_DEVICES jest potrzebne
                }

                var allRequiredPermissionsGranted = false
                when (actionInProgress) {
                    PermissionAction.DISCOVER_PEERS -> {
                        if (nearbyDevicesGranted && fineLocationGranted) {
                            Log.d(TAG, "NEARBY_WIFI_DEVICES and ACCESS_FINE_LOCATION granted for DISCOVER_PEERS on S+")
                            actualDiscoverPeers()
                            allRequiredPermissionsGranted = true
                        } else {
                            Log.e(TAG, "DISCOVER_PEERS on S+: NEARBY_WIFI_DEVICES granted: $nearbyDevicesGranted, ACCESS_FINE_LOCATION granted: $fineLocationGranted")
                        }
                    }
                    PermissionAction.CREATE_GROUP -> {
                        // Zakładamy, że CREATE_GROUP na S+ potrzebuje tylko NEARBY_WIFI_DEVICES
                        // Jeśli potrzebuje również ACCESS_FINE_LOCATION, logika powinna to odzwierciedlać.
                        if (nearbyDevicesGranted) {
                            Log.d(TAG, "NEARBY_WIFI_DEVICES granted for CREATE_GROUP on S+")
                            actualCreateGroup()
                            allRequiredPermissionsGranted = true
                        } else {
                            Log.e(TAG, "CREATE_GROUP on S+: NEARBY_WIFI_DEVICES granted: $nearbyDevicesGranted")
                        }
                    }
                }

                if (allRequiredPermissionsGranted) {
                    viewModel.clearLastPermissionAction()
                } else {
                    Log.e(TAG, "Not all required permissions for action $actionInProgress were granted on Android 12+.")
                    var message = "Uprawnienie do urządzeń w pobliżu jest wymagane."
                    if (actionInProgress == PermissionAction.DISCOVER_PEERS && !fineLocationGranted && nearbyDevicesGranted) {
                        message = "Uprawnienie do lokalizacji jest wymagane do odkrywania urządzeń."
                    } else if (actionInProgress == PermissionAction.DISCOVER_PEERS && !fineLocationGranted && !nearbyDevicesGranted) {
                        message = "Uprawnienia do urządzeń w pobliżu i lokalizacji są wymagane do odkrywania urządzeń."
                    } else if (!nearbyDevicesGranted) {
                        // Pozostaje domyślna wiadomość lub bardziej ogólna
                        message = "Niezbędne uprawnienia nie zostały przyznane."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    viewModel.clearLastPermissionAction()
                }
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_multi_player)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.multiplayer_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        Log.d(TAG, "onCreate")

        statusText = findViewById(R.id.status_text)
        hostButton = findViewById(R.id.host_button)
        discoverButton = findViewById(R.id.discover_button)
        devicesListView = findViewById(R.id.devices_list_view)
        disconnectButton = findViewById(R.id.disconnect_button)
        sendMsgButton = findViewById(R.id.send_msg_button)
        receivedMsgText = findViewById(R.id.received_msg_text)
        startGameButton = findViewById(R.id.start_game_button) // Inicjalizacja przycisku "Grajmy!"


        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        devicesListView.adapter = deviceListAdapter

        hostButton.setOnClickListener {
            Log.d(TAG, "Host button clicked")
            viewModel.onHostGameClicked()
        }
        discoverButton.setOnClickListener {
            Log.d(TAG, "Discover button clicked")
            viewModel.onDiscoverLobbiesClicked()
        }
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            if (discoveredDevices.isNotEmpty() && position < discoveredDevices.size) {
                val selectedDevice = discoveredDevices[position]
                Log.d(TAG, "Device selected: ${selectedDevice.deviceName}")
                viewModel.onDeviceSelected(selectedDevice)
            } else {
                Log.w(TAG, "Selected device is out of bounds or list is empty.")
            }
        }
        disconnectButton.setOnClickListener {
            Log.d(TAG, "Disconnect button clicked")
            viewModel.onDisconnectClicked()
        }


        // Dodajemy obsługę kliknięcia przycisku "Grajmy!"
        startGameButton.setOnClickListener {
            Log.d(TAG, "Start game button clicked - sending player ready signal")
            viewModel.sendPlayerReadySignal()
        }


        initializeWifiDirect()
        createIntentFilter()
        observeViewModel()
    }


    /**
     * Sprawdza, czy WiFi jest włączone w urządzeniu
     */
    private fun isWifiEnabled(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val isWifiEnabled = wifiManager.isWifiEnabled

        if (!isWifiEnabled) {
            Log.w(TAG, "WiFi is disabled on the device")
            Toast.makeText(this, "WiFi jest wyłączone. Proszę włączyć WiFi.", Toast.LENGTH_LONG).show()

            // Opcjonalnie można otworzyć ustawienia WiFi
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        return isWifiEnabled
    }

    private fun initializeWifiDirect() {
        Log.d(TAG, "Initializing Wi-Fi Direct")

        // Sprawdź, czy WiFi jest włączone
        if (!isWifiEnabled()) {
            viewModel.updateStatus("WiFi jest wyłączone. Proszę włączyć WiFi.")
            return
        }

        val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "Cannot get WifiP2pManager service.")
            Toast.makeText(this, "Urządzenie nie wspiera Wi-Fi Direct", Toast.LENGTH_LONG).show()
            viewModel.onP2pStateChanged(false, "Urządzenie nie wspiera Wi-Fi Direct")
            return
        }
        manager = wifiP2pManager

        val tempChannel = manager.initialize(this, Looper.getMainLooper(), this)
        if (tempChannel == null) {
            Log.e(TAG, "Failed to initialize WifiP2pManager channel.")
            Toast.makeText(this, "Błąd inicjalizacji Wi-Fi Direct", Toast.LENGTH_LONG).show()
            viewModel.onP2pStateChanged(false, "Błąd inicjalizacji kanału Wi-Fi Direct")
            return
        }
        channel = tempChannel
        receiver = WiFiDirectBroadcastReceiver(manager, channel, viewModel, this)
        Log.d(TAG, "Wi-Fi Direct Initialized successfully.")
    }

    private fun createIntentFilter() {
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun checkAndRequestPermissions(action: PermissionAction): Boolean {
        viewModel.setLastPermissionAction(action) // Ustaw akcję PRZED sprawdzeniem uprawnień

        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            // Dla odkrywania peerów na S+ ACCESS_FINE_LOCATION jest nadal potrzebne, jeśli usługi lokalizacyjne są wymagane
            if (action == PermissionAction.DISCOVER_PEERS) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions for Android 12+: $permissionsToRequest")
                nearbyDevicesPermissionRequest.launch(permissionsToRequest.toTypedArray())
                return false
            }
            // ...
            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions for Android 12+: $permissionsToRequest") // Istniejący log
                Log.d(TAG, "PERMISSION_LOG: Attempting to launch nearbyDevicesPermissionRequest.") // DODAJ TEN LOG
                nearbyDevicesPermissionRequest.launch(permissionsToRequest.toTypedArray())
                return false
            }
// ...
            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions for Android < 12: $permissionsToRequest") // Istniejący log
                Log.d(TAG, "PERMISSION_LOG: Attempting to launch locationPermissionRequest.") // DODAJ TEN LOG
                locationPermissionRequest.launch(permissionsToRequest.toTypedArray())
                return false
            }
// ...
        } else { // Android < 12
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions for Android < 12: $permissionsToRequest")
                locationPermissionRequest.launch(permissionsToRequest.toTypedArray())
                return false
            }
        }

        Log.d(TAG, "All required permissions already granted for action: $action")
        viewModel.clearLastPermissionAction() // Wyczyść, jeśli uprawnienia są już przyznane
        return true
    }

    @SuppressLint("MissingPermission")
    private fun actualDiscoverPeers() {
        Log.d(TAG, "actualDiscoverPeers called")
        if (!::manager.isInitialized || !::channel.isInitialized) {
            Log.e(TAG, "Manager or Channel not initialized for discoverPeers.")
            viewModel.updateStatus("Błąd: P2P nie zainicjalizowane.")
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess")
                viewModel.onDiscoverPeersSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "discoverPeers onFailure. Reason: $reasonCode")
                viewModel.onDiscoverPeersFailure(reasonCode)
            }
        })
    }


    @SuppressLint("MissingPermission")
    private fun actualCreateGroup() {
        Log.d(TAG, "actualCreateGroup called")
        if (!::manager.isInitialized || !::channel.isInitialized) {
            Log.e(TAG, "Manager or Channel not initialized for createGroup.")
            viewModel.updateStatus("Błąd: P2P nie zainicjalizowane.")
            return
        }
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "manager.createGroup ActionListener onSuccess")
                viewModel.onCreateGroupSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "manager.createGroup ActionListener onFailure. Reason: $reasonCode")
                viewModel.onCreateGroupFailure(reasonCode)
            }
        })
    }


    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: WifiP2pDevice) {
        if (!::manager.isInitialized || !::channel.isInitialized) {
            Log.e(TAG, "Manager or Channel not initialized for connectToDevice.")
            viewModel.updateStatus("Błąd: P2P nie zainicjalizowane.")
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect onSuccess to ${device.deviceName}")
                viewModel.onConnectSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "connect onFailure to ${device.deviceName}. Reason: $reasonCode")
                viewModel.onConnectFailure(reasonCode)
            }
        })
    }

    private fun disconnect() {
        Log.d(TAG, "disconnect called")
        if (!::manager.isInitialized || !::channel.isInitialized) {
            Log.e(TAG, "Manager or Channel not initialized for disconnect.")
            return
        }
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup onSuccess")
                viewModel.onDisconnectSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "removeGroup onFailure. Reason: $reasonCode")
                viewModel.onDisconnectFailure(reasonCode)
            }
        })
        viewModel.stopSocketCommunication()
    }


    private fun observeViewModel() {
        Log.d(TAG, "Setting up observers")
        viewModel.statusText.observe(this) { status ->
            statusText.text = "Status: $status"
            Log.i(TAG, "Status updated: $status")
        }
        viewModel.discoveredDevicesLiveData.observe(this) { devices ->
            Log.d(TAG, "Observed discoveredDevicesLiveData change. Count: ${devices.size}")
            discoveredDevices = devices
            deviceListAdapter.clear()
            if (devices.isEmpty()) {
                deviceListAdapter.add("Nie znaleziono urządzeń")
            } else {
                deviceListAdapter.addAll(devices.map { "${it.deviceName} (${it.statusToString()})" })
            }
            deviceListAdapter.notifyDataSetChanged()
        }
        viewModel.toastMessage.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        viewModel.isHostButtonEnabled.observe(this) { isEnabled -> hostButton.isEnabled = isEnabled }
        viewModel.isDiscoverButtonEnabled.observe(this) { isEnabled -> discoverButton.isEnabled = isEnabled }
        viewModel.isDisconnectButtonVisible.observe(this) {isVisible ->
            disconnectButton.visibility = if(isVisible) Button.VISIBLE else Button.GONE
        }
        viewModel.isCommunicationLayoutVisible.observe(this) { isVisible ->
            sendMsgButton.visibility = if (isVisible) Button.VISIBLE else Button.GONE
            receivedMsgText.visibility = if (isVisible) TextView.VISIBLE else TextView.GONE

            // Gdy komunikacja jest nawiązana, pokaż przycisk "Grajmy!"
            if (isVisible) {
                startGameButton.visibility = Button.VISIBLE
            }
        }

        // Obserwuj stan przycisku "Grajmy!"
        viewModel.isStartGameButtonVisible.observe(this) { isVisible ->
            startGameButton.visibility = if (isVisible) Button.VISIBLE else Button.GONE
        }

        // Obserwuj stan gotowości lokalnego gracza
        viewModel.isLocalPlayerReady.observe(this) { isReady ->
            if (isReady) {
                startGameButton.isEnabled = false
                startGameButton.text = "Czekam na przeciwnika..."
            }
        }

        // Obserwuj stan gotowości przeciwnika
        viewModel.isOpponentReady.observe(this) { isReady ->
            if (isReady && !viewModel.isLocalPlayerReady.value!!) {
                startGameButton.text = "Przeciwnik jest gotowy! Kliknij aby dołączyć!"
            }
        }

        // Obserwuj stan gotowości obu graczy
        viewModel.areBothPlayersReady.observe(this) { bothReady ->
            if (bothReady) {
                startGameButton.text = "Rozpoczynanie gry..."
                startGameButton.isEnabled = false
            }
        }


        // Dodajemy obserwator dla eventu nawigacji do ekranu gry
        viewModel.navigateToGameEvent.observe(this) { event ->
            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Obserwator navigateToGameEvent został wywołany, hasBeenHandled=${event.hasBeenHandled}")
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Przechodzę do CompetitiveMultiplayerActivity!")

                // Upewnijmy się, że sharedViewModel jest poprawnie ustawiony przed nawigacją
                if (CompetitiveMultiplayerActivity.sharedViewModel == null) {
                    Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Ustawiam sharedViewModel przed nawigacją")
                    CompetitiveMultiplayerActivity.sharedViewModel = viewModel
                }

                // Sprawdzenie, czy plansza sudoku jest dostępna
                if (viewModel.sudokuBoard.value == null) {
                    Log.e(TAG, "### DIAGNOSTYKA KLIENTA ### BŁĄD: Próba nawigacji z brakiem planszy sudoku!")
                    Toast.makeText(this, "Błąd: Brak danych planszy Sudoku", Toast.LENGTH_LONG).show()
                    return@let
                }

                Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Plansza dostępna, pierwszy wiersz: ${viewModel.sudokuBoard.value?.get(0)?.joinToString()}")

                // Rozpoczynanie aktywności
                try {
                    val intent = Intent(this, CompetitiveMultiplayerActivity::class.java)
                    Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Wywołuję startActivity()")
                    startActivity(intent)
                    Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Aktywność uruchomiona")
                } catch (e: Exception) {
                    Log.e(TAG, "### DIAGNOSTYKA KLIENTA ### Błąd podczas uruchamiania aktywności: ${e.message}", e)
                    Toast.makeText(this, "Błąd uruchomienia gry: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } ?: run {
                Log.w(TAG, "### DIAGNOSTYKA KLIENTA ### Event nawigacji już obsłużony lub null")
            }
        }

        viewModel.createGroupEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "Received createGroupEvent. Checking permissions...")
                if (checkAndRequestPermissions(PermissionAction.CREATE_GROUP)) {
                    actualCreateGroup()
                }
            }
        }

        viewModel.discoverPeersEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "Received discoverPeersEvent. Checking permissions...")
                if (checkAndRequestPermissions(PermissionAction.DISCOVER_PEERS)) {
                    actualDiscoverPeers()
                }
            }
        }

        viewModel.connectToDeviceEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { device ->
                Log.d(TAG, "Received connectToDeviceEvent for ${device.deviceName}")
                if (viewModel.isWifiP2pEnabled.value == true) {
                    connectToDevice(device)
                } else {
                    Toast.makeText(this, "Włącz Wi-Fi Direct, aby połączyć.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.requestConnectionInfoEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "Received requestConnectionInfoEvent. Calling manager.requestConnectionInfo...")
                if (::manager.isInitialized && ::channel.isInitialized) {
                    manager.requestConnectionInfo(channel, this)
                } else {
                    Log.e(TAG, "Cannot request connection info, manager or channel not initialized.")
                }
            }
        }

        viewModel.disconnectEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "Received disconnectEvent.")
                disconnect()
            }
        }

        viewModel.receivedMessage.observe(this) { message ->
            Log.d(TAG, "Observed receivedMessage: $message")
            receivedMsgText.text = "Odebrano: $message"
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Registering receiver")
        if (::receiver.isInitialized) {
            registerReceiver(receiver, intentFilter)
        } else {
            Log.w(TAG,"onResume - Skipping receiver registration, P2P not initialized correctly")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - Unregistering receiver")
        try {
            if (::receiver.isInitialized) {
                unregisterReceiver(receiver)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver possibly already unregistered.", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // viewModel.onCleared() // Usunięto - ViewModel sam zarządza swoim cyklem życia
        if (viewModel.isHost.value == true && ::manager.isInitialized && ::channel.isInitialized) {
            manager.removeGroup(channel, null) // Rozważ wywołanie tego w onDisconnectSuccess/Failure w ViewModelu lub w onCleared ViewModelu
        }
    }

    override fun onChannelDisconnected() {
        Log.e(TAG, "Wi-Fi P2P Channel Disconnected!")
        Toast.makeText(this, "Kanał Wi-Fi P2P utracony. Spróbuj ponownie włączyć Wi-Fi.", Toast.LENGTH_LONG).show()
        viewModel.onP2pStateChanged(false, "Kanał P2P rozłączony")
        // Re-initialize?
        // initializeWifiDirect() // Może być potrzebne ponowne zainicjowanie, ale ostrożnie
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        Log.d(TAG, "onConnectionInfoAvailable received. Group formed: ${info?.groupFormed}, Is owner: ${info?.isGroupOwner}")
        if (info != null) {
            viewModel.onConnectionInfoAvailable(info)
        } else {
            Log.e(TAG, "Connection info received but it's null!")
            viewModel.updateStatus("Błąd: Informacje o połączeniu są puste.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onPeersAvailable(peerList: WifiP2pDeviceList?) {
        Log.d(TAG, "onPeersAvailable called.")
        if (peerList == null) {
            Log.e(TAG, "Peer list is null.")
            viewModel.onPeersAvailable(emptyList())
            return
        }
        val refreshedPeers = peerList.deviceList.toList()
        Log.d(TAG, "Peers found: ${refreshedPeers.size}")
        refreshedPeers.forEach { device ->
            Log.d(TAG, "Device: ${device.deviceName}, Status: ${device.statusToString()}")
        }
        viewModel.onPeersAvailable(refreshedPeers)
    }

    companion object {
        private const val TAG = "MultiPlayerActivity"
    }

    // Klasa wewnętrzna BroadcastReceiver
    class WiFiDirectBroadcastReceiver(
        private val manager: WifiP2pManager, // manager i channel są teraz val (nie lateinit)
        private val channel: WifiP2pManager.Channel,
        private val viewModel: MultiPlayerViewModel,
        private val activity: MultiPlayerActivity // Używamy jako WifiP2pManager.PeerListListener
    ) : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            Log.d(TAG, "WiFiDirectBroadcastReceiver received action: $action")

            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "P2P state changed: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                    viewModel.onP2pStateChanged(isEnabled, if(isEnabled) "Wi-Fi Direct Włączone" else "Wi-Fi Direct Wyłączone")

                    if (!isEnabled) {
                        viewModel.onPeersAvailable(emptyList())
                        viewModel.resetToDefaultState()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "P2P peers changed. Requesting peers...")
                    var hasRequiredPermissions = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            hasRequiredPermissions = true
                        } else {
                            Log.w(TAG, "Missing NEARBY_WIFI_DEVICES or ACCESS_FINE_LOCATION for requestPeers in BroadcastReceiver on S+")
                        }
                    } else {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            hasRequiredPermissions = true
                        } else {
                            Log.w(TAG, "Missing ACCESS_FINE_LOCATION for requestPeers in BroadcastReceiver on < S")
                        }
                    }

                    if (hasRequiredPermissions) {
                        // Usunięto sprawdzanie .isInitialized, ponieważ manager i channel są parametrami konstruktora
                        // Zakładamy, że jeśli receiver istnieje i działa, to Activity jest w stanie obsłużyć wywołanie.
                        // Dodatkowo, manager i channel w tym kontekście odnoszą się do pól tej klasy (WiFiDirectBroadcastReceiver),
                        // a nie do pól lateinit w MultiPlayerActivity.
                        manager.requestPeers(channel, activity) // 'activity' implementuje PeerListListener
                    } else {
                        Log.w(TAG, "Missing permissions to requestPeers in BroadcastReceiver")
                        viewModel.showToast("Brak uprawnień do wyszukiwania urządzeń.")
                        viewModel.onPeersAvailable(emptyList())
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "P2P connection changed")
                    val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    val isConnected = networkInfo?.isConnected ?: false
                    Log.d(TAG, "Is connected: $isConnected")
                    viewModel.onP2pConnectionChanged(isConnected)

                    if (isConnected) {
                        Log.d(TAG, "Connection established, requesting connection info via manager.")
                        manager.requestConnectionInfo(channel, activity)
                    } else {
                        Log.d(TAG, "Connection lost or group dissolved.")
                        viewModel.handleDisconnectionUI()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val thisDevice: WifiP2pDevice? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "This device details changed: ${thisDevice?.deviceName}, Status: ${thisDevice?.statusToString()}") //
                    thisDevice?.let { viewModel.onThisDeviceDetailsChanged(it) }
                }
            }
        }
    }
}

// Ten enum pozostaje bez zmian
enum class PermissionAction {
    CREATE_GROUP,
    DISCOVER_PEERS
}