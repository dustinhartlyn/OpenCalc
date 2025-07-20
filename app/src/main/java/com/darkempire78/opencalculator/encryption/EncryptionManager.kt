package com.darkempire78.opencalculator.encryption

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptionManager(private val context: Context) {

    private val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
    private val mainKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)

    fun encryptData(data: ByteArray, pin: String): ByteArray {
        val salt = generateSalt()
        val key = getKey(pin, salt)
        val secretKeySpec = SecretKeySpec(key.encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKeySpec)
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)
        return salt + iv + encryptedData
    }

    fun decryptData(encryptedData: ByteArray, pin: String): ByteArray {
        val salt = encryptedData.sliceArray(0..15)
        val iv = encryptedData.sliceArray(16..31)
        val data = encryptedData.sliceArray(32 until encryptedData.size)
        val key = getKey(pin, salt)
        val secretKeySpec = SecretKeySpec(key.encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val gcmParameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKeySpec, gcmParameterSpec)
        return cipher.doFinal(data)
    }

    private fun getEncryptedFile(file: File, key: SecretKey): EncryptedFile {
        return EncryptedFile.Builder(
            file,
            context,
            mainKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).setKeysetAlias("file_keyset_alias")
            .build()
    }

    private fun getKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }
}
