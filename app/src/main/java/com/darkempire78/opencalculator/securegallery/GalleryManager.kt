package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import android.content.Context
import java.io.File
import java.io.ObjectOutputStream
import java.io.ObjectInputStream

object GalleryManager {
    private val galleries = mutableListOf<Gallery>()
    private var applicationContext: Context? = null

    fun setContext(context: Context) {
        applicationContext = context
    }

    fun getGalleries(): List<Gallery> = galleries

    fun addGallery(gallery: Gallery) {
        galleries.add(gallery)
    }

    // Create a new gallery with a pin, name, and security level
    fun createGallery(pin: String, name: String, securityLevel: Int = 1): Boolean {
        if (getGalleries().any { it.name == name }) {
            android.util.Log.d("SecureGallery", "Gallery creation failed: name already exists.")
            return false
        }
        val salt = CryptoUtils.generateSalt()
        val pinHash = CryptoUtils.generatePinHash(pin, salt)
        
        val newGallery = Gallery(
            name = name,
            salt = salt,
            notes = mutableListOf(), // Start with empty notes list
            media = mutableListOf(),
            pinHash = pinHash,
            securityLevel = securityLevel
        )
        addGallery(newGallery)
        saveGalleries()
        android.util.Log.d("SecureGallery", "Gallery created: $name with pin $pin, security level $securityLevel")
        return true
    }

    // Rename an existing gallery by UUID
    fun renameGallery(galleryId: UUID, newName: String): Boolean {
        val gallery = getGalleries().find { it.id == galleryId }
        if (gallery == null) {
            android.util.Log.d("SecureGallery", "Rename failed: gallery not found.")
            return false
        }
        if (getGalleries().any { it.name == newName }) {
            android.util.Log.d("SecureGallery", "Rename failed: new name already exists.")
            return false
        }
        gallery.name = newName
        saveGalleries()
        android.util.Log.d("SecureGallery", "Gallery renamed to $newName")
        return true
    }

    // Delete a gallery by UUID
    fun deleteGallery(galleryId: UUID): Boolean {
        val gallery = getGalleries().find { it.id == galleryId }
        if (gallery == null) {
            android.util.Log.d("SecureGallery", "Delete failed: gallery not found.")
            return false
        }
        galleries.remove(gallery)
        saveGalleries()
        android.util.Log.d("SecureGallery", "Gallery deleted: ${gallery.name}")
        return true
    }

    fun findGalleryByPin(pin: String): Gallery? {
        for (gallery in galleries) {
            // Use the secure PIN hash verification
            if (gallery.pinHash != null && CryptoUtils.verifyPin(pin, gallery.salt, gallery.pinHash!!)) {
                android.util.Log.d("SecureGallery", "Pin correct for gallery '${gallery.name}'")
                return gallery
            }
        }
        android.util.Log.d("SecureGallery", "No gallery found with the provided pin")
        return null
    }

    fun removeGallery(id: UUID) {
        galleries.removeAll { it.id == id }
        saveGalleries()
    }

    fun saveGalleries() {
        val context = applicationContext ?: return
        try {
            val file = File(context.filesDir, "galleries.dat")
            ObjectOutputStream(file.outputStream()).use { oos ->
                oos.writeObject(galleries.toList())
            }
            android.util.Log.d("SecureGallery", "Galleries saved successfully")
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Failed to save galleries", e)
        }
    }

    fun loadGalleries() {
        val context = applicationContext ?: return
        try {
            val file = File(context.filesDir, "galleries.dat")
            if (file.exists()) {
                ObjectInputStream(file.inputStream()).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val loadedGalleries = ois.readObject() as List<Gallery>
                    galleries.clear()
                    galleries.addAll(loadedGalleries)
                    android.util.Log.d("SecureGallery", "Galleries loaded successfully: ${galleries.size} galleries")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Failed to load galleries", e)
        }
    }
}
