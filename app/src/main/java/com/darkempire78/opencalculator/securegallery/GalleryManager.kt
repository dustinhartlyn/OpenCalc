package com.darkempire78.opencalculator.securegallery

import java.util.UUID

object GalleryManager {
    private val galleries = mutableListOf<Gallery>()

    fun getGalleries(): List<Gallery> = galleries

    fun addGallery(gallery: Gallery) {
        galleries.add(gallery)
    }

    fun findGalleryByPin(pin: String): Gallery? {
        // Try to decrypt each gallery with the pin
        for (gallery in galleries) {
            val key = CryptoUtils.deriveKey(pin, gallery.salt)
            // Try to decrypt a known test value (e.g., gallery name)
            // If successful, return gallery
            // This is a placeholder for actual decryption test
        }
        return null
    }

    fun removeGallery(id: UUID) {
        galleries.removeAll { it.id == id }
    }
}
