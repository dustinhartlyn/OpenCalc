package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import java.io.Serializable

// Data model for a secure gallery
class Gallery(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    val salt: ByteArray,
    val notes: MutableList<SecureNote> = mutableListOf(),
    val media: MutableList<SecureMedia> = mutableListOf(), // Changed from photos to media
    var pinHash: ByteArray? = null, // Secure PIN verification hash
    var sortOrder: GallerySortOrder = GallerySortOrder.NAME, // Media sort order
    var customOrder: MutableList<Int> = mutableListOf() // Custom order indices
) : Serializable {
    
    // Backward compatibility property for existing code that references 'photos'
    @Deprecated("Use 'media' instead", ReplaceWith("media"))
    val photos: MutableList<SecurePhoto>
        get() = media.filter { it.isPhoto() }.map { mediaToSecurePhoto(it) }.toMutableList()
    
    // Helper function to convert SecureMedia to SecurePhoto for backward compatibility
    private fun mediaToSecurePhoto(media: SecureMedia): SecurePhoto {
        return SecurePhoto(
            id = media.id,
            encryptedData = media.getEncryptedData(),
            name = media.name,
            date = media.date,
            customOrder = media.customOrder
        )
    }
}
