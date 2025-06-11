package com.example.sudokoonline

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.sudokoonline.util.Event

class MainViewModel : ViewModel() {

    // Prywatna MutableLiveData, która może być zmieniana tylko wewnątrz ViewModelu
    private val _navigateToWyborGry = MutableLiveData<Event<Unit>>()

    // Publiczna, niemutowalna LiveData, którą obserwuje Widok (Activity)
    // Używamy 'Unit', bo potrzebujemy tylko sygnału, bez przekazywania danych
    val navigateToWyborGry: LiveData<Event<Unit>>
        get() = _navigateToWyborGry

    // Funkcja wywoływana przez Widok (np. po kliknięciu przycisku)
    fun onNewGameButtonClicked() {
        // Logika (jeśli jakaś jest potrzebna przed nawigacją) mogłaby być tutaj
        // ...

        // Wyślij zdarzenie nawigacji do obserwatorów (Activity)
        _navigateToWyborGry.value = Event(Unit)
    }
}