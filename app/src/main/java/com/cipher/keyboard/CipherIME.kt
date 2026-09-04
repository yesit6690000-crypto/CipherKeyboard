package com.cipher.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    private var emojiMode = false
    private var clipHistoryMode = false
    private var aesMode = false
    private var previewHidden = true
    private var composing = StringBuilder() // tracks plain-text of the current typed run for recheck preview

    private lateinit var previewText: TextView
    private lateinit var eyeIcon: TextView
    private var keyboardRootView: View? = null

    // repeat-delete while backspace is held down
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // auto-hide the plaintext recheck preview after a few seconds of showing it
    private var previewHideRunnable: Runnable? = null
    private val previewAutoHideMs = 8000L

    // auto-clear the clipboard a while after we put cipher/plain text on it, so it doesn't linger
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private var clipboardClearRunnable: Runnable? = null
    private val clipboardAutoClearMs = 30000L

    // persistent clipboard history (separate from the live system clipboard, which still auto-clears)
    private val clipHistoryPrefsName = "cipher_clip_history"
    private val clipHistoryKey = "items"
    private val clipHistoryMax = 100

    // shared AES passphrase, stored locally only (never leaves the device -- no INTERNET permission
    // exists in this app at all). Not hardware-backed encryption, just this app's private storage.
    private val aesPrefsName = "cipher_aes_prefs"
    private val aesPassphraseKey = "passphrase"

    // last clipboard text we already offered to decode, so we don't re-popup the same one every time
    private var lastSeenClip: String? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            maybeAutoOfferDecode()
            addToClipHistory(readClipboardText())
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
        clipboardHandler.removeCallbacksAndMessages(null)
        keyboardRootView = null
    }

    private var currentEditorInfo: EditorInfo? = null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        // This is the one true place composing should reset -- a genuinely new typing session
        // (a different field, a different app). It must NOT reset on every keyboard redraw
        // (mode switches, shift, etc.) -- that was wiping the in-progress message every time you
        // touched punctuation or switched modes, which is why AES-encrypted messages were
        // missing everything except whatever was typed after the last mode switch.
        composing = StringBuilder()
        // Android blocks background apps from reading the clipboard (privacy rule since Android 10),
        // so the most reliable "live" moment is right when you come back to type -- check here too.
        maybeAutoOfferDecode()
    }

    override fun onCreateInputView(): View {
        return try {
            buildFullKeyboardView()
        } catch (e: Exception) {
            // Never let a build error take the whole keyboard down -- fall back to something
            // usable so the user isn't locked out of typing entirely.
            emojiMode = false
            symbolsMode = false
            try {
                buildFullKeyboardView()
            } catch (e2: Exception) {
                TextView(this).apply {
                    text = "Keyboard failed to load -- switch keyboard and back to retry"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.BLACK)
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                }
            }
        }
    }

    private fun buildFullKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }

        if (emojiMode) {
            root.addView(buildEmojiPanel())
        } else if (clipHistoryMode) {
            root.addView(buildClipHistoryPanel())
        } else {
            root.addView(if (aesMode) buildAesToolbarRow() else buildToolbarRow())
            root.addView(if (aesMode) buildAesStatusRow() else buildPreviewRow())
            root.addView(spacer(6))
            root.addView(if (symbolsMode) buildSymbolRows() else buildLetterRows())
            root.addView(spacer(6))
            root.addView(buildBottomRow())
        }

        keyboardRootView = root
        return root
    }

    // ---------- Row builders ----------

    private fun buildToolbarRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 7f
        }
        row.addView(smallButton("Kbd") { switchToNextKeyboard() })
        row.addView(smallButton("Copy") { performCopy() })
        row.addView(smallButton("Paste") { performPaste() })
        row.addView(smallButton("All") { performSelectAll() })
        row.addView(smallButton("Clip") { requireUnlockThen { clipHistoryMode = true; refreshKeyboardView() } })
        row.addView(smallButton("AES") { aesMode = true; refreshKeyboardView() })
        row.addView(smallButton("Decode") { requireUnlockThen { showDecodePopup() } })
        return row
    }

    private fun buildAesToolbarRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        row.addView(smallButton("\u2190 Cipher") { aesMode = false; refreshKeyboardView() })
        row.addView(smallButton("Key", onLongClick = { onKeyButtonLongPress() }) { showPassphrasePopup() })
        row.addView(smallButton("Encrypt") { encryptCurrentFieldWithAes() })
        row.addView(smallButton("Decode") { requireUnlockThen { showDecodePopup() } })
        return row
    }

    /** AES mode's version of the recheck bar -- same live preview, same eye/hide toggle, same
     *  refresh button as the normal cipher mode, just with an extra line reminding you to Encrypt
     *  before sending. This used to be a static status label with no way to see or hide what
     *  you'd actually typed -- that's fixed now. */
    private fun buildAesStatusRow(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val hasPassphrase = !getStoredPassphrase().isNullOrEmpty()
        col.addView(TextView(this).apply {
            text = if (hasPassphrase) "AES mode -- dots hide typing, tap \u25CF to reveal/hide, auto-encrypts on Send" else "AES mode -- set a shared passphrase first (tap Key)"
            setTextColor(if (hasPassphrase) Color.parseColor("#666666") else Color.parseColor("#E8763C"))
            textSize = 11f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(3))
        })

        val row = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#161616"))
            }
            setPadding(dp(12), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }
        previewText = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 15f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).also { it.rightMargin = dp(70) }
        }
        row.addView(previewText)
        updatePreviewText()

        val recheckBtn = TextView(this).apply {
            text = "\u21BB"
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
            text = "\u25CF"
            setTextColor(Color.parseColor("#999999"))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34)).also {
                it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { togglePreviewVisibility() }
        }
        row.addView(eyeIcon)
        col.addView(row)

        return col
    }

    /**
     * Gates a sensitive action (viewing decoded messages, opening clipboard history) behind the
     * device's own lock screen. If already unlocked within the last 2 minutes, runs immediately;
     * otherwise triggers the system prompt and asks the user to retry once verified, rather than
     * trying to chain the action across the Activity boundary automatically.
     */
    private fun requireUnlockThen(action: () -> Unit) {
        if (UnlockActivity.isCurrentlyUnlocked()) {
            action()
            return
        }
        try {
            val intent = Intent(this, UnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Verify it's you, then tap that again", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the unlock screen", Toast.LENGTH_SHORT).show()
        }
    }

    // "Arm" then confirm within 3 seconds -- fast enough for a genuine panic, but a stray
    // long-press alone can't wipe anything by accident.
    private var panicWipeArmedUntil = 0L

    private fun onKeyButtonLongPress() {
        val now = System.currentTimeMillis()
        if (now < panicWipeArmedUntil) {
            panicWipeArmedUntil = 0L
            performPanicWipe()
        } else {
            panicWipeArmedUntil = now + 3000L
            Toast.makeText(this, "Long-press Key again within 3s to wipe clipboard, history & passphrase", Toast.LENGTH_LONG).show()
        }
    }

    /** Clears everything sensitive this app has stored or is holding onto, right now. */
    private fun performPanicWipe() {
        try {
            saveClipHistory(emptyList())
            getSharedPreferences("cipher_settings", Context.MODE_PRIVATE).edit()
                .remove("passphrase_encrypted").remove("passphrase").apply()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
            composing = StringBuilder()
            lastSeenClip = null
            updatePreviewText()
            Toast.makeText(this, "Wiped: clipboard, history, and passphrase", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Wipe only partially completed", Toast.LENGTH_LONG).show()
        }
    }

    private fun getStoredPassphrase(): String? {
        return try {
            val prefs = getSharedPreferences("cipher_settings", Context.MODE_PRIVATE)
            val encrypted = prefs.getString("passphrase_encrypted", null)
            if (encrypted != null) {
                return SecureStorage.decrypt(encrypted)?.trim()?.takeIf { it.isNotEmpty() }
            }
            // Migration path: earlier versions stored the passphrase as plain text. Read it once,
            // re-save it encrypted, and stop keeping the plain copy around.
            val legacyPlain = prefs.getString("passphrase", null)?.trim()?.takeIf { it.isNotEmpty() }
            if (legacyPlain != null) {
                SecureStorage.encrypt(legacyPlain)?.let { enc ->
                    prefs.edit().putString("passphrase_encrypted", enc).remove("passphrase").apply()
                }
            }
            legacyPlain
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Setting a passphrase from inside the IME's own popup isn't practical (an editable text
     * field inside a keyboard's own popup, with the keyboard itself as the only way to type into
     * it, is a reliability trap on most Android versions) -- so this just opens the app's proper
     * settings screen instead, which already has a normal, safe EditText for it.
     */
    private fun showPassphrasePopup() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Set your shared passphrase, then come back and switch keyboards", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Open the Cipher Keyboard app to set a passphrase", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Encrypts everything currently typed (tracked via `composing`, which is what AES-mode keys
     * commit as real plain text) and replaces the ENTIRE contents of the focused field with the
     * resulting AES ciphertext -- this is a whole-message operation, not per-keystroke, since AES
     * doesn't work as a 1-to-1 letter substitution the way the cipher keyboard does.
     */
    private fun encryptCurrentFieldWithAes(silent: Boolean = false, onEncrypted: (() -> Unit)? = null) {
        val passphrase = getStoredPassphrase()
        if (passphrase.isNullOrEmpty()) {
            if (!silent) Toast.makeText(this, "Set a shared passphrase first (tap Key)", Toast.LENGTH_LONG).show()
            return
        }
        val plaintext = composing.toString()
        if (plaintext.isBlank()) {
            if (!silent) Toast.makeText(this, "Type your message first, then tap Encrypt", Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            if (!silent) Toast.makeText(this, "No text field is focused", Toast.LENGTH_SHORT).show()
            return
        }
        // The actual crypto work (PBKDF2) is deliberately slow and can take real time on a
        // mid-range phone -- running it on the UI thread was what made Encrypt (and the whole
        // keyboard, since it shares the same thread) feel like it froze for a moment. Doing the
        // work on a background thread keeps the keyboard responsive the entire time.
        Thread {
            val encrypted = try {
                AesEngine.encrypt(plaintext, passphrase)
            } catch (e: Exception) {
                null
            }
            repeatHandler.post {
                if (encrypted == null) {
                    Toast.makeText(this, "Encryption failed, nothing was changed", Toast.LENGTH_SHORT).show()
                    return@post
                }
                try {
                    val beforeLen = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
                    val afterLen = ic.getTextAfterCursor(10000, 0)?.length ?: 0
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(beforeLen, afterLen)
                    ic.commitText(encrypted, 1)
                    ic.endBatchEdit()
                    composing = StringBuilder()
                    updatePreviewText()
                    if (!silent) Toast.makeText(this, "Encrypted -- ready to send", Toast.LENGTH_SHORT).show()
                    onEncrypted?.invoke()
                } catch (e: Exception) {
                    Toast.makeText(this, "Encryption failed, nothing was changed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun buildPreviewRow(): View {
        val row = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#161616"))
            }
            setPadding(dp(12), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).also { it.topMargin = dp(6) }
        }
        previewText = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 15f
            isSingleLine = true
            // Truncate from the START (not the end) once the box fills up, so the newest
            // characters you just typed stay visible instead of getting pushed off-screen.
            ellipsize = android.text.TextUtils.TruncateAt.START
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

    // Letters that need their displayed case updated when Shift toggles -- tracked so Shift can
    // update them directly instead of rebuilding the entire keyboard (every key, every popup
    // reference, torn down and recreated). That rebuild was expensive enough to cause real lag
    // and occasionally drop a fast tap that landed mid-rebuild -- especially painful in AES mode,
    // where real English capitalization means Shift gets used constantly.
    private val capsUpdatableViews = mutableListOf<Pair<TextView, Char>>()
    private var shiftKeyView: Button? = null

    private fun updateCapsDisplay() {
        for ((view, plainChar) in capsUpdatableViews) {
            view.text = (if (capsOn) plainChar.uppercaseChar() else plainChar).toString()
        }
        shiftKeyView?.text = if (capsOn) "\u21E7\u21E7" else "\u21E7"
    }

    private fun buildLetterRows(): View {
        capsUpdatableViews.clear()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(keyRow(CipherEngine.row1Plain.toCharArray().toList()))
        col.addView(keyRow(CipherEngine.row2Plain.toCharArray().toList(), sidePad = dp(16)))

        val lastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shiftBtn = controlKey(if (capsOn) "\u21E7\u21E7" else "\u21E7", weight = 1.4f) {
            capsOn = !capsOn
            updateCapsDisplay() // direct update, no full rebuild
        }
        shiftKeyView = shiftBtn as? Button
        lastRow.addView(shiftBtn)
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
            background = greyKeyBackground()
            layoutParams = keyMargins(weight)
        }
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    doBackspace() // immediate first delete
                    var repeatCount = 0
                    val r = object : Runnable {
                        override fun run() {
                            // Stop repeating the instant there's nothing left to delete --
                            // firing deleteSurroundingText on an empty/already-shrinking field
                            // is what triggers a selection-handle crash in some apps' emoji
                            // libraries (seen on WhatsApp/MIUI).
                            val hasTextLeft = !currentInputConnection
                                ?.getTextBeforeCursor(1, 0).isNullOrEmpty()
                            if (hasTextLeft) {
                                doBackspace()
                                repeatCount++
                                // ramp up the speed the longer you hold, like Gboard -- starts
                                // cautious (matches the WhatsApp-safe interval) then accelerates
                                val interval = when {
                                    repeatCount < 6 -> 70L
                                    repeatCount < 16 -> 45L
                                    else -> 25L
                                }
                                repeatHandler.postDelayed(this, interval)
                            } else {
                                repeatRunnable = null
                            }
                        }
                    }
                    repeatRunnable = r
                    repeatHandler.postDelayed(r, 280) // wait a beat before repeating
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
        row.addView(controlKey("\u263A", weight = 1f) {
            emojiMode = true
            refreshKeyboardView()
        })
        val space = Button(this).apply {
            text = if (symbolsMode) "space" else "space (cipher)"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            background = greyKeyBackground()
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 5f).also {
                it.marginStart = dp(2); it.marginEnd = dp(2)
                it.topMargin = dp(2); it.bottomMargin = dp(2)
            }
            setOnClickListener { if (aesMode) commitMaskedForAes(" ") else commitPlain(" ") }
        }
        row.addView(space)
        row.addView(controlKey("\u21B5", weight = 1.4f, accent = true) { sendEnter() })
        return row
    }

    // ---------- Emoji panel ----------

    private var currentEmojiCategory = 0

    private val emojiCategories: List<Pair<String, List<String>>> by lazy {
        listOf(
            "\uD83D\uDE00" to listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😝","😜","🤪","🤨","🧐","🤓","😎","🥸","🤩","🥳","😏","😒","😞","😔","🙁","☹️","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","🤗","🤔","🤭","🤫","🤥","😶","😐","😑","🙄","😯","😦","😮","😲","🥱","😴","🤤","😵","🥴","🤢","🤮","🤧","😷","🤒","🤕","🥹"),
            "\uD83D\uDC36" to listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🐛","🦋","🐌","🐞","🐜","🕷️","🐢","🐍","🦎","🐙","🦑","🦀","🐡","🐠","🐟","🐬","🐳","🐋","🦈","🐊","🦓","🦍","🐘","🦛","🦏","🐪","🦒","🐕","🐩","🐈","🦃","🦚","🦜","🐇","🦔"),
            "\uD83C\uDF4E" to listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🥦","🥒","🌶️","🌽","🥕","🥔","🍠","🥐","🍞","🧀","🥚","🍳","🥞","🥓","🍗","🍔","🍟","🍕","🌭","🌮","🌯","🥗","🍝","🍜","🍲","🍣","🍱","🍤","🍙","🍚","🍧","🍦","🍨","🎂","🍰","🍩","🍪","🍫","🍬","🍭","🍿","🧁","🥜","🍯"),
            "\u26BD" to listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🎱","🏓","🏸","🏒","🏑","🥍","🏏","⛳","🏹","🎣","🥊","🥋","🎽","🛹","🛷","⛸️","🎿","🏂","🏋️","🤼","🤸","⛹️","🤺","🏌️","🏇","🧘","🏄","🏊","🤽","🚣","🧗","🚵","🚴","🏆","🥇","🥈","🥉","🏅","🎖️","🎪","🤹","🎭","🎨","🎬","🎤","🎧","🎼","🎹","🥁","🎷","🎺","🎸","🎻","🎲","🎯","🎳","🎮"),
            "\u2708\uFE0F" to listOf("🚗","🚕","🚙","🚌","🏎️","🚓","🚑","🚒","🚐","🚚","🏍️","🚲","🛴","🚨","🚂","🚆","🚇","🚊","✈️","🛫","🛬","🚀","🛸","🚁","⛵","🚤","🛳️","⚓","🚧","🚦","🗺️","🗽","🗼","🏰","🏯","🎡","🎢","🎠","⛲","🏖️","🏝️","🏜️","🌋","⛰️","🏔️","🏕️","⛺","🏠","🏢","🏬","🏥","🏦","🏨","💒","⛪","🕌","🕍","🛕","⛩️"),
            "\uD83D\uDCA1" to listOf("⌚","📱","💻","⌨️","🖥️","🖨️","🖱️","📷","📸","📹","📞","☎️","📺","📻","🎙️","🧭","⏰","⌛","⏳","🔋","🔌","💡","🔦","🕯️","💰","💳","🧾","💎","🔧","🔨","⚙️","🔩","🔫","💣","🔪","🚪","🛏️","🛋️","🚽","🚿","🛁","🧴","🧹","🧻","🔑","🗝️","📁","📅","📌","📎","✂️","🔒","🔓","💊","💉","🩸","🩹","🚬","⚰️","🏺","🔮","📿","🔭"),
            "\u2764\uFE0F" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","☮️","✝️","☪️","🕉️","☸️","✡️","☯️","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓","⚠️","♻️","✅","❌","❗","❓","‼️","⁉️","💯","🔞","🔥","💧","⭐","🌟","✨","⚡","🌈","☀️","🌙","☁️","❄️","🔴","🟠","🟡","🟢","🔵","🟣"),
            "\uD83C\uDFF3\uFE0F" to listOf("🏳️","🏴","🚩","🏳️‍🌈","🏳️‍⚧️","🇺🇳","🇺🇸","🇬🇧","🇮🇳","🇧🇩","🇨🇦","🇦🇺","🇩🇪","🇫🇷","🇮🇹","🇯🇵","🇰🇷","🇨🇳","🇧🇷","🇷🇺","🇪🇸","🇵🇰","🇸🇦","🇦🇪","🇳🇬","🇿🇦","🇲🇽")
        )
    }

    private fun buildEmojiPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // top control row: back to ABC, and backspace so you can delete an emoji you just tapped
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow.addView(controlKey("\u2190 ABC", weight = 1.6f) {
            emojiMode = false
            refreshKeyboardView()
        })
        controlRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 3f) })
        controlRow.addView(backspaceKey(weight = 1.2f))
        col.addView(controlRow)

        // category tabs
        val tabsScroll = android.widget.HorizontalScrollView(this)
        val tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        emojiCategories.forEachIndexed { index, (icon, _) ->
            tabsRow.addView(TextView(this).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = if (index == currentEmojiCategory) {
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#333333"))
                    }
                } else null
                setOnClickListener {
                    currentEmojiCategory = index
                    refreshKeyboardView()
                }
            })
        }
        tabsScroll.addView(tabsRow)
        col.addView(tabsScroll)

        // emoji grid for the selected category
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210))
        }
        val gridCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val emojis = emojiCategories.getOrNull(currentEmojiCategory)?.second ?: emptyList()
        emojis.chunked(8).forEach { rowEmojis ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowEmojis.forEach { emoji ->
                row.addView(TextView(this).apply {
                    text = emoji
                    textSize = 22f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                    setOnClickListener { commitPlain(emoji) }
                })
            }
            // pad out the last row so keys stay evenly sized even if it's not a full row of 8
            repeat(8 - rowEmojis.size) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f) })
            }
            gridCol.addView(row)
        }
        scroll.addView(gridCol)
        col.addView(scroll)

        return col
    }

    // ---------- Clipboard history panel ----------

    private fun loadClipHistory(): MutableList<String> {
        return try {
            val prefs = getSharedPreferences(clipHistoryPrefsName, Context.MODE_PRIVATE)
            val raw = prefs.getString(clipHistoryKey, null) ?: return mutableListOf()
            // Try as encrypted data first (the normal case going forward).
            val decrypted = SecureStorage.decrypt(raw)
            val jsonText = if (decrypted != null) {
                decrypted
            } else {
                // Migration path: earlier versions stored this as plain JSON, unencrypted.
                // If it parses as JSON directly, it's the old format -- read it, then re-save
                // encrypted so it's protected from here on.
                raw
            }
            val arr = org.json.JSONArray(jsonText)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            if (decrypted == null) saveClipHistory(list) // migrate old plain-text history to encrypted
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveClipHistory(list: List<String>) {
        try {
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it) }
            val encrypted = SecureStorage.encrypt(arr.toString())
            if (encrypted != null) {
                getSharedPreferences(clipHistoryPrefsName, Context.MODE_PRIVATE)
                    .edit().putString(clipHistoryKey, encrypted).apply()
            }
            // If encryption fails for some reason, deliberately don't fall back to saving plain --
            // losing this save is better than silently storing clipboard contents unprotected.
        } catch (e: Exception) {
            // best-effort persistence -- losing history is better than crashing
        }
    }

    /** Adds a copied string to the persistent history, moving it to the front if already present. */
    private fun addToClipHistory(text: String) {
        if (text.isBlank()) return
        try {
            val list = loadClipHistory()
            list.remove(text)
            list.add(0, text)
            while (list.size > clipHistoryMax) list.removeAt(list.size - 1)
            saveClipHistory(list)
        } catch (e: Exception) { /* non-critical */ }
    }

    private fun buildClipHistoryPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow.addView(controlKey("\u2190 ABC", weight = 2f) {
            clipHistoryMode = false
            refreshKeyboardView()
        })
        controlRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
        controlRow.addView(controlKey("Clear all", weight = 2f) {
            saveClipHistory(emptyList())
            refreshKeyboardView()
        })
        col.addView(controlRow)
        col.addView(spacer(4))

        val history = loadClipHistory()
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        }
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (history.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = "Nothing copied yet -- anything you copy will show up here"
                setTextColor(Color.parseColor("#666666"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
        } else {
            history.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).also {
                        it.topMargin = dp(2); it.bottomMargin = dp(2)
                    }
                }
                row.addView(TextView(this).apply {
                    text = entry
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#DDDDDD"))
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(10), 0)
                    background = greyKeyBackground()
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also {
                        it.marginEnd = dp(2)
                    }
                    setOnClickListener {
                        commitPlain(entry)
                        clipHistoryMode = false
                        refreshKeyboardView()
                    }
                })
                row.addView(TextView(this).apply {
                    text = "\u00D7"
                    setTextColor(Color.parseColor("#999999"))
                    textSize = 18f
                    gravity = Gravity.CENTER
                    background = greyKeyBackground()
                    layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT)
                    setOnClickListener {
                        val updated = loadClipHistory()
                        updated.remove(entry)
                        saveClipHistory(updated)
                        refreshKeyboardView()
                    }
                })
                listCol.addView(row)
            }
        }
        scroll.addView(listCol)
        col.addView(scroll)

        return col
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
            layoutParams = keyMargins(weight)
            background = greyKeyBackground()
        }
        val mainLabel = TextView(this).apply {
            text = if (aesMode) shown.toString() else cipherChar.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 19f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(mainLabel)
        if (aesMode) {
            capsUpdatableViews.add(mainLabel to plainChar)
        } else {
            val cornerHint = TextView(this).apply {
                text = shown.toString()
                setTextColor(Color.parseColor("#5A5A5A"))
                textSize = 9f
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.TOP or Gravity.END
                    it.topMargin = dp(3); it.rightMargin = dp(4)
                }
            }
            frame.addView(cornerHint)
            capsUpdatableViews.add(cornerHint to plainChar)
        }
        frame.setOnClickListener { view ->
            val outLabel = plainChar.toString().let { if (capsOn) it.uppercase() else it }
            if (aesMode) {
                // Mask what actually appears in the chat's text field -- someone glancing at your
                // screen sees dots, not your words. The real letter still goes into `composing`
                // behind the scenes, ready to be AES-encrypted as a whole message on Send.
                commitMaskedForAes(outLabel)
                showKeyPreview(view, outLabel)
            } else {
                val outChar = CipherEngine.letterEncodeMap[plainChar] ?: plainChar
                commitCipherChar(outChar, outLabel)
                showKeyPreview(view, outLabel) // show the plain English letter, not the cipher glyph
            }
        }
        return frame
    }

    private fun digitKey(digitChar: Char, weight: Float): View {
        val cipherChar = CipherEngine.digitEncodeMap[digitChar] ?: digitChar
        val frame = FrameLayout(this).apply {
            layoutParams = keyMargins(weight)
            background = greyKeyBackground()
        }
        frame.addView(TextView(this).apply {
            text = if (aesMode) digitChar.toString() else cipherChar.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 19f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        if (!aesMode) {
            frame.addView(TextView(this).apply {
                text = digitChar.toString()
                setTextColor(Color.parseColor("#5A5A5A"))
                textSize = 9f
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.TOP or Gravity.END
                    it.topMargin = dp(3); it.rightMargin = dp(4)
                }
            })
        }
        frame.setOnClickListener { view ->
            if (aesMode) {
                commitMaskedForAes(digitChar.toString())
            } else {
                commitCipherChar(cipherChar, digitChar.toString())
            }
            showKeyPreview(view, digitChar.toString()) // show the plain digit, not the cipher glyph
        }
        return frame
    }

    // ---------- Key-press preview bubble ----------

    private var keyPreviewPopup: android.widget.PopupWindow? = null
    private var keyPreviewText: TextView? = null
    private var keyPreviewHideRunnable: Runnable? = null

    /** Briefly shows a bubble above the tapped key with the character that was typed. */
    private fun showKeyPreview(anchor: View, char: String) {
        try {
            var popup = keyPreviewPopup
            var textView = keyPreviewText
            if (popup == null || textView == null) {
                textView = TextView(this).apply {
                    textSize = 24f
                    setTextColor(Color.parseColor("#EEEEEE"))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#3D3D3D"))
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                popup = android.widget.PopupWindow(
                    textView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    isFocusable = false
                    isTouchable = false
                    isClippingEnabled = false
                }
                keyPreviewPopup = popup
                keyPreviewText = textView
            }
            textView.text = char
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            val x = loc[0] + anchor.width / 2 - dp(20)
            val y = loc[1] - dp(48)
            if (popup.isShowing) {
                popup.update(x, y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
            keyPreviewHideRunnable?.let { repeatHandler.removeCallbacks(it) }
            val hideRunnable = Runnable { try { popup.dismiss() } catch (e: Exception) {} }
            keyPreviewHideRunnable = hideRunnable
            repeatHandler.postDelayed(hideRunnable, 80)
        } catch (e: Exception) {
            // preview is purely cosmetic -- never let it interfere with actual typing
        }
    }

    /** A plain (non-ciphered) key, used for punctuation which the user said doesn't need hiding. */
    private fun plainKey(c: Char, weight: Float): View {
        val btn = Button(this).apply {
            text = c.toString()
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 17f
            background = greyKeyBackground()
            layoutParams = keyMargins(weight)
            setOnClickListener { if (aesMode) commitMaskedForAes(c.toString()) else commitPlain(c.toString()) }
        }
        return btn
    }

    private fun controlKey(label: String, weight: Float, accent: Boolean = false, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            setTextColor(if (accent) Color.parseColor("#111111") else Color.parseColor("#999999"))
            textSize = 14f
            background = if (accent) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#E8763C"))
                }
            } else {
                greyKeyBackground()
            }
            layoutParams = keyMargins(weight)
            setOnClickListener {
                try { onClick() } catch (e: Exception) { /* never let a key press crash the keyboard */ }
            }
        }
    }

    private fun smallButton(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat() // pill shape, closer to Gboard's toolbar chips
                setColor(Color.parseColor("#232323"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).also {
                it.marginStart = dp(3); it.marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick(); true }
            }
        }
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    /** Grey rounded-box background with a lighter tone while pressed -- matches Gboard's key look. */
    // Built once and reused via constantState.newDrawable() -- every key used to construct two
    // fresh GradientDrawable objects plus a StateListDrawable wrapper from scratch, every single
    // time the keyboard redrew (which happens on every Shift press and every mode switch). With
    // ~30 keys on screen, that added up to real, measurable lag on exactly the actions people do
    // most often. Deriving from a cached template is far cheaper while still keeping each key's
    // pressed-state fully independent (a naive single shared Drawable instance would make every
    // key flash "pressed" together, which is worse than the slowness it would "fix").
    private var greyKeyBackgroundTemplate: android.graphics.drawable.StateListDrawable? = null

    private fun greyKeyBackground(): android.graphics.drawable.Drawable {
        var template = greyKeyBackgroundTemplate
        if (template == null) {
            val normal = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat() // slightly rounder corners, closer to modern Gboard
                setColor(Color.parseColor("#2C2C2E"))
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#454548"))
            }
            template = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
            greyKeyBackgroundTemplate = template
        }
        // newDrawable() + mutate() gives each key its own independent state, cheaply derived
        // from the cached template instead of rebuilt from scratch.
        return template.constantState?.newDrawable(resources)?.mutate() ?: template
    }

    private fun keyMargins(weight: Float, heightDp: Int = 50): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(heightDp), weight).also {
            it.marginStart = dp(2); it.marginEnd = dp(2)
            it.topMargin = dp(2); it.bottomMargin = dp(2)
        }
    }

    // ---------- Input actions ----------

    private fun commitCipherChar(cipherChar: Char, plainEquivalent: String) {
        try {
            currentInputConnection?.commitText(cipherChar.toString(), 1)
        } catch (e: Exception) { /* target field rejected the commit -- ignore rather than crash */ }
        // Fast local append -- no round-trip to the target app needed for a normal keystroke,
        // which is what keeps typing feeling instant.
        composing.append(plainEquivalent)
        updatePreviewText()
    }

    private fun commitPlain(text: String) {
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (e: Exception) { /* target field rejected the commit -- ignore rather than crash */ }
        composing.append(text)
        updatePreviewText()
    }

    /**
     * Used by AES mode's keys instead of commitPlain: commits a mask character (dot) to the
     * visible field instead of the real letter, while the real letter still goes into `composing`
     * behind the scenes for later encryption. This is what stops someone glancing at your screen
     * from reading your message as you type it -- real AES can't be encrypted incrementally
     * letter-by-letter (the whole message has to be processed as one block to be secure), so
     * masking what's on screen is what actually delivers "nobody can shoulder-surf this."
     */
    private fun commitMaskedForAes(realText: String) {
        try {
            currentInputConnection?.commitText("\u2022".repeat(realText.length), 1)
        } catch (e: Exception) { /* target field rejected the commit -- ignore rather than crash */ }
        composing.append(realText)
        updatePreviewText()
    }

    /**
     * Re-derives the recheck preview directly from the real text field (decoding whatever cipher
     * text sits before the cursor) instead of trusting the locally tracked buffer. Used only for
     * the less-frequent cases (selection deletes, manual recheck) where accuracy matters more
     * than shaving a few milliseconds -- calling this on every keystroke is what made typing feel
     * sluggish, since it's a round-trip to the target app every time.
     */
    private fun syncComposingFromField() {
        try {
            val before = currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: ""
            composing = StringBuilder(CipherEngine.decode(before).takeLast(200))
        } catch (e: Exception) {
            // If we can't read the field for some reason, leave composing as-is rather than crash.
        }
        updatePreviewText()
    }

    private fun doBackspace() {
        val ic = currentInputConnection ?: return
        try {
            // If the user has an active text selection (dragged the selection handles), a plain
            // deleteSurroundingText behaves inconsistently across apps -- on WhatsApp specifically
            // it can end up deleting from the END of the whole message instead of the selection.
            // Replacing the selection with empty text is the reliable way to handle this. This is
            // the one delete path where we pay for a full resync, since it's a rare, deliberate
            // action rather than something fired dozens of times a second.
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.beginBatchEdit()
                ic.commitText("", 1)
                ic.endBatchEdit()
                syncComposingFromField()
                return
            }

            // Skip the call entirely if there's nothing before the cursor -- an unnecessary
            // delete on an empty field is one of the ways emoji-aware apps (Reddit, etc.) can
            // desync their own text state and crash on rapid-fire backspace.
            val before = ic.getTextBeforeCursor(1, 0)
            if (before.isNullOrEmpty()) return
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.endBatchEdit()
        } catch (e: Exception) {
            // Some apps' custom InputConnection wrappers (emoji-aware text fields especially)
            // can throw on rapid delete calls -- fail quietly rather than taking the keyboard down.
        }
        // Fast local delete for the normal (non-selection) case -- this path fires as often as
        // every 25ms while holding backspace, so it must stay a local, no-IPC operation.
        if (composing.isNotEmpty()) composing.deleteCharAt(composing.length - 1)
        updatePreviewText()
    }

    private fun sendEnter() {
        // In AES mode, with real unencrypted text sitting in the field, do the encrypt-then-send
        // as one motion -- this is what makes "type and hit send" automatically become encrypted,
        // without a separate Encrypt tap. We can only ever hook OUR OWN Enter/Send key this way --
        // there's no way for a keyboard to intercept another app's own on-screen send button.
        if (aesMode && composing.isNotBlank() && !getStoredPassphrase().isNullOrEmpty()) {
            encryptCurrentFieldWithAes(silent = true) { performActualSend() }
            return
        }
        performActualSend()
    }

    private fun performActualSend() {
        try {
            // Try the accessibility-based Send-button tap first -- this is the only way to
            // actually trigger apps like WhatsApp that treat Enter as a newline in their compose
            // box rather than "send." Only does anything if the user has explicitly enabled the
            // service in Android Settings; silently falls through otherwise.
            if (AutoSendAccessibilityService.isAvailable() && AutoSendAccessibilityService.attemptTapSend()) {
                composing.clear()
                updatePreviewText()
                return
            }

            val ic = currentInputConnection
            val actionId = currentEditorInfo?.actionId ?: EditorInfo.IME_ACTION_NONE
            // Prefer the field's own declared action (IME_ACTION_SEND etc.) -- this is what chat
            // apps actually use for their send button, so triggering it directly is more reliable
            // than simulating an Enter keypress, which some apps treat as "new line" instead.
            val sendableActions = setOf(
                EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT
            )
            if (ic != null && actionId in sendableActions) {
                ic.performEditorAction(actionId)
            } else {
                ic?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
        } catch (e: Exception) { /* target app rejected the send -- ignore rather than crash */ }
        composing.clear()
        updatePreviewText()
    }

    private fun refreshKeyboardView() {
        try {
            setInputView(onCreateInputView())
        } catch (e: Exception) {
            Toast.makeText(this, "Keyboard refresh failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Preview / recheck / eye ----------

    private fun updatePreviewText() {
        if (!::previewText.isInitialized) return
        if (previewHidden) {
            previewText.text = "\u2022 ".repeat(minOf(composing.length, 24)).trim()
        } else {
            previewText.text = if (composing.isEmpty()) "type to see plain-text preview" else composing.toString()
            schedulePreviewAutoHide() // typing while visible pushes the auto-hide timer back
        }
    }

    private fun schedulePreviewAutoHide() {
        previewHideRunnable?.let { repeatHandler.removeCallbacks(it) }
        val r = Runnable {
            previewHidden = true
            eyeIcon.text = "\u25CF"
            updatePreviewText()
            if (aesMode) toggleFieldMaskInAes(showReal = false) // safety net: re-mask the real field too if you forget
        }
        previewHideRunnable = r
        repeatHandler.postDelayed(r, previewAutoHideMs)
    }

    private fun refreshPreviewNow() {
        previewHidden = false
        if (aesMode) {
            // In AES mode the field shows dots, not decodable cipher text -- `composing` is
            // already the accurate source of truth, so re-reading the field would just overwrite
            // it with garbage (the dots don't decode to anything meaningful).
            updatePreviewText()
        } else {
            syncComposingFromField()
        }
    }

    private fun togglePreviewVisibility() {
        previewHidden = !previewHidden
        eyeIcon.text = if (previewHidden) "\u25CF" else "\u25CB"
        updatePreviewText()
        if (previewHidden) {
            previewHideRunnable?.let { repeatHandler.removeCallbacks(it) }
        }
        if (aesMode) toggleFieldMaskInAes(showReal = !previewHidden)
    }

    /**
     * Swaps the ACTUAL WhatsApp (or other app) text field between showing dots and showing your
     * real typed message -- not just the small preview line above the keyboard. Lets you review
     * or edit your message in context, then hide it again before someone glances over. Tied to
     * the same eye button (and the same auto-hide timer) as the preview line, so there's only one
     * control to remember.
     */
    private fun toggleFieldMaskInAes(showReal: Boolean) {
        val ic = currentInputConnection ?: return
        try {
            val beforeLen = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val afterLen = ic.getTextAfterCursor(10000, 0)?.length ?: 0
            if (beforeLen == 0 && afterLen == 0) return // nothing typed yet, nothing to swap
            val replacement = if (showReal) composing.toString() else "\u2022".repeat(composing.length)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.commitText(replacement, 1)
            ic.endBatchEdit()
        } catch (e: Exception) {
            // Best effort -- if the field is in an unexpected state, leave it alone rather than
            // risk mangling whatever the user has typed.
        }
    }

    // ---------- Clipboard tools ----------

    private fun performCopy() {
        try {
            val hadConnection = currentInputConnection != null
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
            if (hadConnection) scheduleClipboardAutoClear()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't copy -- select some text first", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wipes the clipboard a while after we last put cipher/plain text on it, so ciphertext
     * (or its decoded plaintext) doesn't sit around for other apps to read. Only clears if the
     * clipboard still holds exactly what we captured -- if the user copied something else in
     * the meantime, we leave it alone.
     */
    private fun scheduleClipboardAutoClear() {
        clipboardClearRunnable?.let { clipboardHandler.removeCallbacks(it) }
        clipboardHandler.postDelayed({
            val snapshot = readClipboardText()
            if (snapshot.isEmpty()) return@postDelayed
            val clearRunnable = Runnable {
                if (readClipboardText() == snapshot) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    Toast.makeText(this, "Clipboard auto-cleared", Toast.LENGTH_SHORT).show()
                }
            }
            clipboardClearRunnable = clearRunnable
            clipboardHandler.postDelayed(clearRunnable, clipboardAutoClearMs)
        }, 250)
    }

    private fun performPaste() {
        try {
            currentInputConnection?.performContextMenuAction(android.R.id.paste)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't paste", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSelectAll() {
        try {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't select all", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToNextKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open keyboard picker", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copying more than one message at once (multi-select in WhatsApp) joins them with newlines
     * into a single clipboard string -- trying to decrypt/decode that whole blob as ONE message
     * always failed. This processes each line independently instead, so "copy several messages,
     * tap Decode once" now actually decodes all of them together.
     *
     * WhatsApp (and other apps) sometimes prepend a timestamp/sender name to each copied line
     * (e.g. "[6:45 AM] Sultan: CK1:..."), so we search for the CK1: marker anywhere in the line
     * rather than requiring it at the very start, and keep whatever came before it intact.
     */
    private fun decodeAllLines(text: String, passphrase: String?): String {
        return text.split("\n").joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            val aesIndex = line.indexOf(AesEngine.PREFIX)
            when {
                line.isEmpty() -> ""
                aesIndex != -1 -> {
                    val leadingLabel = line.substring(0, aesIndex) // e.g. "[6:45 AM] Sultan: " -- kept as-is
                    val payload = line.substring(aesIndex).trim()
                    val decrypted = if (passphrase.isNullOrEmpty()) {
                        "[AES-encrypted -- set a passphrase first]"
                    } else {
                        AesEngine.decrypt(payload, passphrase) ?: "[couldn't decrypt this line -- passphrase mismatch?]"
                    }
                    leadingLabel + decrypted
                }
                CipherEngine.looksEncoded(line) -> CipherEngine.decode(line)
                else -> line // plain text mixed in (e.g. a sender name on its own line) -- leave it untouched
            }
        }
    }

    private fun clipboardHasDecodableContent(text: String): Boolean {
        return text.split("\n").any {
            val line = it.trim()
            line.contains(AesEngine.PREFIX) || CipherEngine.looksEncoded(line)
        }
    }

    private fun showDecodePopup() {
        val text = readClipboardText()
        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty. Copy their encoded text first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!clipboardHasDecodableContent(text)) {
            Toast.makeText(this, "Clipboard text doesn't look encoded.", Toast.LENGTH_SHORT).show()
            return
        }
        lastSeenClip = text
        val passphrase = getStoredPassphrase()
        // Off the main thread -- PBKDF2 runs once per AES-encrypted line found, same reason as
        // always to not block the UI while it works.
        Thread {
            val result = decodeAllLines(text, passphrase)
            repeatHandler.post { showDecodedDialog(result) }
        }.start()
    }

    /**
     * Called whenever the clipboard changes AND every time the keyboard opens.
     * If the new clipboard content is cipher text we haven't already shown, pop the decoded
     * popup automatically -- no need to tap Decode. True always-on background listening isn't
     * possible on modern Android (clipboard reads are blocked while an app isn't focused), so
     * "keyboard becomes visible" is the closest reliable substitute for live.
     */
    private fun maybeAutoOfferDecode() {
        if (!UnlockActivity.isCurrentlyUnlocked()) return // stay silent rather than auto-prompt unexpectedly
        val text = readClipboardText()
        if (text.isEmpty() || text == lastSeenClip) return
        if (!clipboardHasDecodableContent(text)) return
        lastSeenClip = text
        val passphrase = getStoredPassphrase()
        Thread {
            val result = decodeAllLines(text, passphrase)
            repeatHandler.post { showDecodedDialog(result) }
        }.start()
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
        scheduleClipboardAutoClear() // you've now seen the plaintext, no need to keep the ciphertext on the clipboard
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
