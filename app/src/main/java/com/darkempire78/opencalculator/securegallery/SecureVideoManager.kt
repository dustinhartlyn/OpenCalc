package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Log
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*

/**
 * Advanced video handling system for secure gallery
 * Supports streaming, efficient thumbnails, and memory-safe playback
 */
class SecureVideoManager(private val context: Context) {
    private val TAG = "SecureVideoManager"
    
    // Video thumbnail cache with size limits
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val maxThumbnailCacheSize = 20 // Limit to 20 thumbnails
    
    // Temporary file management for video playback
    private val tempVideoFiles = ConcurrentHashMap<String, File>()
    private val tempFileCleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + tempFileCleanupJob)
    
    // Video chunk cache for streaming large videos
    private val chunkCache = ConcurrentHashMap<String, ByteArray>()
    private val chunkSize = 512 * 1024 // 512KB chunks for video streaming
    
    init {
        // Start cleanup task for temporary files
        startTempFileCleanup()
    }
    
    /**
     * Generate video thumbnail with memory safety - SIMPLIFIED VERSION
     */
    suspend fun generateVideoThumbnail(
        encryptedFile: File,
        encryptionKey: String,
        maxWidth: Int = 320,
        maxHeight: Int = 240
    ): Bitmap? = withContext(Dispatchers.IO) {
        
        val cacheKey = "${encryptedFile.name}_thumb_${maxWidth}x${maxHeight}"
        
        // Check cache first
        thumbnailCache[cacheKey]?.let { cached ->
            if (!cached.isRecycled) {
                Log.d(TAG, "Using cached thumbnail for: ${encryptedFile.name}")
                return@withContext cached
            } else {
                thumbnailCache.remove(cacheKey)
            }
        }
        
        try {
            val fileSizeMB = encryptedFile.length() / (1024 * 1024)
            Log.d(TAG, "Generating video thumbnail for: ${encryptedFile.name} (${fileSizeMB}MB)")
            
            // Skip thumbnail generation for very large videos that caused OutOfMemoryError
            if (MemoryManager.isVideoTooLargeForThumbnail(encryptedFile)) {
                Log.w(TAG, "Video too large for thumbnail generation: ${encryptedFile.name} (${fileSizeMB}MB), using default thumbnail")
                // Return a default video thumbnail instead of null
                val defaultThumbnail = DefaultThumbnailGenerator.createDefaultVideoThumbnail(context, maxWidth, maxHeight)
                if (defaultThumbnail != null) {
                    thumbnailCache[cacheKey] = defaultThumbnail
                }
                return@withContext defaultThumbnail
            }
            
            // Force memory cleanup before processing
            if (MemoryManager.isLowMemory()) {
                MemoryManager.forceMemoryCleanup()
                delay(500) // Give GC time to work
            }
            
            // Generate thumbnail with memory safety
            val thumbnail = generateSimpleVideoThumbnail(encryptedFile, encryptionKey, maxWidth, maxHeight)
            
            // Cache the thumbnail if successful
            thumbnail?.let {
                // Limit cache size
                if (thumbnailCache.size >= maxThumbnailCacheSize) {
                    // Remove oldest thumbnail
                    val oldestKey = thumbnailCache.keys.firstOrNull()
                    oldestKey?.let { key ->
                        thumbnailCache.remove(key)?.let { oldBitmap ->
                            if (!oldBitmap.isRecycled) {
                                oldBitmap.recycle()
                            }
                        }
                    }
                }
                thumbnailCache[cacheKey] = it
                Log.d(TAG, "Successfully cached thumbnail for: ${encryptedFile.name}")
            } ?: run {
                Log.w(TAG, "Failed to generate thumbnail for: ${encryptedFile.name}, using default")
                // Create a default video thumbnail as fallback
                DefaultThumbnailGenerator.createDefaultVideoThumbnail(context, maxWidth, maxHeight)
            }
            
            thumbnail
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating video thumbnail for: ${encryptedFile.name}", e)
            null
        }
    }
    
    /**
     * Simple video thumbnail generation using streaming approach - MEMORY EFFICIENT
     */
    private suspend fun generateSimpleVideoThumbnail(
        encryptedFile: File,
        encryptionKey: String,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
        
        var tempVideoFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        
        return try {
            Log.d(TAG, "Starting streaming video thumbnail generation")
            
            // Add timeout to prevent hanging
            withTimeout(30000) { // 30 second timeout
                
                // Create temp video file in cache directory with unique name
                tempVideoFile = File.createTempFile("video_thumb_", ".mp4", context.cacheDir)
                
                // Stream only the first portion of the video for thumbnail generation
                val streamedSize = streamVideoHeaderForThumbnail(encryptedFile, tempVideoFile, encryptionKey)
                
                if (streamedSize == 0L) {
                    Log.w(TAG, "Failed to stream video data for thumbnail")
                    return@withTimeout null
                }
                
                Log.d(TAG, "Streamed ${streamedSize / 1024}KB for thumbnail generation (instead of ${encryptedFile.length() / 1024}KB)")
                
                // Use MediaMetadataRetriever to get thumbnail from streamed portion
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(tempVideoFile.absolutePath)
                
                // Try to get frame at 1 second, fallback to beginning
                var bitmap = try {
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get frame at 1s, trying beginning", e)
                    null
                }
                
                // Fallback to first frame
                if (bitmap == null) {
                    bitmap = try {
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get first frame, trying any frame", e)
                        // Try getting any available frame
                        try {
                            retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } catch (e2: Exception) {
                            Log.w(TAG, "Failed to get any frame", e2)
                            null
                        }
                    }
                }
                
                // Scale bitmap if needed and memory allows
                bitmap?.let { originalBitmap ->
                    if (!MemoryManager.isLowMemory() && 
                        (originalBitmap.width > maxWidth || originalBitmap.height > maxHeight)) {
                        
                        try {
                            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, maxWidth, maxHeight, true)
                            if (scaledBitmap != originalBitmap) {
                                originalBitmap.recycle()
                            }
                            Log.d(TAG, "Scaled thumbnail to ${maxWidth}x${maxHeight}")
                            scaledBitmap
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "OutOfMemoryError scaling thumbnail, using original")
                            originalBitmap
                        }
                    } else {
                        originalBitmap
                    }
                } ?: run {
                    Log.w(TAG, "No bitmap generated from streamed video")
                    null
                }
            }
            
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Video thumbnail generation timed out for: ${encryptedFile.name}")
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in video thumbnail generation for: ${encryptedFile.name}")
            MemoryManager.forceMemoryCleanup()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming video thumbnail generation", e)
            null
        } finally {
            // Cleanup
            retriever?.let {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
                }
            }
            
            tempVideoFile?.let {
                try {
                    if (it.exists()) {
                        it.delete()
                        Log.d(TAG, "Cleaned up temp video file")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error deleting temp video file", e)
                }
            }
        }
    }
    
    /**
     * Stream only the header and first few seconds of video for thumbnail generation
     * This avoids loading the entire video into memory
     */
    private suspend fun streamVideoHeaderForThumbnail(
        encryptedFile: File,
        outputFile: File,
        encryptionKey: String
    ): Long {
        
        return try {
            // Determine how much video data we need for thumbnail generation
            // For MP4 files, we typically need:
            // - File header (first 64KB for metadata)
            // - First keyframe and a few seconds of video data
            // This usually means 2-5MB is more than enough for thumbnail generation
            
            val maxStreamSize = calculateOptimalStreamSize(encryptedFile)
            
            Log.d(TAG, "Streaming first ${maxStreamSize / 1024}KB of ${encryptedFile.length() / 1024}KB video for thumbnail")
            
            var totalBytesWritten = 0L
            val buffer = ByteArray(64 * 1024) // 64KB buffer for streaming
            
            FileInputStream(encryptedFile).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (totalBytesRead < maxStreamSize && 
                           inputStream.read(buffer).also { bytesRead = it } != -1) {
                        
                        // Calculate how much to read (don't exceed maxStreamSize)
                        val bytesToProcess = minOf(bytesRead, (maxStreamSize - totalBytesRead).toInt())
                        
                        // Decrypt this chunk
                        val encryptedChunk = buffer.copyOf(bytesToProcess)
                        val decryptedChunk = decryptDataWithKey(encryptedChunk, encryptionKey)
                        
                        // Write decrypted chunk to output
                        outputStream.write(decryptedChunk)
                        
                        totalBytesRead += bytesToProcess
                        totalBytesWritten += decryptedChunk.size
                        
                        // Yield to prevent blocking the main thread
                        if (totalBytesRead % (512 * 1024) == 0L) { // Every 512KB
                            yield()
                        }
                    }
                }
            }
            
            Log.d(TAG, "Successfully streamed ${totalBytesWritten / 1024}KB for thumbnail generation")
            totalBytesWritten
            
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming video header", e)
            0L
        }
    }
    
    /**
     * Calculate optimal stream size based on video file size and available memory
     */
    private fun calculateOptimalStreamSize(encryptedFile: File): Long {
        val fileSizeBytes = encryptedFile.length()
        val fileSizeMB = fileSizeBytes / (1024 * 1024)
        
        return when {
            // For very large files (>100MB), stream only 3MB
            fileSizeMB > 100 -> 3 * 1024 * 1024L
            
            // For large files (50-100MB), stream 5MB
            fileSizeMB > 50 -> 5 * 1024 * 1024L
            
            // For medium files (20-50MB), stream 8MB
            fileSizeMB > 20 -> 8 * 1024 * 1024L
            
            // For small files (<20MB), stream up to 50% of the file
            else -> minOf(fileSizeBytes / 2, 10 * 1024 * 1024L)
        }
    }
    
    /**
     * Generate standard thumbnail by fully decrypting video
     */
    private suspend fun generateStandardThumbnail(
        encryptedFile: File,
        encryptionKey: String,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
        
        return try {
            // Check memory before proceeding
            if (MemoryManager.isLowMemory()) {
                MemoryManager.forceMemoryCleanup()
            }
            
            // Decrypt entire video to temp file
            val tempVideoFile = decryptVideoToTempFile(encryptedFile, encryptionKey)
            
            if (tempVideoFile == null) {
                Log.e(TAG, "Failed to decrypt video for thumbnail")
                return null
            }
            
            // Generate thumbnail
            val thumbnail = extractThumbnailFromVideoFile(tempVideoFile, maxWidth, maxHeight)
            
            // Schedule cleanup of temp file
            scheduleFileCleanup(tempVideoFile)
            
            thumbnail
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError generating standard thumbnail", e)
            MemoryManager.forceMemoryCleanup()
            // Return null instead of calling non-existent method
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate standard thumbnail", e)
            null
        }
    }
    
    /**
     * Extract thumbnail from video file with proper scaling
     */
    private fun extractThumbnailFromVideoFile(
        videoFile: File,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
        
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            
            // Get frame at 1 second (or beginning if video is shorter)
            val timeUs = 1_000_000L // 1 second in microseconds
            var bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            
            // If that fails, try getting frame at beginning
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            
            // Scale bitmap if necessary
            bitmap?.let { original ->
                if (original.width > maxWidth || original.height > maxHeight) {
                    val scaledBitmap = scaleBitmapSafely(original, maxWidth, maxHeight)
                    if (scaledBitmap != original) {
                        original.recycle()
                    }
                    scaledBitmap
                } else {
                    original
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video thumbnail", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Scale bitmap safely with memory management
     */
    private fun scaleBitmapSafely(
        original: Bitmap,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap {
        val width = original.width
        val height = original.height
        
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return try {
            Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError scaling bitmap, using original")
            original
        }
    }
    
    /**
     * Decrypt video to temporary file for playback
     */
    suspend fun prepareVideoForPlayback(
        encryptedFile: File,
        encryptionKey: String
    ): File? = withContext(Dispatchers.IO) {
        
        val videoId = "${encryptedFile.name}_${System.currentTimeMillis()}"
        
        // Check if already prepared
        tempVideoFiles[videoId]?.let { existingFile ->
            if (existingFile.exists()) {
                Log.d(TAG, "Using existing temp video file: ${existingFile.name}")
                return@withContext existingFile
            } else {
                tempVideoFiles.remove(videoId)
            }
        }
        
        // Check if video should be streamed instead of fully decrypted
        if (MemoryManager.shouldStreamVideo(encryptedFile)) {
            Log.d(TAG, "Video too large for full decryption, preparing for streaming: ${encryptedFile.name}")
            prepareVideoForStreaming(encryptedFile, encryptionKey, videoId)
        } else {
            Log.d(TAG, "Fully decrypting video for playback: ${encryptedFile.name}")
            decryptVideoToTempFile(encryptedFile, encryptionKey, videoId)
        }
    }
    
    /**
     * Prepare video for streaming by creating a streaming-capable temp file
     */
    private suspend fun prepareVideoForStreaming(
        encryptedFile: File,
        encryptionKey: String,
        videoId: String
    ): File? {
        
        return try {
            // Create a special temp file that supports streaming
            val tempFile = createTempVideoFile(videoId)
            
            // Start streaming decryption in background
            cleanupScope.launch {
                streamDecryptVideo(encryptedFile, encryptionKey, tempFile)
            }
            
            // Return file immediately for streaming playback
            tempVideoFiles[videoId] = tempFile
            tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare video for streaming", e)
            null
        }
    }
    
    /**
     * Stream decrypt video in chunks
     */
    private suspend fun streamDecryptVideo(
        encryptedFile: File,
        encryptionKey: String,
        outputFile: File
    ) {
        
        try {
            FileInputStream(encryptedFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check for memory pressure
                        if (MemoryManager.isCriticalMemory()) {
                            Log.w(TAG, "Critical memory during streaming, pausing")
                            MemoryManager.forceMemoryCleanup()
                            delay(1000)
                        }
                        
                        // Decrypt chunk
                        val chunk = if (bytesRead < chunkSize) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer
                        }
                        
                        val decryptedChunk = decryptData(chunk, encryptionKey)
                        output.write(decryptedChunk)
                        output.flush()
                        
                        // Small delay to prevent overwhelming the system
                        delay(10)
                    }
                }
            }
            
            Log.d(TAG, "Stream decryption completed for: ${encryptedFile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during stream decryption", e)
        }
    }
    
    /**
     * Decrypt entire video to temporary file
     */
    private suspend fun decryptVideoToTempFile(
        encryptedFile: File,
        encryptionKey: String,
        videoId: String? = null
    ): File? {
        
        return try {
            val fileId = videoId ?: "${encryptedFile.name}_${System.currentTimeMillis()}"
            val tempFile = createTempVideoFile(fileId)
            
            // Read and decrypt file
            val encryptedData = encryptedFile.readBytes()
            val decryptedData = decryptData(encryptedData, encryptionKey)
            
            // Write to temp file
            tempFile.writeBytes(decryptedData)
            
            if (videoId != null) {
                tempVideoFiles[videoId] = tempFile
            }
            
            Log.d(TAG, "Video decrypted to temp file: ${tempFile.name}, size: ${tempFile.length()} bytes")
            tempFile
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError decrypting video: ${encryptedFile.name}", e)
            MemoryManager.forceMemoryCleanup()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting video to temp file", e)
            null
        }
    }
    
    /**
     * Create temporary video file
     */
    private fun createTempVideoFile(identifier: String): File {
        val tempDir = File(context.getExternalFilesDir(null), "temp_videos")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return File(tempDir, "playback_${identifier}.mp4")
    }
    
    /**
     * Schedule cleanup of temporary file
     */
    private fun scheduleFileCleanup(file: File, delayMs: Long = 30000) {
        cleanupScope.launch {
            delay(delayMs)
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleaned up temp file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up temp file: ${file.name}", e)
            }
        }
    }
    
    /**
     * Start periodic cleanup of temporary files
     */
    private fun startTempFileCleanup() {
        cleanupScope.launch {
            while (cleanupScope.isActive) {
                delay(60000) // Run every minute
                
                try {
                    cleanupOldTempFiles()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during temp file cleanup", e)
                }
            }
        }
    }
    
    /**
     * Clean up old temporary files
     */
    private fun cleanupOldTempFiles() {
        val tempDir = File(context.getExternalFilesDir(null), "temp_videos")
        if (!tempDir.exists()) return
        
        val now = System.currentTimeMillis()
        val maxAgeMs = 30 * 60 * 1000 // 30 minutes
        
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                try {
                    file.delete()
                    Log.d(TAG, "Cleaned up old temp file: ${file.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cleanup old temp file: ${file.name}", e)
                }
            }
        }
        
        // Also cleanup from memory tracking
        tempVideoFiles.entries.removeAll { (_, file) ->
            !file.exists()
        }
    }
    
    /**
     * Decrypt data using AES encryption
     */
    private fun decryptData(encryptedData: ByteArray, encryptionKey: String): ByteArray {
        val cipher = Cipher.getInstance("AES")
        val secretKey = SecretKeySpec(encryptionKey.toByteArray().copyOf(16), "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(encryptedData)
    }
    
    /**
     * Get video metadata safely
     */
    fun getVideoMetadata(videoFile: File): VideoMetadata? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            
            VideoMetadata(width, height, duration, mimeType)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video metadata", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
    
    data class VideoMetadata(
        val width: Int,
        val height: Int,
        val duration: Long,
        val mimeType: String
    )
    
    /**
     * Clean up all resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up SecureVideoManager")
        
        // Cancel cleanup jobs
        tempFileCleanupJob.cancel()
        
        // Clear caches
        thumbnailCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        thumbnailCache.clear()
        chunkCache.clear()
        
        // Cleanup temp files
        tempVideoFiles.values.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting temp file during cleanup: ${file.name}")
            }
        }
        tempVideoFiles.clear()
    }
}
