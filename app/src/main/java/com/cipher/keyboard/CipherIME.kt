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
    private var emojiMode = false
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
        clipboardHandler.removeCallbacksAndMessages(null)
        keyboardRootView = null
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        composing = StringBuilder()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }

        if (emojiMode) {
            root.addView(buildEmojiPanel())
        } else {
            root.addView(buildToolbarRow())
            root.addView(buildPreviewRow())
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
            background = greyKeyBackground()
            layoutParams = keyMargins(weight)
        }
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    doBackspace() // immediate first delete
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
                                repeatHandler.postDelayed(this, 90) // slower than 50ms to avoid racing the host app's text engine
                            } else {
                                repeatRunnable = null
                            }
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
        row.addView(controlKey("\u263A", weight = 1f) {
            emojiMode = true
            refreshKeyboardView()
        })
        val space = Button(this).apply {
            text = if (symbolsMode) "space" else "space (cipher)"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            background = greyKeyBackground()
            layoutParams = LinearLayout.LayoutParams(0, dp(60), 5f).also {
                it.marginStart = dp(3); it.marginEnd = dp(3)
                it.topMargin = dp(3); it.bottomMargin = dp(3)
            }
            setOnClickListener { commitPlain(" ") }
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
            layoutParams = keyMargins(weight)
            background = greyKeyBackground()
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
            background = greyKeyBackground()
            layoutParams = keyMargins(weight)
            setOnClickListener { commitPlain(c.toString()) }
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

    /** Grey rounded-box background with a lighter tone while pressed -- matches Gboard's key look. */
    private fun greyKeyBackground(): android.graphics.drawable.Drawable {
        val normal = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#2B2B2B"))
        }
        val pressed = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#3D3D3D"))
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun keyMargins(weight: Float, heightDp: Int = 60): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(heightDp), weight).also {
            it.marginStart = dp(3); it.marginEnd = dp(3)
            it.topMargin = dp(3); it.bottomMargin = dp(3)
        }
    }

    // ---------- Input actions ----------

    private fun commitCipherChar(cipherChar: Char, plainEquivalent: String) {
        try {
            currentInputConnection?.commitText(cipherChar.toString(), 1)
        } catch (e: Exception) { /* target field rejected the commit -- ignore rather than crash */ }
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

    private fun doBackspace() {
        val ic = currentInputConnection ?: return
        try {
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
        if (composing.isNotEmpty()) composing.deleteCharAt(composing.length - 1)
        updatePreviewText()
    }

    private fun sendEnter() {
        try {
            currentInputConnection?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
            currentInputConnection?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
            )
        } catch (e: Exception) { /* target app rejected the key event -- ignore rather than crash */ }
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
        }
        previewHideRunnable = r
        repeatHandler.postDelayed(r, previewAutoHideMs)
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
        if (previewHidden) {
            previewHideRunnable?.let { repeatHandler.removeCallbacks(it) }
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
