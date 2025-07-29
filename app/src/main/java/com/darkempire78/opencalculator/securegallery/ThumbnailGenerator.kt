package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

object ThumbnailGenerator {
    
    private const val THUMBNAIL_SIZE = 16 // 16x scaling for very small thumbnails
    private const val THUMBNAIL_QUALITY = 75 // JPEG quality for thumbnails
    
    /**
     * Generates and saves an encrypted thumbnail for a photo during import
     */
    fun generatePhotoThumbnail(
        context: Context,
        originalImageBytes: ByteArray,
        mediaId: String,
        galleryName: String,
        key: SecretKeySpec
    ): String? {
        return try {
            // Create thumbnail bitmap with small size
            val options = BitmapFactory.Options().apply {
                inSampleSize = THUMBNAIL_SIZE
                inPreferredConfig = Bitmap.Config.RGB_565
                inPurgeable = true
                inInputShareable = true
            }
            
            val thumbnail = BitmapFactory.decodeByteArray(originalImageBytes, 0, originalImageBytes.size, options)
                ?: return null
            
            // Convert thumbnail to JPEG bytes
            val thumbnailBytes = ByteArrayOutputStream().use { stream ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
                stream.toByteArray()
            }
            
            // Clean up bitmap
            if (!thumbnail.isRecycled) {
                thumbnail.recycle()
            }
            
            // Encrypt thumbnail
            val (iv, ciphertext) = CryptoUtils.encrypt(thumbnailBytes, key)
            val encryptedThumbnail = iv + ciphertext // Combine IV and ciphertext
            
            // Save encrypted thumbnail to file
            val thumbnailFile = getThumbnailFile(context, galleryName, mediaId)
            thumbnailFile.writeBytes(encryptedThumbnail)
            
            thumbnailFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to generate photo thumbnail", e)
            null
        }
    }
    
    /**
     * Generates and saves an encrypted thumbnail for a video during import
     */
    fun generateVideoThumbnail(
        context: Context,
        videoBytes: ByteArray,
        mediaId: String,
        galleryName: String,
        key: SecretKeySpec
    ): String? {
        return try {
            // Use VideoUtils to generate thumbnail from video bytes
            val thumbnail = VideoUtils.generateVideoThumbnailFromBytes(videoBytes, key)
                ?: return null
            
            // Convert thumbnail to JPEG bytes
            val thumbnailBytes = ByteArrayOutputStream().use { stream ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
                stream.toByteArray()
            }
            
            // Clean up bitmap
            if (!thumbnail.isRecycled) {
                thumbnail.recycle()
            }
            
            // Encrypt thumbnail
            val (iv, ciphertext) = CryptoUtils.encrypt(thumbnailBytes, key)
            val encryptedThumbnail = iv + ciphertext // Combine IV and ciphertext
            
            // Save encrypted thumbnail to file
            val thumbnailFile = getThumbnailFile(context, galleryName, mediaId)
            thumbnailFile.writeBytes(encryptedThumbnail)
            
            thumbnailFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to generate video thumbnail", e)
            null
        }
    }
    
    /**
     * Generates and saves an encrypted thumbnail for a video file during import
     */
    fun generateVideoThumbnailFromFile(
        context: Context,
        videoFilePath: String,
        mediaId: String,
        galleryName: String,
        key: SecretKeySpec
    ): String? {
        return try {
            // Use VideoUtils to generate thumbnail from file
            val thumbnail = VideoUtils.generateVideoThumbnailFromFile(videoFilePath, key)
                ?: return null
            
            // Convert thumbnail to JPEG bytes
            val thumbnailBytes = ByteArrayOutputStream().use { stream ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
                stream.toByteArray()
            }
            
            // Clean up bitmap
            if (!thumbnail.isRecycled) {
                thumbnail.recycle()
            }
            
            // Encrypt thumbnail
            val (iv, ciphertext) = CryptoUtils.encrypt(thumbnailBytes, key)
            val encryptedThumbnail = iv + ciphertext // Combine IV and ciphertext
            
            // Save encrypted thumbnail to file
            val thumbnailFile = getThumbnailFile(context, galleryName, mediaId)
            thumbnailFile.writeBytes(encryptedThumbnail)
            
            thumbnailFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to generate video thumbnail from file", e)
            null
        }
    }
    
    /**
     * Loads a pre-generated encrypted thumbnail
     */
    fun loadEncryptedThumbnail(
        thumbnailPath: String,
        key: SecretKeySpec
    ): Bitmap? {
        return try {
            val encryptedBytes = File(thumbnailPath).readBytes()
            val iv = encryptedBytes.copyOfRange(0, 16)
            val ct = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
            
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to load encrypted thumbnail", e)
            null
        }
    }
    
    /**
     * Gets the thumbnail path for a media item
     */
    fun getThumbnailPath(context: Context, galleryName: String, mediaId: String): String {
        return getThumbnailFile(context, galleryName, mediaId).absolutePath
    }
    
    /**
     * Gets the file path for storing a thumbnail
     */
    private fun getThumbnailFile(context: Context, galleryName: String, mediaId: String): File {
        val thumbnailsDir = File(context.filesDir, "thumbnails/$galleryName")
        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }
        return File(thumbnailsDir, "$mediaId.thumb")
    }
    
    /**
     * Deletes a thumbnail file
     */
    fun deleteThumbnail(thumbnailPath: String) {
        try {
            File(thumbnailPath).delete()
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to delete thumbnail", e)
        }
    }
    
    /**
     * Cleans up orphaned thumbnail files for a gallery
     */
    fun cleanupThumbnails(context: Context, galleryName: String, validMediaIds: Set<String>) {
        try {
            val thumbnailsDir = File(context.filesDir, "thumbnails/$galleryName")
            if (!thumbnailsDir.exists()) return
            
            thumbnailsDir.listFiles()?.forEach { file ->
                val mediaId = file.nameWithoutExtension
                if (mediaId !in validMediaIds) {
                    file.delete()
                    android.util.Log.d("ThumbnailGenerator", "Deleted orphaned thumbnail: ${file.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ThumbnailGenerator", "Failed to cleanup thumbnails", e)
        }
    }
}
