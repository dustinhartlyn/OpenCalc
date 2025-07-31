package com.darkempire78.opencalculator.securegallery

// Holds pin in memory only while gallery is open
object TempPinHolder {
    private var _pin: String? = null
    var pin: String?
        get() = _pin
        set(value) {
            val stackTrace = Thread.currentThread().stackTrace
            val caller = if (stackTrace.size > 3) "${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}" else "unknown"
            android.util.Log.d("SecureGallery", "TempPinHolder.pin set from '${_pin ?: "null"}' to '${value ?: "null"}' by $caller")
            _pin = value
        }
    private var securityTriggerCount: Int = 0
    private var lastClearTime: Long = 0L
    
    // Public read-only property for backward compatibility
    val securityTriggered: Boolean
        get() = securityTriggerCount > 0
    
    fun clear() { 
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) "${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}" else "unknown"
        android.util.Log.d("SecureGallery", "TempPinHolder.clear() called - PIN was: '${_pin ?: "null"}', called by: $caller")
        _pin = null
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
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) "${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}" else "unknown"
        android.util.Log.d("SecureGallery", "TempPinHolder.triggerSecurity('$reason') called by $caller - count: $securityTriggerCount -> ${securityTriggerCount + 1}")
        securityTriggerCount++
    }
    
    fun getSecurityTriggerCount(): Int = securityTriggerCount
}
