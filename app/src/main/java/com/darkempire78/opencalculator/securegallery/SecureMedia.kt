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
    val encryptedData: ByteArray,
    val name: String,
    val date: Long,
    val mediaType: MediaType,
    var customOrder: Int = -1 // For custom sorting, -1 means not set
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
}
