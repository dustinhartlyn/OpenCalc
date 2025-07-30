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
        pin = null
        securityTriggerCount = 0
    }
    
    fun clearSecurityTrigger() {
        val oldCount = securityTriggerCount
        securityTriggerCount = 0
        lastClearTime = System.currentTimeMillis()
        android.util.Log.d("TempPinHolder", "Security trigger cleared: count $oldCount -> 0 at time $lastClearTime")
    }
    
    fun wasRecentlyCleared(): Boolean {
        val timeSinceClear = System.currentTimeMillis() - lastClearTime
        val recentlyCleared = timeSinceClear < 3000
        android.util.Log.d("TempPinHolder", "wasRecentlyCleared check: timeSinceClear=${timeSinceClear}ms, result=$recentlyCleared")
        return recentlyCleared
    }
    
    fun triggerSecurity(reason: String) {
        securityTriggerCount++
        android.util.Log.d("TempPinHolder", "Security triggered: $reason (count: $securityTriggerCount)")
    }
    
    fun getSecurityTriggerCount(): Int = securityTriggerCount
}
