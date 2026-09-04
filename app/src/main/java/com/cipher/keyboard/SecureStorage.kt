package com.cipher.keyboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts data AT REST on this device -- the clipboard history and the saved passphrase, which
 * both used to sit as plain text in the app's private storage. Someone with root access or a
 * backup-extraction tool could previously read them directly; now they'd need this device's
 * Keystore key too, which (on most modern phones) lives in hardware-backed secure storage and
 * isn't something a backup or root shell can just copy out.
 *
 * This is a completely different mechanism from AesEngine -- that one encrypts MESSAGES with a
 * passphrase you share with someone else; this one protects DATA SITTING ON THIS PHONE with a
 * key unique to this device that's never shared with anyone, not even the other person you're
 * messaging.
 */
object SecureStorage {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "cipher_keyboard_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Encrypts a string for storage. Returns null (and the caller should fall back to not saving) on failure. */
    fun encrypt(plainText: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv // GCM generates its own random IV when no spec is passed to init()
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Decrypts a string previously produced by encrypt(). Returns null on any failure (wrong/corrupt data, key unavailable). */
    fun decrypt(storedText: String): String? {
        return try {
            val combined = Base64.decode(storedText, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH_BYTES) return null
            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
