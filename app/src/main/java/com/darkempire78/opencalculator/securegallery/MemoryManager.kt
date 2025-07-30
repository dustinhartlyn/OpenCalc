package com.darkempire78.opencalculator.securegallery

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive memory management system for secure gallery
 * Handles bitmap caching, video streaming, and memory pressure
 */
object MemoryManager {
    private const val TAG = "MemoryManager"
    
    // Memory thresholds
    private const val LOW_MEMORY_THRESHOLD = 0.85f // 85% of max heap
    private const val CRITICAL_MEMORY_THRESHOLD = 0.95f // 95% of max heap
    private const val MAX_VIDEO_SIZE_MB = 50 // Maximum video size to load fully into memory
    
    // Bitmap cache with LRU eviction
    private var bitmapCache: LruCache<String, Bitmap>? = null
    private val maxCacheSize = (Runtime.getRuntime().maxMemory() / 8).toInt() // 1/8 of max memory
    
    // Video chunk cache for streaming
    private val videoChunkCache = ConcurrentHashMap<String, ByteArray>()
    private const val VIDEO_CHUNK_SIZE = 1024 * 1024 // 1MB chunks
    
    // Weak references to active bitmaps to prevent recycling while in use
    private val activeBitmaps = ConcurrentHashMap<String, WeakReference<Bitmap>>()
    
    fun initialize(context: Context) {
        if (bitmapCache == null) {
            bitmapCache = object : LruCache<String, Bitmap>(maxCacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount
                }
                
                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    oldValue: Bitmap,
                    newValue: Bitmap?
                ) {
                    // Only recycle if not actively being used
                    if (evicted && !isActiveBitmap(key)) {
                        if (!oldValue.isRecycled) {
                            oldValue.recycle()
                            Log.d(TAG, "Recycled evicted bitmap: $key")
                        }
                    }
                }
            }
            Log.d(TAG, "Memory manager initialized with cache size: ${maxCacheSize / 1024 / 1024}MB")
        }
    }
    
    /**
     * Get current memory usage percentage
     */
    fun getMemoryUsage(): Float {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return usedMemory.toFloat() / maxMemory.toFloat()
    }
    
    /**
     * Check if we're in low memory situation
     */
    fun isLowMemory(): Boolean = getMemoryUsage() > LOW_MEMORY_THRESHOLD
    
    /**
     * Check if we're in critical memory situation
     */
    fun isCriticalMemory(): Boolean = getMemoryUsage() > CRITICAL_MEMORY_THRESHOLD
    
    /**
     * Force memory cleanup when needed
     */
    fun forceMemoryCleanup() {
        Log.d(TAG, "Forcing memory cleanup - Memory usage: ${(getMemoryUsage() * 100).toInt()}%")
        
        // Clear video chunk cache first
        videoChunkCache.clear()
        
        // Trim bitmap cache by 50%
        bitmapCache?.trimToSize(maxCacheSize / 2)
        
        // Force garbage collection
        System.gc()
        
        Log.d(TAG, "Memory cleanup completed - New usage: ${(getMemoryUsage() * 100).toInt()}%")
    }
    
    /**
     * Safe bitmap loading with memory checks
     */
    fun loadBitmapSafely(
        file: File,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080,
        cacheKey: String? = null
    ): Bitmap? {
        // Check memory before loading
        if (isCriticalMemory()) {
            forceMemoryCleanup()
            if (isCriticalMemory()) {
                Log.w(TAG, "Critical memory - skipping bitmap load")
                return null
            }
        }
        
        // Check cache first
        cacheKey?.let { key ->
            bitmapCache?.get(key)?.let { cached ->
                if (!cached.isRecycled) {
                    markBitmapActive(key, cached)
                    return cached
                }
            }
        }
        
        try {
            // Load with size constraints
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
            
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            
            // Cache if successful
            if (bitmap != null && cacheKey != null) {
                bitmapCache?.put(cacheKey, bitmap)
                markBitmapActive(cacheKey, bitmap)
            }
            
            return bitmap
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError loading bitmap: ${file.name}")
            forceMemoryCleanup()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${file.name}", e)
            return null
        }
    }
    
    /**
     * Mark bitmap as actively being used to prevent recycling
     */
    fun markBitmapActive(key: String, bitmap: Bitmap) {
        activeBitmaps[key] = WeakReference(bitmap)
    }
    
    /**
     * Unmark bitmap as active (safe to recycle)
     */
    fun unmarkBitmapActive(key: String) {
        activeBitmaps.remove(key)
    }
    
    /**
     * Check if bitmap is actively being used
     */
    private fun isActiveBitmap(key: String): Boolean {
        val ref = activeBitmaps[key]
        val bitmap = ref?.get()
        return bitmap != null && !bitmap.isRecycled
    }
    
    /**
     * Calculate appropriate sample size for bitmap loading
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight &&
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Check if video file should be streamed vs loaded fully
     */
    fun shouldStreamVideo(file: File): Boolean {
        val sizeInMB = file.length() / (1024 * 1024)
        return sizeInMB > MAX_VIDEO_SIZE_MB || isLowMemory()
    }
    
    /**
     * Stream video in chunks to prevent memory overload
     */
    fun getVideoChunk(file: File, chunkIndex: Int): ByteArray? {
        val cacheKey = "${file.name}_chunk_$chunkIndex"
        
        // Check cache first
        videoChunkCache[cacheKey]?.let { return it }
        
        try {
            FileInputStream(file).use { fis ->
                val skipBytes = chunkIndex * VIDEO_CHUNK_SIZE.toLong()
                fis.skip(skipBytes)
                
                val buffer = ByteArray(VIDEO_CHUNK_SIZE)
                val bytesRead = fis.read(buffer)
                
                if (bytesRead > 0) {
                    val chunk = if (bytesRead < VIDEO_CHUNK_SIZE) {
                        buffer.copyOf(bytesRead)
                    } else {
                        buffer
                    }
                    
                    // Cache chunk if not low on memory
                    if (!isLowMemory()) {
                        videoChunkCache[cacheKey] = chunk
                    }
                    
                    return chunk
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading video chunk", e)
        }
        
        return null
    }
    
    /**
     * Clear all caches and cleanup memory
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up memory manager")
        
        // Clear all caches
        bitmapCache?.evictAll()
        videoChunkCache.clear()
        activeBitmaps.clear()
        
        // Force garbage collection
        System.gc()
    }
    
    /**
     * Get memory statistics for debugging
     */
    fun getMemoryStats(): String {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val cacheSize = bitmapCache?.size() ?: 0
        val videoChunks = videoChunkCache.size
        
        return "Memory: ${usedMemory / 1024 / 1024}MB/${maxMemory / 1024 / 1024}MB " +
               "(${(usedMemory.toFloat() / maxMemory * 100).toInt()}%) " +
               "Cache: ${cacheSize / 1024 / 1024}MB, VideoChunks: $videoChunks"
    }
}
