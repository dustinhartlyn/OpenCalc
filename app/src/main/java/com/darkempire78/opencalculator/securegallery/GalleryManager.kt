package com.darkempire78.opencalculator.securegallery

import java.util.UUID

object GalleryManager {
    // Create a new gallery with a pin and name
    fun createGallery(pin: String, name: String): Boolean {
        if (getGalleries().any { it.name == name }) {
            android.util.Log.d("SecureGallery", "Gallery creation failed: name already exists.")
            return false
        }
        val salt = CryptoUtils.generateSalt()
        val key = CryptoUtils.deriveKey(pin, salt)
        val titlePlain = "Welcome"
        val bodyPlain = "Your new gallery is ready."
        val encryptedTitlePair = CryptoUtils.encrypt(titlePlain.toByteArray(Charsets.UTF_8), key)
        val encryptedBodyPair = CryptoUtils.encrypt(bodyPlain.toByteArray(Charsets.UTF_8), key)
        val encryptedTitle = encryptedTitlePair.first + encryptedTitlePair.second
        val encryptedBody = encryptedBodyPair.first + encryptedBodyPair.second
        val welcomeNote = SecureNote(
            encryptedTitle = encryptedTitle,
            encryptedBody = encryptedBody,
            date = System.currentTimeMillis()
        )
        val newGallery = Gallery(
            name = name,
            salt = salt,
            notes = mutableListOf(welcomeNote),
            photos = mutableListOf()
        )
        addGallery(newGallery)
        android.util.Log.d("SecureGallery", "Gallery created: $name with pin $pin")
        return true
    }

    // Rename an existing gallery
    fun renameGallery(oldName: String, newName: String): Boolean {
        val gallery = getGalleries().find { it.name == oldName }
        if (gallery == null) {
            android.util.Log.d("SecureGallery", "Rename failed: gallery not found.")
            return false
        }
        if (getGalleries().any { it.name == newName }) {
            android.util.Log.d("SecureGallery", "Rename failed: new name already exists.")
            return false
        }
        gallery.name = newName
        android.util.Log.d("SecureGallery", "Gallery renamed from $oldName to $newName")
        return true
    }

    // Delete a gallery by name
    fun deleteGallery(name: String): Boolean {
        val gallery = getGalleries().find { it.name == name }
        if (gallery == null) {
            android.util.Log.d("SecureGallery", "Delete failed: gallery not found.")
            return false
        }
        galleries.remove(gallery)
        android.util.Log.d("SecureGallery", "Gallery deleted: $name")
        return true
    }
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
