package com.darkempire78.opencalculator.securegallery

import java.util.UUID

// Data model for an encrypted note
import java.io.Serializable

class SecureNote(
    val id: UUID = UUID.randomUUID(),
    val encryptedTitle: ByteArray,
    val encryptedBody: ByteArray,
    val date: Long
) : Serializable
