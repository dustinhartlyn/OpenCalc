package com.darkempire78.opencalculator.securegallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object VideoUtils {
    
    /**
     * Generates a thumbnail bitmap from SecureMedia
     */
    fun generateVideoThumbnail(secureMedia: SecureMedia, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        try {
            if (secureMedia.usesExternalStorage()) {
                // For file-based storage, decrypt the file directly
                return generateVideoThumbnailFromFile(secureMedia.filePath!!, key)
            } else {
                // For in-memory storage, use the original method
                return generateVideoThumbnail(secureMedia.encryptedData, key)
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Failed to generate thumbnail", e)
            return null
        }
    }
    
    /**
     * Generates a thumbnail bitmap from encrypted video data (legacy method)
     */
    fun generateVideoThumbnail(encryptedVideoData: ByteArray, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
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
     */
    private fun generateVideoThumbnailFromFile(encryptedFilePath: String, key: javax.crypto.spec.SecretKeySpec): android.graphics.Bitmap? {
        var tempFile: File? = null
        try {
            // Create temporary file for decrypted video
            tempFile = File.createTempFile("temp_video", ".mp4")
            tempFile.deleteOnExit()
            
            // Decrypt file using streaming
            val encryptedFile = File(encryptedFilePath)
            val inputStream = FileInputStream(encryptedFile)
            val outputStream = FileOutputStream(tempFile)
            
            // Read IV from file
            val iv = ByteArray(16)
            inputStream.read(iv)
            
            // Decrypt the rest using streaming
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
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
            
            // Generate thumbnail from decrypted temp file
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            
            return bitmap
        } catch (e: Exception) {
            android.util.Log.e("VideoUtils", "Failed to generate thumbnail from file", e)
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
            getVideoDuration(secureMedia.encryptedData, key)
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
            
            // Decrypt the rest using streaming
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            
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
