package dev.butterflysky.argus.fabric

import dev.butterflysky.argus.common.LinkMessageParser
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Builds chat/kick messages that include a copy-to-clipboard click target
 * when a link token is present ("/link <token>"). Falls back to plain text.
 */
object LinkText {
    private const val HOVER = "Click to copy token"

    @JvmStatic
    fun of(message: String): Text {
        val match = LinkMessageParser.find(message) ?: return Text.literal(message)

        val tokenText =
            Text.literal(match.token)
                .setStyle(
                    Style.EMPTY
                        .withColor(Formatting.AQUA)
                        .withClickEvent(ClickEvent.CopyToClipboard(match.token))
                        .withHoverEvent(HoverEvent.ShowText(Text.literal(HOVER))),
                )

        return Text.literal(match.prefix).append(tokenText).append(Text.literal(match.suffix))
    }
}
