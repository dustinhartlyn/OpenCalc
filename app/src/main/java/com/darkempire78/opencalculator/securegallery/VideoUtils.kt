package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object VideoUtils {
    
    /**
     * ENCRYPTED THUMBNAIL SYSTEM:
     * 
     * Thumbnails are now saved as encrypted files using ThumbnailGenerator, not unencrypted cache.
     * Benefits:
     * - Generated once during import and saved as encrypted files
     * - During login, thumbnails are decrypted from saved files (fast)
     * - No regeneration needed on each login (major performance improvement)
     * - Secure: thumbnails are encrypted like original media files
     * - Cleared on logout for security
     * 
     * Migration path:
     * - New galleries automatically use encrypted thumbnails
     * - Old galleries fall back to legacy cache if no encrypted thumbnail exists
     * - Legacy cache will be gradually replaced as thumbnails are regenerated
     */
    
    /**
     * Generate and save encrypted thumbnail for a video during import (OPTIMIZED)
     * Uses optimized partial decryption and saves as encrypted thumbnail file
     */
    fun generateAndSaveThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec, galleryName: String): Boolean {
        try {
            Log.d("VideoUtils", "ENCRYPTED: Starting generateAndSaveThumbnail for ${secureMedia.name}")
            
            // Check if encrypted thumbnail already exists
            val thumbnailPath = ThumbnailGenerator.getThumbnailPath(context, galleryName, secureMedia.id.toString())
            if (File(thumbnailPath).exists()) {
                Log.d("VideoUtils", "ENCRYPTED: Thumbnail already exists for ${secureMedia.name}")
                return true
            }
            
            // Generate thumbnail using optimized methods
            val thumbnail = if (secureMedia.usesExternalStorage()) {
                Log.d("VideoUtils", "ENCRYPTED: Generating thumbnail from file for ${secureMedia.name}")
                generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
            } else {
                Log.d("VideoUtils", "ENCRYPTED: Generating thumbnail from data for ${secureMedia.name}")
                generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
            }
            
            if (thumbnail != null) {
                Log.d("VideoUtils", "ENCRYPTED: Thumbnail generated successfully, saving encrypted for ${secureMedia.name}")
                // Save thumbnail as encrypted file using ThumbnailGenerator
                return saveEncryptedThumbnail(context, secureMedia, thumbnail, key, galleryName)
            } else {
                Log.e("VideoUtils", "ENCRYPTED: Failed to generate thumbnail for ${secureMedia.name}")
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "ENCRYPTED: Failed to generate and save thumbnail for ${secureMedia.name}", e)
        }
        return false
    }

    /**
     * Generate and save thumbnail (LEGACY - for backwards compatibility)
     * Falls back to unencrypted cache since galleryName is not available
     */
    @Deprecated("Use generateAndSaveThumbnail(context, secureMedia, key, galleryName) for encrypted thumbnails")
    fun generateAndSaveThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): Boolean {
        Log.d("VideoUtils", "LEGACY: Using legacy thumbnail generation for ${secureMedia.name}")
        
        // Check if we already have a cached thumbnail
        val existingThumbnail = loadCachedThumbnail(context, secureMedia)
        if (existingThumbnail != null) {
            Log.d("VideoUtils", "LEGACY: Thumbnail already exists in cache for ${secureMedia.name}")
            return true
        }
        
        // Generate thumbnail using optimized methods
        val thumbnail = if (secureMedia.usesExternalStorage()) {
            Log.d("VideoUtils", "LEGACY: Generating thumbnail from file for ${secureMedia.name}")
            generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
        } else {
            Log.d("VideoUtils", "LEGACY: Generating thumbnail from data for ${secureMedia.name}")
            generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
        }
        
        if (thumbnail != null) {
            Log.d("VideoUtils", "LEGACY: Thumbnail generated successfully, saving to legacy cache for ${secureMedia.name}")
            // Save thumbnail to legacy cache
            return saveThumbnailToCache(context, secureMedia, thumbnail)
        } else {
            Log.e("VideoUtils", "LEGACY: Failed to generate thumbnail for ${secureMedia.name}")
        }
        return false
    }
    
    /**
     * Save thumbnail as encrypted file using ThumbnailGenerator
     */
    private fun saveEncryptedThumbnail(context: Context, secureMedia: SecureMedia, thumbnail: Bitmap, key: javax.crypto.spec.SecretKeySpec, galleryName: String): Boolean {
        try {
            // Convert thumbnail to JPEG bytes
            val thumbnailBytes = java.io.ByteArrayOutputStream().use { stream ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                stream.toByteArray()
            }
            
            // Use ThumbnailGenerator to save encrypted thumbnail
            val savedPath = ThumbnailGenerator.generateThumbnailFromBytes(
                context,
                thumbnailBytes,
                secureMedia.id.toString(),
                galleryName,
                key,
                isVideo = true
            )
            
            if (savedPath != null) {
                Log.d("VideoUtils", "ENCRYPTED: Successfully saved encrypted thumbnail for ${secureMedia.name}")
                return true
            } else {
                Log.e("VideoUtils", "ENCRYPTED: Failed to save encrypted thumbnail for ${secureMedia.name}")
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "ENCRYPTED: Error saving encrypted thumbnail for ${secureMedia.name}", e)
        }
        return false
    }

    /**
     * Load encrypted thumbnail for a video (UPDATED)
     * Now uses encrypted thumbnail files instead of unencrypted cache
     */
    fun loadCachedThumbnail(context: Context, secureMedia: SecureMedia, galleryName: String, key: javax.crypto.spec.SecretKeySpec): Bitmap? {
        try {
            val thumbnailPath = ThumbnailGenerator.getThumbnailPath(context, galleryName, secureMedia.id.toString())
            if (File(thumbnailPath).exists()) {
                Log.d("VideoUtils", "ENCRYPTED: Loading encrypted thumbnail for ${secureMedia.name}")
                val bitmap = ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key)
                if (bitmap != null) {
                    Log.d("VideoUtils", "ENCRYPTED: Successfully loaded encrypted thumbnail for ${secureMedia.name}, size: ${bitmap.width}x${bitmap.height}")
                    return bitmap
                } else {
                    Log.w("VideoUtils", "ENCRYPTED: Encrypted thumbnail file exists but failed to decode for ${secureMedia.name}")
                    // Don't delete the file here - it might be a temporary decryption issue
                }
            } else {
                Log.d("VideoUtils", "ENCRYPTED: No encrypted thumbnail found for ${secureMedia.name}")
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "ENCRYPTED: Failed to load encrypted thumbnail for ${secureMedia.name}", e)
        }
        return null
    }


    /**
     * Load cached thumbnail for a video (LEGACY - for backwards compatibility)
     * Tries encrypted thumbnail first, falls back to old unencrypted cache
     */
    fun loadCachedThumbnail(context: Context, secureMedia: SecureMedia): Bitmap? {
        // First try to load from legacy unencrypted cache (will be deprecated)
        try {
            val thumbnailFile = getThumbnailFile(context, secureMedia)
            if (thumbnailFile.exists() && thumbnailFile.length() > 0) {
                Log.d("VideoUtils", "LEGACY: Loading legacy cached thumbnail for ${secureMedia.name}")
                val bitmap = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                if (bitmap != null) {
                    Log.d("VideoUtils", "LEGACY: Successfully loaded legacy thumbnail for ${secureMedia.name}")
                    return bitmap
                } else {
                    Log.w("VideoUtils", "LEGACY: Legacy thumbnail file corrupted, deleting: ${thumbnailFile.absolutePath}")
                    thumbnailFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "LEGACY: Failed to load legacy thumbnail for ${secureMedia.name}", e)
        }
        return null
    }
    
    /**
     * Save thumbnail bitmap to internal cache (LEGACY - DEPRECATED)
     * This method is kept for backwards compatibility but will be removed
     * New code should use encrypted thumbnails via ThumbnailGenerator
     */
    @Deprecated("Use encrypted thumbnails via ThumbnailGenerator instead")
    private fun saveThumbnailToCache(context: Context, secureMedia: SecureMedia, thumbnail: Bitmap): Boolean {
        try {
            val thumbnailFile = getThumbnailFile(context, secureMedia)
            
            // Ensure parent directory exists
            thumbnailFile.parentFile?.let { parentDir ->
                if (!parentDir.exists()) {
                    val created = parentDir.mkdirs()
                    Log.d("VideoUtils", "LEGACY: Created thumbnail directory: $created")
                }
            }
            
            // Save with high quality JPEG compression for smaller file size but good quality
            FileOutputStream(thumbnailFile).use { outputStream ->
                val success = thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.flush()
                
                if (success && thumbnailFile.exists() && thumbnailFile.length() > 0) {
                    Log.d("VideoUtils", "LEGACY: Successfully saved thumbnail for ${secureMedia.name}, size: ${thumbnailFile.length()} bytes")
                    return true
                } else {
                    Log.e("VideoUtils", "LEGACY: Failed to save thumbnail for ${secureMedia.name} - compression failed or empty file")
                    thumbnailFile.delete() // Clean up failed save
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "LEGACY: Failed to save thumbnail for ${secureMedia.name}", e)
        }
        return false
    }
    
    /**
     * Get the file path for a video's thumbnail
     */
    private fun getThumbnailFile(context: Context, secureMedia: SecureMedia): File {
        val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
        val mediaHash = generateMediaHash(secureMedia)
        return File(thumbnailsDir, "$mediaHash.jpg")
    }
    
    /**
     * OPTIMIZATION: Preload thumbnails for video media items in background
     * This helps avoid "broken thumbnail" appearance on app startup
     */
    fun preloadThumbnailsInBackground(context: Context, mediaList: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec, galleryName: String = "Default Gallery") {
        Thread {
            try {
                Log.d("VideoUtils", "PRELOAD: Starting background thumbnail preloading for ${mediaList.size} media items")
                
                val videoItems = mediaList.filter { it.mediaType == MediaType.VIDEO }
                Log.d("VideoUtils", "PRELOAD: Found ${videoItems.size} video items to preload")
                
                for ((index, media) in videoItems.withIndex()) {
                    try {
                        val existingThumbnail = loadCachedThumbnail(context, media, galleryName, key)
                        if (existingThumbnail == null) {
                            Log.d("VideoUtils", "PRELOAD: Generating missing thumbnail for ${media.name} (${index + 1}/${videoItems.size})")
                            generateAndSaveThumbnail(context, media, key, galleryName)
                        } else {
                            Log.d("VideoUtils", "PRELOAD: Thumbnail already cached for ${media.name} (${index + 1}/${videoItems.size})")
                        }
                        
                        // Small delay to avoid overwhelming the system
                        Thread.sleep(100)
                        
                    } catch (e: Exception) {
                        Log.e("VideoUtils", "PRELOAD: Failed to preload thumbnail for ${media.name}", e)
                    }
                }
                
                Log.d("VideoUtils", "PRELOAD: Background thumbnail preloading completed")
                
            } catch (e: Exception) {
                Log.e("VideoUtils", "PRELOAD: Background thumbnail preloading failed", e)
            }
        }.apply {
            name = "VideoThumbnailPreloader"
            priority = Thread.MIN_PRIORITY // Low priority to not interfere with UI
        }.start()
    }
    
    /**
     * OPTIMIZATION: Check and repair corrupted thumbnail cache
     * Useful for fixing "broken thumbnails" after app updates or crashes
     * Now supports both encrypted and legacy thumbnail systems
     */
    fun validateAndRepairThumbnailCache(context: Context, mediaList: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec, galleryName: String = "Default Gallery") {
        Thread {
            try {
                Log.d("VideoUtils", "CACHE_REPAIR: Starting thumbnail cache validation")
                
                val videoItems = mediaList.filter { it.mediaType == MediaType.VIDEO }
                var repairedCount = 0
                
                for (media in videoItems) {
                    try {
                        // First check for encrypted thumbnails (new system)
                        val encryptedThumbnailPath = ThumbnailGenerator.getThumbnailPath(context, galleryName, media.id.toString())
                        val encryptedThumbnailExists = File(encryptedThumbnailPath).exists()
                        
                        if (encryptedThumbnailExists) {
                            // Try to load the encrypted thumbnail to validate it
                            val encryptedThumbnail = ThumbnailGenerator.loadEncryptedThumbnail(encryptedThumbnailPath, key)
                            if (encryptedThumbnail == null) {
                                // Corrupted encrypted thumbnail, regenerate
                                Log.d("VideoUtils", "CACHE_REPAIR: Repairing corrupted encrypted thumbnail for ${media.name}")
                                File(encryptedThumbnailPath).delete()
                                generateAndSaveThumbnail(context, media, key, galleryName)
                                repairedCount++
                            } else {
                                Log.d("VideoUtils", "CACHE_REPAIR: Encrypted thumbnail validated for ${media.name}")
                            }
                        } else {
                            // Check legacy thumbnail system
                            val legacyThumbnailFile = getThumbnailFile(context, media)
                            
                            if (legacyThumbnailFile.exists()) {
                                // Try to load the legacy thumbnail to validate it
                                val bitmap = BitmapFactory.decodeFile(legacyThumbnailFile.absolutePath)
                                if (bitmap == null) {
                                    // Corrupted legacy thumbnail, regenerate as encrypted
                                    Log.d("VideoUtils", "CACHE_REPAIR: Migrating corrupted legacy thumbnail to encrypted for ${media.name}")
                                    legacyThumbnailFile.delete()
                                    generateAndSaveThumbnail(context, media, key, galleryName)
                                    repairedCount++
                                } else {
                                    // Migrate working legacy thumbnail to encrypted system
                                    Log.d("VideoUtils", "CACHE_REPAIR: Migrating working legacy thumbnail to encrypted for ${media.name}")
                                    saveEncryptedThumbnail(context, media, bitmap, key, galleryName)
                                    legacyThumbnailFile.delete() // Clean up legacy file
                                    repairedCount++
                                }
                            } else {
                                // No thumbnail exists, generate new encrypted one
                                Log.d("VideoUtils", "CACHE_REPAIR: Generating missing encrypted thumbnail for ${media.name}")
                                generateAndSaveThumbnail(context, media, key, galleryName)
                                repairedCount++
                            }
                        }
                        
                    } catch (e: Exception) {
                        Log.e("VideoUtils", "CACHE_REPAIR: Failed to validate thumbnail for ${media.name}", e)
                    }
                }
                
                Log.d("VideoUtils", "CACHE_REPAIR: Thumbnail cache validation completed, repaired: $repairedCount")
                
            } catch (e: Exception) {
                Log.e("VideoUtils", "CACHE_REPAIR: Thumbnail cache validation failed", e)
            }
        }.apply {
            name = "VideoThumbnailCacheValidator"
            priority = Thread.MIN_PRIORITY
        }.start()
    }
    
    /**
     * LEGACY: Check and repair corrupted thumbnail cache (without gallery name)
     * Maintains backwards compatibility but will gradually migrate to encrypted system
     */
    fun validateAndRepairThumbnailCache(context: Context, mediaList: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        validateAndRepairThumbnailCache(context, mediaList, key, "Default Gallery")
    }
    
    /**
     * Generate a unique hash for a media item
     */
    private fun generateMediaHash(secureMedia: SecureMedia): String {
        val identifier = if (secureMedia.usesExternalStorage()) {
            val file = java.io.File(secureMedia.filePath!!)
            secureMedia.filePath + secureMedia.name + file.length()
        } else {
            secureMedia.name + secureMedia.getEncryptedData().size
        }
        
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(identifier.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * SECURITY: Clear all thumbnail caches on logout
     * This prevents unencrypted thumbnail data from persisting after logout
     */
    fun clearAllThumbnailCaches(context: Context) {
        try {
            Log.d("VideoUtils", "SECURITY: Clearing all thumbnail caches on logout")
            
            val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
            if (thumbnailsDir.exists()) {
                val deletedCount = thumbnailsDir.listFiles()?.size ?: 0
                
                // Securely delete all thumbnail files
                thumbnailsDir.listFiles()?.forEach { file ->
                    try {
                        // Security: Overwrite file content before deletion
                        if (file.length() > 0) {
                            val randomData = ByteArray(minOf(file.length().toInt(), 1024 * 1024)) // Max 1MB
                            java.security.SecureRandom().nextBytes(randomData)
                            file.writeBytes(randomData)
                        }
                        file.delete()
                        Log.d("VideoUtils", "SECURITY: Securely deleted thumbnail: ${file.name}")
                    } catch (e: Exception) {
                        Log.w("VideoUtils", "Failed to securely delete thumbnail: ${file.name}", e)
                        // Fallback to regular deletion
                        try { file.delete() } catch (e2: Exception) { /* Ignore */ }
                    }
                }
                
                // Remove the directory itself
                thumbnailsDir.delete()
                Log.d("VideoUtils", "SECURITY: Cleared $deletedCount thumbnail cache files")
            } else {
                Log.d("VideoUtils", "SECURITY: No thumbnail cache directory to clear")
            }
            
            // Also clear any temp video files that might contain decrypted data
            val tempVideoDir = File(context.cacheDir, "temp_videos")
            if (tempVideoDir.exists()) {
                tempVideoDir.listFiles()?.forEach { file ->
                    try {
                        // Security: Overwrite temp video files before deletion
                        if (file.length() > 0) {
                            val randomData = ByteArray(minOf(file.length().toInt(), 10 * 1024 * 1024)) // Max 10MB
                            java.security.SecureRandom().nextBytes(randomData)
                            file.writeBytes(randomData)
                        }
                        file.delete()
                        Log.d("VideoUtils", "SECURITY: Securely deleted temp video: ${file.name}")
                    } catch (e: Exception) {
                        Log.w("VideoUtils", "Failed to securely delete temp video: ${file.name}", e)
                        try { file.delete() } catch (e2: Exception) { /* Ignore */ }
                    }
                }
                tempVideoDir.delete()
                Log.d("VideoUtils", "SECURITY: Cleared temp video cache")
            }
            
            // Force garbage collection to clear any remaining thumbnail data from memory
            System.gc()
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "SECURITY: Failed to clear thumbnail caches", e)
        }
    }
    
    /**
     * SECURITY: Use in-memory only thumbnail generation (no persistent cache)
     * Thumbnails are regenerated on each login for maximum security
     */
    fun generateVideoThumbnailSecure(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): Bitmap? {
        try {
            Log.d("VideoUtils", "SECURITY: Generating thumbnail in memory only for ${secureMedia.name}")
            
            // Generate thumbnail using optimized methods but don't save to cache
            val thumbnail = if (secureMedia.usesExternalStorage()) {
                Log.d("VideoUtils", "SECURITY: Generating thumbnail from file for ${secureMedia.name}")
                generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
            } else {
                Log.d("VideoUtils", "SECURITY: Generating thumbnail from data for ${secureMedia.name}")
                generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
            }
            
            if (thumbnail != null) {
                Log.d("VideoUtils", "SECURITY: Thumbnail generated in memory for ${secureMedia.name}, size: ${thumbnail.width}x${thumbnail.height}")
                return thumbnail
            } else {
                Log.e("VideoUtils", "SECURITY: Failed to generate thumbnail for ${secureMedia.name}")
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "SECURITY: Failed to generate secure thumbnail for ${secureMedia.name}", e)
        }
        return null
    }
    
    /**
     * Collect all cached thumbnails for export
     */
    fun collectCachedThumbnails(context: Context, mediaList: List<SecureMedia>): Map<String, ByteArray> {
        val thumbnails = mutableMapOf<String, ByteArray>()
        
        try {
            for (media in mediaList) {
                if (media.mediaType == MediaType.VIDEO) {
                    val thumbnailFile = getThumbnailFile(context, media)
                    if (thumbnailFile.exists()) {
                        val thumbnailBytes = thumbnailFile.readBytes()
                        val mediaHash = generateMediaHash(media)
                        thumbnails[mediaHash] = thumbnailBytes
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to collect cached thumbnails", e)
        }
        
        return thumbnails
    }
    
    /**
     * Restore cached thumbnails from export data
     */
    fun restoreCachedThumbnails(context: Context, mediaList: List<SecureMedia>, thumbnailData: Map<String, ByteArray>) {
        try {
            val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
            thumbnailsDir.mkdirs()
            
            for (media in mediaList) {
                if (media.mediaType == MediaType.VIDEO) {
                    val mediaHash = generateMediaHash(media)
                    val thumbnailBytes = thumbnailData[mediaHash]
                    
                    if (thumbnailBytes != null) {
                        val thumbnailFile = getThumbnailFile(context, media)
                        thumbnailFile.writeBytes(thumbnailBytes)
                        Log.d("VideoUtils", "Restored thumbnail for: ${media.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to restore cached thumbnails", e)
        }
    }
    
    /**
     * Generates a thumbnail bitmap from SecureMedia (SECURE - NO PERSISTENT CACHE)
     * Thumbnails are generated fresh each time for security - no disk cache
     */
    fun generateVideoThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        Log.d("VideoUtils", "SECURITY: Generating video thumbnail in memory only for ${secureMedia.name}")
        
        // DO NOT check for cached thumbnails - always generate fresh for security
        // Small thumbnails are fast to generate and this prevents data interception
        
        // Generate thumbnail using optimized methods but don't save to persistent cache
        val thumbnail = if (secureMedia.usesExternalStorage()) {
            generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
        } else {
            generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
        }
        
        if (thumbnail != null) {
            Log.d("VideoUtils", "SECURITY: Thumbnail generated in memory for ${secureMedia.name}, size: ${thumbnail.width}x${thumbnail.height}")
        } else {
            Log.e("VideoUtils", "SECURITY: Failed to generate thumbnail for ${secureMedia.name}")
        }
        
        return thumbnail
    }
    
    /**
     * Generates a thumbnail bitmap from SecureMedia (SECURE - legacy method without context)
     * Always generates fresh - no persistent cache for security
     */
    fun generateVideoThumbnail(secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        Log.d("VideoUtils", "SECURITY: Generating video thumbnail (legacy) in memory only for ${secureMedia.name}")
        
        return if (secureMedia.usesExternalStorage()) {
            generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
        } else {
            generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
        }
    }
    
    /**
     * Generates a thumbnail bitmap from encrypted video data (OPTIMIZED)
     * Only decrypts the beginning of the video data to extract thumbnail
     */
    fun generateVideoThumbnailFromData(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Generating thumbnail from data (OPTIMIZED), encrypted size: ${encryptedVideoData.size}")
            
            // Ensure we have enough data for IV + content
            if (encryptedVideoData.size < 17) { // 16 bytes IV + at least 1 byte data
                Log.e("VideoUtils", "Encrypted video data too small: ${encryptedVideoData.size} bytes")
                return null
            }
            
            // Extract IV from encrypted data
            val iv = encryptedVideoData.copyOfRange(0, 16)
            val ciphertext = encryptedVideoData.copyOfRange(16, encryptedVideoData.size)
            
            // OPTIMIZATION: Only decrypt the first 5MB for thumbnail generation
            val maxBytesToDecrypt = 5 * 1024 * 1024 // 5MB limit
            val bytesToDecrypt = minOf(maxBytesToDecrypt, ciphertext.size)
            
            Log.d("VideoUtils", "OPTIMIZATION: Decrypting only first ${bytesToDecrypt / 1024 / 1024}MB instead of full ${ciphertext.size / 1024 / 1024}MB")
            
            // Create a temporary file to store the partially decrypted video
            tempFile = File.createTempFile("temp_video_thumb_", ".mp4")
            tempFile.deleteOnExit()
            
            // Initialize cipher for partial decryption
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            // Decrypt only the beginning portion
            val partialCiphertext = ciphertext.copyOfRange(0, bytesToDecrypt)
            
            try {
                val decryptedData = cipher.doFinal(partialCiphertext)
                tempFile.writeBytes(decryptedData)
                
                Log.d("VideoUtils", "OPTIMIZED: Partial decrypted data written to temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}")
                
                // Try to generate thumbnail from partial data
                retriever = MediaMetadataRetriever()
                
                try {
                    retriever.setDataSource(tempFile.absolutePath)
                    
                    // Try to get thumbnail from the beginning of the video (most likely to succeed with partial data)
                    val timePositions = longArrayOf(0, 100000, 500000, 1000000) // 0s, 0.1s, 0.5s, 1s
                    
                    for (timeUs in timePositions) {
                        try {
                            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (bitmap != null) {
                                Log.d("VideoUtils", "OPTIMIZED: Successfully generated thumbnail from data at time ${timeUs}us")
                                return bitmap
                            }
                        } catch (e: Exception) {
                            Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us from partial data", e)
                        }
                    }
                    
                    // If specific time positions fail, try getting any frame
                    try {
                        val bitmap = retriever.frameAtTime
                        if (bitmap != null) {
                            Log.d("VideoUtils", "OPTIMIZED: Successfully generated thumbnail using default frame extraction from data")
                            return bitmap
                        }
                    } catch (e: Exception) {
                        Log.w("VideoUtils", "Failed to get default frame from partial data", e)
                    }
                    
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to set data source on partial data, falling back to full decryption", e)
                }
                
            } catch (e: Exception) {
                Log.w("VideoUtils", "Partial decryption failed, trying full decryption", e)
            }
            
            // FALLBACK: Try full decryption if partial approach fails
            Log.d("VideoUtils", "Falling back to full data decryption for thumbnail generation")
            return generateVideoThumbnailFromDataFull(encryptedVideoData, key)
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate thumbnail from encrypted data (optimized)", e)
            
            // FALLBACK: Try full decryption if optimized approach fails
            Log.d("VideoUtils", "Optimized approach failed, falling back to full data decryption")
            return generateVideoThumbnailFromDataFull(encryptedVideoData, key)
            
        } finally {
            retriever?.release()
            tempFile?.delete()
        }
    }
    
    /**
     * FALLBACK: Full data decryption for thumbnail generation (used when optimized approach fails)
     */
    private fun generateVideoThumbnailFromDataFull(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "FALLBACK: Full data decryption for thumbnail, encrypted size: ${encryptedVideoData.size}")
            
            // Extract IV and decrypt all data
            val iv = encryptedVideoData.copyOfRange(0, 16)
            val ciphertext = encryptedVideoData.copyOfRange(16, encryptedVideoData.size)
            
            // Initialize cipher for full decryption
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            // Create a temporary file to store the decrypted video
            tempFile = File.createTempFile("temp_video_full_", ".mp4")
            tempFile.deleteOnExit()
            
            // Decrypt all data using streaming to avoid memory issues
            val inputStream = ByteArrayInputStream(ciphertext)
            val outputStream = FileOutputStream(tempFile)
            val cipherInputStream = javax.crypto.CipherInputStream(inputStream, cipher)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            cipherInputStream.close()
            inputStream.close()
            outputStream.close()
            
            Log.d("VideoUtils", "FALLBACK: Full decrypted data written to temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}")
            
            // Use MediaMetadataRetriever to get thumbnail
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Try multiple time positions if the first one fails
            val timePositions = longArrayOf(0, 1000000, 500000, 2000000) // 0s, 1s, 0.5s, 2s
            
            for (timeUs in timePositions) {
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "FALLBACK: Successfully generated thumbnail from full data at time ${timeUs}us")
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us from full data", e)
                }
            }
            
            Log.e("VideoUtils", "FALLBACK: Failed to generate thumbnail from full data at all time positions")
            return null
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "FALLBACK: Failed to generate thumbnail from full data", e)
            return null
        } finally {
            retriever?.release()
            tempFile?.delete()
        }
    }
    
    /**
     * Generate video thumbnail from unencrypted video bytes (for thumbnail generation during import)
     */
    fun generateVideoThumbnailFromBytes(videoBytes: ByteArray, key: javax.crypto.spec.SecretKeySpec? = null): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Generating thumbnail from unencrypted bytes, size: ${videoBytes.size}")
            
            // Create a temporary file to store the video
            tempFile = File.createTempFile("temp_video_", ".mp4")
            tempFile.deleteOnExit()
            tempFile.writeBytes(videoBytes)
            
            // Use MediaMetadataRetriever to get thumbnail
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            Log.d("VideoUtils", "Thumbnail generated successfully from bytes")
            return bitmap
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate thumbnail from bytes", e)
            return null
        } finally {
            retriever?.release()
            tempFile?.delete()
        }
    }
    
    /**
     * Generate video thumbnail from already decrypted video bytes (OPTIMIZED)
     * Used when video data is already decrypted, no need for further decryption
     */
    fun generateVideoThumbnailFromDecryptedBytes(decryptedVideoData: ByteArray): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "OPTIMIZED: Starting video thumbnail generation from decrypted bytes, data size: ${decryptedVideoData.size}")
            
            // Create a temporary file for the video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            // Write decrypted data to temp file
            tempFile.writeBytes(decryptedVideoData)
            
            Log.d("VideoUtils", "OPTIMIZED: Temporary video file created: ${tempFile.absolutePath}, size: ${tempFile.length()}")
            
            // Verify file was written correctly
            if (tempFile.length() == 0L) {
                Log.e("VideoUtils", "Temporary video file is empty!")
                return null
            }
            
            // Use MediaMetadataRetriever to get thumbnail
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Get video metadata for better thumbnail extraction
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            Log.d("VideoUtils", "OPTIMIZED: Video metadata - Duration: ${duration}ms, Resolution: ${width}x${height}")
            
            // Try multiple time positions for better thumbnail extraction, focusing on early frames
            val timePositions = if (duration > 0) {
                listOf(
                    0L, // Start of video (most likely to work)
                    minOf(100000L, duration * 100L), // 0.1 second or 10% of duration 
                    minOf(500000L, duration * 250L), // 0.5 second or 25% of duration
                    minOf(1000000L, duration * 500L) // 1 second or 50% of duration
                )
            } else {
                listOf(0L, 100000L, 500000L, 1000000L) // 0s, 0.1s, 0.5s, 1s
            }
            
            var bitmap: android.graphics.Bitmap? = null
            for (timeUs in timePositions) {
                try {
                    Log.d("VideoUtils", "OPTIMIZED: Attempting to extract frame at time: ${timeUs}µs")
                    bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "OPTIMIZED: Successfully extracted frame at time: ${timeUs}µs, bitmap size: ${bitmap.width}x${bitmap.height}")
                        break
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to extract frame at time ${timeUs}µs", e)
                }
            }
            
            if (bitmap == null) {
                Log.w("VideoUtils", "Failed to extract any frame, trying without time specification")
                try {
                    bitmap = retriever.frameAtTime
                    if (bitmap != null) {
                        Log.d("VideoUtils", "OPTIMIZED: Successfully extracted frame using default method")
                    }
                } catch (e: Exception) {
                    Log.e("VideoUtils", "Failed to extract any frame from video", e)
                }
            }
            
            if (bitmap != null) {
                Log.d("VideoUtils", "OPTIMIZED: Video thumbnail generated successfully, size: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e("VideoUtils", "OPTIMIZED: Failed to generate video thumbnail - all attempts failed")
            }
            
            return bitmap
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "OPTIMIZED: Failed to generate video thumbnail from decrypted bytes", e)
            return null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.e("VideoUtils", "Error releasing MediaMetadataRetriever", e)
            }
            
            // Clean up temporary file
            tempFile?.let { file ->
                try {
                    if (file.exists()) {
                        file.delete()
                        Log.d("VideoUtils", "OPTIMIZED: Temporary video file deleted")
                    }
                } catch (e: Exception) {
                    Log.e("VideoUtils", "Error deleting temporary file", e)
                }
            }
        }
    }

    private fun generateVideoThumbnailFromDecryptedBytes(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            // Extract IV from the encrypted data
            val iv = encryptedVideoData.copyOfRange(0, 16)
            val ciphertext = encryptedVideoData.copyOfRange(16, encryptedVideoData.size)
            
            // Initialize cipher for decryption
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            // Create a temporary file for the decrypted video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            val inputStream = ByteArrayInputStream(ciphertext)
            val outputStream = FileOutputStream(tempFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val decrypted = cipher.update(buffer, 0, bytesRead)
                if (decrypted != null) {
                    outputStream.write(decrypted)
                }
            }
            
            val finalData = cipher.doFinal()
            if (finalData.isNotEmpty()) {
                outputStream.write(finalData)
            }
            
            inputStream.close()
            outputStream.close()
            
            Log.d("VideoUtils", "Video decryption completed, file size: ${tempFile!!.length()}")
            
            // Use MediaMetadataRetriever to get thumbnail
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Try multiple time positions if the first one fails
            val timePositions = longArrayOf(1000000, 0, 500000, 2000000) // 1s, 0s, 0.5s, 2s
            
            for (timeUs in timePositions) {
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "Successfully generated thumbnail at time ${timeUs}us")
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us", e)
                }
            }
            
            Log.e("VideoUtils", "Failed to generate thumbnail at all time positions")
            return null
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate video thumbnail from data", e)
            return null
        } finally {
            // Clean up
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error releasing MediaMetadataRetriever", e)
            }
            
            // Clean up temporary file
            try {
                tempFile?.delete()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error deleting temp file", e)
            }
        }
    }
    
    /**
     * Gets video duration from encrypted video data
     */
    fun getVideoDuration(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): String {
        var tempFile: File? = null
        try {
            // Decrypt the video data
            val iv = encryptedVideoData.copyOfRange(0, 16)
            val ciphertext = encryptedVideoData.copyOfRange(16, encryptedVideoData.size)
            val decryptedData = CryptoUtils.decrypt(iv, ciphertext, key)
            
            // Create a temporary file to store the decrypted video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            val fos = FileOutputStream(tempFile)
            fos.write(decryptedData)
            fos.close()
            
            // Use MediaMetadataRetriever to get duration
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationString?.toLongOrNull() ?: 0L
            retriever.release()
            
            // Convert milliseconds to MM:SS format
            val minutes = (duration / 1000) / 60
            val seconds = (duration / 1000) % 60
            return String.format("%d:%02d", minutes, seconds)
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to get video duration", e)
            return "0:00"
        } finally {
            // Clean up temporary file
            tempFile?.delete()
        }
    }
    
    /**
     * Determines if a URI represents a video file based on MIME type
     */
    fun isVideoMimeType(mimeType: String?): Boolean {
        return mimeType?.startsWith("video/") == true
    }
    
    /**
     * Generate thumbnail from encrypted video file (file-based storage) - OPTIMIZED VERSION
     * Only decrypts the beginning of the video file to extract thumbnail
     */
    fun generateVideoThumbnailFromFile(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Generating thumbnail from file (OPTIMIZED): $encryptedFilePath")
            
            val encryptedFile = File(encryptedFilePath)
            if (!encryptedFile.exists()) {
                Log.e("VideoUtils", "Encrypted file does not exist: $encryptedFilePath")
                return null
            }
            
            Log.d("VideoUtils", "Encrypted file size: ${encryptedFile.length()} bytes")
            
            // Create temporary file for PARTIAL decrypted video
            tempFile = File.createTempFile("temp_video_file_", ".mp4")
            tempFile.deleteOnExit()
            
            val inputStream = FileInputStream(encryptedFile)
            val outputStream = FileOutputStream(tempFile)
            
            // Read IV from file
            val iv = ByteArray(16)
            val ivBytesRead = inputStream.read(iv)
            if (ivBytesRead != 16) {
                Log.e("VideoUtils", "Failed to read complete IV from file")
                return null
            }
            
            // OPTIMIZATION: Only decrypt the first 5MB for thumbnail generation
            // This is usually enough to get video headers and first frames
            val maxBytesToDecrypt = 5 * 1024 * 1024 // 5MB limit
            val totalEncryptedSize = encryptedFile.length() - 16 // Subtract IV size
            val bytesToDecrypt = minOf(maxBytesToDecrypt.toLong(), totalEncryptedSize)
            
            Log.d("VideoUtils", "OPTIMIZATION: Decrypting only first ${bytesToDecrypt / 1024 / 1024}MB instead of full ${totalEncryptedSize / 1024 / 1024}MB")
            
            // Decrypt only the beginning of the file
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            val buffer = ByteArray(8192)
            var totalBytesDecrypted = 0L
            var bytesRead = 0
            
            while (totalBytesDecrypted < bytesToDecrypt && inputStream.read(buffer).also { bytesRead = it } != -1) {
                val remainingBytes = (bytesToDecrypt - totalBytesDecrypted).toInt()
                val bytesToProcess = minOf(bytesRead, remainingBytes)
                
                val decrypted = cipher.update(buffer, 0, bytesToProcess)
                if (decrypted != null) {
                    outputStream.write(decrypted)
                }
                
                totalBytesDecrypted += bytesToProcess
                
                // If we've read enough data, break out of the loop
                if (totalBytesDecrypted >= bytesToDecrypt) {
                    break
                }
            }
            
            // Finalize only if we processed some data
            if (totalBytesDecrypted > 0) {
                try {
                    val finalData = cipher.doFinal()
                    if (finalData.isNotEmpty()) {
                        outputStream.write(finalData)
                    }
                } catch (e: Exception) {
                    // Ignore padding errors when doing partial decryption
                    Log.d("VideoUtils", "Padding error ignored during partial decryption (expected): ${e.message}")
                }
            }
            
            inputStream.close()
            outputStream.close()
            
            Log.d("VideoUtils", "OPTIMIZED: Partial decrypted video file created: ${tempFile.absolutePath}, size: ${tempFile.length()} (${totalBytesDecrypted} bytes decrypted)")
            
            // Generate thumbnail from partial decrypted temp file
            retriever = MediaMetadataRetriever()
            
            try {
                retriever.setDataSource(tempFile.absolutePath)
                
                // Try to get thumbnail from the beginning of the video (most likely to succeed with partial data)
                val timePositions = longArrayOf(0, 100000, 500000, 1000000) // 0s, 0.1s, 0.5s, 1s
                
                for (timeUs in timePositions) {
                    try {
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bitmap != null) {
                            Log.d("VideoUtils", "OPTIMIZED: Successfully generated thumbnail from file at time ${timeUs}us")
                            return bitmap
                        }
                    } catch (e: Exception) {
                        Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us from partial file", e)
                    }
                }
                
                // If specific time positions fail, try getting any frame
                try {
                    val bitmap = retriever.frameAtTime
                    if (bitmap != null) {
                        Log.d("VideoUtils", "OPTIMIZED: Successfully generated thumbnail using default frame extraction")
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to get default frame from partial file", e)
                }
                
            } catch (e: Exception) {
                Log.w("VideoUtils", "Failed to set data source on partial file, falling back to full decryption", e)
                
                // FALLBACK: If partial decryption doesn't work, try full decryption
                return generateVideoThumbnailFromFileFull(encryptedFilePath, key)
            }
            
            Log.e("VideoUtils", "OPTIMIZED: Failed to generate thumbnail from partial file at all time positions")
            
            // FALLBACK: Try full decryption if partial approach fails
            Log.d("VideoUtils", "Falling back to full file decryption for thumbnail generation")
            return generateVideoThumbnailFromFileFull(encryptedFilePath, key)
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate thumbnail from file (optimized)", e)
            
            // FALLBACK: Try full decryption if optimized approach fails
            Log.d("VideoUtils", "Optimized approach failed, falling back to full file decryption")
            return generateVideoThumbnailFromFileFull(encryptedFilePath, key)
            
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error releasing MediaMetadataRetriever", e)
            }
            
            try {
                tempFile?.delete()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error deleting temp file", e)
            }
        }
    }
    
    /**
     * FALLBACK: Full file decryption for thumbnail generation (used when optimized approach fails)
     */
    private fun generateVideoThumbnailFromFileFull(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "FALLBACK: Full file decryption for thumbnail: $encryptedFilePath")
            
            val encryptedFile = File(encryptedFilePath)
            if (!encryptedFile.exists()) {
                Log.e("VideoUtils", "Encrypted file does not exist: $encryptedFilePath")
                return null
            }
            
            // Create temporary file for full decrypted video
            tempFile = File.createTempFile("temp_video_full_", ".mp4")
            tempFile.deleteOnExit()
            
            val inputStream = FileInputStream(encryptedFile)
            val outputStream = FileOutputStream(tempFile)
            
            // Read IV from file
            val iv = ByteArray(16)
            val ivBytesRead = inputStream.read(iv)
            if (ivBytesRead != 16) {
                Log.e("VideoUtils", "Failed to read complete IV from file")
                return null
            }
            
            // Decrypt the rest of the file
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            val cipherInputStream = javax.crypto.CipherInputStream(inputStream, cipher)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            
            while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            cipherInputStream.close()
            inputStream.close()
            outputStream.close()
            
            Log.d("VideoUtils", "FALLBACK: Full decrypted video file created: ${tempFile.absolutePath}, size: ${tempFile.length()}")
            
            // Generate thumbnail from decrypted temp file
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Try multiple time positions if the first one fails
            val timePositions = longArrayOf(0, 1000000, 500000, 2000000) // 0s, 1s, 0.5s, 2s
            
            for (timeUs in timePositions) {
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "FALLBACK: Successfully generated thumbnail from full file at time ${timeUs}us")
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us from full file", e)
                }
            }
            
            Log.e("VideoUtils", "FALLBACK: Failed to generate thumbnail from full file at all time positions")
            return null
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "FALLBACK: Failed to generate thumbnail from full file", e)
            return null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error releasing MediaMetadataRetriever", e)
            }
            
            try {
                tempFile?.delete()
            } catch (e: Exception) {
                Log.w("VideoUtils", "Error deleting temp file", e)
            }
        }
    }
    
    /**
     * Get video duration from SecureMedia
     */
    fun getVideoDuration(secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): String {
        return if (secureMedia.usesExternalStorage()) {
            getVideoDurationFromFile(secureMedia.filePath!!, key)
        } else {
            getVideoDuration(secureMedia.getEncryptedData(), key)
        }
    }
    
    /**
     * Get video duration from encrypted file (file-based storage)
     */
    private fun getVideoDurationFromFile(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): String {
        var tempFile: File? = null
        try {
            // Create temporary file for decrypted video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            // Decrypt file using streaming (same as thumbnail method)
            val encryptedFile = File(encryptedFilePath)
            val inputStream = FileInputStream(encryptedFile)
            val outputStream = FileOutputStream(tempFile)
            
            // Read IV from file
            val iv = ByteArray(16)
            inputStream.read(iv)
            
            // Decrypt using proper streaming approach
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
            // Use CipherInputStream for proper streaming decryption
            val cipherInputStream = javax.crypto.CipherInputStream(inputStream, cipher)
            
            // Copy decrypted data in chunks
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = cipherInputStream.read(buffer)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
            }
            
            cipherInputStream.close()
            inputStream.close()
            
            outputStream.close()
            
            // Get duration from decrypted temp file
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationString?.toLongOrNull() ?: 0L
            retriever.release()
            
            // Convert milliseconds to MM:SS format
            val minutes = (duration / 1000) / 60
            val seconds = (duration / 1000) % 60
            return String.format("%02d:%02d", minutes, seconds)
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Failed to get duration from file", e)
            return "00:00"
        } finally {
            tempFile?.delete()
        }
    }
}
