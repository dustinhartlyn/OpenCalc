package com.darkempire78.opencalculator.securegallery

object GalleryCreationUtils {
    fun createNewGallery(name: String, pin: String): Gallery {
        val salt = CryptoUtils.generateSalt()
        return Gallery(
            name = name,
            salt = salt
        )
    }
}
