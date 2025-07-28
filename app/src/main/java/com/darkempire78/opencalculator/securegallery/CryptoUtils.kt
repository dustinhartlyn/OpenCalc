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
