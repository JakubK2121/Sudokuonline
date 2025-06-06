package com.example.sudokoonline
import android.util.Log

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel


// Możesz ponownie użyć klasy Event z poprzedniego przykładu

open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set // Zezwól na odczyt z zewnątrz, ale nie na zapis

    // Zwraca zawartość i oznacza jako obsłużoną
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    // Zwraca zawartość bez oznaczania jako obsłużona (rzadziej używane)
    fun peekContent(): T = content
}

class WyborGryViewModel : ViewModel() {

    // Nawigacja
    private val _navigateToSinglePlayer = MutableLiveData<Event<Unit>>()
    val navigateToSinglePlayer: LiveData<Event<Unit>> get() = _navigateToSinglePlayer

    private val _navigateToMultiPlayer = MutableLiveData<Event<Unit>>()
    val navigateToMultiPlayer: LiveData<Event<Unit>> get() = _navigateToMultiPlayer

    // Stan Wi-Fi
    private val _promptEnableWifi = MutableLiveData<Event<Unit>>()
    val promptEnableWifi: LiveData<Event<Unit>> get() = _promptEnableWifi

    // --- NOWE STANY DLA UPRAWNIEŃ ---
    // Sygnał do Activity, aby poprosiła o konkretne uprawnienia
    private val _requestPermissionsEvent = MutableLiveData<Event<Array<String>>>()
    val requestPermissionsEvent: LiveData<Event<Array<String>>> get() = _requestPermissionsEvent

    // Sygnał do Activity, że uprawnienia zostały odrzucone
    private val _showPermissionDeniedMessage = MutableLiveData<Event<Unit>>()
    val showPermissionDeniedMessage: LiveData<Event<Unit>> get() = _showPermissionDeniedMessage

    // Sygnał do Activity, że uprawnienia zostały trwale odrzucone (opcjonalnie, można dodać później)
    // private val _showPermissionPermanentlyDeniedMessage = MutableLiveData<Event<Unit>>()
    // val showPermissionPermanentlyDeniedMessage: LiveData<Event<Unit>> get() = _showPermissionPermanentlyDeniedMessage

    // --- ZMIENIONA LOGIKA OBSŁUGI KLIKNIĘCIA ---
    fun onMultiPlayerClicked(isWifiEnabled: Boolean, requiredPermissions: Array<String>, arePermissionsGranted: Boolean) {
        Log.d("WyborGryViewModel", "onMultiPlayerClicked - Wifi: $isWifiEnabled, Permissions Granted: $arePermissionsGranted, Required: ${requiredPermissions.joinToString()}")

        if (!isWifiEnabled) {
            Log.d("WyborGryViewModel", "Wi-Fi disabled. Requesting prompt.")
            _promptEnableWifi.value = Event(Unit)
            return // Zakończ, jeśli Wi-Fi jest wyłączone
        }

        // Wi-Fi jest włączone, sprawdzamy uprawnienia
        if (arePermissionsGranted) {
            Log.d("WyborGryViewModel", "Wi-Fi enabled and permissions granted. Navigating to MultiPlayer.")
            _navigateToMultiPlayer.value = Event(Unit)
        } else {
            // Wi-Fi włączone, ale brakuje uprawnień - poproś o nie
            Log.d("WyborGryViewModel", "Wi-Fi enabled but permissions missing. Requesting permissions.")
            _requestPermissionsEvent.value = Event(requiredPermissions)
            // Tutaj można by dodać logikę sprawdzania, czy pokazać wyjaśnienie (rationale)
            // przed ponownym poproszeniem, ale na razie upraszczamy.
        }
    }

    // --- NOWA METODA DO OBSŁUGI WYNIKU PROŚBY O UPRAWNIENIA ---
    fun onPermissionsResult(grantedPermissions: Map<String, Boolean>) {
        // Sprawdzamy, czy *wszystkie* wymagane uprawnienia zostały przyznane
        // (zakładamy, że Activity poprosiła o te, które były potrzebne)
        val allGranted = grantedPermissions.isNotEmpty() && grantedPermissions.all { it.value }

        Log.d("WyborGryViewModel", "onPermissionsResult - All Granted: $allGranted. Results: $grantedPermissions")

        if (allGranted) {
            // Wszystko OK, możemy nawigować
            Log.d("WyborGryViewModel", "Permissions granted after request. Navigating to MultiPlayer.")
            _navigateToMultiPlayer.value = Event(Unit)
        } else {
            // Użytkownik odrzucił przynajmniej jedno uprawnienie
            Log.d("WyborGryViewModel", "Permissions denied after request. Showing denial message.")
            _showPermissionDeniedMessage.value = Event(Unit)
            // Tutaj można by dodać bardziej złożoną logikę, np. sprawdzić,
            // czy odmowa była trwała ("Don't ask again") i pokazać inny komunikat
            // lub skierować do ustawień aplikacji.
        }
    }

    // Metoda dla Single Player bez zmian
    fun onSinglePlayerClicked() {
        _navigateToSinglePlayer.value = Event(Unit)
    }
}