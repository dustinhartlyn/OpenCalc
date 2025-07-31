package com.darkempire78.opencalculator.securegallery

import java.util.UUID

// Data model for an encrypted note
import java.io.Serializable

class SecureNote(
    val id: UUID = UUID.randomUUID(),
    var encryptedTitle: ByteArray,
    var encryptedBody: ByteArray,
    val date: Long
) : Serializable
