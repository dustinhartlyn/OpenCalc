package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import java.io.Serializable

// Media types supported by the secure gallery
enum class MediaType {
    PHOTO, VIDEO
}

// Data model for encrypted media (photos and videos)
class SecureMedia(
    val id: UUID = UUID.randomUUID(),
    private val _encryptedData: ByteArray,
    val name: String,
    val date: Long,
    val mediaType: MediaType,
    var customOrder: Int = -1, // For custom sorting, -1 means not set
    val filePath: String? = null // For large files stored separately
) : Serializable {
    
    // Convenience method to check if this is a photo
    fun isPhoto(): Boolean = mediaType == MediaType.PHOTO
    
    // Convenience method to check if this is a video
    fun isVideo(): Boolean = mediaType == MediaType.VIDEO
    
    // Get file extension based on media type
    fun getDefaultExtension(): String {
        return when (mediaType) {
            MediaType.PHOTO -> ".jpg"
            MediaType.VIDEO -> ".mp4"
        }
    }
    
    // Check if this media uses external file storage
    fun usesExternalStorage(): Boolean = filePath != null
    
    // Get the actual encrypted data (from memory or file)
    fun getEncryptedData(): ByteArray {
        return if (filePath != null) {
            java.io.File(filePath).readBytes()
        } else {
            _encryptedData
        }
    }
    
    // Companion object for file management
    companion object {
        fun createWithFileStorage(
            name: String,
            date: Long,
            mediaType: MediaType,
            encryptedFilePath: String,
            customOrder: Int = -1
        ): SecureMedia {
            return SecureMedia(
                _encryptedData = ByteArray(0), // Empty array for file-based storage
                name = name,
                date = date,
                mediaType = mediaType,
                customOrder = customOrder,
                filePath = encryptedFilePath
            )
        }
    }
}
