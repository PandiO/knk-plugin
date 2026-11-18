package net.knightsandkings.knk.core.chat.tokens;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The chat hashtag tokens (PandiO/knk-workspace#233): a player types {@code #hand}, {@code #kills} or
 * {@code #kills@Steve} in chat, a private message or staff chat, and the token is shown as a hoverable value. This
 * enum is the registry: adding a token is one constant here plus its view in {@link ChatTokenViews} (statistics) or
 * in the plugin's renderer (items, location).
 * <p>
 * Each token has its own permission node, {@code knk.chat.token.<name>} (the Default group holds
 * {@code knk.chat.token.*}).
 */
public enum ChatToken {
    HAND("hand", Kind.ITEM, "the item in your main hand"),
    STATS("stats", Kind.STATISTICS, "a short overview of your public statistics"),
    KILLS("kills", Kind.STATISTICS, "your player kills"),
    DEATHS("deaths", Kind.STATISTICS, "your deaths"),
    KD("kd", Kind.STATISTICS, "your player kills per death"),
    STREAK("streak", Kind.STATISTICS, "your highest killstreak"),
    WINS("wins", Kind.STATISTICS, "your minigame wins"),
    PLAYTIME("playtime", Kind.STATISTICS, "your active playtime"),
    BALANCE("balance", Kind.STATISTICS, "your coins and gems"),
    TITLE("title", Kind.STATISTICS, "your title"),
    XP("xp", Kind.STATISTICS, "your experience"),
    DISCOVERIES("discoveries", Kind.STATISTICS, "the places you discovered"),
    LOCATION("location", Kind.LOCATION, "where you are"),
    TOKENS("tokens", Kind.HELP, "this list, shown only to you");

    /** Where a token's value comes from. */
    public enum Kind {
        /** The sender's held item, read on the main thread. */
        ITEM,
        /** The player's statistics as any signed-in player sees them (an API read; the cooldown applies). */
        STATISTICS,
        /** The sender's position. */
        LOCATION,
        /** The token list, shown privately to the sender. */
        HELP
    }

    public static final String NODE_PREFIX = "knk.chat.token.";
    /** Skips the Game Settings limits. Deliberately not under {@code knk.chat.token.*}. */
    public static final String BYPASS_NODE = "knk.chat.tokens.bypass";

    private static final Map<String, ChatToken> BY_NAME = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(ChatToken::tokenName, Function.identity()));

    private final String tokenName;
    private final Kind kind;
    private final String description;

    ChatToken(String tokenName, Kind kind, String description) {
        this.tokenName = tokenName;
        this.kind = kind;
        this.description = description;
    }

    /** As typed after the {@code #}, lower case. */
    public String tokenName() {
        return tokenName;
    }

    public Kind kind() {
        return kind;
    }

    /** For {@code #tokens}: "the item in your main hand". */
    public String description() {
        return description;
    }

    /** {@code knk.chat.token.<name>}. */
    public String node() {
        return NODE_PREFIX + tokenName;
    }

    /** Only statistics can be asked about another player ({@code #kills@Steve}). */
    public boolean allowsTarget() {
        return kind == Kind.STATISTICS;
    }

    /** Case-insensitive; empty for an unknown name (the text stays as typed). */
    public static Optional<ChatToken> byName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every token, in the order {@code #tokens} lists them. */
    public static List<ChatToken> all() {
        return List.of(values());
    }
}
