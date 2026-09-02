package com.cipher.keyboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val passphraseInput = EditText(this).apply {
            hint = "Shared passphrase"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(20, 20, 20, 20)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("passphrase", ""))
        }
        root.addView(passphraseInput)

        val passphraseStatus = TextView(this).apply {
            text = if (prefs.getString("passphrase", "").isNullOrEmpty())
                "Not set -- Encrypt/Decode won't work until both sides set one" else "Passphrase is set on this device"
            setTextColor(if (prefs.getString("passphrase", "").isNullOrEmpty()) Color.parseColor("#CC6666") else Color.parseColor("#66AA66"))
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(passphraseStatus)

        root.addView(actionButton("Save passphrase") {
            val value = passphraseInput.text.toString().trim()
            prefs.edit().putString("passphrase", value).apply()
            passphraseStatus.text = if (value.isEmpty()) "Not set -- Encrypt/Decode won't work until both sides set one" else "Passphrase is set on this device"
            passphraseStatus.setTextColor(if (value.isEmpty()) Color.parseColor("#CC6666") else Color.parseColor("#66AA66"))
            Toast.makeText(this, if (value.isEmpty()) "Passphrase cleared" else "Passphrase saved", Toast.LENGTH_SHORT).show()
        }.apply { setPadding(0, 8, 0, 0) })

        root.addView(TextView(this).apply {
            text = "Stored only on this device, never sent anywhere -- but it's kept as plain " +
                "text in this app's private storage, so anyone with root access or a device " +
                "backup exploit could read it. Treat it like any other shared secret."
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setPadding(0, 10, 0, 0)
        })

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
            val pass = (prefs.getString("passphrase", "") ?: "").trim()
            if (pass.isEmpty()) {
                Toast.makeText(this, "Set a passphrase above first", Toast.LENGTH_SHORT).show()
            } else {
                output.text = AesEngine.encrypt(input.text.toString(), pass)
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        aesRow.addView(actionButton("AES Decode") {
            val pass = (prefs.getString("passphrase", "") ?: "").trim()
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
}
