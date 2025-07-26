package com.darkempire78.opencalculator.securegallery

import android.content.Context
import java.io.File

object SensitiveDataStore {
    fun saveGallery(context: Context, gallery: Gallery) {
        // TODO: Serialize and save encrypted gallery data to file
    }

    fun loadGalleries(context: Context): List<Gallery> {
        // TODO: Load and deserialize encrypted gallery data from files
        return emptyList()
    }
}
