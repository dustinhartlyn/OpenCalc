package com.darkempire78.opencalculator.securegallery

object GallerySortUtils {
    fun sortMediaByName(media: MutableList<SecureMedia>) {
        media.sortBy { it.name }
    }
    
    fun sortMediaByDate(media: MutableList<SecureMedia>) {
        media.sortBy { it.date }
    }
    
    fun sortMediaByCustomOrder(media: MutableList<SecureMedia>, customOrder: List<Int>) {
        if (customOrder.isEmpty() || customOrder.size != media.size) {
            // Fall back to name sorting if custom order is invalid
            sortMediaByName(media)
            return
        }
        
        // Create a map of original indices to media
        val indexedMedia = media.mapIndexed { index, mediaItem -> index to mediaItem }.toMap()
        
        // Clear and reorder based on custom order
        media.clear()
        for (originalIndex in customOrder) {
            indexedMedia[originalIndex]?.let { media.add(it) }
        }
    }
    
    // Backward compatibility methods for existing code that references 'photos'
    @Deprecated("Use sortMediaByName instead", ReplaceWith("sortMediaByName(media)"))
    fun sortPhotosByName(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.name }
    }
    
    @Deprecated("Use sortMediaByDate instead", ReplaceWith("sortMediaByDate(media)"))
    fun sortPhotosByDate(photos: MutableList<SecurePhoto>) {
        photos.sortBy { it.date }
    }
    
    @Deprecated("Use sortMediaByCustomOrder instead", ReplaceWith("sortMediaByCustomOrder(media, customOrder)"))
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
            GallerySortOrder.NAME -> sortMediaByName(gallery.media)
            GallerySortOrder.DATE -> sortMediaByDate(gallery.media)
            GallerySortOrder.CUSTOM -> sortMediaByCustomOrder(gallery.media, gallery.customOrder)
        }
    }
}
