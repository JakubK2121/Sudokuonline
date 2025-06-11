package com.example.sudokoonline.multiplayer.model

/**
 * Klasa reprezentująca możliwe stany gry wieloosobowej
 */
enum class GameStatus {
    WAITING_FOR_PLAYERS,  // Oczekiwanie na graczy
    STARTING,            // Gra się rozpoczyna, tablica jest przygotowywana
    IN_PROGRESS,         // Gra w trakcie
    FINISHED             // Gra zakończona
}

/**
 * Klasa reprezentująca stan gry wieloosobowej.
 *
 * @property status Aktualny status gry
 * @property startTime Czas rozpoczęcia gry (w milisekundach)
 * @property endTime Czas zakończenia gry (w milisekundach), null jeśli gra nie jest zakończona
 * @property players Lista stanów graczy biorących udział w grze
 */
data class GameState(
    var status: GameStatus = GameStatus.WAITING_FOR_PLAYERS,
    var startTime: Long = 0,
    var endTime: Long? = null,
    val players: MutableList<PlayerState> = mutableListOf(),
    var winnerDeviceId: String? = null
) {
    /**
     * Sprawdzenie czy gra się zakończyła
     */
    val isFinished: Boolean
        get() = status == GameStatus.FINISHED

    /**
     * Sprawdzenie czy gra jest w trakcie
     */
    val isInProgress: Boolean
        get() = status == GameStatus.IN_PROGRESS

    /**
     * Dodaje nowego gracza do gry
     *
     * @param deviceId Unikalny identyfikator urządzenia gracza
     * @param deviceName Nazwa urządzenia/gracza
     * @return Stan gracza, który został utworzony i dodany
     */
    fun addPlayer(deviceId: String, deviceName: String): PlayerState {
        val player = PlayerState(deviceId, deviceName)
        players.add(player)
        return player
    }

    /**
     * Rozpoczyna grę, ustawiając odpowiedni status i zapisując czas rozpoczęcia
     */
    fun start() {
        status = GameStatus.IN_PROGRESS
        startTime = System.currentTimeMillis()
    }

    /**
     * Kończy grę, ustawiając odpowiedni status i zapisując czas zakończenia
     *
     * @param winnerDeviceId Identyfikator urządzenia gracza, który wygrał
     */
    fun finish(winnerDeviceId: String) {
        status = GameStatus.FINISHED
        endTime = System.currentTimeMillis()
        this.winnerDeviceId = winnerDeviceId
    }

    /**
     * Aktualizuje postęp gracza
     *
     * @param deviceId Identyfikator urządzenia gracza
     * @param completionPercentage Nowy procent ukończenia planszy
     * @return true jeśli udało się zaktualizować, false jeśli nie znaleziono gracza
     */
    fun updatePlayerProgress(deviceId: String, completionPercentage: Int): Boolean {
        val player = players.find { it.deviceId == deviceId }
        return if (player != null) {
            player.completionPercentage = completionPercentage
            true
        } else {
            false
        }
    }

    /**
     * Zwraca stan gracza o podanym ID
     *
     * @param deviceId Identyfikator urządzenia gracza
     * @return Stan gracza lub null jeśli nie znaleziono
     */
    fun getPlayerState(deviceId: String): PlayerState? {
        return players.find { it.deviceId == deviceId }
    }
}
