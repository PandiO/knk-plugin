package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One head of a live match in {@code duel_matches} and the arena menu (DESIGN §9.2 "both heads"): every match is two
 * rows, the challenger's head then the opponent's, in a grid eight wide so a pair never splits. Both open the match
 * detail.
 */
public final class DuelMatchRowView implements MenuRowKey {

    private final DuelMatchSnapshot match;
    private final DuelSide side;

    DuelMatchRowView(DuelMatchSnapshot match, DuelSide side) {
        this.match = Objects.requireNonNull(match, "match");
        this.side = Objects.requireNonNull(side, "side");
    }

    /** Two rows per listed match, in the given order. */
    public static List<DuelMatchRowView> rows(List<DuelMatchSnapshot> matches) {
        List<DuelMatchRowView> rows = new ArrayList<>();
        for (DuelMatchSnapshot m : matches) {
            if (!m.isListed()) continue;
            rows.add(new DuelMatchRowView(m, DuelSide.CHALLENGER));
            rows.add(new DuelMatchRowView(m, DuelSide.OPPONENT));
        }
        return rows;
    }

    @Override
    public Object menuRowKey() {
        return "duel:" + match.token() + ":" + side;
    }

    public String getToken() {
        return match.token().toString();
    }

    public String getMaterial() {
        return "PLAYER_HEAD";
    }

    public String getSkullOwner() {
        return match.side(side).name();
    }

    public String getName() {
        return "&e" + match.side(side).name() + " &7vs &e" + match.side(side.other()).name();
    }

    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&7" + match.arenaName() + (match.battlegroundName() != null ? " &8- &7" + match.battlegroundName() : ""));
        lines.add("&7Stage: &a" + DuelViewText.phase(match.phase(), match.waitingForBattleground()));
        String timer = DuelViewText.timer(match);
        if (!timer.isEmpty()) lines.add("&7Timer: &f" + timer);
        lines.add("&7Gear: &f" + DuelAnnouncements.gearOf(match));
        lines.add("&7Stakes: &f" + DuelAnnouncements.stakesOf(match));
        lines.add("");
        lines.add("&eClick for the details");
        return lines;
    }
}
