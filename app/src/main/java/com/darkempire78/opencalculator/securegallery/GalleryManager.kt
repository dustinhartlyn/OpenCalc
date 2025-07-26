package com.darkempire78.opencalculator.securegallery

import java.util.UUID

object GalleryManager {
    private val galleries = mutableListOf<Gallery>()

    fun getGalleries(): List<Gallery> = galleries

    fun addGallery(gallery: Gallery) {
        galleries.add(gallery)
    }

    fun findGalleryByPin(pin: String): Gallery? {
        for (gallery in galleries) {
            val key = CryptoUtils.deriveKey(pin, gallery.salt)
            if (gallery.notes.isNotEmpty()) {
                val note = gallery.notes[0]
                try {
                    // Try to decrypt the title using the key
                    // Assume the first 16 bytes of encryptedTitle are IV, rest is ciphertext
                    val iv = note.encryptedTitle.sliceArray(0 until 16)
                    val ciphertext = note.encryptedTitle.sliceArray(16 until note.encryptedTitle.size)
                    val decrypted = CryptoUtils.decrypt(iv, ciphertext, key)
                    val title = String(decrypted, Charsets.UTF_8)
                    android.util.Log.d("SecureGallery", "Pin test: decrypted title='${title}' for gallery='${gallery.name}'")
                    // If the decrypted title is valid UTF-8 and matches expected, unlock
                    if (title == "Welcome") {
                        android.util.Log.d("SecureGallery", "Pin correct for gallery '${gallery.name}'")
                        return gallery
                    }
                } catch (e: Exception) {
                    android.util.Log.d("SecureGallery", "Pin test failed for gallery '${gallery.name}': ${e.message}")
                }
            }
        }
        return null
    }

    fun removeGallery(id: UUID) {
        galleries.removeAll { it.id == id }
    }
}
