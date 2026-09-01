package com.cipher.keyboard

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real AES-256-GCM encryption, unlike CipherEngine's substitution cipher.
 *
 * A substitution cipher only stops a casual glance -- anyone who actually gets your ciphertext
 * (a screenshot, a backup, whatever) can break it with basic frequency analysis, no special
 * access needed. This is genuine cryptography: without the exact passphrase, the ciphertext is
 * not practically recoverable, screenshot or not.
 *
 * Both sides must set the exact same passphrase (in the app's main screen) for this to work --
 * there's no way to recover a message encrypted with a passphrase you don't have.
 */
object AesEngine {
    private const val PREFIX = "CK1:"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12

    /** Whether this text looks like something AesEngine produced (vs. plain text or the old substitution cipher). */
    fun looksEncrypted(text: String): Boolean = text.startsWith(PREFIX)

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Encrypts plaintext with a random salt+IV each time (so the same message never looks the same twice). */
    fun encrypt(plaintext: String, passphrase: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + ciphertext
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Returns null if the passphrase is wrong or the payload is corrupt/tampered -- GCM's
     * built-in authentication tag makes both cases fail cleanly instead of returning garbage,
     * so a null result reliably means "wrong passphrase or bad data," not silent corruption.
     */
    fun decrypt(payload: String, passphrase: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val combined = Base64.decode(payload.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size < SALT_LENGTH_BYTES + IV_LENGTH_BYTES) return null
            val salt = combined.copyOfRange(0, SALT_LENGTH_BYTES)
            val iv = combined.copyOfRange(SALT_LENGTH_BYTES, SALT_LENGTH_BYTES + IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(SALT_LENGTH_BYTES + IV_LENGTH_BYTES, combined.size)
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
