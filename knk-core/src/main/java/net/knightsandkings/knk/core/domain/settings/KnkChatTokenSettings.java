package net.knightsandkings.knk.core.domain.settings;

/**
 * The chat hashtag token limits from the Game Settings page (PandiO/knk-workspace#233, knk-web-api
 * {@code ChatTokenSettingsDto}). Both are off (0) by default; holders of {@code knk.chat.tokens.bypass} skip them.
 *
 * @param maxTokensPerMessage the most tokens expanded in one message (later ones stay plain text); 0 = no limit
 * @param statCooldownSeconds seconds a player waits between messages with statistics tokens; 0 = no cooldown
 */
public record KnkChatTokenSettings(int maxTokensPerMessage, int statCooldownSeconds) {

    private static final KnkChatTokenSettings OFF = new KnkChatTokenSettings(0, 0);

    public KnkChatTokenSettings {
        maxTokensPerMessage = Math.max(0, maxTokensPerMessage);
        statCooldownSeconds = Math.max(0, statCooldownSeconds);
    }

    /** No limits: the API sent none, or an older API without the block. */
    public static KnkChatTokenSettings off() {
        return OFF;
    }
}
