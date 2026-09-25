package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipResult;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.VoteTally.DrawMethod;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Player-facing siege texts. Every result enum maps to a sentence that says what happened or why
 * nothing did (N9: no silent no-ops, no NPEs), and the same texts serve commands now and the
 * Phase 8b menus later.
 */
public final class SiegeMessages {
    private SiegeMessages() {}

    public static final NamedTextColor INFO = NamedTextColor.GRAY;
    public static final NamedTextColor GOOD = NamedTextColor.GREEN;
    public static final NamedTextColor BAD = NamedTextColor.RED;
    public static final NamedTextColor HIGHLIGHT = NamedTextColor.GOLD;

    private static final Component PREFIX = Component.text("[Siege] ", NamedTextColor.DARK_RED);

    public static Component prefixed(Component body) {
        return PREFIX.append(body);
    }

    public static Component info(String text) {
        return prefixed(Component.text(text, INFO));
    }

    public static Component good(String text) {
        return prefixed(Component.text(text, GOOD));
    }

    public static Component bad(String text) {
        return prefixed(Component.text(text, BAD));
    }

    /** A clickable {@code /command} in gold, e.g. the join hint. */
    public static Component command(String command) {
        return Component.text(command, HIGHLIGHT)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to run " + command, INFO)));
    }

    /** "5 minutes", "1 minute 30 seconds", "45 seconds". */
    public static String duration(int seconds) {
        int s = Math.max(0, seconds);
        int minutes = s / 60;
        int rest = s % 60;
        if (minutes == 0) return rest + (rest == 1 ? " second" : " seconds");
        String m = minutes + (minutes == 1 ? " minute" : " minutes");
        return rest == 0 ? m : m + " " + rest + (rest == 1 ? " second" : " seconds");
    }

    /** "04:05" for scoreboards. */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    public static String phaseLabel(SiegePhase phase) {
        return switch (phase) {
            case DISABLED -> "Disabled";
            case MATCHMAKING -> "Matchmaking";
            case HUB -> "Starting (hub)";
            case IN_PROGRESS -> "In progress";
            case ENDING -> "Ending";
            case COOLDOWN -> "Cooldown";
        };
    }

    public static Component voteResult(VoteResult result, String choiceLabel) {
        return switch (result) {
            case CAST -> good("You voted for " + choiceLabel + ".");
            case CHANGED -> good("You changed your vote to " + choiceLabel + ".");
            case REMOVED -> info("You withdrew your vote for " + choiceLabel + ".");
            case NOT_A_CANDIDATE -> bad(choiceLabel + " isn't one of this round's scenarios. Use /siege vote to see them.");
            case RANDOM_NOT_ALLOWED -> bad("This lobby doesn't allow a Random vote.");
            case VOTING_CLOSED -> bad("Voting is closed for this round.");
        };
    }

    public static Component skipResult(SkipResult result, String lobbyName, SiegePhase phase, boolean admin) {
        return switch (result) {
            case COOLDOWN_SKIPPED -> good("Skipped the cooldown of " + lobbyName + ": matchmaking has started.");
            case MATCHMAKING_SHORTENED -> good("Shortened matchmaking of " + lobbyName + ": voting closes in 1 second.");
            case TOO_LATE -> bad("Too late to skip in " + lobbyName + ": voting has already closed.");
            case NOT_SKIPPABLE -> bad(admin
                    ? "Nothing to skip in " + lobbyName + " while it is " + phaseLabel(phase).toLowerCase() + "."
                    : "You can only skip a cooldown; " + lobbyName + " is " + phaseLabel(phase).toLowerCase() + ".");
        };
    }

    public static String cancelReason(CancelReason reason, int playersMin) {
        return switch (reason) {
            case NOT_ENOUGH_PLAYERS -> "not enough players" + (playersMin > 0 ? " (at least " + playersMin + " needed)" : "");
            case NO_SCENARIO_AVAILABLE -> "no scenario is available right now";
            case ADMIN_STOPPED -> "an admin stopped the lobby";
            case SERVER_RESTART -> "the server is restarting";
        };
    }

    public static String drawMethod(DrawMethod method) {
        return switch (method) {
            case MOST_VOTES -> "most votes";
            case TIE_BROKEN_AT_RANDOM -> "tie broken at random";
            case RANDOM_VOTE -> "Random won the vote";
            case NO_VOTES -> "nobody voted, picked at random";
            case CANDIDATES_UNAVAILABLE -> "the voted scenarios became unavailable";
        };
    }

    public static String endReason(SiegeEndReason reason) {
        return switch (reason) {
            case INSTANT_VICTORY -> "main objective captured";
            case TIME_EXPIRED -> "time ran out";
            case TEAM_ELIMINATED -> "only one side has players left";
            case NOT_ENOUGH_PLAYERS -> "too few players left";
            case ADMIN_STOPPED -> "stopped by an admin";
            case SERVER_RESTART -> "server restart";
        };
    }

    public static String decision(WinResolver.Decision decision) {
        return switch (decision) {
            case INSTANT_VICTORY_CAPTURE -> "captured the main objective";
            case INSTANT_VICTORY_HOLDER -> "held the main objective";
            case MOST_OBJECTIVES -> "held the most objectives";
            case DEFENDER_ALLIANCE -> "defenders win the tie";
            case LAST_ALLIANCE_STANDING -> "last side standing";
            case DRAW -> "draw";
            case ABORTED -> "aborted";
        };
    }
}
