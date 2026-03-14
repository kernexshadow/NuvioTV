@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

/**
 * Dean Edwards p,a,c,k,e,d JavaScript unpacker.
 * Used by many CloudStream extensions to deobfuscate packed JS.
 */
/**
 * Wrapper for constructor-style usage: JsUnpacker(data).unpack()
 * Used by CloudStream3 extractors.
 */
class JsUnpackerInstance(private val data: String?) {
    fun unpack(): String? {
        if (data.isNullOrBlank()) return null
        return if (JsUnpacker.detect(data)) JsUnpacker.unpack(data) else null
    }
}

object JsUnpacker {
    /** Constructor-style usage: JsUnpacker("packed data").unpack() */
    operator fun invoke(data: String?): JsUnpackerInstance = JsUnpackerInstance(data)

    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)"""
    )

    /**
     * Detects whether the given string contains packed JavaScript.
     */
    fun detect(html: String): Boolean {
        return html.contains("eval(function(p,a,c,k,e,")
    }

    /**
     * Unpacks packed JavaScript code. Returns the unpacked string, or the original if not packed.
     */
    fun unpack(html: String): String {
        val match = PACKED_REGEX.find(html) ?: return html
        val payload = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return html
        val count = match.groupValues[3].toIntOrNull() ?: return html
        val keywords = match.groupValues[4].split("|")

        if (radix < 2 || count == 0) return html

        return unbase(payload, radix, keywords)
    }

    /**
     * Unpacks all packed scripts found in the input and returns the concatenated results.
     */
    fun unpackAll(html: String): String {
        if (!detect(html)) return html

        val sb = StringBuilder()
        var remaining = html
        while (true) {
            val match = PACKED_REGEX.find(remaining) ?: break
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: break
            val keywords = match.groupValues[4].split("|")
            sb.append(unbase(payload, radix, keywords))
            remaining = remaining.substring(match.range.last + 1)
        }
        return if (sb.isEmpty()) html else sb.toString()
    }

    private fun unbase(payload: String, radix: Int, keywords: List<String>): String {
        // Replace each word-boundary token with its keyword lookup
        return Regex("""\b(\w+)\b""").replace(payload) { matchResult ->
            val word = matchResult.groupValues[1]
            val index = parseBaseN(word, radix)
            if (index >= 0 && index < keywords.size && keywords[index].isNotEmpty()) {
                keywords[index]
            } else {
                word
            }
        }
    }

    /**
     * Parse a string as a base-N number.
     * Supports up to base 62 (0-9, a-z, A-Z).
     */
    private fun parseBaseN(str: String, radix: Int): Int {
        if (radix <= 36) {
            return str.toIntOrNull(radix) ?: -1
        }
        // Extended base (37-62): 0-9, a-z (10-35), A-Z (36-61)
        var result = 0
        for (c in str) {
            result *= radix
            result += when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 36
                else -> return -1
            }
        }
        return result
    }
}
