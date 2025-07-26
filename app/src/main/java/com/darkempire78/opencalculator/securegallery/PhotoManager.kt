package com.darkempire78.opencalculator.securegallery

object PhotoManager {
    fun addPhoto(gallery: Gallery, photo: SecurePhoto) {
        gallery.photos.add(photo)
    }
    fun removePhoto(gallery: Gallery, photoId: java.util.UUID) {
        gallery.photos.removeAll { it.id == photoId }
    }
}
