// MultiPlayerViewModel.kt
package com.example.sudokoonline

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

import com.example.sudokoonline.util.Event
import com.example.sudokoonline.util.SudokuLogic // Dodany import klasy SudokuLogic
// import kotlinx.coroutines.CoroutineScope // Już jest viewModelScope
// import kotlinx.coroutines.Dispatchers // Używane bezpośrednio
// import kotlinx.coroutines.Job // Używane dla communicationJob
// import kotlinx.coroutines.isActive // Import dla top-level isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Job // Dodano dla typu communicationJob
import kotlinx.coroutines.isActive // Dodano dla sprawdzania statusu korutyny
import com.example.sudokoonline.multiplayer.model.GameState
import com.example.sudokoonline.multiplayer.model.GameStatus
import com.example.sudokoonline.multiplayer.model.PlayerState
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class MultiPlayerViewModel : ViewModel() {

    private val _statusText = MutableLiveData("Oczekiwanie...")
    val statusText: LiveData<String> get() = _statusText

    private val _discoveredDevicesLiveData = MutableLiveData<List<WifiP2pDevice>>(emptyList())
    val discoveredDevicesLiveData: LiveData<List<WifiP2pDevice>> get() = _discoveredDevicesLiveData

    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage

    private val _isHostButtonEnabled = MutableLiveData(true)
    val isHostButtonEnabled: LiveData<Boolean> get() = _isHostButtonEnabled

    private val _isDiscoverButtonEnabled = MutableLiveData(true)
    val isDiscoverButtonEnabled: LiveData<Boolean> get() = _isDiscoverButtonEnabled

    private val _isDisconnectButtonVisible = MutableLiveData(false)
    val isDisconnectButtonVisible: LiveData<Boolean> get() = _isDisconnectButtonVisible

    private val _isCommunicationLayoutVisible = MutableLiveData(false)
    val isCommunicationLayoutVisible: LiveData<Boolean> get() = _isCommunicationLayoutVisible

    // Nowe zmienne LiveData dla przycisku "Grajmy!"
    private val _isStartGameButtonVisible = MutableLiveData(false)
    val isStartGameButtonVisible: LiveData<Boolean> get() = _isStartGameButtonVisible

    // Flagi gotowości graczy
    private var _isLocalPlayerReady = MutableLiveData(false)
    val isLocalPlayerReady: LiveData<Boolean> get() = _isLocalPlayerReady

    private var _isOpponentReady = MutableLiveData(false)
    val isOpponentReady: LiveData<Boolean> get() = _isOpponentReady

    // Flaga czy obaj gracze są gotowi
    private val _areBothPlayersReady = MutableLiveData(false)
    val areBothPlayersReady: LiveData<Boolean> get() = _areBothPlayersReady

    private val _createGroupEvent = MutableLiveData<Event<Unit>>()
    val createGroupEvent: LiveData<Event<Unit>> get() = _createGroupEvent

    private val _discoverPeersEvent = MutableLiveData<Event<Unit>>()
    val discoverPeersEvent: LiveData<Event<Unit>> get() = _discoverPeersEvent

    private val _connectToDeviceEvent = MutableLiveData<Event<WifiP2pDevice>>()
    val connectToDeviceEvent: LiveData<Event<WifiP2pDevice>> get() = _connectToDeviceEvent

    private val _requestConnectionInfoEvent = MutableLiveData<Event<Unit>>()
    val requestConnectionInfoEvent: LiveData<Event<Unit>> get() = _requestConnectionInfoEvent

    private val _disconnectEvent = MutableLiveData<Event<Unit>>()
    val disconnectEvent: LiveData<Event<Unit>> get() = _disconnectEvent

    private val _isWifiP2pEnabled = MutableLiveData(false)
    val isWifiP2pEnabled: LiveData<Boolean> get() = _isWifiP2pEnabled

    private val _isHost = MutableLiveData(false)
    val isHost: LiveData<Boolean> get() = _isHost

    private var currentGroupInfo: WifiP2pInfo? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var communicationJob: Job? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val _receivedMessage = MutableLiveData<String>()
    val receivedMessage: LiveData<String> get() = _receivedMessage


    private var _lastPermissionAction: PermissionAction? = null
    val lastPermissionAction: PermissionAction? get() = _lastPermissionAction

    fun setLastPermissionAction(action: PermissionAction) {
        _lastPermissionAction = action
    }

    fun clearLastPermissionAction() {
        _lastPermissionAction = null
    }


    // Zmienne dla trybu gry wieloosobowej
    private val gson = GsonBuilder().setLenient().create()

    // Stan gry
    private val _gameState = MutableLiveData<GameState>()
    val gameState: LiveData<GameState> get() = _gameState

    // Informacje o tym urządzeniu
    private var myDeviceId: String = ""
    private var myDeviceName: String = "Gracz"

    // Tablica Sudoku dla obu graczy
    private val _sudokuBoard = MutableLiveData<Array<IntArray>>()
    val sudokuBoard: LiveData<Array<IntArray>> get() = _sudokuBoard

    // Rozwiązanie tablicy Sudoku
    private var sudokuSolution: Array<IntArray>? = null

    // Postęp mojego gracza (procent)
    private val _myProgress = MutableLiveData<Int>(0)
    val myProgress: LiveData<Int> get() = _myProgress

    // Postęp przeciwnika (procent)
    private val _opponentProgress = MutableLiveData<Int>(0)
    val opponentProgress: LiveData<Int> get() = _opponentProgress

    // Nazwa przeciwnika
    private val _opponentName = MutableLiveData<String>("Przeciwnik")
    val opponentName: LiveData<String> get() = _opponentName

    // Zdarzenie wygranej/przegranej
    private val _gameResultEvent =
        MutableLiveData<Event<Boolean>>() // true = wygrana, false = przegrana
    val gameResultEvent: LiveData<Event<Boolean>> get() = _gameResultEvent

    // Zdarzenie do nawigacji do ekranu gry
    private val _navigateToGameEvent = MutableLiveData<Event<Unit>>()
    val navigateToGameEvent: LiveData<Event<Unit>> get() = _navigateToGameEvent

    // Liczba stałych komórek na początku gry (do obliczania postępu)
    private var initialFilledCells = 0

    // Pozycja aktualnie wybranej komórki (używane przez CompetitiveMultiplayerActivity)
    var selectedCellPosition: Pair<Int, Int>? = null

    init {
        Log.d(TAG, "MultiPlayerViewModel initialized")
    }

    fun updateStatus(message: String) {
        _statusText.postValue(message)
    }

    fun showToast(message: String) {
        _toastMessage.postValue(Event(message))
    }

    fun onHostGameClicked() {
        Log.d(TAG, "Host Game Clicked")
        updateStatus("Tworzenie lobby...")
        _isHostButtonEnabled.value = false
        _isDiscoverButtonEnabled.value = false
        _createGroupEvent.value = Event(Unit)
    }

    fun onDiscoverLobbiesClicked() {
        Log.d(TAG, "Discover Lobbies Clicked")
        if (_isWifiP2pEnabled.value == false) {
            showToast("Włącz Wi-Fi Direct, aby wyszukiwać gry.")
            return
        }
        updateStatus("Wyszukiwanie lobby...")
        _discoveredDevicesLiveData.value = emptyList()
        _isDiscoverButtonEnabled.value = false
        _discoverPeersEvent.value = Event(Unit)
    }

    fun onDeviceSelected(device: WifiP2pDevice) {
        Log.d(TAG, "Device selected for connection: ${device.deviceName}")
        if (device.status == WifiP2pDevice.CONNECTED || device.status == WifiP2pDevice.INVITED) {
            showToast("Już połączono lub zaproszono to urządzenie.")
            return
        }
        updateStatus("Łączenie z ${device.deviceName}...")
        _connectToDeviceEvent.value = Event(device)
    }

    fun onDisconnectClicked() {
        Log.d(TAG, "Disconnect Clicked")
        updateStatus("Rozłączanie...")
        _disconnectEvent.value = Event(Unit)
    }

    fun onCreateGroupSuccess() {
        Log.d(TAG, "createGroup succeeded (ViewModel callback)")
        updateStatus("Lobby tworzone, czekam na potwierdzenie IP...")
        _isHost.value = true
    }

    fun onCreateGroupFailure(reasonCode: Int) {
        val reason = translateP2pError(reasonCode)
        Log.e(TAG, "createGroup failed (ViewModel callback). Reason: $reason ($reasonCode)")
        updateStatus("Błąd tworzenia lobby: $reason")
        showToast("Błąd tworzenia lobby: $reason")
        resetToDefaultState()
    }

    fun onDiscoverPeersSuccess() {
        Log.d(TAG, "discoverPeers success (ViewModel)")
        updateStatus("Wyszukiwanie zakończone. Sprawdź listę.")
    }

    fun onDiscoverPeersFailure(reasonCode: Int) {
        val reason = translateP2pError(reasonCode)
        Log.e(TAG, "discoverPeers failed (ViewModel). Reason: $reason ($reasonCode)")
        updateStatus("Błąd wyszukiwania lobby: $reason")
        showToast("Błąd wyszukiwania: $reason")
        _isDiscoverButtonEnabled.value = true
    }


    fun onConnectSuccess() {
        Log.d(TAG, "connect success (ViewModel)")
        updateStatus("Pomyślnie wysłano żądanie połączenia. Oczekiwanie na odpowiedź...")
        _isHostButtonEnabled.value = false
        _isDiscoverButtonEnabled.value = false
        _isDisconnectButtonVisible.value = true
    }

    fun onConnectFailure(reasonCode: Int) {
        val reason = translateP2pError(reasonCode)
        Log.e(TAG, "connect failed (ViewModel). Reason: $reason ($reasonCode)")
        updateStatus("Błąd połączenia: $reason")
        showToast("Błąd połączenia: $reason")
        if (currentGroupInfo == null || !currentGroupInfo!!.groupFormed) {
            resetToDefaultState()
        }
    }

    fun onDisconnectSuccess() {
        Log.d(TAG, "disconnect success (ViewModel)")
        updateStatus("Rozłączono pomyślnie.")
        showToast("Rozłączono.")
        currentGroupInfo = null
        _isHost.value = false
        resetToDefaultState()
        _discoveredDevicesLiveData.value = emptyList()
        stopSocketCommunication()
    }

    fun onDisconnectFailure(reasonCode: Int) {
        val reason = translateP2pError(reasonCode)
        Log.e(TAG, "disconnect failed (ViewModel). Reason: $reason ($reasonCode)")
        updateStatus("Błąd rozłączania: $reason")
        showToast("Błąd rozłączania: $reason")
    }


    fun onP2pStateChanged(isEnabled: Boolean, message: String) {
        Log.d(TAG, "onP2pStateChanged called with isEnabled: $isEnabled, message: $message")
        _isWifiP2pEnabled.value = isEnabled
        if (!isEnabled) {
            updateStatus(message)
            _isHostButtonEnabled.value = false
            _isDiscoverButtonEnabled.value = false
            _discoveredDevicesLiveData.value = emptyList()
            _isDisconnectButtonVisible.value = false
            _isCommunicationLayoutVisible.value = false
            stopSocketCommunication()
            currentGroupInfo = null
            _isHost.value = false
        } else {
            if (currentGroupInfo == null || !currentGroupInfo!!.groupFormed) {
                updateStatus(message)
                resetToDefaultStateButKeepStatus()
            }
        }
    }

    fun onP2pConnectionChanged(isConnected: Boolean) {
        Log.d(TAG, "P2P Connection changed in ViewModel: $isConnected")
        if (isConnected) {
            Log.d(TAG, "P2P Connection established (from Broadcast). Requesting connection info.")
            _requestConnectionInfoEvent.value = Event(Unit)
        } else {
            Log.d(TAG, "P2P Connection lost or group dissolved (from Broadcast).")
            val currentStatus = _statusText.value ?: ""
            if (currentStatus.contains("Lobby stworzone", ignoreCase = true) ||
                currentStatus.contains("Połączono", ignoreCase = true) ||
                currentStatus.contains("tworzone", ignoreCase = true) ||
                currentStatus.contains("Łączenie", ignoreCase = true)
            ) {
                updateStatus("Rozłączono lub lobby rozwiązane.")
                showToast("Połączenie P2P zostało przerwane.")
                currentGroupInfo = null
                _isHost.value = false
                resetToDefaultState()
                _discoveredDevicesLiveData.value = emptyList()
            }
            stopSocketCommunication()
        }
    }

    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        Log.d(TAG, "Connection info available. Is Group Owner: ${info.isGroupOwner}, Group Formed: ${info.groupFormed}")
        currentGroupInfo = info

        if (info.groupFormed) {
            _isHost.value = info.isGroupOwner

            if (info.isGroupOwner) {
                // Host inicjuje server socket
                updateStatus("Oczekiwanie na połączenie...")
                _isHostButtonEnabled.value = false
                _isDiscoverButtonEnabled.value = false
                _isDisconnectButtonVisible.value = true
                startServerSocket()
            } else {
                // Klient łączy się z server socketem hosta
                updateStatus("Łączenie...")
                _isHostButtonEnabled.value = false
                _isDiscoverButtonEnabled.value = false
                _isDisconnectButtonVisible.value = true
                connectToHostSocket(info.groupOwnerAddress)
            }
        } else {
            Log.w(TAG, "Connection info available, but group not formed yet.")
            updateStatus("Oczekiwanie na połączenie...")
        }
    }

    fun onPeersAvailable(peers: List<WifiP2pDevice>) {
        Log.d(TAG, "ViewModel received peers list. Count: ${peers.size}")
        // Używamy globalnej funkcji rozszerzającej statusToString()
        _discoveredDevicesLiveData.value = peers.filter { it.status != WifiP2pDevice.CONNECTED }
        if (peers.isEmpty() && _statusText.value?.contains("Wyszukiwanie") == true) {
            updateStatus("Nie znaleziono urządzeń.")
        }
        if (_isDiscoverButtonEnabled.value == false && _statusText.value?.contains("Wyszukiwanie") == true) {
            _isDiscoverButtonEnabled.value = true
        }
    }

    fun onThisDeviceDetailsChanged(device: WifiP2pDevice) {
        // Używamy globalnej funkcji rozszerzającej statusToString()
        Log.d(
            TAG,
            "This device details changed: ${device.deviceName}, Status: ${device.statusToString()}"
        )

        // Aktualizuj nazwę tego urządzenia, jeśli jeszcze nie została zmieniona
        if (myDeviceName == "Gracz") {
            myDeviceName = device.deviceName
            Log.d(TAG, "Updated this device name to: $myDeviceName")
        }
    }

    fun handleDisconnectionUI() {
        Log.d(TAG, "Handling UI for disconnection")
        updateStatus("Rozłączono lub utracono połączenie P2P.")
        currentGroupInfo = null
        _isHost.value = false
        resetToDefaultState()
        _discoveredDevicesLiveData.value = emptyList()
        stopSocketCommunication()
    }

    fun resetToDefaultState() {
        Log.d(TAG, "Resetting UI to default state")
        updateStatus("Oczekiwanie...")
        _isHostButtonEnabled.value = true
        _isDiscoverButtonEnabled.value = true
        _isDisconnectButtonVisible.value = false
        _isCommunicationLayoutVisible.value = false
    }

    fun resetToDefaultStateButKeepStatus() {
        _isHostButtonEnabled.value = true
        _isDiscoverButtonEnabled.value = true
        _isDisconnectButtonVisible.value = false
        _isCommunicationLayoutVisible.value = false
    }


    private fun startServerSocket() {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            Log.w(TAG, "Server socket already running")
            return
        }

        communicationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SOCKET_PORT)
                Log.i(TAG, "Host: ServerSocket started on port $SOCKET_PORT")

                withContext(Dispatchers.Main) {
                    updateStatus("Oczekiwanie na połączenie...")
                }

                // Blokuje wątek do czasu połączenia klienta
                clientSocket = serverSocket!!.accept()
                Log.i(TAG, "Host: Client connected!")

                // Po połączeniu inicjujemy strumienie i aktualizujemy UI
                outputStream = clientSocket?.getOutputStream()
                inputStream = clientSocket?.getInputStream()

                withContext(Dispatchers.Main) {
                    updateStatus("Połączono!")
                    _isCommunicationLayoutVisible.value = true
                }

                // Rozpoczynamy nasłuchiwanie wiadomości
                listenForMessages()
            } catch (e: IOException) {
                if (communicationJob?.isActive == true) {
                    Log.e(TAG, "Host: ServerSocket error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        updateStatus("Błąd połączenia: ${e.message}")
                        _isCommunicationLayoutVisible.value = false
                    }
                }
            }
        }
    }

    private fun connectToHostSocket(hostAddress: InetAddress?) {
        if (hostAddress == null) {
            Log.e(TAG, "Client: Host address is null, cannot connect socket.")
            updateStatus("Błąd: Brak adresu hosta do połączenia socket.")
            return
        }

        // Zamykamy poprzednie połączenie, jeśli istnieje
        stopSocketCommunication()

        communicationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    updateStatus("Łączenie z hostem...")
                }

                val newSocket = Socket()
                // Ustawiamy opcje socketu przed połączeniem
                newSocket.keepAlive = true
                newSocket.reuseAddress = true
                newSocket.tcpNoDelay = true

                val socketAddress = InetSocketAddress(hostAddress, SOCKET_PORT)
                newSocket.connect(socketAddress, 5000) // Zmniejszony timeout do 5 sekund

                if (!newSocket.isConnected) {
                    throw IOException("Nie można nawiązać połączenia z hostem")
                }

                // Socket połączony poprawnie
                clientSocket = newSocket
                outputStream = clientSocket?.getOutputStream()
                inputStream = clientSocket?.getInputStream()

                withContext(Dispatchers.Main) {
                    updateStatus("Połączono!")
                    _isCommunicationLayoutVisible.value = true
                }

                listenForMessages()

            } catch (e: IOException) {
                Log.e(TAG, "Client: Socket error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Błąd połączenia: ${e.message}")
                    showToast("Błąd połączenia z hostem")
                }
                stopSocketCommunication()
            } catch (e: Exception) {
                Log.e(TAG, "Client: Unexpected error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Nieoczekiwany błąd: ${e.message}")
                }
                stopSocketCommunication()
            }
        }
    }

    private suspend fun listenForMessages() {
        Log.d(TAG, "Starting to listen for messages...")
        val buffer = ByteArray(4096) // Zwiększony bufor dla większych wiadomości JSON
        var bytes: Int

        try {
            while (communicationJob?.isActive == true && inputStream != null) {
                bytes = inputStream!!.read(buffer)
                if (bytes > 0) {
                    val received = String(buffer, 0, bytes)
                    Log.i(TAG, "Message received: $received")
                    withContext(Dispatchers.Main) {
                        _receivedMessage.value = received
                        // Przetwarzanie otrzymanej wiadomości
                        processReceivedMessage(received)
                    }
                } else if (bytes == -1) {
                    Log.i(TAG, "Input stream ended, connection closed by peer.")
                    withContext(Dispatchers.Main) {
                        showToast("Połączenie zakończone przez drugiego gracza.")
                        handleDisconnectionUI()
                    }
                    break
                }
            }
        } catch (e: IOException) {
            if (communicationJob?.isActive == true) {
                if (clientSocket?.isClosed == false && serverSocket?.isClosed == false) {
                    Log.e(TAG, "IOException while listening for messages: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        showToast("Błąd odczytu wiadomości: ${e.message}")
                        handleDisconnectionUI()
                    }
                } else {
                    Log.d(TAG, "Socket closed, IOException during read is expected: ${e.message}")
                }
            }
        } finally {
            Log.d(TAG, "Finished listening for messages.")
            withContext(Dispatchers.Main) {
                if (_isCommunicationLayoutVisible.value == true && communicationJob?.isActive == true) {
                    // showToast("Zakończono nasłuchiwanie wiadomości.")
                }
            }
        }
    }

    // Metody protokołu gry wieloosobowej

    /**
     * Przetwarzanie odebranej wiadomości
     */
    private fun processReceivedMessage(messageJson: String) {
        try {
            // Dodajemy logowanie długości wiadomości i jej fragmentu
            Log.d(TAG, "Processing received message with length: ${messageJson.length}")
            if (messageJson.length > 200) {
                // Logujemy tylko początek długiej wiadomości
                Log.d(TAG, "Message preview: ${messageJson.substring(0, 200)}...")
            } else {
                Log.d(TAG, "Message content: $messageJson")
            }

            // Sprawdzamy, czy wiadomość jest poprawnym obiektem JSON
            val trimmedMessage = messageJson.trim()
            if (!trimmedMessage.startsWith("{")) {
                Log.e(TAG, "Received message is not a valid JSON object: $messageJson")
                showToast("Odebrano nieprawidłową wiadomość (brak obiektu JSON)")
                return
            }

            val message = gson.fromJson(messageJson, MultiplayerMessage::class.java)
            Log.d(TAG, "Message type: ${message.type}, payload length: ${message.payload.length}")

            when (message.type) {
                MESSAGE_TYPE_PLAYER_INFO -> handlePlayerInfoMessage(message.payload)
                MESSAGE_TYPE_GAME_START -> {
                    Log.d(TAG, "Received GAME_START message, processing...")
                    handleGameStartMessage(message.payload)
                    showToast("Odebrano planszę od hosta!")
                }

                MESSAGE_TYPE_PROGRESS_UPDATE -> handleProgressUpdateMessage(message.payload)
                MESSAGE_TYPE_GAME_COMPLETE -> handleGameCompleteMessage(message.payload)
                MESSAGE_TYPE_PLAYER_READY -> handlePlayerReadyMessage(message.payload)
                else -> Log.w(TAG, "Unknown message type: ${message.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}", e)
            // Logowanie stacktrace dla dokładniejszej diagnozy problemu
            e.printStackTrace()
            showToast("Błąd przetwarzania wiadomości: ${e.message}")
        }
    }

    /**
     * Obsługa wiadomości z informacjami o graczu
     */
    private fun handlePlayerInfoMessage(payload: String) {
        try {
            val playerInfo = gson.fromJson(payload, PlayerInfoMessage::class.java)
            Log.d(TAG, "Received player info: ${playerInfo.deviceName}")

            // Aktualizacja informacji o przeciwniku
            _opponentName.value = playerInfo.deviceName

            // Dodanie gracza do stanu gry
            val gameStateValue = _gameState.value ?: GameState()
            gameStateValue.addPlayer(playerInfo.deviceId, playerInfo.deviceName)
            _gameState.value = gameStateValue

            // Odpowiedź informacjami o tym urządzeniu
            if (_isHost.value == true) {
                sendPlayerInfo()
                // Host inicjuje grę po otrzymaniu informacji o kliencie
                setupAndStartGame()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling player info: ${e.message}", e)
        }
    }

    /**
     * Obsługa wiadomości z planszą Sudoku
     */
    private fun handleGameStartMessage(payload: String) {
        try {
            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Rozpoczynam przetwarzanie danych planszy")
            Log.d(TAG, "Payload długość: ${payload.length}, początek: ${payload.take(100)}...")

            val gameStartData = gson.fromJson(payload, GameStartMessage::class.java)
            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Dane planszy zdekodowane z JSON")

            // Sprawdzamy, czy dane planszy są poprawne
            val boardValid = gameStartData.board != null && gameStartData.board.size == 9
            val solutionValid = gameStartData.solution != null && gameStartData.solution.size == 9

            Log.d(
                TAG,
                "### DIAGNOSTYKA KLIENTA ### Walidacja planszy: poprawna=${boardValid && solutionValid}"
            )

            if (!boardValid || !solutionValid) {
                Log.e(TAG, "### DIAGNOSTYKA KLIENTA ### Nieprawidłowe dane planszy!")
                _toastMessage.postValue(Event("Otrzymano nieprawidłowe dane planszy!"))
                return
            }

            // Loguj wszystkie wiersze planszy dla lepszej diagnostyki
            for (i in 0..8) {
                Log.d(TAG, "Wiersz planszy $i: ${gameStartData.board[i].joinToString()}")
            }
            Log.d(TAG, "Rozwiązanie - pierwszy wiersz: ${gameStartData.solution[0].joinToString()}")

            // Toast diagnostyczny
            _toastMessage.postValue(Event("Otrzymano planszę Sudoku!"))

            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Ustawiam sudokuBoard...")
            // Zapisz tablicę i rozwiązanie
            _sudokuBoard.value = gameStartData.board
            sudokuSolution = gameStartData.solution

            // Sprawdź, czy wartość została poprawnie zapisana
            val boardAfterSet = _sudokuBoard.value
            if (boardAfterSet != null) {
                Log.d(
                    TAG,
                    "### DIAGNOSTYKA KLIENTA ### Plansza poprawnie ustawiona w _sudokuBoard. Pierwszy wiersz: ${boardAfterSet[0].joinToString()}"
                )

                // Wyślij potwierdzenie odbioru planszy do hosta
                if (_isHost.value != true) {
                    sendBoardReceivedConfirmation()
                }
            } else {
                Log.e(TAG, "### DIAGNOSTYKA KLIENTA ### BŁĄD: _sudokuBoard.value nadal jest null po ustawieniu!")
                return
            }

            // Oblicz początkową liczbę wypełnionych komórek
            initialFilledCells = countFilledCells(gameStartData.board)
            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Liczba początkowych komórek: $initialFilledCells")

            // Aktualizuj stan gry
            val gameStateValue = _gameState.value ?: GameState()
            gameStateValue.status = GameStatus.IN_PROGRESS
            gameStateValue.start()
            _gameState.value = gameStateValue

            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Przygotowanie do nawigacji, isHost=${_isHost.value}")
            Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Status ViewModelu: sudokuBoard null? ${_sudokuBoard.value == null}")

            // Krótkie opóźnienie przed nawigacją
            viewModelScope.launch {
                Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Opóźnienie 500ms przed nawigacją...")
                // Dajemy czas na zaktualizowanie danych w pamięci
                kotlinx.coroutines.delay(500)

                // Powiadom UI o rozpoczęciu gry
                Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Wywołuję navigateToGameEvent!")
                _navigateToGameEvent.value = Event(Unit)

                // Dodatkowe potwierdzenie
                Log.d(TAG, "### DIAGNOSTYKA KLIENTA ### Event nawigacji wysłany. Status: ${_navigateToGameEvent.value?.hasBeenHandled}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "### DIAGNOSTYKA KLIENTA ### Błąd przetwarzania planszy: ${e.message}", e)
            e.printStackTrace() // Dodanie pełnego stack trace dla dokładniejszej diagnozy
            // Toast diagnostyczny dla błędu
            _toastMessage.postValue(Event("Błąd planszy: ${e.message}"))
        }
    }

    /**
     * Obsługa wiadomości z aktualizacją postępu
     */
    private fun handleProgressUpdateMessage(payload: String) {
        try {
            val progressUpdate = gson.fromJson(payload, ProgressUpdateMessage::class.java)
            Log.d(
                TAG,
                "Received progress update: ${progressUpdate.completionPercentage}% from ${progressUpdate.deviceId}"
            )

            // Aktualizuj postęp przeciwnika
            _opponentProgress.value = progressUpdate.completionPercentage

            // Aktualizuj stan gry
            val gameStateValue = _gameState.value ?: return
            gameStateValue.updatePlayerProgress(
                progressUpdate.deviceId,
                progressUpdate.completionPercentage
            )
            _gameState.value = gameStateValue

        } catch (e: Exception) {
            Log.e(TAG, "Error handling progress update: ${e.message}", e)
        }
    }

    /**
     * Obsługa wiadomości o zakończeniu gry
     */
    private fun handleGameCompleteMessage(payload: String) {
        try {
            val gameComplete = gson.fromJson(payload, GameCompleteMessage::class.java)
            Log.d(TAG, "Received game complete from: ${gameComplete.deviceId}")

            // Sprawdź, czy to przeciwnik zakończył grę
            if (gameComplete.deviceId != myDeviceId) {
                // Przeciwnik wygrał
                _gameResultEvent.value = Event(false) // false = przegrana

                // Aktualizuj stan gry
                val gameStateValue = _gameState.value ?: return
                gameStateValue.finish(gameComplete.deviceId)
                gameStateValue.status = GameStatus.FINISHED
                _gameState.value = gameStateValue

                showToast("Przeciwnik ukończył grę pierwszy!")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling game complete: ${e.message}", e)
        }
    }

    /**
     * Obsługa wiadomości o gotowości gracza
     */
    private fun handlePlayerReadyMessage(payload: String) {
        try {
            val playerReady = gson.fromJson(payload, PlayerReadyMessage::class.java)
            Log.d(TAG, "Received player ready: ${playerReady.deviceId}")

            // Aktualizacja flagi gotowości przeciwnika
            if (playerReady.deviceId != myDeviceId) {
                _isOpponentReady.value = true
                Log.d(TAG, "Opponent is ready! Local player ready: ${_isLocalPlayerReady.value}")

                // Jeśli to host, automatycznie ustaw jego gotowość po otrzymaniu sygnału od klienta
                if (_isHost.value == true && _isLocalPlayerReady.value != true) {
                    Log.d(TAG, "Host auto-responding to client ready signal")
                    _isLocalPlayerReady.value = true

                    // Wysyłamy potwierdzenie gotowości do klienta
                    val readyMessage = PlayerReadyMessage(myDeviceId)
                    val message = MultiplayerMessage(
                        type = MESSAGE_TYPE_PLAYER_READY,
                        payload = gson.toJson(readyMessage)
                    )
                    val messageJson = gson.toJson(message)
                    sendMessage(messageJson)

                    // Ustawiamy flagę gotowości obu graczy
                    _areBothPlayersReady.value = true

                    // Host automatycznie rozpoczyna grę
                    Log.d(TAG, "Host auto-starting game after receiving client ready signal")
                    setupAndStartGame()
                    return  // Kończymy, bo setupAndStartGame już zajmie się resztą
                }

                // W przypadku klienta, automatycznie ustaw jego gotowość, jeśli jeszcze nie jest gotowy
                else if (_isHost.value != true && _isLocalPlayerReady.value != true) {
                    Log.d(TAG, "Client auto-responding to host ready signal")
                    _isLocalPlayerReady.value = true
                    sendPlayerReadySignal()
                }
            }

            // Sprawdź, czy obaj gracze są gotowi
            _areBothPlayersReady.value =
                (_isLocalPlayerReady.value == true && _isOpponentReady.value == true)
            Log.d(TAG, "Both players ready status: ${_areBothPlayersReady.value}")

            // Jeśli obaj gracze są gotowi, tylko host powinien zainicjować grę
            if (_areBothPlayersReady.value == true) {
                Log.d(TAG, "Both players are ready. Is host: ${_isHost.value}")
                if (_isHost.value == true) {
                    Log.d(TAG, "Host is calling setupAndStartGame()...")
                    setupAndStartGame()
                } else {
                    Log.d(TAG, "Client is waiting for host to prepare game board...")
                    showToast("Oczekiwanie na przygotowanie planszy przez hosta...")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling player ready: ${e.message}", e)
        }
    }

    /**
     * Wysłanie informacji o graczu
     */
    fun sendPlayerInfo() {
        try {
            // Jeśli ID urządzenia nie zostało ustawione, wygeneruj je
            if (myDeviceId.isEmpty()) {
                myDeviceId = generateDeviceId()
            }

            val playerInfo = PlayerInfoMessage(myDeviceId, myDeviceName)
            val payload = gson.toJson(playerInfo)
            val message = MultiplayerMessage(MESSAGE_TYPE_PLAYER_INFO, payload)
            val messageJson = gson.toJson(message)

            sendMessage(messageJson)
            Log.d(TAG, "Sent player info: $myDeviceName")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending player info: ${e.message}", e)
            showToast("Błąd wysyłania informacji o graczu: ${e.message}")
        }
    }

    /**
     * Wysłanie planszy Sudoku i rozpoczęcie gry
     */
    fun sendGameBoard(board: Array<IntArray>, solution: Array<IntArray>) {
        try {
            Log.d(TAG, "Preparing to send game board to client...")

            // Najpierw ustaw lokalną tablicę i rozwiązanie
            Log.d(TAG, "Setting up local board data first")
            _sudokuBoard.value = board.map { it.clone() }
                .toTypedArray()  // Używamy kopii, aby uniknąć problemów z referencjami
            sudokuSolution = solution.map { it.clone() }.toTypedArray()
            initialFilledCells = countFilledCells(board)

            // Aktualizuj stan gry
            val gameStateValue = _gameState.value ?: GameState()
            gameStateValue.status = GameStatus.IN_PROGRESS
            gameStateValue.start()
            _gameState.value = gameStateValue

            // Teraz wyślij planszę do klienta
            val gameStartData = GameStartMessage(board, solution)
            val payload = gson.toJson(gameStartData)
            Log.d(TAG, "GameStartMessage payload size: ${payload.length} bytes")

            val message = MultiplayerMessage(MESSAGE_TYPE_GAME_START, payload)
            val messageJson = gson.toJson(message)

            // Wyślij wiadomość z planszą
            sendMessage(messageJson)
            Log.d(TAG, "Game board message sent successfully")

            // Przejdź do ekranu gry PO wysłaniu planszy
            viewModelScope.launch {
                Log.d(TAG, "Waiting 1000ms before navigating to game screen...")
                kotlinx.coroutines.delay(1000)

                // Przejdź do ekranu gry
                Log.d(TAG, "Emitting navigateToGameEvent to start CompetitiveMultiplayerActivity")
                _navigateToGameEvent.value = Event(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending game board: ${e.message}", e)
            showToast("Błąd wysyłania planszy: ${e.message}")
        }
    }

    /**
     * Wysłanie aktualizacji postępu
     */
    fun sendProgressUpdate(completionPercentage: Int, totalCellsFilled: Int) {
        try {
            val progressUpdate =
                ProgressUpdateMessage(myDeviceId, completionPercentage, totalCellsFilled)
            val payload = gson.toJson(progressUpdate)
            val message = MultiplayerMessage(MESSAGE_TYPE_PROGRESS_UPDATE, payload)
            val messageJson = gson.toJson(message)

            sendMessage(messageJson)
            Log.d(TAG, "Sent progress update: $completionPercentage%")

            // Aktualizuj własny postęp
            _myProgress.value = completionPercentage

            // Aktualizuj stan gry
            val gameStateValue = _gameState.value ?: return
            val myPlayer = gameStateValue.players.find { it.deviceId == myDeviceId }
                ?: gameStateValue.addPlayer(myDeviceId, myDeviceName)
            myPlayer.completionPercentage = completionPercentage
            myPlayer.totalCellsFilled = totalCellsFilled
            _gameState.value = gameStateValue

        } catch (e: Exception) {
            Log.e(TAG, "Error sending progress update: ${e.message}", e)
        }
    }

    /**
     * Wysłanie informacji o zakończeniu gry
     */
    fun sendGameComplete() {
        try {
            val gameCompleteData = GameCompleteMessage(myDeviceId, System.currentTimeMillis())
            val payload = gson.toJson(gameCompleteData)
            val message = MultiplayerMessage(MESSAGE_TYPE_GAME_COMPLETE, payload)
            val messageJson = gson.toJson(message)

            sendMessage(messageJson)
            Log.d(TAG, "Sent game complete")

            // Aktualizuj stan gry
            val gameStateValue = _gameState.value ?: return
            gameStateValue.finish(myDeviceId)
            gameStateValue.status = GameStatus.FINISHED
            _gameState.value = gameStateValue

            // Powiadom UI o wygranej
            _gameResultEvent.value = Event(true) // true = wygrana

        } catch (e: Exception) {
            Log.e(TAG, "Error sending game complete: ${e.message}", e)
        }
    }

    /**
     * Inicjalizacja i rozpoczęcie gry przez hosta
     */
    private fun setupAndStartGame() {
        // Sprawdź, czy to host - tylko host może generować planszę
        if (_isHost.value != true) {
            Log.d(TAG, "setupAndStartGame called but this device is not host. Ignoring.")
            return
        }

        Log.d(TAG, "setupAndStartGame: Host is preparing game...")
        showToast("Przygotowywanie gry...")

        // Używamy generatora plansz zamiast statycznych tablic
        viewModelScope.launch {
            try {
                // Generujemy nową planszę Sudoku z mniejszą trudnością (mniej pustych komórek)
                // dla szybszej rozgrywki w trybie wieloosobowym
                val difficulty = 30 // Mniej pustych komórek niż w trybie SinglePlayer (domyślnie 40)
                val (puzzleBoard, solutionBoard) = SudokuLogic.generateNewGame(difficulty)

                // Logujemy wygenerowaną planszę do celów diagnostycznych
                Log.d(TAG, "Generated Sudoku board with difficulty: $difficulty")
                for (i in 0..8) {
                    Log.d(TAG, "Generated board row $i: ${puzzleBoard[i].joinToString()}")
                }

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Setting generated Sudoku board")

                    // Najpierw ustaw planszę lokalnie
                    _sudokuBoard.value = puzzleBoard
                    sudokuSolution = solutionBoard

                    // Liczymy wypełnione komórki
                    initialFilledCells = countFilledCells(puzzleBoard)
                    Log.d(TAG, "Generated board has $initialFilledCells filled cells")

                    // Aktualizuj stan gry
                    val gameStateValue = _gameState.value ?: GameState()

                    // ZMIANA: Ustaw status IN_PROGRESS już przed wysłaniem planszy
                    // zamiast STARTING, co zapewni, że status będzie prawidłowy
                    // w momencie przejścia do ekranu gry
                    gameStateValue.status = GameStatus.IN_PROGRESS
                    gameStateValue.start()
                    _gameState.value = gameStateValue

                    showToast("Wysyłanie planszy do przeciwnika...")

                    // WAŻNE: Wyślij planszę do klienta PRZED nawigacją do ekranu gry
                    sendGameBoard(puzzleBoard, solutionBoard)

                    // Ustawimy small delay przed nawigacją do gry
                    kotlinx.coroutines.delay(1500)

                    // Przejdź do ekranu gry
                    Log.d(
                        TAG,
                        "Host: Emitting navigateToGameEvent to start CompetitiveMultiplayerActivity"
                    )
                    _navigateToGameEvent.value = Event(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up game: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("Błąd przygotowania gry: ${e.message}")
                }
            }
        }
    }

    /**
     * Obsługa wpisania cyfry do komórki przez gracza
     */
    fun onCellValueUpdated(row: Int, col: Int, value: Int) {
        val currentBoard = _sudokuBoard.value ?: return

        // Aktualizuj planszę
        currentBoard[row][col] = value
        _sudokuBoard.value = currentBoard

        // Oblicz postęp na podstawie obecnie wypełnionych komórek
        val filledCells = countNonFixedFilledCells(currentBoard)
        val totalToFill = 81 - initialFilledCells
        val progress = if (totalToFill > 0) (filledCells * 100) / totalToFill else 0

        // Wyślij aktualizację postępu
        sendProgressUpdate(progress, filledCells)

        // Sprawdź, czy plansza jest rozwiązana
        if (isBoardSolved(currentBoard)) {
            sendGameComplete()
        }
    }

    /**
     * Sprawdza, czy plansza jest rozwiązana
     */
    private fun isBoardSolved(board: Array<IntArray>): Boolean {
        val solution = sudokuSolution ?: return false

        for (i in 0..8) {
            for (j in 0..8) {
                if (board[i][j] != solution[i][j]) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Liczy wypełnione komórki na planszy
     */
    private fun countFilledCells(board: Array<IntArray>): Int {
        var count = 0
        for (i in 0..8) {
            for (j in 0..8) {
                if (board[i][j] != 0) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Liczy wypełnione komórki przez gracza (bez stałych komórek)
     */
    private fun countNonFixedFilledCells(board: Array<IntArray>): Int {
        return countFilledCells(board) - initialFilledCells
    }

    /**
     * Generuje unikalny identyfikator urządzenia
     */
    private fun generateDeviceId(): String {
        return System.currentTimeMillis().toString() + "_" + (0..1000).random()
    }

    /**
     * Inicjalizuje komunikację po nawiązaniu połączenia socket
     */
    fun initializeMultiplayerCommunication() {
        // Ustaw ID urządzenia, jeśli nie zostało ustawione
        if (myDeviceId.isEmpty()) {
            myDeviceId = generateDeviceId()
        }

        // Utwórz nowy stan gry
        val gameState = GameState()
        gameState.addPlayer(myDeviceId, myDeviceName)
        _gameState.value = gameState

        // Klient wysyła informacje o sobie do hosta
        if (_isHost.value != true) {
            sendPlayerInfo()
        }
    }
    fun sendPlayerReadySignal() {
        try {
            Log.d(TAG, "Sending player ready signal")

            // Ustaw flagę gotowości dla lokalnego gracza
            _isLocalPlayerReady.value = true

            // Automatycznie oznacz przeciwnika jako gotowego również
            _isOpponentReady.value = true

            // Ustaw flagę gotowości obu graczy
            _areBothPlayersReady.value = true

            // Wyślij sygnał gotowości do przeciwnika
            val readyMessage = PlayerReadyMessage(
                deviceId = myDeviceId
            )

            val message = MultiplayerMessage(
                type = MESSAGE_TYPE_PLAYER_READY,
                payload = gson.toJson(readyMessage)
            )

            val messageJson = gson.toJson(message)
            sendMessage(messageJson)

            Log.d(TAG, "Player ready signal sent")

            // Natychmiast rozpocznij grę, jeśli to host
            if (_isHost.value == true) {
                Log.d(TAG, "Host is starting game immediately after ready signal")
                setupAndStartGame()
            } else {
                Log.d(TAG, "Client sent ready signal, waiting for host to start game")
                showToast("Wysłano gotowość do hosta. Oczekiwanie na rozpoczęcie gry...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending player ready signal: ${e.message}", e)
            showToast("Błąd wysyłania sygnału gotowości: ${e.message}")
        }
    }
    fun sendMessage(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "Cannot send message, OutputStream is null")
                    withContext(Dispatchers.Main) {
                        showToast("Błąd: Nie można wysłać wiadomości - brak połączenia")
                    }
                    return@launch
                }

                if (clientSocket?.isClosed == true) {
                    Log.e(TAG, "Cannot send message, Socket is closed")
                    withContext(Dispatchers.Main) {
                        showToast("Błąd: Socket jest zamknięty")
                    }
                    return@launch
                }

                // Sprawdź rozmiar wiadomości
                val messageBytes = message.toByteArray()
                val messageSize = messageBytes.size
                Log.d(TAG, "Attempting to send message of size $messageSize bytes")

                // Używamy BufferedOutputStream dla lepszej wydajności
                val bufferedOutput = java.io.BufferedOutputStream(outputStream)

                // Dla dużych wiadomości (>1MB) wyświetl ostrzeżenie diagnostyczne
                if (messageSize > 1024 * 1024) {
                    Log.w(TAG, "Large message being sent (${messageSize / 1024} KB)")
                }

                // Wysyłamy wiadomość
                try {
                    bufferedOutput.write(messageBytes)
                    bufferedOutput.flush()
                    Log.d(TAG, "Message sent successfully, size: $messageSize bytes")
                } catch (e: IOException) {
                    // Sprawdź stan socketu
                    if (clientSocket?.isConnected == false || clientSocket?.isClosed == true) {
                        Log.e(TAG, "Socket disconnected during send: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            showToast("Utracono połączenie z drugim graczem")
                            handleDisconnectionUI()
                        }
                    } else {
                        throw e // przekaż wyjątek dalej, jeśli to nie problem z socketem
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("Błąd wysyłania wiadomości: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending message: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("Nieoczekiwany błąd: ${e.message}")
                }
            }
        }
    }

    fun stopSocketCommunication() {
        Log.d(TAG, "Stopping socket communication")

        // Najpierw anulujemy zadanie komunikacji
        try {
            communicationJob?.let { job ->
                if (job.isActive) {
                    Log.d(TAG, "Cancelling active communication job")
                    job.cancel()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling communication job: ${e.message}", e)
        }

        // Następnie bezpiecznie zamykamy wszystkie zasoby
        try {
            // Zamykamy strumienie najpierw
            try {
                if (inputStream != null) {
                    Log.d(TAG, "Closing input stream")
                    inputStream?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream: ${e.message}", e)
            }

            try {
                if (outputStream != null) {
                    Log.d(TAG, "Closing output stream")
                    outputStream?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream: ${e.message}", e)
            }

            // Następnie zamykamy sockety
            try {
                if (clientSocket?.isClosed == false) {
                    Log.d(TAG, "Closing client socket")
                    clientSocket?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket: ${e.message}", e)
            }

            try {
                if (serverSocket?.isClosed == false) {
                    Log.d(TAG, "Closing server socket")
                    serverSocket?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "General error during socket cleanup: ${e.message}", e)
        } finally {
            // Zwalniamy referencje do zasobów
            inputStream = null
            outputStream = null
            clientSocket = null
            serverSocket = null
            Log.d(TAG, "All socket resources released")
        }
    }


    private fun translateP2pError(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct nie jest obsługiwane na tym urządzeniu"
            WifiP2pManager.BUSY -> "System jest zajęty, spróbuj ponownie później"
            WifiP2pManager.ERROR -> "Wewnętrzny błąd operacji"
            else -> "Nieznany błąd (kod: $reasonCode)"
        }
    }

    // onCleared jest chronione w ViewModel, nie trzeba go wywoływać z Activity.
    // Framework sam zadba o jego wywołanie.
    override fun onCleared() {
        super.onCleared() // Ważne, aby wywołać super.onCleared()
        Log.d(TAG, "MultiPlayerViewModel onCleared called")
        stopSocketCommunication()
    }

    companion object {
        private const val TAG = "MultiPlayerViewModel"
        private const val SOCKET_PORT = 8888

        // Typy wiadomości dla protokołu komunikacji
        const val MESSAGE_TYPE_PLAYER_INFO = "PLAYER_INFO"
        const val MESSAGE_TYPE_GAME_START = "GAME_START"
        const val MESSAGE_TYPE_PROGRESS_UPDATE = "PROGRESS_UPDATE"
        const val MESSAGE_TYPE_GAME_COMPLETE = "GAME_COMPLETE"
        const val MESSAGE_TYPE_PLAYER_READY = "PLAYER_READY"
        const val MESSAGE_TYPE_BOARD_RECEIVED = "BOARD_RECEIVED" // Nowy typ dla potwierdzenia odbioru planszy

        // NOWA UPROSZCZONA TABLICA - Z BARDZO MAŁĄ LICZBĄ PUSTYCH KOMÓREK (TYLKO 9)
        val SIMPLE_TEST_BOARD = arrayOf(
            intArrayOf(5, 3, 4, 6, 7, 8, 9, 1, 2),
            intArrayOf(6, 7, 2, 1, 9, 5, 3, 4, 8),
            intArrayOf(1, 9, 8, 3, 4, 2, 5, 6, 7),
            intArrayOf(8, 5, 9, 7, 0, 1, 4, 2, 3),
            intArrayOf(4, 2, 6, 8, 5, 3, 7, 9, 1),
            intArrayOf(7, 1, 3, 9, 2, 0, 8, 5, 6),
            intArrayOf(9, 6, 1, 5, 3, 7, 0, 8, 4),
            intArrayOf(2, 8, 7, 4, 1, 9, 6, 0, 5),
            intArrayOf(3, 4, 5, 2, 8, 6, 1, 7, 0)
        )

        // Rozwiązanie dla uproszczonej tablicy (identyczne, bo ma tylko 9 pustych komórek)
        val SIMPLE_TEST_SOLUTION = arrayOf(
            intArrayOf(5, 3, 4, 6, 7, 8, 9, 1, 2),
            intArrayOf(6, 7, 2, 1, 9, 5, 3, 4, 8),
            intArrayOf(1, 9, 8, 3, 4, 2, 5, 6, 7),
            intArrayOf(8, 5, 9, 7, 6, 1, 4, 2, 3),
            intArrayOf(4, 2, 6, 8, 5, 3, 7, 9, 1),
            intArrayOf(7, 1, 3, 9, 2, 4, 8, 5, 6),
            intArrayOf(9, 6, 1, 5, 3, 7, 2, 8, 4),
            intArrayOf(2, 8, 7, 4, 1, 9, 6, 3, 5),
            intArrayOf(3, 4, 5, 2, 8, 6, 1, 7, 9)
        )

        // Stara wersja statycznej planszy Sudoku do testów komunikacji
        val STATIC_SUDOKU_BOARD = arrayOf(
            intArrayOf(5, 3, 0, 0, 7, 0, 0, 0, 0),
            intArrayOf(6, 0, 0, 1, 9, 5, 0, 0, 0),
            intArrayOf(0, 9, 8, 0, 0, 0, 0, 6, 0),
            intArrayOf(8, 0, 0, 0, 6, 0, 0, 0, 3),
            intArrayOf(4, 0, 0, 8, 0, 3, 0, 0, 1),
            intArrayOf(7, 0, 0, 0, 2, 0, 0, 0, 6),
            intArrayOf(0, 6, 0, 0, 0, 0, 2, 8, 0),
            intArrayOf(0, 0, 0, 4, 1, 9, 0, 0, 5),
            intArrayOf(0, 0, 0, 0, 8, 0, 0, 7, 9)
        )

        // Rozwiązanie statycznej planszy Sudoku
        val STATIC_SUDOKU_SOLUTION = arrayOf(
            intArrayOf(5, 3, 4, 6, 7, 8, 9, 1, 2),
            intArrayOf(6, 7, 2, 1, 9, 5, 3, 4, 8),
            intArrayOf(1, 9, 8, 3, 4, 2, 5, 6, 7),
            intArrayOf(8, 5, 9, 7, 6, 1, 4, 2, 3),
            intArrayOf(4, 2, 6, 8, 5, 3, 7, 9, 1),
            intArrayOf(7, 1, 3, 9, 2, 4, 8, 5, 6),
            intArrayOf(9, 6, 1, 5, 3, 7, 2, 8, 4),
            intArrayOf(2, 8, 7, 4, 1, 9, 6, 3, 5),
            intArrayOf(3, 4, 5, 2, 8, 6, 1, 7, 9)
        )
    }

    // Klasy wiadomości do serializacji/deserializacji przez Gson
    data class MultiplayerMessage(
        val type: String,
        val payload: String
    )

    data class PlayerInfoMessage(
        val deviceId: String,
        val deviceName: String
    )

    data class GameStartMessage(
        val board: Array<IntArray>,
        val solution: Array<IntArray>
    ) {
        // Niezbędne dla poprawnego porównywania tablic wielowymiarowych
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as GameStartMessage

            if (!board.contentDeepEquals(other.board)) return false
            if (!solution.contentDeepEquals(other.solution)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = board.contentDeepHashCode()
            result = 31 * result + solution.contentDeepHashCode()
            return result
        }
    }

    data class ProgressUpdateMessage(
        val deviceId: String,
        val completionPercentage: Int,
        val totalCellsFilled: Int
    )

    data class GameCompleteMessage(
        val deviceId: String,
        val completionTime: Long
    )

    data class PlayerReadyMessage(
        val deviceId: String
    )

    // Nowa klasa do potwierdzania odbioru planszy przez klienta
    data class BoardReceivedMessage(
        val deviceId: String,
        val success: Boolean,
        val message: String = ""
    )
    /**
     * Wysyłanie potwierdzenia odbioru planszy przez klienta
     */
    private fun sendBoardReceivedConfirmation() {
        try {
            Log.d(TAG, "Sending board received confirmation to host")

            val boardReceivedMsg = BoardReceivedMessage(
                deviceId = myDeviceId,
                success = true,
                message = "Plansza odebrana poprawnie"
            )

            val message = MultiplayerMessage(
                type = MESSAGE_TYPE_BOARD_RECEIVED,
                payload = gson.toJson(boardReceivedMsg)
            )

            val messageJson = gson.toJson(message)
            sendMessage(messageJson)

            Log.d(TAG, "Board received confirmation sent to host")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending board received confirmation: ${e.message}", e)
        }
    }
}
