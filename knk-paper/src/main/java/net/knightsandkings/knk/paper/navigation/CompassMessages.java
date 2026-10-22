package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Kind;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import net.knightsandkings.knk.paper.roads.RoadMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Every player-facing string of the navigation compass (KNG-234). Pure: no Bukkit, so the wording is
 * unit-tested, like {@link NavigationMessages}.
 */
public final class CompassMessages {

    private CompassMessages() {
    }

    /** "Compass 2/5: Keep Gate (defend, under attack, 62% held)" - the target a click just selected. */
    public static Component selected(int number, int total, String label, String detail) {
        Component line = Component.text("Compass " + number + "/" + total + ": ", RoadMessages.INFO)
            .append(Component.text(label, NamedTextColor.WHITE));
        if (detail != null && !detail.isBlank()) {
            line = line.append(Component.text(" (" + detail + ")", RoadMessages.INFO));
        }
        return NavigationMessages.prefixed(line);
    }

    /** What a siege target is about: role, attack and progress, e.g. {@code "defend, under attack, 62% held"}. */
    public static String siegeDetail(Target target) {
        List<String> parts = new ArrayList<>();
        switch (target.kind()) {
            case DEFEND -> parts.add("defend");
            case CAPTURE -> parts.add("capture");
            case SPAWN -> parts.add("your spawn");
        }
        if (target.underAttack()) {
            parts.add("under attack");
        }
        if (target.kind() != Kind.SPAWN && target.progressPercent() >= 0 && target.progressPercent() < 100) {
            parts.add(target.progressPercent() + (target.kind() == Kind.DEFEND ? "% held" : "% to go"));
        }
        return String.join(", ", parts);
    }

    public static Component noPermission() {
        return NavigationMessages.bad("You don't have permission to use the navigation compass.");
    }

    public static Component unavailable() {
        return NavigationMessages.disabled();
    }

    /** A siege member holds the compass before the match has started. */
    public static Component waitForMatch() {
        return NavigationMessages.info("The navigation compass works once the siege match has started.");
    }

    public static Component disabledInSiege() {
        return NavigationMessages.info("The navigation compass is switched off during sieges.");
    }

    public static Component disabledHere() {
        return NavigationMessages.info("The navigation compass has no destinations here.");
    }

    public static Component nothingToResume() {
        return NavigationMessages.info("Right-click the compass to pick a destination.");
    }

    /** The siege gave the player the compass at the hub. */
    public static Component given(String itemName) {
        return NavigationMessages.good("You received a " + itemName
            + ". Once the match starts, right-click it to be guided to the objectives; shift-right-click stops the guidance.");
    }

    /** The siege wanted to give the compass but the inventory was full. */
    public static Component noRoom(String itemName) {
        return NavigationMessages.warn("Your inventory is full, so you did not get the " + itemName
            + ". Make room and ask a staff member, or use /navigate.");
    }
}
