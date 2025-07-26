package com.darkempire78.opencalculator.securegallery

object PinAttemptManager {
    private var failedAttempts = 0
    private var cooldownEnd: Long = 0
    private const val MAX_ATTEMPTS = 3
    private const val COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes

    fun canAttempt(): Boolean {
        return System.currentTimeMillis() > cooldownEnd
    }

    fun registerFailure() {
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            cooldownEnd = System.currentTimeMillis() + COOLDOWN_MS
            failedAttempts = 0
        }
    }

    fun reset() {
        failedAttempts = 0
        cooldownEnd = 0
    }
}
