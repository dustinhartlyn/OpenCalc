package com.darkempire78.opencalculator.securegallery

object DefaultGalleryInitializer {
    fun createDefaultGallery(): Gallery {
        val salt = CryptoUtils.generateSalt()
        return Gallery(
            name = "Default",
            salt = salt
        )
    }
}
