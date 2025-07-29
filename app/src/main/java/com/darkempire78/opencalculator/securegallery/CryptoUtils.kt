package com.darkempire78.opencalculator.securegallery

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.security.SecureRandom

object CryptoUtils {
    private const val ITERATION_COUNT = 100_000
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encrypt(data: ByteArray, key: SecretKeySpec): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data)
        return Pair(iv, encrypted)
    }

    fun decrypt(iv: ByteArray, encrypted: ByteArray, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }
    
    // Streaming encryption for large files to avoid OutOfMemoryError
    fun encryptStream(inputStream: java.io.InputStream, outputStream: java.io.OutputStream, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        
        // Write IV first
        outputStream.write(iv)
        
        val buffer = ByteArray(8192) // 8KB buffer
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val encrypted = if (bytesRead < buffer.size) {
                // Last chunk - finalize
                cipher.doFinal(buffer, 0, bytesRead)
            } else {
                // Normal chunk - update
                cipher.update(buffer, 0, bytesRead)
            }
            if (encrypted != null) {
                outputStream.write(encrypted)
            }
        }
        
        // Finalize if we haven't already
        val finalData = cipher.doFinal()
        if (finalData.isNotEmpty()) {
            outputStream.write(finalData)
        }
        
        return iv
    }
    
    // Generate a secure hash for PIN verification that doesn't rely on encrypted content
    fun generatePinHash(pin: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATION_COUNT * 2, 256) // Double iterations for extra security
        return factory.generateSecret(spec).encoded
    }
    
    // Verify PIN against stored hash
    fun verifyPin(pin: String, salt: ByteArray, storedHash: ByteArray): Boolean {
        val computedHash = generatePinHash(pin, salt)
        return computedHash.contentEquals(storedHash)
    }
}
