package dev.butterflysky.argus.common

/**
 * Utility for finding Argus link tokens embedded in user-facing messages.
 * It looks for patterns like "/link <token>" and returns positional info
 * so platform-specific layers can add click events without changing text.
 */
object LinkMessageParser {
    private val tokenRegex = Regex("/link\\s+([0-9a-fA-F]{8,})")

    data class LinkMatch(
        val message: String,
        val token: String,
        val tokenStart: Int,
        val tokenEnd: Int,
    ) {
        val prefix: String get() = message.substring(0, tokenStart)
        val suffix: String get() = message.substring(tokenEnd)
    }

    /**
     * Finds the first link token in [message], if present.
     */
    fun find(message: String): LinkMatch? {
        val match = tokenRegex.find(message) ?: return null
        val range = match.groups[1]?.range ?: return null
        return LinkMatch(
            message = message,
            token = match.groupValues[1],
            tokenStart = range.first,
            tokenEnd = range.last + 1,
        )
    }
}
