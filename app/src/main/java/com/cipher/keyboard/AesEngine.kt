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
    // Public so CipherIME can search for this marker inside a line (e.g. when WhatsApp prepends
    // a timestamp/sender name to each copied message), not just check startsWith.
    const val PREFIX = "CK1:"
    // 45k iterations -- trimmed down again from 60k for a snappier feel. Still meaningfully more
    // resistant to brute-forcing than skipping PBKDF2 entirely; combined with running this off
    // the main thread, Encrypt/Decode should feel closer to instant now.
    // Trimmed again from 45k -- decode is already off the main thread, but every bit of raw
    // speed still matters for "feels instant." Still meaningfully more resistant to brute-force
    // than no stretching at all.
    private const val PBKDF2_ITERATIONS = 30_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    // URL_SAFE avoids '+' and '/' and NO_PADDING drops the trailing '=' -- standard Base64's
    // characters are occasionally mangled by messaging apps' autocorrect/smart-punctuation,
    // which is the most likely cause of "copied but won't decode."
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Whether this text looks like something AesEngine produced (vs. plain text or the old substitution cipher). */
    fun looksEncrypted(text: String): Boolean = text.startsWith(PREFIX)

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext with a random salt+IV each time (so the same message never looks the
     * same twice). This does real cryptographic work (PBKDF2) and can take noticeable time on
     * slower devices -- callers should run this off the main thread.
     */
    fun encrypt(plaintext: String, passphrase: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + ciphertext
        return PREFIX + Base64.encodeToString(combined, BASE64_FLAGS)
    }

    /**
     * Returns null if the passphrase is wrong or the payload is corrupt/tampered -- GCM's
     * built-in authentication tag makes both cases fail cleanly instead of returning garbage,
     * so a null result reliably means "wrong passphrase or bad data," not silent corruption.
     * Also does real cryptographic work -- run off the main thread.
     */
    fun decrypt(payload: String, passphrase: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val combined = Base64.decode(payload.removePrefix(PREFIX), BASE64_FLAGS)
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
