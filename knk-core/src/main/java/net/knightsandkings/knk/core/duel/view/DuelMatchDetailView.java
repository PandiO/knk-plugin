package net.knightsandkings.knk.core.duel.view;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code duelMatch} root of {@code duel_match_detail} (DESIGN §9.2): the centre "VS / phase / timer / gear mode"
 * tile and what the spectator-area button needs. A match that is over (or an unknown token) renders as "This duel is
 * over".
 */
public final class DuelMatchDetailView {

    private final DuelMatchSnapshot match;

    private DuelMatchDetailView(DuelMatchSnapshot match) {
        this.match = match;
    }

    public static DuelMatchDetailView of(DuelMatchSnapshot match) {
        return new DuelMatchDetailView(match != null && match.isListed() ? match : null);
    }

    public static DuelMatchDetailView gone() {
        return new DuelMatchDetailView(null);
    }

    /** "true" while the match is listed (WARMUP → ENDING). */
    public String getKnown() {
        return String.valueOf(match != null);
    }

    public String getArenaId() {
        return match == null ? "0" : String.valueOf(match.arenaId());
    }

    public String getVsMaterial() {
        return match == null ? "BARRIER" : "IRON_SWORD";
    }

    public String getVsTitle() {
        return match == null ? "&cThis duel is over" : "&6" + match.challenger().name() + " &7vs &6" + match.opponent().name();
    }

    public List<String> getVsLines() {
        if (match == null) return List.of("&7It isn't running any more.", "&7/duels lists the live duels.");
        List<String> lines = new ArrayList<>();
        lines.add("&7Arena: &f" + match.arenaName());
        if (match.battlegroundName() != null) lines.add("&7Battleground: &f" + match.battlegroundName());
        lines.add("&7Stage: &a" + DuelViewText.phase(match.phase(), match.waitingForBattleground()));
        String timer = DuelViewText.timer(match);
        if (!timer.isEmpty()) lines.add("&7Timer: &f" + timer);
        lines.add("&7Gear: &f" + DuelAnnouncements.gearOf(match));
        lines.add("&7Pot: &f" + DuelAnnouncements.stakesOf(match));
        if (match.potCoins() > 0 && match.feePercent() > 0) {
            lines.add("&7Fee: &f" + match.feePercent() + "% &7of the coins");
        }
        if (match.held()) lines.add("&eThe result is pending staff review.");
        return lines;
    }

    public String getChallengerName() {
        return match == null ? "" : match.challenger().name();
    }

    public String getOpponentName() {
        return match == null ? "" : match.opponent().name();
    }
}
