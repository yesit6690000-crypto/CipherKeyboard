package com.cipher.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private var keyboardRootView: View? = null

    // repeat-delete while backspace is held down
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // last clipboard text we already offered to decode, so we don't re-popup the same one every time
    private var lastSeenClip: String? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            maybeAutoOfferDecode()
        }
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener?.let { cm.removePrimaryClipChangedListener(it) }
        repeatHandler.removeCallbacksAndMessages(null)
        keyboardRootView = null
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Android blocks background apps from reading the clipboard (privacy rule since Android 10),
        // so the most reliable "live" moment is right when you come back to type -- check here too.
        maybeAutoOfferDecode()
    }

    override fun onCreateInputView(): View {
        composing = StringBuilder()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }

        root.addView(buildToolbarRow())
        root.addView(buildPreviewRow())
        root.addView(spacer(6))
        root.addView(if (symbolsMode) buildSymbolRows() else buildLetterRows())
        root.addView(spacer(6))
        root.addView(buildBottomRow())

        keyboardRootView = root
        return root
    }

    // ---------- Row builders ----------

    private fun buildToolbarRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        row.addView(smallButton("Kbd") { switchToNextKeyboard() })
        row.addView(smallButton("Copy") { performCopy() })
        row.addView(smallButton("Paste") { performPaste() })
        row.addView(smallButton("All") { performSelectAll() })
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
        lastRow.addView(backspaceKey(weight = 1.4f))
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
        lastRow.addView(backspaceKey(weight = 1.4f))
        col.addView(lastRow)
        return col
    }

    /** Backspace with hold-to-repeat: single tap deletes one char, holding deletes repeatedly until released. */
    private fun backspaceKey(weight: Float): View {
        val btn = Button(this).apply {
            text = "\u232B"
            setTextColor(Color.parseColor("#999999"))
            textSize = 14f
            background = null
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(0, dp(56), weight)
        }
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    doBackspace() // immediate first delete
                    val r = object : Runnable {
                        override fun run() {
                            doBackspace()
                            repeatHandler.postDelayed(this, 50)
                        }
                    }
                    repeatRunnable = r
                    repeatHandler.postDelayed(r, 400) // wait a beat before repeating
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                    repeatRunnable = null
                    true
                }
                else -> false
            }
        }
        return btn
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
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 5f)
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
            layoutParams = LinearLayout.LayoutParams(0, dp(56), weight)
            setBackgroundResource(android.R.drawable.list_selector_background)
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
            layoutParams = LinearLayout.LayoutParams(0, dp(56), weight)
            setBackgroundResource(android.R.drawable.list_selector_background)
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
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(0, dp(56), weight)
            setOnClickListener { commitPlain(c.toString()) }
        }
        return btn
    }

    private fun controlKey(label: String, weight: Float, accent: Boolean = false, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            setTextColor(if (accent) Color.parseColor("#111111") else Color.parseColor("#999999"))
            textSize = 14f
            if (accent) {
                setBackgroundColor(Color.parseColor("#E8763C"))
            } else {
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(56), weight)
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

    private fun switchToNextKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun showDecodePopup() {
        val text = readClipboardText()
        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty. Copy their cipher text first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!CipherEngine.looksEncoded(text)) {
            Toast.makeText(this, "Clipboard text doesn't look encoded.", Toast.LENGTH_SHORT).show()
            return
        }
        lastSeenClip = text
        showDecodedDialog(CipherEngine.decode(text))
    }

    /**
     * Called whenever the clipboard changes AND every time the keyboard opens.
     * If the new clipboard content is cipher text we haven't already shown, pop the decoded
     * popup automatically -- no need to tap Decode. True always-on background listening isn't
     * possible on modern Android (clipboard reads are blocked while an app isn't focused), so
     * "keyboard becomes visible" is the closest reliable substitute for live.
     */
    private fun maybeAutoOfferDecode() {
        val text = readClipboardText()
        if (text.isEmpty() || text == lastSeenClip) return
        if (!CipherEngine.looksEncoded(text)) return
        lastSeenClip = text
        showDecodedDialog(CipherEngine.decode(text))
    }

    private fun readClipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            try { clip.getItemAt(0).coerceToText(this).toString() } catch (e: Exception) { "" }
        } else ""
    }

    /** Simple in-IME popup window rather than an Activity dialog, since IMEs can't easily launch dialogs. */
    private fun showDecodedDialog(decoded: String) {
        val anchor = keyboardRootView
        if (anchor == null || !anchor.isAttachedToWindow) {
            // Keyboard isn't actually on-screen right now (window has no valid token yet) --
            // showing a popup here would crash, so just surface it as a toast instead.
            Toast.makeText(this, "Decoded: $decoded", Toast.LENGTH_LONG).show()
            return
        }
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
        try {
            popup.showAtLocation(anchor, Gravity.TOP, 0, dp(40))
        } catch (e: Exception) {
            // Window token became invalid between the check above and this call (rare race) --
            // fail safe instead of crashing the keyboard.
            Toast.makeText(this, "Decoded: $decoded", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing = StringBuilder()
    }
}
