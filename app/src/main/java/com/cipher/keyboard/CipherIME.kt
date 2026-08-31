package com.cipher.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CipherIME : InputMethodService() {

    private var capsOn = false
    private var symbolsMode = false
    private var previewHidden = true
    private var composing = StringBuilder() // tracks plain-text of the current typed run for recheck preview

    private lateinit var previewText: TextView
    private lateinit var eyeIcon: TextView

    override fun onCreateInputView(): View {
        composing = StringBuilder()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }

        root.addView(buildClipboardRow())
        root.addView(buildPreviewRow())
        root.addView(spacer(6))
        root.addView(if (symbolsMode) buildSymbolRows() else buildLetterRows())
        root.addView(spacer(6))
        root.addView(buildBottomRow())

        return root
    }

    // ---------- Row builders ----------

    private fun buildClipboardRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        row.addView(smallButton("Copy") { performCopy() })
        row.addView(smallButton("Paste") { performPaste() })
        row.addView(smallButton("Select all") { performSelectAll() })
        row.addView(smallButton("Decode") { showDecodePopup() })
        return row
    }

    private fun buildPreviewRow(): View {
        val row = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).also { it.topMargin = dp(6) }
        }
        previewText = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 15f
            maxLines = 1
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).also { it.rightMargin = dp(70) }
        }
        row.addView(previewText)
        updatePreviewText()

        val recheckBtn = TextView(this).apply {
            text = "\u21BB" // refresh glyph, avoids needing an icon font
            setTextColor(Color.parseColor("#999999"))
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34)).also {
                it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                it.rightMargin = dp(38)
            }
            setOnClickListener { refreshPreviewNow() }
        }
        row.addView(recheckBtn)

        eyeIcon = TextView(this).apply {
            text = "\u25CF" // filled dot placeholder for "hidden"; swap to an eye drawable/icon font if you have one
            setTextColor(Color.parseColor("#999999"))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34)).also {
                it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { togglePreviewVisibility() }
        }
        row.addView(eyeIcon)
        return row
    }

    private fun buildLetterRows(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(keyRow(CipherEngine.row1Plain.toCharArray().toList()))
        col.addView(keyRow(CipherEngine.row2Plain.toCharArray().toList(), sidePad = dp(16)))

        val lastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lastRow.addView(controlKey(if (capsOn) "\u21E7\u21E7" else "\u21E7", weight = 1.4f) {
            capsOn = !capsOn
            refreshKeyboardView()
        })
        for (c in CipherEngine.row3Plain) {
            lastRow.addView(letterKey(c, weight = 1f))
        }
        lastRow.addView(controlKey("\u232B", weight = 1.4f) { doBackspace() })
        col.addView(lastRow)

        return col
    }

    private fun buildSymbolRows(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(keyRow(listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'), digits = true))
        val punctRow1 = "-/:;()".toCharArray().toList()
        val punctRow2 = ".,?!'\"".toCharArray().toList()
        col.addView(plainKeyRow(punctRow1))
        val lastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in punctRow2) lastRow.addView(plainKey(c, weight = 1f))
        lastRow.addView(controlKey("\u232B", weight = 1.4f) { doBackspace() })
        col.addView(lastRow)
        return col
    }

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(controlKey(if (symbolsMode) "ABC" else "?123", weight = 1.4f) {
            symbolsMode = !symbolsMode
            refreshKeyboardView()
        })
        val space = Button(this).apply {
            text = if (symbolsMode) "space" else "space (cipher)"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            setBackgroundColor(Color.parseColor("#181818"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 5f).also { it.marginStart = dp(4); it.marginEnd = dp(4) }
            setOnClickListener { commitPlain(" ") }
        }
        row.addView(space)
        row.addView(controlKey("\u21B5", weight = 1.4f, accent = true) { sendEnter() })
        return row
    }

    // ---------- Key factories ----------

    private fun keyRow(chars: List<Char>, sidePad: Int = 0, digits: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(sidePad, 0, sidePad, 0)
        }
        for (c in chars) {
            row.addView(if (digits) digitKey(c, 1f) else letterKey(c, 1f))
        }
        return row
    }

    private fun plainKeyRow(chars: List<Char>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in chars) row.addView(plainKey(c, 1f))
        return row
    }

    /** A letter key: shows the big cipher glyph, small plain letter in the top-right corner. */
    private fun letterKey(plainChar: Char, weight: Float): View {
        val shown = if (capsOn) plainChar.uppercaseChar() else plainChar
        val cipherChar = CipherEngine.letterEncodeMap[plainChar] ?: plainChar
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).also {
                it.marginStart = dp(2); it.marginEnd = dp(2)
            }
            setBackgroundColor(Color.parseColor("#181818"))
        }
        frame.addView(TextView(this).apply {
            text = cipherChar.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 19f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        frame.addView(TextView(this).apply {
            text = shown.toString()
            setTextColor(Color.parseColor("#5A5A5A"))
            textSize = 9f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = dp(3); it.rightMargin = dp(4)
            }
        })
        frame.setOnClickListener {
            val outChar = CipherEngine.letterEncodeMap[plainChar] ?: plainChar
            commitCipherChar(if (capsOn) outChar else outChar, plainChar.toString().let { if (capsOn) it.uppercase() else it })
        }
        return frame
    }

    private fun digitKey(digitChar: Char, weight: Float): View {
        val cipherChar = CipherEngine.digitEncodeMap[digitChar] ?: digitChar
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).also {
                it.marginStart = dp(2); it.marginEnd = dp(2)
            }
            setBackgroundColor(Color.parseColor("#181818"))
        }
        frame.addView(TextView(this).apply {
            text = cipherChar.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 19f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        frame.addView(TextView(this).apply {
            text = digitChar.toString()
            setTextColor(Color.parseColor("#5A5A5A"))
            textSize = 9f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = dp(3); it.rightMargin = dp(4)
            }
        })
        frame.setOnClickListener { commitCipherChar(cipherChar, digitChar.toString()) }
        return frame
    }

    /** A plain (non-ciphered) key, used for punctuation which the user said doesn't need hiding. */
    private fun plainKey(c: Char, weight: Float): View {
        val btn = Button(this).apply {
            text = c.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 17f
            setBackgroundColor(Color.parseColor("#181818"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).also {
                it.marginStart = dp(2); it.marginEnd = dp(2)
            }
            setOnClickListener { commitPlain(c.toString()) }
        }
        return btn
    }

    private fun controlKey(label: String, weight: Float, accent: Boolean = false, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            setTextColor(if (accent) Color.parseColor("#111111") else Color.parseColor("#999999"))
            textSize = 14f
            setBackgroundColor(if (accent) Color.parseColor("#E8763C") else Color.parseColor("#181818"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).also {
                it.marginStart = dp(2); it.marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            setBackgroundColor(Color.parseColor("#1C1C1C"))
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).also {
                it.marginStart = dp(3); it.marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    // ---------- Input actions ----------

    private fun commitCipherChar(cipherChar: Char, plainEquivalent: String) {
        currentInputConnection?.commitText(cipherChar.toString(), 1)
        composing.append(plainEquivalent)
        updatePreviewText()
    }

    private fun commitPlain(text: String) {
        currentInputConnection?.commitText(text, 1)
        composing.append(text)
        updatePreviewText()
    }

    private fun doBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (composing.isNotEmpty()) composing.deleteCharAt(composing.length - 1)
        updatePreviewText()
    }

    private fun sendEnter() {
        currentInputConnection?.sendKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        )
        currentInputConnection?.sendKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
        )
        composing.clear()
        updatePreviewText()
    }

    private fun refreshKeyboardView() {
        setInputView(onCreateInputView())
    }

    // ---------- Preview / recheck / eye ----------

    private fun updatePreviewText() {
        if (!::previewText.isInitialized) return
        if (previewHidden) {
            previewText.text = "\u2022 ".repeat(minOf(composing.length, 24)).trim()
        } else {
            previewText.text = if (composing.isEmpty()) "type to see plain-text preview" else composing.toString()
        }
    }

    private fun refreshPreviewNow() {
        // Re-derive composing from the actual field in case the user edited via cursor/selection elsewhere.
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        val decodedTail = CipherEngine.decode(before)
        composing = StringBuilder(decodedTail.takeLast(200))
        previewHidden = false
        updatePreviewText()
    }

    private fun togglePreviewVisibility() {
        previewHidden = !previewHidden
        eyeIcon.text = if (previewHidden) "\u25CF" else "\u25CB"
        updatePreviewText()
    }

    // ---------- Clipboard tools ----------

    private fun performCopy() {
        currentInputConnection?.performContextMenuAction(android.R.id.copy)
    }

    private fun performPaste() {
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
    }

    private fun performSelectAll() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun showDecodePopup() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""

        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty. Copy their cipher text first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!CipherEngine.looksEncoded(text)) {
            Toast.makeText(this, "Clipboard text doesn't look encoded.", Toast.LENGTH_SHORT).show()
            return
        }
        val decoded = CipherEngine.decode(text)
        showDecodedDialog(decoded)
    }

    /** Simple in-IME popup window rather than an Activity dialog, since IMEs can't easily launch dialogs. */
    private fun showDecodedDialog(decoded: String) {
        val popup = android.widget.PopupWindow(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = "Decoded message"
            setTextColor(Color.parseColor("#E8763C"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = decoded
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        })
        box.addView(Button(this).apply {
            text = "Close"
            setTextColor(Color.parseColor("#111111"))
            setBackgroundColor(Color.parseColor("#E8763C"))
            setOnClickListener { popup.dismiss() }
        })
        popup.contentView = box
        popup.width = ViewGroup.LayoutParams.MATCH_PARENT
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.showAtLocation(box, Gravity.TOP, 0, dp(40))
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing = StringBuilder()
    }
}
