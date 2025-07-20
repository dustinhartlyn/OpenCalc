package com.darkempire78.opencalculator.gallery

import android.content.Context
import com.darkempire78.opencalculator.encryption.EncryptionManager
import com.google.gson.Gson
import java.io.File

class GalleryManager(private val context: Context) {

    private val encryptionManager = EncryptionManager(context)
    private val galleriesDir = File(context.filesDir, "galleries")
    private val gson = Gson()

    init {
        if (!galleriesDir.exists()) {
            galleriesDir.mkdirs()
        }
        val noMediaFile = File(galleriesDir, ".nomedia")
        if (!noMediaFile.exists()) {
            noMediaFile.createNewFile()
        }
    }

    fun createGallery(name: String, pin: String) {
        val galleryDir = File(galleriesDir, name)
        if (!galleryDir.exists()) {
            galleryDir.mkdir()
            val gallery = Gallery(name, mutableListOf())
            saveGallery(gallery, pin)
        }
    }

    fun deleteGallery(name: String) {
        val galleryDir = File(galleriesDir, name)
        if (galleryDir.exists()) {
            galleryDir.deleteRecursively()
        }
    }

    fun exportGallery(name: String, destination: File) {
        val galleryDir = File(galleriesDir, name)
        if (galleryDir.exists()) {
            galleryDir.copyRecursively(destination)
        }
    }

    fun addPhoto(galleryName: String, pin: String, photo: File) {
        val gallery = getGallery(galleryName, pin)
        val encryptedPhotoData = encryptionManager.encryptData(photo.readBytes(), pin)
        val encryptedPhotoFile = File(getGalleryDir(galleryName), photo.name + ".enc")
        encryptedPhotoFile.writeBytes(encryptedPhotoData)
        gallery.mediaItems.add(MediaItem.Photo(encryptedPhotoFile))
        saveGallery(gallery, pin)
    }

    fun addNote(galleryName: String, pin: String, title: String, content: String) {
        val gallery = getGallery(galleryName, pin)
        val encryptedNoteData = encryptionManager.encryptData(content.toByteArray(), pin)
        val encryptedNoteFile = File(getGalleryDir(galleryName), title + ".note.enc")
        encryptedNoteFile.writeBytes(encryptedNoteData)
        gallery.mediaItems.add(MediaItem.Note(encryptedNoteFile, title, content))
        saveGallery(gallery, pin)
    }

    fun getGallery(name: String, pin: String): Gallery {
        val galleryDir = getGalleryDir(name)
        val galleryFile = File(galleryDir, "gallery.json.enc")
        val decryptedJson = encryptionManager.decryptData(galleryFile.readBytes(), pin).toString(Charsets.UTF_8)
        return gson.fromJson(decryptedJson, Gallery::class.java)
    }

    private fun saveGallery(gallery: Gallery, pin: String) {
        val galleryDir = getGalleryDir(gallery.name)
        val galleryFile = File(galleryDir, "gallery.json.enc")
        val json = gson.toJson(gallery)
        val encryptedJson = encryptionManager.encryptData(json.toByteArray(), pin)
        galleryFile.writeBytes(encryptedJson)
    }

    private fun getGalleryDir(name: String): File {
        return File(galleriesDir, name)
    }
}
