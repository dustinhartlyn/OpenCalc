package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import java.io.Serializable

// Data model for a secure gallery
class Gallery(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    val salt: ByteArray,
    val notes: MutableList<SecureNote> = mutableListOf(),
    val photos: MutableList<SecurePhoto> = mutableListOf(),
    var pinHash: ByteArray? = null, // Secure PIN verification hash
    var sortOrder: GallerySortOrder = GallerySortOrder.NAME, // Photo sort order
    var customOrder: MutableList<Int> = mutableListOf() // Custom order indices
) : Serializable
