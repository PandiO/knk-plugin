package net.knightsandkings.knk.paper.chat.tokens;

import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/**
 * Chat hashtag tokens for private messages and staff chat (PandiO/knk-workspace#233). Called on the main thread.
 */
@FunctionalInterface
public interface ChatTokenExpander {

    /**
     * The message body to show: {@code Component.text(text)} when it has no tokens, the expanded body otherwise; or
     * null when the message must not be sent now (the sender was asked whether to share a private statistic and
     * {@code resend} runs once they answer, or the message was only {@code #tokens}). Completes off the main thread.
     */
    CompletableFuture<Component> expand(Player sender, String text, Runnable resend);
}
