package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Proper security manager that handles face-down detection without grace periods
 * Uses state-based design instead of timing hacks
 */
class SecurityManager(private val context: Context, private val listener: SecurityEventListener) : SensorEventListener {
    
    interface SecurityEventListener {
        fun onSecurityTrigger(reason: String)
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    // State management - no timing dependencies
    private var isEnabled = false
    private var isFaceDown = false
    private var consecutiveFaceDownReadings = 0
    private var lastLogTime = 0L
    
    // Configuration
    private val requiredConsecutiveReadings = 3 // Must be face-down for 3 consecutive readings
    private val faceDownThreshold = -7.0f
    private val stabilityThreshold = 4.0f
    
    fun enable() {
        if (!isEnabled) {
            accelerometer?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                isEnabled = true
                Log.d("SecurityManager", "Security monitoring enabled")
            }
        }
    }
    
    fun disable() {
        if (isEnabled) {
            sensorManager.unregisterListener(this)
            isEnabled = false
            reset()
            Log.d("SecurityManager", "Security monitoring disabled")
        }
    }
    
    fun isMonitoringEnabled(): Boolean = isEnabled
    
    private fun reset() {
        isFaceDown = false
        consecutiveFaceDownReadings = 0
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // Debug logging every 2 seconds
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 2000) {
            Log.d("SecurityManager", "Sensor: x=$x, y=$y, z=$z, faceDown=$isFaceDown, consecutive=$consecutiveFaceDownReadings")
            lastLogTime = currentTime
        }
        
        // Check if device is face down with stability
        val isCurrentlyFaceDown = z < faceDownThreshold && 
                                  kotlin.math.abs(x) < stabilityThreshold && 
                                  kotlin.math.abs(y) < stabilityThreshold
        
        if (isCurrentlyFaceDown) {
            consecutiveFaceDownReadings++
            if (consecutiveFaceDownReadings >= requiredConsecutiveReadings && !isFaceDown) {
                isFaceDown = true
                Log.d("SecurityManager", "Face-down security trigger (x=$x, y=$y, z=$z)")
                listener.onSecurityTrigger("Face-down detected")
            }
        } else {
            // Reset if device is not face-down
            if (consecutiveFaceDownReadings > 0) {
                Log.d("SecurityManager", "Face-down state reset")
            }
            consecutiveFaceDownReadings = 0
            isFaceDown = false
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }
}
