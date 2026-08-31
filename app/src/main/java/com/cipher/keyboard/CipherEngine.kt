package com.cipher.keyboard

/**
 * Substitution cipher: each lowercase a-z maps to a unique Armenian-script glyph,
 * each digit 0-9 maps to a unique Bengali-digit glyph. This is obfuscation, not
 * real cryptography -- it hides text from casual shoulder-surfing / keyword search,
 * not from a determined attacker.
 *
 * Both devices must use the SAME mapping (this file) for encode/decode to match.
 */
object CipherEngine {

    // 26 letters -> 26 Armenian capital letters (visually unfamiliar to most readers)
    private val lettersPlain = "abcdefghijklmnopqrstuvwxyz".toCharArray()
    private val lettersCipher = "ԱԲԳԴԵԶԷԸԹԺԻԼԽԾԿՀՁՂՃՄՅՆՇՈՉՊ".toCharArray()

    // 10 digits -> 10 Bengali digits
    private val digitsPlain = "0123456789".toCharArray()
    private val digitsCipher = "০১২৩৪৫৬৭৮৯".toCharArray()

    val letterEncodeMap: Map<Char, Char> = lettersPlain.zip(lettersCipher).toMap()
    val letterDecodeMap: Map<Char, Char> = lettersCipher.zip(lettersPlain).toMap()

    val digitEncodeMap: Map<Char, Char> = digitsPlain.zip(digitsCipher).toMap()
    val digitDecodeMap: Map<Char, Char> = digitsCipher.zip(digitsPlain).toMap()

    // Ordered list of (cipherChar, plainChar, row) for building the keyboard UI
    // Rows follow a standard QWERTY layout so muscle memory carries over.
    val row1Plain = "qwertyuiop"
    val row2Plain = "asdfghjkl"
    val row3Plain = "zxcvbnm"

    fun cipherFor(plainChar: Char): Char {
        val lower = plainChar.lowercaseChar()
        return letterEncodeMap[lower] ?: digitEncodeMap[plainChar] ?: plainChar
    }

    /** Encode plain text to ciphertext. Punctuation, spaces, and unknown chars pass through unchanged. */
    fun encode(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            val lower = c.lowercaseChar()
            when {
                letterEncodeMap.containsKey(lower) -> sb.append(letterEncodeMap[lower])
                digitEncodeMap.containsKey(c) -> sb.append(digitEncodeMap[c])
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Decode ciphertext back to plain text. Anything not in the cipher alphabet passes through unchanged. */
    fun decode(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when {
                letterDecodeMap.containsKey(c) -> sb.append(letterDecodeMap[c])
                digitDecodeMap.containsKey(c) -> sb.append(digitDecodeMap[c])
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** True if the string contains at least one cipher-alphabet character (used to decide whether "Decode" has anything to show). */
    fun looksEncoded(input: String): Boolean {
        return input.any { letterDecodeMap.containsKey(it) || digitDecodeMap.containsKey(it) }
    }
}
