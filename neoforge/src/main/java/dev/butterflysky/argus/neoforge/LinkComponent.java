package dev.butterflysky.argus.neoforge;

import dev.butterflysky.argus.common.LinkMessageParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Builds a Component that copies the link token when clicked, if a "/link <token>" is present.
 */
public final class LinkComponent {
    private static final Component HOVER = Component.literal("Click to copy token");

    private LinkComponent() {}

    public static Component of(String message) {
        LinkMessageParser.LinkMatch match = LinkMessageParser.INSTANCE.find(message);
        if (match == null) {
            return Component.literal(message);
        }

        MutableComponent tokenText =
                Component.literal(match.getToken())
                        .withStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.AQUA)
                                        .withClickEvent(new ClickEvent.CopyToClipboard(match.getToken()))
                                        .withHoverEvent(new HoverEvent.ShowText(HOVER)));

        return Component.literal(match.getPrefix()).append(tokenText).append(Component.literal(match.getSuffix()));
    }
}
