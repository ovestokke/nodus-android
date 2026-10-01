package org.qosp.notes.ui.utils

/** Presentation-only offsets into the original text; never normalize stored content. */
object HttpLinks {
    data class Link(val start: Int, val end: Int, val destination: String)

    private val scheme = Regex("https?://", RegexOption.IGNORE_CASE)
    private val boundaries = "([{<>'\"‘“;,!?"
    private val terminators = "<>\"'‘’“”`\\"
    private val punctuation = ".,!?;:"

    fun find(text: String): List<Link> {
        val links = mutableListOf<Link>()
        var searchStart = 0
        while (searchStart < text.length) {
            val match = scheme.find(text, searchStart) ?: break
            val start = match.range.first
            var end = match.range.last + 1
            while (end < text.length && !isWhitespace(text[end]) && !isControl(text[end]) &&
                text[end] !in terminators
            ) end++
            // Consume the whole candidate, including rejected ones; never extract a nested scheme.
            searchStart = end
            if (start > 0 && !isWhitespace(text[start - 1]) && text[start - 1] !in boundaries) continue
            var candidate = text.substring(start, end)
            while (candidate.isNotEmpty()) {
                val last = candidate.last()
                val opener = when (last) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
                if (last in punctuation || (opener != null && candidate.count { it == last } >
                        candidate.count { it == opener })) {
                    candidate = candidate.dropLast(1)
                    end--
                } else break
            }
            if (isValidDestination(candidate)) links.add(Link(start, end, candidate))
        }
        return links
    }

    fun isValidDestination(url: String): Boolean {
        val prefix = scheme.find(url)?.takeIf { it.range.first == 0 } ?: return false
        if (url.any { isWhitespace(it) || isControl(it) || isBidi(it) || it in terminators }) return false
        val authority = url.substring(prefix.range.last + 1).takeWhile { it !in "/?#" }
        if (authority.isEmpty() || '@' in authority) return false
        val host: String
        val port: String?
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            if (closing < 0) return false
            host = authority.substring(1, closing)
            if (':' !in host || host.any { it !in "0123456789abcdefABCDEF:." }) return false
            val suffix = authority.substring(closing + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return false
            port = suffix.takeIf { it.isNotEmpty() }?.drop(1)
        } else {
            host = authority.substringBefore(':')
            port = authority.takeIf { ':' in it }?.substringAfter(':')
            if (host.isEmpty() || host.all { it in ".-" } || host.any {
                    !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-" || it.code > 127)
                }) return false
        }
        return port == null || (port.length in 1..5 && port.all { it in '0'..'9' } &&
            port.toIntOrNull()?.let { it in 1..65535 } == true)
    }

    // Match the shared JS contract's whitespace, not Kotlin's extra U+001C–001F separators.
    private fun isWhitespace(char: Char) = char in '\t'..'\r' || char == ' ' ||
        char.code in 0x2000..0x200A || char.code in listOf(0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    private fun isControl(char: Char) = char.code in 0..31 || char.code in 127..159
    private fun isBidi(char: Char) = char.code in 0x202A..0x202E || char.code in 0x2066..0x2069 ||
        char.code in listOf(0x061C, 0x200E, 0x200F)
}
