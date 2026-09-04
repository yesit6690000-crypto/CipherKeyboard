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
