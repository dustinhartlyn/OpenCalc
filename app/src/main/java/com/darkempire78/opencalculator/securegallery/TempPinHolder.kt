package com.darkempire78.opencalculator.securegallery

// Holds pin in memory only while gallery is open
object TempPinHolder {
    var pin: String? = null
    private var securityTriggerCount: Int = 0
    private var lastClearTime: Long = 0L
    
    // Public read-only property for backward compatibility
    val securityTriggered: Boolean
        get() = securityTriggerCount > 0
    
    fun clear() { 
        android.util.Log.d("SecureGallery", "TempPinHolder.clear() called - PIN was: '${pin ?: "null"}', clearing now")
        pin = null
        securityTriggerCount = 0
    }
    
    fun clearSecurityTrigger() {
        securityTriggerCount = 0
        lastClearTime = System.currentTimeMillis()
    }
    
    fun wasRecentlyCleared(): Boolean {
        val timeSinceClear = System.currentTimeMillis() - lastClearTime
        return timeSinceClear < 3000
    }
    
    fun triggerSecurity(reason: String) {
        securityTriggerCount++
    }
    
    fun getSecurityTriggerCount(): Int = securityTriggerCount
}
