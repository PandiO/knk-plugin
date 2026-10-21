package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.List;

/**
 * One online player in the arena menu's "Challenge a player" picker ({@code duel_challenge}). A click runs
 * {@code /duel <name>}, so the invite rules and the proposal editor are P5's. {@link #none()} is the disabled
 * "nobody to challenge" tile.
 */
public final class DuelChallengeRowView implements MenuRowKey {

    private final String name;
    private final String busy;

    private DuelChallengeRowView(String name, String busy) {
        this.name = name;
        this.busy = busy;
    }

    /** @param busy why they can't be challenged now ("in a duel"), or null */
    public static DuelChallengeRowView of(String name, String busy) {
        return new DuelChallengeRowView(name, busy);
    }

    public static DuelChallengeRowView none() {
        return new DuelChallengeRowView(null, null);
    }

    @Override
    public Object menuRowKey() {
        return name == null ? "duel-challenge:none" : "duel-challenge:" + name;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getMaterial() {
        return name == null ? "BARRIER" : "PLAYER_HEAD";
    }

    public String getSkullOwner() {
        return name;
    }

    public String getTitle() {
        return name == null ? "&7Nobody to challenge" : (busy == null ? "&a" : "&7") + name;
    }

    public String getDisplayMode() {
        return name == null || busy != null ? "DISABLED" : "NORMAL";
    }

    public List<String> getLines() {
        if (name == null) return List.of("&7No other player is online.");
        if (busy != null) return List.of("&7" + capitalised(busy) + ".");
        return List.of("&7Propose a duel to " + name + ".", "", "&eClick to open the proposal");
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
