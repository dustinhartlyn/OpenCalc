package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
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
            secureMedia.filePath + secureMedia.name
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
     */
    fun generateVideoThumbnail(context: Context, secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        // First, try to load from cache
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
            
            // Use MediaMetadataRetriever to get thumbnail
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            
            // Get thumbnail at 1 second (1000000 microseconds)
            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            
            return bitmap
            
        } catch (e: Exception) {
            Log.e("VideoUtils", "Failed to generate video thumbnail", e)
            return null
        } finally {
            // Clean up temporary file
            tempFile?.delete()
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
     * Optimized to only decrypt the minimum amount needed for thumbnail
     */
    private fun generateVideoThumbnailFromFile(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        try {
            // Create temporary file for partially decrypted video
            tempFile = File.createTempFile("temp_video_thumb", ".mp4")
            tempFile.deleteOnExit()
            
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
            
            // Only decrypt first 2MB for thumbnail generation (should be enough for headers + first frames)
            val maxBytesForThumbnail = 2 * 1024 * 1024 // 2MB
            val buffer = ByteArray(8192)
            var totalBytesRead = 0
            
            while (totalBytesRead < maxBytesForThumbnail) {
                val bytesRead = cipherInputStream.read(buffer)
                if (bytesRead == -1) break
                
                val bytesToWrite = minOf(bytesRead, maxBytesForThumbnail - totalBytesRead)
                outputStream.write(buffer, 0, bytesToWrite)
                totalBytesRead += bytesToWrite
                
                if (totalBytesRead >= maxBytesForThumbnail) break
            }
            
            cipherInputStream.close()
            inputStream.close()
            outputStream.close()
            
            // Generate thumbnail from partially decrypted temp file
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                return bitmap
            } catch (e: Exception) {
                // If partial file doesn't work, try with a bit more data
                retriever.release()
                return generateThumbnailWithMoreData(encryptedFilePath, key)
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Failed to generate thumbnail from file", e)
            return null
        } finally {
            tempFile?.delete()
        }
    }
    
    /**
     * Fallback method to generate thumbnail with more data if initial attempt fails
     */
    private fun generateThumbnailWithMoreData(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        try {
            // Create temporary file for more decrypted video data
            tempFile = File.createTempFile("temp_video_full", ".mp4")
            tempFile.deleteOnExit()
            
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
            
            // Decrypt up to 10MB for thumbnail (larger fallback)
            val maxBytesForThumbnail = 10 * 1024 * 1024 // 10MB
            val buffer = ByteArray(8192)
            var totalBytesRead = 0
            
            while (totalBytesRead < maxBytesForThumbnail) {
                val bytesRead = cipherInputStream.read(buffer)
                if (bytesRead == -1) break
                
                val bytesToWrite = minOf(bytesRead, maxBytesForThumbnail - totalBytesRead)
                outputStream.write(buffer, 0, bytesToWrite)
                totalBytesRead += bytesToWrite
                
                if (totalBytesRead >= maxBytesForThumbnail) break
            }
            
            cipherInputStream.close()
            inputStream.close()
            outputStream.close()
            
            // Generate thumbnail from larger partially decrypted temp file
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            
            return bitmap
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Failed to generate thumbnail with more data", e)
            return null
        } finally {
            tempFile?.delete()
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
