package com.darkempire78.opencalculator.securegallery

object GalleryDeleteUtils {
    fun deleteGallery(manager: GalleryManager, galleryId: java.util.UUID) {
        manager.removeGallery(galleryId)
        // TODO: Delete gallery data from storage
    }
}
