package com.darkempire78.opencalculator.securegallery

object NoteManager {
    fun addNote(gallery: Gallery, note: SecureNote) {
        gallery.notes.add(note)
    }
    fun removeNote(gallery: Gallery, noteId: java.util.UUID) {
        gallery.notes.removeAll { it.id == noteId }
    }
}
