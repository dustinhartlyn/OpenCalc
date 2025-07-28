package com.darkempire78.opencalculator.securegallery

import java.util.UUID
import java.io.Serializable

// Data model for an encrypted photo
class SecurePhoto(
    val id: UUID = UUID.randomUUID(),
    val encryptedData: ByteArray,
    val name: String,
    val date: Long,
    var customOrder: Int = -1 // For custom sorting, -1 means not set
) : Serializable
