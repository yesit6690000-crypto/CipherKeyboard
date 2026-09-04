package com.cipher.keyboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Blocks screenshots and the recent-apps preview thumbnail of this screen -- it can show
        // your passphrase in plain text and, further down, a QR code that encodes it.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(40, 60, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Cipher keyboard"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Step 1: enable it in system settings, then switch to it from any text field's keyboard picker."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            setPadding(0, 20, 0, 20)
        })

        root.addView(actionButton("Open keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        root.addView(actionButton("Choose input method now") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })

        root.addView(TextView(this).apply {
            text = "One-tap encrypt + send (optional)"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 18f
            setPadding(0, 50, 0, 6)
        })
        root.addView(TextView(this).apply {
            text = "Without this, AES mode still auto-encrypts when you hit Enter, but some apps " +
                "(WhatsApp included) need one extra manual tap on their own Send button afterward, " +
                "since a keyboard can't normally reach another app's UI. Enabling this lets Cipher " +
                "Keyboard find and tap that Send button for you -- it only ever looks for a button " +
                "labeled \"Send\" at that exact moment, and doesn't read, log, or store anything else."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(0, 0, 0, 14)
        })
        root.addView(actionButton("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find and enable \"Cipher Keyboard\" in the list", Toast.LENGTH_LONG).show()
        })
        root.addView(TextView(this).apply {
            text = if (AutoSendAccessibilityService.isAvailable()) "Currently enabled" else "Currently not enabled"
            setTextColor(if (AutoSendAccessibilityService.isAvailable()) Color.parseColor("#66AA66") else Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "Real encryption passphrase"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 18f
            setPadding(0, 50, 0, 6)
        })
        root.addView(TextView(this).apply {
            text = "The keyboard's letter symbols only stop a casual glance -- they don't survive a " +
                "screenshot. This passphrase powers real AES encryption instead: both you and the " +
                "other person must set the exact same passphrase here for the keyboard's Encrypt/" +
                "Decode buttons to work between you."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(0, 0, 0, 14)
        })

        val prefs = getSharedPreferences("cipher_settings", MODE_PRIVATE)
        val savedPassphrase = loadPassphraseFromPrefs(prefs)
        val passphraseInput = EditText(this).apply {
            hint = "Shared passphrase"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(20, 20, 20, 20)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPassphrase)
        }
        root.addView(passphraseInput)

        val passphraseStatus = TextView(this).apply {
            text = if (savedPassphrase.isEmpty())
                "Not set -- Encrypt/Decode won't work until both sides set one" else "Passphrase is set on this device"
            setTextColor(if (savedPassphrase.isEmpty()) Color.parseColor("#CC6666") else Color.parseColor("#66AA66"))
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(passphraseStatus)

        root.addView(actionButton("Save passphrase") {
            val value = passphraseInput.text.toString().trim()
            savePassphraseToPrefs(prefs, value)
            passphraseStatus.text = if (value.isEmpty()) "Not set -- Encrypt/Decode won't work until both sides set one" else "Passphrase is set on this device"
            passphraseStatus.setTextColor(if (value.isEmpty()) Color.parseColor("#CC6666") else Color.parseColor("#66AA66"))
            Toast.makeText(this, if (value.isEmpty()) "Passphrase cleared" else "Passphrase saved", Toast.LENGTH_SHORT).show()
        }.apply { setPadding(0, 8, 0, 0) })

        root.addView(TextView(this).apply {
            text = "Stored only on this device, never sent anywhere -- and encrypted at rest using " +
                "Android's own hardware-backed Keystore, not kept as plain text. Still worth treating " +
                "like any other shared secret."
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setPadding(0, 10, 0, 14)
        })

        root.addView(TextView(this).apply {
            text = "Share it in person, not over chat"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 15f
            setPadding(0, 10, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Sending the passphrase through the same chat you're trying to protect defeats the " +
                "point. Show this QR code to the other person in person and have them scan it with " +
                "any camera or QR app, then paste what it reveals into their own passphrase field."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 0, 0, 10)
        })

        val qrImageView = ImageView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(600, 600).also { it.gravity = Gravity.CENTER_HORIZONTAL }
            setPadding(0, 10, 0, 10)
        }

        root.addView(actionButton("Show passphrase as QR code") {
            val pass = passphraseInput.text.toString().trim()
            if (pass.isEmpty()) {
                Toast.makeText(this, "Type or save a passphrase first", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val writer = com.google.zxing.qrcode.QRCodeWriter()
                    val matrix = writer.encode(pass, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
                    qrImageView.setImageBitmap(bitMatrixToBitmap(matrix))
                    qrImageView.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't generate QR code", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { setPadding(0, 4, 0, 0) })
        root.addView(qrImageView)
        root.addView(TextView(this).apply {
            text = "Hide this again once they've scanned it -- screenshots of this screen are already blocked."
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setPadding(0, 0, 0, 4)
        })
        root.addView(actionButton("Hide QR code") {
            qrImageView.visibility = View.GONE
        }.apply { setPadding(0, 4, 0, 0) })

        root.addView(TextView(this).apply {
            text = "Quick encode / decode tool"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 18f
            setPadding(0, 50, 0, 10)
        })

        val input = EditText(this).apply {
            hint = "Paste text here"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(20, 20, 20, 20)
        }
        root.addView(input)

        val output = TextView(this).apply {
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 16f
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }
        row.addView(actionButton("Encode") {
            output.text = CipherEngine.encode(input.text.toString())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(actionButton("Decode") {
            output.text = CipherEngine.decode(input.text.toString())
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        root.addView(row)

        val aesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        aesRow.addView(actionButton("AES Encrypt") {
            val pass = loadPassphraseFromPrefs(prefs)
            if (pass.isEmpty()) {
                Toast.makeText(this, "Set a passphrase above first", Toast.LENGTH_SHORT).show()
            } else {
                output.text = AesEngine.encrypt(input.text.toString(), pass)
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        aesRow.addView(actionButton("AES Decode") {
            val pass = loadPassphraseFromPrefs(prefs)
            if (pass.isEmpty()) {
                Toast.makeText(this, "Set a passphrase above first", Toast.LENGTH_SHORT).show()
            } else {
                val result = AesEngine.decrypt(input.text.toString(), pass)
                output.text = result ?: "Couldn't decode -- wrong passphrase or not AES-encrypted text"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        root.addView(aesRow)

        root.addView(TextView(this).apply {
            text = "Result (long-press to copy):"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
        })
        output.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cipher", output.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
        root.addView(output)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#111111"))
            setBackgroundColor(Color.parseColor("#E8763C"))
            setOnClickListener { onClick() }
        }
    }

    /** Reads the passphrase, transparently migrating an older plain-text-stored one to encrypted. */
    private fun loadPassphraseFromPrefs(prefs: android.content.SharedPreferences): String {
        val encrypted = prefs.getString("passphrase_encrypted", null)
        if (encrypted != null) {
            return SecureStorage.decrypt(encrypted)?.trim() ?: ""
        }
        val legacy = prefs.getString("passphrase", null)?.trim() ?: ""
        if (legacy.isNotEmpty()) {
            SecureStorage.encrypt(legacy)?.let { enc ->
                prefs.edit().putString("passphrase_encrypted", enc).remove("passphrase").apply()
            }
        }
        return legacy
    }

    private fun savePassphraseToPrefs(prefs: android.content.SharedPreferences, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove("passphrase_encrypted").remove("passphrase").apply()
            return
        }
        val encrypted = SecureStorage.encrypt(value)
        if (encrypted != null) {
            prefs.edit().putString("passphrase_encrypted", encrypted).remove("passphrase").apply()
        } else {
            Toast.makeText(this, "Couldn't securely save the passphrase -- try again", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bitMatrixToBitmap(matrix: com.google.zxing.common.BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
