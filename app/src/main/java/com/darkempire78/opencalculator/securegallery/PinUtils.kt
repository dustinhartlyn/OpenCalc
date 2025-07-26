package com.darkempire78.opencalculator.securegallery

object PinUtils {
    fun isValidPin(pin: String): Boolean {
        return pin.length in 4..30 && pin.all { it.isDigit() }
    }
}
