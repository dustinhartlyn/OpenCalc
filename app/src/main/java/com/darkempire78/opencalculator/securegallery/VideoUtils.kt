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
     * Generate and save thumbnail for a video during import
     */
    fun generateAndSaveThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): Boolean {
        try {
            // Generate thumbnail
            val thumbnail = if (secureMedia.usesExternalStorage()) {
                generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
            } else {
                generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
            }
            
            if (thumbnail != null) {
                // Save thumbnail to internal storage
                saveThumbnailToCache(context, secureMedia, thumbnail)
                return true
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate and save thumbnail", e)
        }
        return false
    }
    
    /**
     * Load cached thumbnail for a video
     */
    fun loadCachedThumbnail(context: Context, secureMedia: SecureMedia): Bitmap? {
        try {
            val thumbnailFile = getThumbnailFile(context, secureMedia)
            if (thumbnailFile.exists()) {
                return BitmapFactory.decodeFile(thumbnailFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to load cached thumbnail", e)
        }
        return null
    }
    
    /**
     * Save thumbnail bitmap to internal cache
     */
    private fun saveThumbnailToCache(context: Context, secureMedia: SecureMedia, thumbnail: Bitmap) {
        val thumbnailFile = getThumbnailFile(context, secureMedia)
        thumbnailFile.parentFile?.mkdirs()
        
        FileOutputStream(thumbnailFile).use { outputStream ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        }
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
     * Clean up cached thumbnails for media that no longer exists
     */
    fun cleanupOrphanedThumbnails(context: Context, existingMedia: List<SecureMedia>) {
        try {
            val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
            if (!thumbnailsDir.exists()) return
            
            val validHashes = existingMedia.map { generateMediaHash(it) }.toSet()
            
            thumbnailsDir.listFiles()?.forEach { file ->
                val hashFromFile = file.nameWithoutExtension
                if (hashFromFile !in validHashes) {
                    file.delete()
                    Log.d("VideoUtils", "Deleted orphaned thumbnail: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to cleanup orphaned thumbnails", e)
        }
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
     * Generates a thumbnail bitmap from SecureMedia (with caching)
     * Note: This method is kept for backward compatibility but ThumbnailGenerator is preferred
     */
    fun generateVideoThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        // First, try to load from cache (both encrypted and unencrypted formats)
        val cachedThumbnail = loadCachedThumbnail(context, secureMedia)
        if (cachedThumbnail != null) {
            return cachedThumbnail
        }
        
        // If not cached, generate and save
        val thumbnail = if (secureMedia.usesExternalStorage()) {
            generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
        } else {
            generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
        }
        
        if (thumbnail != null) {
            // Save to cache for next time
            saveThumbnailToCache(context, secureMedia, thumbnail)
        }
        
        return thumbnail
    }
    
    /**
     * Generates a thumbnail bitmap from SecureMedia (legacy method without context)
     */
    fun generateVideoThumbnail(secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        return if (secureMedia.usesExternalStorage()) {
            generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
        } else {
            generateVideoThumbnailFromData(secureMedia.getEncryptedData(), key)
        }
    }
    
    /**
     * Generates a thumbnail bitmap from encrypted video data (legacy method)
     */
    fun generateVideoThumbnailFromData(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Generating thumbnail from data, encrypted size: ${encryptedVideoData.size}")
            
            // Ensure we have enough data for IV + content
            if (encryptedVideoData.size < 17) { // 16 bytes IV + at least 1 byte data
                Log.e("VideoUtils", "Encrypted video data too small: ${encryptedVideoData.size} bytes")
                return null
            }
            
            // Decrypt the video data using streaming to avoid memory issues
            val iv = encryptedVideoData.copyOfRange(0, 16)
            
            // Create a temporary file to store the decrypted video
            tempFile = File.createTempFile("temp_video_thumb_", ".mp4")
            tempFile.deleteOnExit()
            
            // Use streaming decryption to avoid loading entire file into memory
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            return generateVideoThumbnailFromDecryptedBytes(encryptedVideoData, key)
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate thumbnail from encrypted data", e)
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
     * Generate video thumbnail from already decrypted video bytes (for thumbnail generation)
     */
    fun generateVideoThumbnailFromDecryptedBytes(decryptedVideoData: ByteArray): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Starting video thumbnail generation, data size: ${decryptedVideoData.size}")
            
            // Create a temporary file for the video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            // Write decrypted data to temp file
            tempFile.writeBytes(decryptedVideoData)
            
            Log.d("VideoUtils", "Temporary video file created: ${tempFile.absolutePath}, size: ${tempFile.length()}")
            
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
            
            Log.d("VideoUtils", "Video metadata - Duration: ${duration}ms, Resolution: ${width}x${height}")
            
            // Try multiple time positions for better thumbnail extraction
            val timePositions = if (duration > 0) {
                listOf(
                    minOf(1000000L, duration * 100L), // 1 second or 10% of duration
                    duration * 500L, // 50% of duration
                    duration * 250L  // 25% of duration
                )
            } else {
                listOf(1000000L, 2000000L, 500000L) // 1s, 2s, 0.5s
            }
            
            var bitmap: android.graphics.Bitmap? = null
            for (timeUs in timePositions) {
                try {
                    Log.d("VideoUtils", "Attempting to extract frame at time: ${timeUs}µs")
                    bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "Successfully extracted frame at time: ${timeUs}µs, bitmap size: ${bitmap.width}x${bitmap.height}")
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
                } catch (e: Exception) {
                    Log.e("VideoUtils", "Failed to extract any frame from video", e)
                }
            }
            
            if (bitmap != null) {
                Log.d("VideoUtils", "Video thumbnail generated successfully, size: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e("VideoUtils", "Failed to generate video thumbnail - all attempts failed")
            }
            
            return bitmap
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate video thumbnail from decrypted bytes", e)
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
                        Log.d("VideoUtils", "Temporary video file deleted")
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
     * Generate thumbnail from encrypted video file (file-based storage)
     */
    fun generateVideoThumbnailFromFile(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("VideoUtils", "Generating thumbnail from file: $encryptedFilePath")
            
            val encryptedFile = File(encryptedFilePath)
            if (!encryptedFile.exists()) {
                Log.e("VideoUtils", "Encrypted file does not exist: $encryptedFilePath")
                return null
            }
            
            Log.d("VideoUtils", "Encrypted file size: ${encryptedFile.length()} bytes")
            
            // Create temporary file for decrypted video
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
            
            Log.d("VideoUtils", "Decrypted video file created: ${tempFile.absolutePath}, size: ${tempFile.length()}")
            
            // Generate thumbnail from decrypted temp file
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Try multiple time positions if the first one fails
            val timePositions = longArrayOf(0, 1000000, 500000, 2000000) // 0s, 1s, 0.5s, 2s
            
            for (timeUs in timePositions) {
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        Log.d("VideoUtils", "Successfully generated thumbnail from file at time ${timeUs}us")
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w("VideoUtils", "Failed to get frame at time ${timeUs}us from file", e)
                }
            }
            
            Log.e("VideoUtils", "Failed to generate thumbnail from file at all time positions")
            return null
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate thumbnail from file", e)
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
