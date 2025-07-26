package com.darkempire78.opencalculator.securegallery

object GallerySortUtils {
    fun sortPhotosByName(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.name }
    }
    fun sortPhotosByDate(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.date }
    }
}
