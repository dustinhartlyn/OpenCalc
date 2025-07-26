package com.darkempire78.opencalculator.securegallery

// Holds pin in memory only while gallery is open
object TempPinHolder {
    var pin: String? = null
    fun clear() { pin = null }
}
