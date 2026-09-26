package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * One teleport handed to {@link TeleportService} (docs/specs/teleport/DESIGN.md §3.4). Every
 * command and menu click builds one of these; nothing calls {@code Player.teleport} directly.
 *
 * @param subject          the player who moves
 * @param destination      where to, read when the teleport is checked and again when it happens
 *                         (so a player destination is their live location); null = no longer available
 * @param kind             what started it - decides warmup, cooldown, combat tag and safety check
 * @param actor            who started it (the subject for player teleports; staff member or console)
 * @param visited          the player whose location is the destination, when there is one
 * @param silent           no message to the moved/visited player
 * @param destinationLabel for log lines ("Bob", "10, 64, -3 in world")
 */
public record TeleportPlan(
    Player subject,
    Supplier<Location> destination,
    TeleportKind kind,
    CommandSender actor,
    Player visited,
    boolean silent,
    String destinationLabel
) {
    public TeleportPlan {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        actor = actor != null ? actor : subject;
        destinationLabel = destinationLabel != null ? destinationLabel : "?";
    }

    /** A staff teleport of {@code subject} to {@code visited}'s live location. */
    public static TeleportPlan staffToPlayer(CommandSender actor, Player subject, Player visited, boolean silent) {
        return new TeleportPlan(subject, () -> visited.isOnline() ? visited.getLocation() : null,
            TeleportKind.STAFF, actor, visited, silent, visited.getName());
    }

    /** A staff teleport of {@code subject} to a fixed location. */
    public static TeleportPlan staffToLocation(CommandSender actor, Player subject, Location destination, String label) {
        Location target = destination.clone();
        return new TeleportPlan(subject, target::clone, TeleportKind.STAFF, actor, null, false, label);
    }

    public boolean movesActor() {
        return actor instanceof Player player && player.getUniqueId().equals(subject.getUniqueId());
    }
}
