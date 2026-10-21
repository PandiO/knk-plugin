package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A duelist's card on {@code duel_match_detail} (DESIGN §9.2): their head, kit and stake, and their statistics as
 * the API shows them to the viewer: title, XP and playtime (always public), duel W/L/D (public by default, D17) and
 * PvP kills (the player's own settings). A metric the API didn't return is hidden from this viewer and isn't shown.
 */
public final class DuelSideCardView implements MenuRowKey {

    static final String DUEL_CONTEXT = "duel";

    /** Where the viewer's statistics read is. */
    public enum Stats { LOADING, LOADED, UNAVAILABLE }

    private final DuelMatchSnapshot match;
    private final DuelSide side;
    private final Stats state;
    private final PlayerStatistics statistics;

    private DuelSideCardView(DuelMatchSnapshot match, DuelSide side, Stats state, PlayerStatistics statistics) {
        this.match = Objects.requireNonNull(match, "match");
        this.side = Objects.requireNonNull(side, "side");
        this.state = statistics != null ? Stats.LOADED : state;
        this.statistics = statistics;
    }

    public static DuelSideCardView loading(DuelMatchSnapshot match, DuelSide side) {
        return new DuelSideCardView(match, side, Stats.LOADING, null);
    }

    /** {@code statistics} null = the read failed. */
    public static DuelSideCardView of(DuelMatchSnapshot match, DuelSide side, PlayerStatistics statistics) {
        return new DuelSideCardView(match, side, Stats.UNAVAILABLE, statistics);
    }

    @Override
    public Object menuRowKey() {
        return "duel-side:" + match.token() + ":" + side;
    }

    public String getMaterial() {
        return "PLAYER_HEAD";
    }

    public String getSkullOwner() {
        return match.side(side).name();
    }

    public String getTitle() {
        boolean winner = match.winner() == side;
        return (winner ? "&a" : "&e") + match.side(side).name() + (winner ? " &7(winner)" : "");
    }

    public String getDisplayMode() {
        return match.winner() == side ? "HIGHLIGHT" : "NORMAL";
    }

    public List<String> getLines() {
        DuelMatchSnapshot.Side s = match.side(side);
        List<String> lines = new ArrayList<>();
        lines.add(side == DuelSide.CHALLENGER ? "&7Challenger" : "&7Opponent");
        if (s.kitName() != null) lines.add("&7Kit: &f" + s.kitName());
        lines.add("&7Stake: &f" + DuelViewText.stakes(s.coins(), s.itemCount()));
        lines.add("");
        switch (state) {
            case LOADING -> lines.add("&8Loading statistics...");
            case UNAVAILABLE -> lines.add("&8Statistics aren't available right now.");
            case LOADED -> lines.addAll(statLines(statistics));
        }
        return lines;
    }

    /** Title, XP, playtime; duel W/L/D; PvP kills - each only when the API returned it. */
    static List<String> statLines(PlayerStatistics statistics) {
        List<String> lines = new ArrayList<>(StatisticsLines.profileLines(statistics.profile()));
        Optional<Double> wins = duelValue(statistics, "wins");
        Optional<Double> losses = duelValue(statistics, "losses");
        Optional<Double> draws = duelValue(statistics, "draws");
        if (wins.isPresent() || losses.isPresent() || draws.isPresent()) {
            lines.add("&7Duels: &a" + count(wins) + "W &c" + count(losses) + "L &f" + count(draws) + "D");
        }
        statistics.metric("pvp_kills").map(PlayerStatistics.Metric::value)
                .ifPresent(v -> lines.add("&7PvP kills: &f" + String.format(Locale.ENGLISH, "%,d", Math.round(v))));
        return lines;
    }

    /** The metric's {@code duel} context value, if the viewer may see it. */
    static Optional<Double> duelValue(PlayerStatistics statistics, String metricKey) {
        return statistics.metric(metricKey).flatMap(m -> m.contexts().stream()
                .filter(c -> DUEL_CONTEXT.equals(c.context())).findFirst()
                .map(PlayerStatistics.ContextValue::value));
    }

    private static String count(Optional<Double> value) {
        return value.map(v -> String.valueOf(Math.round(v))).orElse("?");
    }
}
