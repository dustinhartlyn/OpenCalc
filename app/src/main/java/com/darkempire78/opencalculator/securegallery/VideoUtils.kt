package com.darkempire78.opencalculator.securegallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object VideoUtils {
    
    /**
     * Generates a thumbnail bitmap from encrypted video data
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
}
