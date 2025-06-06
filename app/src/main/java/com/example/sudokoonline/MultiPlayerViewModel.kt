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
import kotlinx.coroutines.Dispatchers // Dodano brakujący import

import com.example.sudokoonline.util.Event
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
        Log.d(TAG, "Connection info available. Is Group Owner: ${info.isGroupOwner}, Group Formed: ${info.groupFormed}, Address: ${info.groupOwnerAddress?.hostAddress}")
        currentGroupInfo = info

        if (info.groupFormed) {
            _isHost.value = info.isGroupOwner

            if (info.isGroupOwner) {
                Log.i(TAG, "Lobby successfully created! IP: ${info.groupOwnerAddress?.hostAddress}. Waiting for client to connect socket...")
                updateStatus("Lobby stworzone! IP: ${info.groupOwnerAddress?.hostAddress}. Oczekiwanie na gracza...")
                _isHostButtonEnabled.value = false
                _isDiscoverButtonEnabled.value = false
                _isDisconnectButtonVisible.value = true
                startServerSocket()
            } else {
                Log.i(TAG, "Connected to lobby as client. Host IP: ${info.groupOwnerAddress?.hostAddress}")
                updateStatus("Połączono z lobby jako klient. Host IP: ${info.groupOwnerAddress?.hostAddress}")
                _isHostButtonEnabled.value = false
                _isDiscoverButtonEnabled.value = false
                _isDisconnectButtonVisible.value = true
                connectToHostSocket(info.groupOwnerAddress)
            }
        } else {
            Log.w(TAG, "Connection info available, but group not formed yet.")
            updateStatus("Oczekiwanie na sformowanie grupy...")
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
        Log.d(TAG, "This device details changed: ${device.deviceName}, Status: ${device.statusToString()}")
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
            Log.w(TAG, "Server socket already running or not properly closed.")
            return
        }
        communicationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SOCKET_PORT)
                Log.i(TAG, "Host: ServerSocket started on port $SOCKET_PORT. Waiting for client...")
                withContext(Dispatchers.Main) {
                    updateStatus("Host: Oczekiwanie na połączenie klienta (socket)...")
                }
                clientSocket = serverSocket!!.accept()
                Log.i(TAG, "Host: Client connected! ${clientSocket?.inetAddress?.hostAddress}")
                withContext(Dispatchers.Main) {
                    updateStatus("Host: Klient połączony przez socket! (${clientSocket?.inetAddress?.hostAddress})")
                    _isCommunicationLayoutVisible.value = true
                }
                outputStream = clientSocket?.getOutputStream()
                inputStream = clientSocket?.getInputStream()
                listenForMessages()
            } catch (e: IOException) {
                if (communicationJob?.isActive == true) { // Sprawdzamy job korutyny
                    Log.e(TAG, "Host: ServerSocket IOException: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        updateStatus("Host: Błąd ServerSocket: ${e.message}")
                        showToast("Błąd serwera: ${e.message}")
                        _isCommunicationLayoutVisible.value = false
                    }
                }
            } finally {
                Log.d(TAG, "Host: Server socket communication scope finished.")
            }
        }
    }

    private fun connectToHostSocket(hostAddress: InetAddress?) {
        if (hostAddress == null) {
            Log.e(TAG, "Client: Host address is null, cannot connect socket.")
            updateStatus("Błąd: Brak adresu hosta do połączenia socket.")
            return
        }
        if (clientSocket != null && clientSocket!!.isConnected) {
            Log.w(TAG, "Client socket already connected or not properly closed.")
            return
        }

        communicationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Client: Attempting to connect socket to $hostAddress:$SOCKET_PORT...")
                withContext(Dispatchers.Main) {
                    updateStatus("Klient: Łączenie z hostem przez socket (${hostAddress.hostAddress})...")
                }
                clientSocket = Socket()
                clientSocket!!.connect(InetSocketAddress(hostAddress, SOCKET_PORT), 5000)
                Log.i(TAG, "Client: Connected to host socket!")
                withContext(Dispatchers.Main) {
                    updateStatus("Klient: Połączono z hostem przez socket!")
                    _isCommunicationLayoutVisible.value = true
                }
                outputStream = clientSocket?.getOutputStream()
                inputStream = clientSocket?.getInputStream()
                listenForMessages()
            } catch (e: IOException) {
                if (communicationJob?.isActive == true) { // Sprawdzamy job korutyny
                    Log.e(TAG, "Client: Socket IOException: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        updateStatus("Klient: Błąd połączenia socket: ${e.message}")
                        showToast("Błąd połączenia z hostem: ${e.message}")
                        _isCommunicationLayoutVisible.value = false
                    }
                }
            } finally {
                Log.d(TAG, "Client: Socket communication scope finished.")
            }
        }
    }

    private suspend fun listenForMessages() {
        Log.d(TAG, "Starting to listen for messages...")
        val buffer = ByteArray(1024)
        var bytes: Int

        try {
            while (communicationJob?.isActive == true && inputStream != null) { // Sprawdzamy job korutyny
                bytes = inputStream!!.read(buffer)
                if (bytes > 0) {
                    val received = String(buffer, 0, bytes)
                    Log.i(TAG, "Message received: $received")
                    withContext(Dispatchers.Main) {
                        _receivedMessage.value = received
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

    fun sendMessage(message: String) {
        if (outputStream == null || (clientSocket?.isClosed == true && serverSocket?.isClosed == true)) {
            showToast("Nie można wysłać wiadomości - brak połączenia.")
            Log.w(TAG, "Cannot send message, outputStream is null or socket closed.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending message: $message")
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
                Log.i(TAG, "Message sent: $message")
                withContext(Dispatchers.Main) {
                    showToast("Wiadomość wysłana: $message")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("Błąd wysyłania wiadomości: ${e.message}")
                }
            }
        }
    }

    fun stopSocketCommunication() {
        Log.d(TAG, "stopSocketCommunication called")
        communicationJob?.cancel()
        communicationJob = null
        try {
            outputStream?.close()
            inputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing sockets: ${e.message}", e)
        }
        outputStream = null
        inputStream = null
        clientSocket = null
        serverSocket = null
        Log.d(TAG, "Sockets and streams closed.")
        _isCommunicationLayoutVisible.postValue(false)
    }


    private fun translateP2pError(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "Błąd wewnętrzny P2P"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P nie jest wspierane"
            WifiP2pManager.BUSY -> "Urządzenie P2P zajęte"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "Brak żądań usługi P2P"
            else -> "Nieznany błąd P2P ($reasonCode)"
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
    }
}
