package com.darkempire78.opencalculator.securegallery

object GalleryAccessUtils {
    fun handleAppFocusLost() {
        SensitiveDataUtils.wipeSensitiveData()
        // TODO: Return to calculator UI
    }
}
