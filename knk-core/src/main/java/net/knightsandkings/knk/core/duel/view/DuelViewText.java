package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.GearMode;

import java.util.Locale;

/**
 * Shared wording of the duel announcements, sidebar, boss bar and menus (arena duels DESIGN §9). Plain text with
 * {@code &} colour codes where a caller asks for colour; the Paper side parses it.
 */
public final class DuelViewText {

    private DuelViewText() {
    }

    /** {@code 1:42}; {@code 0:07}. Negative counts as 0. */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** "1,250" (grouped, no decimals). */
    public static String coins(long coins) {
        return String.format(Locale.ROOT, "%,d", coins);
    }

    /**
     * The gear mode as the warmup broadcast names it: "own gear", "OWN GEAR, LOSER DROPS ALL" (drop mode in
     * capitals, as the chat offers write it), "kit duel (Knight)", "kit choice".
     */
    public static String gearMode(GearMode mode, String kitName) {
        return switch (mode) {
            case OWN_GEAR_KEEP -> "own gear";
            case OWN_GEAR_DROP -> "OWN GEAR, LOSER DROPS ALL";
            case FIXED_KIT -> kitName == null || kitName.isBlank() ? "kit duel" : "kit duel (" + kitName + ")";
            case KIT_CHOICE -> "kit choice";
        };
    }

    /** "700 coins + 2 items", "700 coins", "1 item", or "no stakes". */
    public static String stakes(long coins, int items) {
        StringBuilder out = new StringBuilder();
        if (coins > 0) {
            out.append(coins(coins)).append(coins == 1 ? " coin" : " coins");
        }
        if (items > 0) {
            if (!out.isEmpty()) out.append(" + ");
            out.append(items).append(items == 1 ? " item" : " items");
        }
        return out.isEmpty() ? "no stakes" : out.toString();
    }

    /** The phase as players read it. */
    public static String phase(DuelPhase phase, boolean waitingForBattleground) {
        return switch (phase) {
            case LOCKING -> "Locking in";
            case TRAVELLING -> "Travelling";
            case WARMUP -> waitingForBattleground ? "Waiting for a battleground" : "Warmup";
            case BATTLE_COUNTDOWN -> "Countdown";
            case FIGHT -> "Fighting";
            case ENDING -> "Finished";
            case CLOSED -> "Over";
        };
    }

    /** The phase's timer line: "0:23 left", "#2 in line", "no time limit", or empty. */
    public static String timer(DuelMatchSnapshot match) {
        if (match.phase() == DuelPhase.WARMUP && match.waitingForBattleground()) {
            return match.queuePosition() > 0 ? "#" + match.queuePosition() + " in line" : "waiting";
        }
        if (match.phase() == DuelPhase.FIGHT && match.phaseSeconds() == 0) {
            return "no time limit";
        }
        if (match.phaseSeconds() == 0 && match.secondsRemaining() == 0) {
            return "";
        }
        return clock(match.secondsRemaining()) + " left";
    }
}
