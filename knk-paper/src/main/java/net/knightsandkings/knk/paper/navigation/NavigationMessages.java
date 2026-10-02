package net.knightsandkings.knk.paper.navigation;

import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.roads.route.BlockedExplainer;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.knightsandkings.knk.paper.roads.RoadMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Every player-facing string of {@code /navigate} (DESIGN §6; road navigation plan §2 R26: the
 * {@link RoadMessages} / {@code SiegeMessages} conventions - a feature-local message class with the
 * same prefix, colours and clickable-command pattern). Pure: no Bukkit, so the wording is unit-tested.
 */
public final class NavigationMessages {

    public static final String USAGE = "/navigate <destination> [spawn|region] | /navigate stop | /navigate";
    public static final int MAX_CHOICES = 8;

    private NavigationMessages() {
    }

    // ==================== plain wrappers ====================

    public static Component prefixed(Component body) {
        return RoadMessages.prefixed(body);
    }

    public static Component info(String text) {
        return RoadMessages.info(text);
    }

    public static Component good(String text) {
        return RoadMessages.good(text);
    }

    public static Component bad(String text) {
        return RoadMessages.bad(text);
    }

    public static Component warn(String text) {
        return RoadMessages.warn(text);
    }

    public static Component usage() {
        return RoadMessages.usage(USAGE);
    }

    // ==================== command ====================

    public static Component noPermission() {
        return bad("You don't have permission to use /navigate.");
    }

    public static Component playersOnly() {
        return bad("Only players can navigate.");
    }

    public static Component disabled() {
        return bad("Navigation is not available on this server.");
    }

    public static Component loading() {
        return warn("Your account is still loading - try again in a moment.");
    }

    public static Component frozen() {
        return bad("You can't navigate while frozen.");
    }

    public static Component inSiege() {
        return bad("You can't navigate while you are in a siege.");
    }

    public static Component noNetworkHere(String world) {
        return warn("No road network is loaded for " + world + " yet.");
    }

    public static Component unknownDestination(String input) {
        return bad("No place called \"" + input + "\" was found.");
    }

    /** "Did you mean: Kardenna, Kardenna Mill?" - the suggestions are clickable. */
    public static Component didYouMean(List<String> suggestions) {
        Component line = Component.text("Did you mean: ", RoadMessages.INFO);
        for (int i = 0; i < suggestions.size() && i < MAX_CHOICES; i++) {
            if (i > 0) {
                line = line.append(Component.text(", ", RoadMessages.INFO));
            }
            line = line.append(RoadMessages.suggest(suggestions.get(i), "/navigate " + suggestions.get(i)));
        }
        return prefixed(line);
    }

    /** Several places share the name: one clickable {@code type:name} choice per line. */
    public static Component ambiguous(String input, List<String> qualifiedNames) {
        Component text = Component.text("Several places are called \"" + input + "\" - pick one: ", RoadMessages.INFO);
        for (int i = 0; i < qualifiedNames.size() && i < MAX_CHOICES; i++) {
            if (i > 0) {
                text = text.append(Component.text(", ", RoadMessages.INFO));
            }
            text = text.append(RoadMessages.command(qualifiedNames.get(i), "/navigate " + qualifiedNames.get(i)));
        }
        return prefixed(text);
    }

    public static Component noLocation(String name) {
        return bad(name + " has no location and no region to navigate to.");
    }

    public static Component otherWorld(String name) {
        return bad(name + " is in another world.");
    }

    public static Component notNavigating() {
        return info("You are not navigating anywhere. " + USAGE);
    }

    public static Component stopped() {
        return info("Navigation stopped.");
    }

    /** {@code /navigate} with an active session: where to and how far. */
    public static Component status(String destination, double remainingBlocks, EtaEstimator eta) {
        return prefixed(Component.text("Navigating to ", RoadMessages.INFO)
            .append(Component.text(destination, RoadMessages.HIGHLIGHT))
            .append(Component.text(" - " + eta.describe(remainingBlocks) + " to go. ", RoadMessages.INFO))
            .append(RoadMessages.command("[Stop]", "/navigate stop")));
    }

    // ==================== refusals (DESIGN §6.2) ====================

    public static Component playerTooFar(double maxSnapDistance) {
        return bad("You're too far from a road - get within " + (int) maxSnapDistance + " blocks of one.");
    }

    public static Component destinationTooFar(String name) {
        return bad(name + " is too far from any road.");
    }

    public static Component noRoadConnects(String name) {
        return bad("No road connects you to " + name + ".");
    }

    public static Component noRoute(String name) {
        return bad("No route to " + name + " was found.");
    }

    public static Component alreadyThere(String name) {
        return info("You are already in " + name + ".");
    }

    // ==================== guidance ====================

    public static Component started(String name, double lengthBlocks, EtaEstimator eta) {
        return prefixed(Component.text("Navigating to ", RoadMessages.INFO)
            .append(Component.text(name, RoadMessages.HIGHLIGHT))
            .append(Component.text(" - " + eta.describe(lengthBlocks) + ". Follow the trail; ", RoadMessages.INFO))
            .append(RoadMessages.command("[Stop]", "/navigate stop")));
    }

    /** Direct mode: the target is within the snap distance, no road is used. */
    public static Component startedDirect(String name, double distance) {
        return prefixed(Component.text(name, RoadMessages.HIGHLIGHT)
            .append(Component.text(" is " + RoadMessages.distance(distance) + " away - follow the trail.", RoadMessages.INFO)));
    }

    /**
     * A partial route (DESIGN §6.7): "No open route to Cinix Keep - the West Gate is closed. Guiding
     * you to the gate." / "You may not enter Kardenna Castle. Guiding you to its edge."
     */
    public static Component partialRoute(String name, BlockedExplainer.Explanation explanation) {
        if (explanation.isDomainBlock()) {
            return warn(capitalize(explanation.reason()) + ". Guiding you to its edge.");
        }
        return warn("No open route to " + name + " - " + explanation.reason() + ". Guiding you to the gate.");
    }

    /** A pass-through gate on the route: "Hint: right-click the West Gate to pass." */
    public static Component passThroughHint(String hint) {
        return info("Hint: " + hint + ".");
    }

    /** "The West Gate closed - recalculating." */
    public static Component elementBlocked(EdgeVerdict verdict) {
        String what = verdict.cause() != null && verdict.cause().name() != null
            ? verdict.cause().name() : "the way";
        String why = verdict.message() == null ? "is blocked" : verdict.message();
        // the verdict text repeats the name ("the West Gate is closed"); keep it as one clause
        return warn(capitalize(why.startsWith(what) ? why : what + " " + why) + " - recalculating.");
    }

    public static Component offRoute() {
        return info("You left the road - recalculating.");
    }

    /** Direct mode's drift signal (no road to leave): "You're heading away from the Well - recalculating." */
    public static Component directRecalculating(String name) {
        return info("You're heading away from " + name + " - recalculating.");
    }

    /** "The North Gate opened - shorter route found." */
    public static Component shorterRoute() {
        return good("A shorter route opened - following it now.");
    }

    public static Component arrived(String name) {
        return good("You have arrived at " + name + ".");
    }

    public static Component ended(EndReason reason, String name) {
        return switch (reason) {
            case STOPPED -> stopped();
            case TIMEOUT -> info("Navigation to " + name + " ended - the session timed out.");
            case QUIT, DEATH, WORLD_CHANGE, TELEPORT -> info("Navigation to " + name + " ended.");
            case SIEGE -> info("Navigation to " + name + " ended - you joined a siege.");
            case DESTINATION_LOST -> warn("Navigation to " + name + " ended - the road network changed.");
            case NO_ROUTE -> noRoute(name);
            case DIFFERENT_COMPONENTS -> noRoadConnects(name);
            case ALREADY_THERE -> alreadyThere(name);
        };
    }

    /** The next instruction, announced when it comes within 20 blocks: "In 12 m: Turn left onto Merchantstreet". */
    public static Component maneuver(double metersToNext, String text) {
        return info("In " + RoadMessages.distance(metersToNext) + ": " + text);
    }

    /** The boss bar text (DESIGN §6.4): "→ Merchantstreet · 340 m · ~1 min". */
    public static String bossBar(String nextOrDestination, double remainingBlocks, EtaEstimator eta) {
        return "→ " + nextOrDestination + " · " + EtaEstimator.formatDistance(remainingBlocks)
            + " · " + EtaEstimator.formatSeconds(eta.seconds(remainingBlocks));
    }

    // ==================== /knk road why ====================

    public static Component whyHeader(String player, String destination) {
        return prefixed(Component.text("Route for ", RoadMessages.INFO)
            .append(Component.text(player, RoadMessages.HIGHLIGHT))
            .append(Component.text(" to ", RoadMessages.INFO))
            .append(Component.text(destination, RoadMessages.HIGHLIGHT))
            .append(Component.text(":", RoadMessages.INFO)));
    }

    public static Component whyVerdict(int edgeId, EdgeVerdict verdict) {
        NamedTextColor colour = verdict.isBlocked() ? RoadMessages.BAD : verdict.isPassThrough() ? RoadMessages.WARN : RoadMessages.GOOD;
        String kind = verdict.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
        Component line = Component.text("  edge #" + edgeId + " ", RoadMessages.INFO)
            .append(Component.text(kind, colour));
        if (verdict.message() != null && !verdict.message().isBlank()) {
            line = line.append(Component.text(" - " + verdict.message(), RoadMessages.INFO));
        }
        return line;
    }

    public static Component whyResult(String text, boolean ok) {
        return ok ? good(text) : warn(text);
    }

    // ==================== helpers ====================

    /** A clickable command that also shows on hover (RoadMessages.command's pattern, kept here for the tests). */
    public static Component clickable(String label, String command) {
        return Component.text(label, RoadMessages.HIGHLIGHT, TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
