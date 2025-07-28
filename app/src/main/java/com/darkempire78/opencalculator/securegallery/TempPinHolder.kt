package com.darkempire78.opencalculator.securegallery

// Holds pin in memory only while gallery is open
object TempPinHolder {
    var pin: String? = null
    var securityTriggered: Boolean = false
    
    fun clear() { 
        pin = null
        securityTriggered = false
    }
    
    fun clearSecurityTrigger() {
        securityTriggered = false
    }
}
