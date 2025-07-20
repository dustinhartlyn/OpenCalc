package com.darkempire78.opencalculator.gallery

import java.io.File

data class Gallery(
    val name: String,
    val mediaItems: MutableList<MediaItem>
)

sealed class MediaItem(val file: File) {
    class Photo(file: File) : MediaItem(file)
    class Note(file: File, var title: String, var content: String) : MediaItem(file)
}
