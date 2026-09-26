package net.knightsandkings.knk.paper.currency;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Online players as {@code viewer} is allowed to see them: a player hidden by owner/staff mode
 * ({@code ModeService} hides them with {@code Player#hidePlayer}) doesn't exist for someone who
 * can't see them - no tab completion, no "is online" difference in any answer. The console sees
 * everyone.
 * <p>
 * Minimal on purpose: the private-messages branch has its own {@code VisiblePlayers}; unify the
 * two when both have merged (currency Phase 3 handoff note).
 */
public final class VisiblePlayers {

    private final Function<String, Player> playerExact;
    private final Supplier<Collection<? extends Player>> onlinePlayers;

    public VisiblePlayers(Function<String, Player> playerExact, Supplier<Collection<? extends Player>> onlinePlayers) {
        this.playerExact = playerExact;
        this.onlinePlayers = onlinePlayers;
    }

    public static boolean canSee(CommandSender viewer, Player target) {
        return !(viewer instanceof Player player) || player.equals(target) || player.canSee(target);
    }

    /** The online player called {@code name} if {@code viewer} can see them, else null (main thread). */
    public Player find(CommandSender viewer, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player player = playerExact.apply(name);
        return player != null && canSee(viewer, player) ? player : null;
    }

    /** Names of the online players {@code viewer} can see that start with {@code prefix} (any case), without the viewer. */
    public List<String> names(CommandSender viewer, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return onlinePlayers.get().stream()
            .filter(p -> !p.equals(viewer) && canSee(viewer, p))
            .map(Player::getName)
            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(lower))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }
}
