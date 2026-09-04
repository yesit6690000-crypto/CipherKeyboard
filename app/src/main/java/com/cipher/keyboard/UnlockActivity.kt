package com.cipher.keyboard

import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * No UI of its own -- immediately triggers the device's own biometric/PIN/pattern prompt (via
 * BiometricPrompt) and finishes. Using the device's own lock screen mechanism instead of a custom
 * PIN we'd have to build and store ourselves is deliberate: Android's own credential system is
 * far better tested and more secure than anything reasonable to hand-roll here.
 *
 * Successful auth marks a short unlock window (2 minutes) that CipherIME checks before showing
 * anything sensitive (decoded messages, clipboard history).
 */
class UnlockActivity : FragmentActivity() {

    companion object {
        @Volatile private var unlockedUntilMillis: Long = 0L
        private const val UNLOCK_WINDOW_MS = 120_000L

        fun isCurrentlyUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntilMillis

        private fun markUnlocked() {
            unlockedUntilMillis = System.currentTimeMillis() + UNLOCK_WINDOW_MS
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                markUnlocked()
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                finish() // cancelled, or no lock screen set up at all -- either way, stop here
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // A single failed attempt (e.g. wrong fingerprint) -- let the system prompt's own
                // UI handle retries, don't finish yet.
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Cipher Keyboard")
            .setSubtitle("Verify it's you to view decoded messages or clipboard history")
            .setDeviceCredentialAllowed(true) // falls back to PIN/pattern/password if no fingerprint/face is set up
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            finish()
        }
    }
}
