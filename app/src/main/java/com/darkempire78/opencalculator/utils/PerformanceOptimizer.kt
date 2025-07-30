package com.darkempire78.opencalculator.utils

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Performance optimization utilities for the calculator
 */
object PerformanceOptimizer {
    
    // Debouncing for text input to reduce calculation frequency
    private var debounceJob: Job? = null
    private const val DEBOUNCE_DELAY_MS = 100L
    
    // Cache for calculation results to avoid repeated computations
    private val calculationCache = ConcurrentHashMap<String, String>()
    private const val MAX_CACHE_SIZE = 100
    
    // Performance counters
    private val calculationCount = AtomicLong(0)
    private val cacheHitCount = AtomicLong(0)
    
    /**
     * Debounce function calls to reduce frequency of expensive operations
     */
    fun debounce(
        scope: CoroutineScope,
        delay: Long = DEBOUNCE_DELAY_MS,
        action: suspend () -> Unit
    ) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delay)
            action()
        }
    }
    
    /**
     * Cache calculation results to avoid repeated computations
     */
    fun getCachedResult(expression: String): String? {
        val result = calculationCache[expression]
        if (result != null) {
            cacheHitCount.incrementAndGet()
        }
        return result
    }
    
    /**
     * Store calculation result in cache
     */
    fun cacheResult(expression: String, result: String) {
        if (calculationCache.size >= MAX_CACHE_SIZE) {
            // Remove oldest entries (simple LRU-like behavior)
            val iterator = calculationCache.iterator()
            repeat(10) { // Remove 10 entries at once
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        calculationCache[expression] = result
        calculationCount.incrementAndGet()
    }
    
    /**
     * Clear cache when memory is needed
     */
    fun clearCache() {
        calculationCache.clear()
    }
    
    /**
     * Get cache statistics for monitoring performance
     */
    fun getCacheStats(): Pair<Long, Long> {
        return Pair(calculationCount.get(), cacheHitCount.get())
    }
    
    /**
     * Optimize string operations by reducing allocations
     */
    fun optimizedStringReplace(
        input: String,
        replacements: Map<String, String>
    ): String {
        if (input.isEmpty() || replacements.isEmpty()) return input
        
        val sb = StringBuilder(input.length)
        var i = 0
        
        while (i < input.length) {
            var found = false
            
            // Check for replacements starting at current position
            for ((from, to) in replacements) {
                if (input.startsWith(from, i)) {
                    sb.append(to)
                    i += from.length
                    found = true
                    break
                }
            }
            
            if (!found) {
                sb.append(input[i])
                i++
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Batch UI updates to reduce layout thrashing
     */
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private var pendingUIUpdate: Runnable? = null
    
    fun batchUIUpdate(action: () -> Unit) {
        pendingUIUpdate?.let { uiUpdateHandler.removeCallbacks(it) }
        
        pendingUIUpdate = Runnable { action() }
        uiUpdateHandler.postDelayed(pendingUIUpdate!!, 16) // ~60fps
    }
    
    /**
     * Memory-efficient number formatting check
     */
    fun needsFormatting(text: String, groupingSeparator: String): Boolean {
        // Quick check if formatting is actually needed
        var digitCount = 0
        var hasGroupingSeparator = false
        
        for (char in text) {
            when {
                char.isDigit() -> digitCount++
                char.toString() == groupingSeparator -> hasGroupingSeparator = true
            }
        }
        
        // Only format if we have enough digits and no existing separators
        return digitCount > 3 && !hasGroupingSeparator
    }
}
