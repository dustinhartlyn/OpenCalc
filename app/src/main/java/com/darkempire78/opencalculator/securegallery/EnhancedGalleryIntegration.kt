package com.darkempire78.opencalculator.securegallery

import android.app.ProgressDialog
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*

/**
 * Enhanced gallery integration that fixes memory issues and improves progress handling
 * Replaces problematic parts of GalleryActivity with memory-safe implementations
 */
class EnhancedGalleryIntegration(private val context: Context) {
    private val TAG = "EnhancedGalleryIntegration"
    
    // Memory-safe managers
    private val memoryManager = MemoryManager
    private val videoManager = SecureVideoManager(context)
    private var progressManager: MediaImportProgressManager? = null
    
    // Progress UI elements
    private var progressDialog: ProgressDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    
    // Thumbnail management
    private val safeThumbnailCache = mutableMapOf<String, Bitmap>()
    private val activeThumbnails = mutableSetOf<String>()
    
    init {
        memoryManager.initialize(context)
        Log.d(TAG, "Enhanced gallery integration initialized")
    }
    
    /**
     * Start media import with comprehensive progress tracking
     */
    fun startMediaImport(
        mediaUris: List<android.net.Uri>,
        galleryName: String,
        encryptionKey: String,
        onComplete: (successful: Int, failed: Int) -> Unit,
        onError: (error: String) -> Unit
    ) {
        
        Log.d(TAG, "Starting enhanced media import for ${mediaUris.size} items")
        
        // Initialize progress manager
        progressManager = MediaImportProgressManager(context)
        
        // Show enhanced progress dialog
        showProgressDialog("Importing Media", "Preparing import...")
        
        // Set up progress callbacks
        val progressCallback = object : MediaImportProgressManager.ProgressCallback {
            override fun onProgressUpdate(progress: MediaImportProgressManager.ImportProgress) {
                updateProgressDialog(progress)
            }
            
            override fun onPhaseChanged(newPhase: MediaImportProgressManager.ImportPhase) {
                val phaseText = when (newPhase) {
                    MediaImportProgressManager.ImportPhase.ENCRYPTING -> "Encrypting files..."
                    MediaImportProgressManager.ImportPhase.GENERATING_THUMBNAILS -> "Generating thumbnails..."
                    MediaImportProgressManager.ImportPhase.FINALIZING -> "Finalizing import..."
                }
                updateProgressText(phaseText)
            }
            
            override fun onCompleted(totalProcessed: Int, totalFailed: Int) {
                hideProgressDialog()
                onComplete(totalProcessed, totalFailed)
                Log.d(TAG, "Import completed: $totalProcessed successful, $totalFailed failed")
            }
            
            override fun onError(error: String, itemName: String?) {
                Log.e(TAG, "Import error: $error for item: $itemName")
                onError("$error${itemName?.let { " ($it)" } ?: ""}")
            }
        }
        
        // Start import
        progressManager?.startMediaImport(mediaUris, galleryName, encryptionKey, progressCallback)
    }
    
    /**
     * Generate thumbnail safely with memory management
     */
    suspend fun generateThumbnailSafely(
        encryptedFile: java.io.File,
        encryptionKey: String,
        mediaType: MediaType,
        thumbnailSize: Int = 320
    ): Bitmap? = withContext(Dispatchers.IO) {
        
        val cacheKey = "${encryptedFile.name}_thumb_$thumbnailSize"
        
        // Check if already cached and active
        safeThumbnailCache[cacheKey]?.let { cached ->
            if (!cached.isRecycled && activeThumbnails.contains(cacheKey)) {
                Log.d(TAG, "Using safe cached thumbnail: $cacheKey")
                return@withContext cached
            } else {
                // Remove invalid cache entry
                safeThumbnailCache.remove(cacheKey)
                activeThumbnails.remove(cacheKey)
            }
        }
        
        // Check memory before generating
        if (memoryManager.isCriticalMemory()) {
            Log.w(TAG, "Critical memory - skipping thumbnail generation for: ${encryptedFile.name}")
            return@withContext null
        }
        
        try {
            val thumbnail = when (mediaType) {
                MediaType.VIDEO -> {
                    videoManager.generateVideoThumbnail(
                        encryptedFile, 
                        encryptionKey, 
                        thumbnailSize, 
                        thumbnailSize
                    )
                }
                MediaType.PHOTO -> {
                    generatePhotoThumbnailSafely(encryptedFile, encryptionKey, thumbnailSize)
                }
            }
            
            // Cache safely
            thumbnail?.let {
                if (safeThumbnailCache.size >= 30) { // Limit cache size
                    // Remove oldest inactive thumbnail
                    val oldestInactive = safeThumbnailCache.keys.find { !activeThumbnails.contains(it) }
                    oldestInactive?.let { key ->
                        safeThumbnailCache.remove(key)?.let { oldBitmap ->
                            if (!oldBitmap.isRecycled) {
                                oldBitmap.recycle()
                                Log.d(TAG, "Recycled old thumbnail: $key")
                            }
                        }
                    }
                }
                
                safeThumbnailCache[cacheKey] = it
                activeThumbnails.add(cacheKey)
                memoryManager.markBitmapActive(cacheKey, it)
                Log.d(TAG, "Generated and cached thumbnail: $cacheKey")
            }
            
            thumbnail
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError generating thumbnail for: ${encryptedFile.name}")
            memoryManager.forceMemoryCleanup()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for: ${encryptedFile.name}", e)
            null
        }
    }
    
    /**
     * Generate photo thumbnail with memory safety
     */
    private suspend fun generatePhotoThumbnailSafely(
        encryptedFile: java.io.File,
        encryptionKey: String,
        thumbnailSize: Int
    ): Bitmap? {
        
        return try {
            // For photos, decrypt and load with size constraints
            val decryptedData = CryptoUtils.decryptData(encryptedFile.readBytes(), encryptionKey)
            
            // Create temp file for bitmap loading
            val tempFile = java.io.File.createTempFile("photo_thumb", ".jpg")
            tempFile.writeBytes(decryptedData)
            
            // Load bitmap safely
            val thumbnail = memoryManager.loadBitmapSafely(
                tempFile, 
                thumbnailSize, 
                thumbnailSize, 
                "${encryptedFile.name}_photo_thumb"
            )
            
            // Cleanup temp file
            tempFile.delete()
            
            thumbnail
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating photo thumbnail", e)
            null
        }
    }
    
    /**
     * Mark thumbnail as active (in use by UI)
     */
    fun markThumbnailActive(cacheKey: String) {
        activeThumbnails.add(cacheKey)
        safeThumbnailCache[cacheKey]?.let { bitmap ->
            memoryManager.markBitmapActive(cacheKey, bitmap)
        }
    }
    
    /**
     * Mark thumbnail as inactive (safe to recycle)
     */
    fun markThumbnailInactive(cacheKey: String) {
        activeThumbnails.remove(cacheKey)
        memoryManager.unmarkBitmapActive(cacheKey)
    }
    
    /**
     * Get thumbnail safely without risk of recycled bitmap
     */
    fun getThumbnailSafely(cacheKey: String): Bitmap? {
        val bitmap = safeThumbnailCache[cacheKey]
        return if (bitmap != null && !bitmap.isRecycled) {
            markThumbnailActive(cacheKey)
            bitmap
        } else {
            safeThumbnailCache.remove(cacheKey)
            activeThumbnails.remove(cacheKey)
            null
        }
    }
    
    /**
     * Show enhanced progress dialog
     */
    private fun showProgressDialog(title: String, message: String) {
        try {
            hideProgressDialog() // Ensure no duplicate dialogs
            
            progressDialog = ProgressDialog(context).apply {
                setTitle(title)
                setMessage(message)
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                setCancelable(false)
                max = 100
                show()
            }
            
            Log.d(TAG, "Progress dialog shown: $title")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing progress dialog", e)
        }
    }
    
    /**
     * Update progress dialog with comprehensive information
     */
    private fun updateProgressDialog(progress: MediaImportProgressManager.ImportProgress) {
        try {
            progressDialog?.let { dialog ->
                if (dialog.isShowing) {
                    val progressPercent = (progress.overallProgress * 100).toInt()
                    dialog.progress = progressPercent
                    
                    val phaseText = when (progress.phase) {
                        MediaImportProgressManager.ImportPhase.ENCRYPTING -> "Encrypting"
                        MediaImportProgressManager.ImportPhase.GENERATING_THUMBNAILS -> "Generating Thumbnails"
                        MediaImportProgressManager.ImportPhase.FINALIZING -> "Finalizing"
                    }
                    
                    val message = "$phaseText (${progress.currentItem}/${progress.totalItems})\n" +
                                 "${progress.currentItemName}\n" +
                                 "Time remaining: ${progress.estimatedTimeRemaining}\n" +
                                 "Memory: ${memoryManager.getMemoryStats()}"
                    
                    dialog.setMessage(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress dialog", e)
        }
    }
    
    /**
     * Update progress text
     */
    private fun updateProgressText(text: String) {
        try {
            progressText?.text = text
            Log.d(TAG, "Progress text updated: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress text", e)
        }
    }
    
    /**
     * Hide progress dialog
     */
    private fun hideProgressDialog() {
        try {
            progressDialog?.let { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
            progressDialog = null
            Log.d(TAG, "Progress dialog hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding progress dialog", e)
        }
    }
    
    /**
     * Prepare video for viewing with memory safety
     */
    suspend fun prepareVideoForViewing(
        encryptedFile: java.io.File,
        encryptionKey: String
    ): java.io.File? {
        
        return try {
            Log.d(TAG, "Preparing video for viewing: ${encryptedFile.name}")
            
            // Check memory before processing
            if (memoryManager.isCriticalMemory()) {
                memoryManager.forceMemoryCleanup()
                if (memoryManager.isCriticalMemory()) {
                    Log.w(TAG, "Critical memory - cannot prepare video")
                    return null
                }
            }
            
            val preparedFile = videoManager.prepareVideoForPlayback(encryptedFile, encryptionKey)
            
            if (preparedFile != null) {
                Log.d(TAG, "Video prepared successfully: ${preparedFile.name}")
            } else {
                Log.w(TAG, "Failed to prepare video: ${encryptedFile.name}")
            }
            
            preparedFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing video for viewing", e)
            null
        }
    }
    
    /**
     * Get memory usage information for debugging
     */
    fun getMemoryInfo(): String {
        return memoryManager.getMemoryStats()
    }
    
    /**
     * Force memory cleanup when needed
     */
    fun forceMemoryCleanup() {
        Log.d(TAG, "Forcing memory cleanup")
        
        // Clear inactive thumbnails
        val inactiveKeys = safeThumbnailCache.keys.filter { !activeThumbnails.contains(it) }
        inactiveKeys.forEach { key ->
            safeThumbnailCache.remove(key)?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        
        // Force system cleanup
        memoryManager.forceMemoryCleanup()
        
        Log.d(TAG, "Memory cleanup completed - ${getMemoryInfo()}")
    }
    
    /**
     * Cleanup all resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up enhanced gallery integration")
        
        // Cancel any ongoing operations
        progressManager?.cleanup()
        
        // Hide progress dialog
        hideProgressDialog()
        
        // Clear all thumbnails
        safeThumbnailCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        safeThumbnailCache.clear()
        activeThumbnails.clear()
        
        // Cleanup managers
        videoManager.cleanup()
        memoryManager.cleanup()
        
        Log.d(TAG, "Enhanced gallery integration cleanup completed")
    }
}
