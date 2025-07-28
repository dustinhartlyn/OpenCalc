package com.darkempire78.opencalculator.securegallery

object GallerySortUtils {
    fun sortPhotosByName(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.name }
    }
    
    fun sortPhotosByDate(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.date }
    }
    
    fun sortPhotosByCustomOrder(photos: MutableList<SecurePhoto>, customOrder: List<Int>) {
        if (customOrder.isEmpty() || customOrder.size != photos.size) {
            // Fall back to name sorting if custom order is invalid
            sortPhotosByName(photos)
            return
        }
        
        // Create a map of original indices to photos
        val indexedPhotos = photos.mapIndexed { index, photo -> index to photo }.toMap()
        
        // Clear and reorder based on custom order
        photos.clear()
        for (originalIndex in customOrder) {
            indexedPhotos[originalIndex]?.let { photos.add(it) }
        }
    }
    
    fun applySortOrder(gallery: Gallery) {
        when (gallery.sortOrder) {
            GallerySortOrder.NAME -> sortPhotosByName(gallery.photos)
            GallerySortOrder.DATE -> sortPhotosByDate(gallery.photos)
            GallerySortOrder.CUSTOM -> sortPhotosByCustomOrder(gallery.photos, gallery.customOrder)
        }
    }
}
