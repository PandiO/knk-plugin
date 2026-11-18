package net.knightsandkings.knk.core.chat.tokens;

import java.util.Locale;

/**
 * One token found in a message: {@code #kills} ({@code target} null = the sender) or {@code #kills@Steve}.
 *
 * @param raw the text as typed, e.g. {@code #Kills@steve}
 */
public record ChatTokenRef(ChatToken token, String target, String raw) {

    /** Whether the token is about the sender: no target, or the sender's own name. */
    public boolean isAbout(String senderName) {
        return target == null || target.equalsIgnoreCase(senderName);
    }

    /** A dedupe key: the same token about the same player resolves once per message. */
    public String key() {
        return token.tokenName() + "@" + (target == null ? "" : target.toLowerCase(Locale.ROOT));
    }
}
