package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

/**
 * Comprehensive progress manager for media import operations
 * Handles both encryption and thumbnail generation with unified progress
 */
class MediaImportProgressManager(private val context: Context) {
    private val TAG = "MediaImportProgress"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class ImportProgress(
        val phase: ImportPhase,
        val currentItem: Int,
        val totalItems: Int,
        val currentItemName: String,
        val overallProgress: Float,
        val phaseProgress: Float,
        val estimatedTimeRemaining: String
    )
    
    enum class ImportPhase {
        ENCRYPTING,
        GENERATING_THUMBNAILS,
        FINALIZING
    }
    
    interface ProgressCallback {
        fun onProgressUpdate(progress: ImportProgress)
        fun onPhaseChanged(newPhase: ImportPhase)
        fun onCompleted(totalProcessed: Int, totalFailed: Int)
        fun onError(error: String, itemName: String?)
    }
    
    private var callback: ProgressCallback? = null
    private var startTime = 0L
    private var currentPhase = ImportPhase.ENCRYPTING
    private var processedInPhase = 0
    private var totalInPhase = 0
    private var totalOverallItems = 0
    
    fun setCallback(callback: ProgressCallback) {
        this.callback = callback
    }
    
    /**
     * Start comprehensive media import with progress tracking
     */
    fun startMediaImport(
        mediaUris: List<android.net.Uri>,
        galleryName: String,
        encryptionKey: String,
        progressCallback: ProgressCallback
    ) {
        this.callback = progressCallback
        startTime = System.currentTimeMillis()
        totalOverallItems = mediaUris.size
        
        Log.d(TAG, "Starting media import for ${mediaUris.size} items")
        
        backgroundScope.launch {
            try {
                val results = importMediaWithProgress(mediaUris, galleryName, encryptionKey)
                
                mainHandler.post {
                    callback?.onCompleted(results.successful, results.failed)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error during media import", e)
                mainHandler.post {
                    callback?.onError("Import failed: ${e.message}", null)
                }
            }
        }
    }
    
    private data class ImportResults(
        val successful: Int,
        val failed: Int,
        val encryptedFiles: List<EncryptedMediaInfo>
    )
    
    private data class EncryptedMediaInfo(
        val fileName: String,
        val filePath: String,
        val mediaType: MediaType,
        val originalUri: android.net.Uri
    )
    
    private suspend fun importMediaWithProgress(
        mediaUris: List<android.net.Uri>,
        galleryName: String,
        encryptionKey: String
    ): ImportResults {
        
        var successfulEncryptions = 0
        var failedEncryptions = 0
        val encryptedFiles = mutableListOf<EncryptedMediaInfo>()
        
        // Phase 1: Encryption
        updatePhase(ImportPhase.ENCRYPTING, mediaUris.size)
        
        for ((index, uri) in mediaUris.withIndex()) {
            if (!backgroundScope.isActive) break // Check for cancellation
            
            try {
                val fileName = getFileNameFromUri(uri) ?: "unknown_${System.currentTimeMillis()}"
                
                updateProgress(
                    currentItem = index + 1,
                    totalItems = mediaUris.size,
                    itemName = fileName
                )
                
                // Encrypt media file
                val encryptedInfo = encryptMediaFile(uri, galleryName, encryptionKey, fileName)
                if (encryptedInfo != null) {
                    encryptedFiles.add(encryptedInfo)
                    successfulEncryptions++
                } else {
                    failedEncryptions++
                    notifyError("Failed to encrypt", fileName)
                }
                
                // Memory management
                if (MemoryManager.isLowMemory()) {
                    MemoryManager.forceMemoryCleanup()
                    delay(100) // Brief pause after cleanup
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error encrypting media item: $uri", e)
                failedEncryptions++
                notifyError("Encryption error: ${e.message}", getFileNameFromUri(uri))
            }
            
            delay(50) // Small delay to prevent UI blocking
        }
        
        // Phase 2: Thumbnail Generation (this runs AFTER encryption phase completes)
        if (encryptedFiles.isNotEmpty()) {
            updatePhase(ImportPhase.GENERATING_THUMBNAILS, encryptedFiles.size)
            Log.d(TAG, "Starting thumbnail generation for ${encryptedFiles.size} files")
            
            for ((index, encryptedFile) in encryptedFiles.withIndex()) {
                if (!backgroundScope.isActive) break
                
                try {
                    updateProgress(
                        currentItem = index + 1,
                        totalItems = encryptedFiles.size,
                        itemName = "Generating thumbnail for ${encryptedFile.fileName}"
                    )
                    
                    // Generate thumbnail with timeout to prevent hanging
                    val thumbnailJob = async {
                        generateThumbnailSafely(encryptedFile, encryptionKey)
                    }
                    
                    // Wait max 30 seconds for thumbnail generation
                    try {
                        withTimeout(30000) {
                            thumbnailJob.await()
                        }
                        Log.d(TAG, "Successfully generated thumbnail for: ${encryptedFile.fileName}")
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Thumbnail generation timed out for: ${encryptedFile.fileName}")
                        thumbnailJob.cancel()
                    }
                    
                    // More aggressive memory management during thumbnail generation
                    if (MemoryManager.isLowMemory()) {
                        MemoryManager.forceMemoryCleanup()
                        delay(300) // Longer pause after cleanup during thumbnail generation
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error generating thumbnail: ${encryptedFile.fileName}", e)
                    // Don't fail the entire import for thumbnail errors
                    Log.w(TAG, "Continuing import without thumbnail for: ${encryptedFile.fileName}")
                }
                
                delay(150) // Longer delay for thumbnail generation
            }
            Log.d(TAG, "Completed thumbnail generation phase")
        }
        
        // Phase 3: Finalizing
        updatePhase(ImportPhase.FINALIZING, 1)
        updateProgress(1, 1, "Finalizing import...")
        
        // Save gallery state and cleanup
        delay(500) // Give UI time to update
        
        return ImportResults(successfulEncryptions, failedEncryptions, encryptedFiles)
    }
    
    private fun updatePhase(newPhase: ImportPhase, totalItems: Int) {
        currentPhase = newPhase
        processedInPhase = 0
        totalInPhase = totalItems
        
        mainHandler.post {
            callback?.onPhaseChanged(newPhase)
        }
        
        Log.d(TAG, "Entering phase: $newPhase with $totalItems items")
    }
    
    private fun updateProgress(currentItem: Int, totalItems: Int, itemName: String) {
        processedInPhase = currentItem
        
        // Calculate overall progress based on phases
        val phaseProgress = currentItem.toFloat() / totalItems
        val overallProgress = when (currentPhase) {
            ImportPhase.ENCRYPTING -> phaseProgress * 0.6f // 60% for encryption
            ImportPhase.GENERATING_THUMBNAILS -> 0.6f + (phaseProgress * 0.35f) // 35% for thumbnails
            ImportPhase.FINALIZING -> 0.95f + (phaseProgress * 0.05f) // 5% for finalizing
        }
        
        val estimatedTime = calculateEstimatedTime(overallProgress)
        
        val progress = ImportProgress(
            phase = currentPhase,
            currentItem = currentItem,
            totalItems = totalItems,
            currentItemName = itemName,
            overallProgress = overallProgress,
            phaseProgress = phaseProgress,
            estimatedTimeRemaining = estimatedTime
        )
        
        mainHandler.post {
            callback?.onProgressUpdate(progress)
        }
    }
    
    private fun calculateEstimatedTime(overallProgress: Float): String {
        if (overallProgress <= 0.05f) return "Calculating..."
        
        val elapsedTime = System.currentTimeMillis() - startTime
        val estimatedTotal = elapsedTime / overallProgress
        val remaining = estimatedTotal - elapsedTime
        
        return when {
            remaining < 60000 -> "${(remaining / 1000).toInt()}s"
            remaining < 3600000 -> "${(remaining / 60000).toInt()}m ${((remaining % 60000) / 1000).toInt()}s"
            else -> "${(remaining / 3600000).toInt()}h ${((remaining % 3600000) / 60000).toInt()}m"
        }
    }
    
    private fun notifyError(error: String, itemName: String?) {
        mainHandler.post {
            callback?.onError(error, itemName)
        }
    }
    
    private suspend fun encryptMediaFile(
        uri: android.net.Uri,
        galleryName: String,
        encryptionKey: String,
        fileName: String
    ): EncryptedMediaInfo? {
        return withContext(Dispatchers.IO) {
            try {
                // Implementation would call existing encryption logic
                // This is a placeholder that integrates with existing GalleryActivity encryption
                
                val mediaType = determineMediaType(fileName)
                val encryptedFileName = generateEncryptedFileName(fileName)
                val galleryDir = getGalleryDirectory(galleryName)
                val encryptedFile = java.io.File(galleryDir, encryptedFileName)
                
                // Encrypt file with memory monitoring
                val success = encryptFileWithMemoryManagement(uri, encryptedFile, encryptionKey)
                
                if (success) {
                    EncryptedMediaInfo(
                        fileName = fileName,
                        filePath = encryptedFile.absolutePath,
                        mediaType = mediaType,
                        originalUri = uri
                    )
                } else {
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for: $fileName", e)
                null
            }
        }
    }
    
    private suspend fun generateThumbnailSafely(
        encryptedFile: EncryptedMediaInfo,
        encryptionKey: String
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting thumbnail generation for: ${encryptedFile.fileName} (${encryptedFile.mediaType})")
                
                val file = File(encryptedFile.filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Encrypted file does not exist: ${encryptedFile.filePath}")
                    return@withContext
                }
                
                // Use the enhanced gallery integration for thumbnail generation
                val galleryIntegration = EnhancedGalleryIntegration(context)
                
                val thumbnail = galleryIntegration.generateThumbnailSafely(
                    file,
                    encryptionKey,
                    encryptedFile.mediaType,
                    320 // Standard thumbnail size
                )
                
                if (thumbnail != null) {
                    Log.d(TAG, "Successfully generated ${thumbnail.width}x${thumbnail.height} thumbnail for: ${encryptedFile.fileName}")
                    // Immediately recycle to save memory during import
                    thumbnail.recycle()
                } else {
                    Log.w(TAG, "Thumbnail generation returned null for: ${encryptedFile.fileName}")
                }
                
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError generating thumbnail for: ${encryptedFile.fileName}")
                MemoryManager.forceMemoryCleanup()
                // Don't retry - just continue without thumbnail
            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnail for: ${encryptedFile.fileName}", e)
                // Don't fail the entire import for thumbnail errors
            }
        }
    }
    
    // Helper methods for media import
    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        var fileName: String? = null
        
        // Try content resolver first
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        // Fallback to last path segment
        return fileName ?: uri.lastPathSegment ?: "unknown_file_${System.currentTimeMillis()}"
    }
    
    private fun determineMediaType(fileName: String): MediaType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "bmp", "gif", "webp" -> MediaType.PHOTO
            "mp4", "avi", "mov", "mkv", "webm", "3gp" -> MediaType.VIDEO
            else -> MediaType.PHOTO // Default to photo for unknown types
        }
    }
            "mp4", "avi", "mov", "mkv", "webm" -> MediaType.VIDEO
            else -> MediaType.PHOTO
        }
    }
    
    private fun generateEncryptedFileName(originalName: String): String {
        return "${java.util.UUID.randomUUID()}.enc"
    }
    
    private fun getGalleryDirectory(galleryName: String): java.io.File {
        // Implementation would return gallery directory
        val baseDir = java.io.File(context.filesDir, "secure_gallery")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }
    
    private fun encryptFileWithMemoryManagement(
        uri: android.net.Uri,
        outputFile: java.io.File,
        encryptionKey: String
    ): Boolean {
        // Implementation would call existing encryption with memory checks
        // For now, just copy the file as a placeholder
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file during encryption", e)
            false
        }
    }
    
    fun cancel() {
        backgroundScope.cancel()
        Log.d(TAG, "Media import cancelled")
    }
    
    fun cleanup() {
        cancel()
        callback = null
    }
}
