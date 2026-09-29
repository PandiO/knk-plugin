package net.knightsandkings.knk.paper.teleport;

import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * What a {@link TeleportRestriction} gets to judge: who moves, from where to where, why, and a
 * permission lookup for the restriction's bypass nodes.
 * <p>
 * Bypasses are resolved against the teleport's <em>authority</em>: the staff member for a
 * {@link TeleportKind#STAFF} teleport (so a staff member holding {@code knk.region.bypass} can put a
 * player into a closed domain, e.g. a jail), the subject for every player teleport; the console
 * holds every node. Nodes a restriction declares in {@link TeleportRestriction#bypassNodes()} are
 * resolved fresh (async) before the check runs; any other node answers false.
 *
 * @param visited the player whose location is the destination, or null
 */
public record TeleportCheck(
    Player subject,
    Location from,
    Location to,
    TeleportKind kind,
    CommandSender actor,
    Player visited,
    Predicate<String> authority
) {
    public boolean hasBypass(String node) {
        return authority.test(node);
    }
}
