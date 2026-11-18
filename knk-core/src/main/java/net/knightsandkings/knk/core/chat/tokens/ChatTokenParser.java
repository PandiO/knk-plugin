package net.knightsandkings.knk.core.chat.tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds chat hashtag tokens (PandiO/knk-workspace#233). A token is {@code #name} or {@code #name@Player}:
 * <ul>
 *   <li>not glued to a word or a colour code before it ({@code abc#hand}, {@code &#FF0000}, {@code ##hand} are text);</li>
 *   <li>not followed by a letter, digit, {@code _} or {@code @} ({@code #handy}, {@code #kills@} are text);</li>
 *   <li>a known name ({@link ChatToken#byName}); unknown ones such as {@code #lol} stay as typed;</li>
 *   <li>{@code @Player} (a Minecraft name, 1-16 of {@code A-Z a-z 0-9 _}) only on tokens that allow one
 *   ({@link ChatToken#allowsTarget()}); {@code #hand@Steve} is text.</li>
 * </ul>
 * The plugin runs {@link #PATTERN} through Adventure's {@code replaceText} and judges each match with
 * {@link #parse(MatchResult)}, so styled chat (colour codes) is matched the same way; {@link #find} is the same rule
 * over a plain string. Pure.
 */
public final class ChatTokenParser {

    public static final Pattern PATTERN =
        Pattern.compile("(?<![\\p{Alnum}_#&])#([A-Za-z]+)(?:@([A-Za-z0-9_]{1,16}))?(?![\\p{Alnum}_@])");

    private ChatTokenParser() {
    }

    /** The token behind one match of {@link #PATTERN}; empty when it is unknown or may not have a target. */
    public static Optional<ChatTokenRef> parse(MatchResult match) {
        String target = match.group(2);
        return ChatToken.byName(match.group(1))
            .filter(token -> target == null || token.allowsTarget())
            .map(token -> new ChatTokenRef(token, target, match.group()));
    }

    /** Every token in {@code text}, in order. */
    public static List<ChatTokenRef> find(String text) {
        List<ChatTokenRef> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            parse(matcher.toMatchResult()).ifPresent(found::add);
        }
        return found;
    }
}
