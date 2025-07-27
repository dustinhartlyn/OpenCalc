package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import java.io.Serializable

// Data model for a secure gallery
class Gallery(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    val salt: ByteArray,
    val notes: MutableList<SecureNote> = mutableListOf(),
    val photos: MutableList<SecurePhoto> = mutableListOf()
) : Serializable
