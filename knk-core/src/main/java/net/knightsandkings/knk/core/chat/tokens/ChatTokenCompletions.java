package net.knightsandkings.knk.core.chat.tokens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The chat completions for the tokens (PandiO/knk-workspace#233): what the Minecraft client offers on Tab while a
 * chat word is being typed. The client completes the current word from a list the server sets per player, matching
 * from the start of the word, so {@code #kills@} + Tab offers {@code #kills@Steve}. Pure.
 */
public final class ChatTokenCompletions {

    private ChatTokenCompletions() {
    }

    /**
     * Every token the viewer may use ({@code #hand}, {@code #kills}, …), plus each statistics token with
     * {@code @name} for every player in {@code visibleOthers} (online players the viewer can see, the viewer left
     * out: their own token needs no name).
     */
    public static List<String> forViewer(Collection<ChatToken> allowed, Collection<String> visibleOthers) {
        List<String> completions = new ArrayList<>();
        for (ChatToken token : ChatToken.all()) {
            if (!allowed.contains(token)) {
                continue;
            }
            completions.add("#" + token.tokenName());
            if (token.allowsTarget()) {
                for (String name : visibleOthers) {
                    completions.add("#" + token.tokenName() + "@" + name);
                }
            }
        }
        return completions;
    }
}
