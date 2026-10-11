package net.knightsandkings.knk.paper.analytics;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;

/**
 * Heatmap sampling (KNG-34 link 7, DESIGN.md D10, IMPLEMENTATION_PLAN.md §7 "≤ 1 sample / player /
 * 10 s"): a main-thread timer every {@code world-analytics.movement-sample-seconds} takes one block
 * position per online player and adds it to the window's cell counts. Never a {@code PlayerMoveEvent}
 * listener. Skipped: AFK players (they would paint their AFK spot), spectators, the configured
 * excluded game modes and dead players. Only the cell count is kept - no player id, no trail.
 */
public final class MovementSampler implements Runnable {

    private final WorldAnalyticsWindow window;
    private final Supplier<Collection<? extends Player>> online;
    private final Predicate<UUID> afk;
    private final Set<GameMode> excludedGameModes;

    public MovementSampler(WorldAnalyticsWindow window, Supplier<Collection<? extends Player>> online, Predicate<UUID> afk,
                           Set<GameMode> excludedGameModes) {
        this.window = window;
        this.online = online;
        this.afk = afk == null ? id -> false : afk;
        this.excludedGameModes = excludedGameModes == null ? Set.of() : excludedGameModes;
    }

    @Override
    public void run() {
        for (Player player : online.get()) {
            if (!eligible(player)) {
                continue;
            }
            Location at = player.getLocation();
            World world = at == null ? null : at.getWorld();
            if (world == null) {
                continue;
            }
            window.sample(world.getName(), at.getBlockX(), at.getBlockZ());
        }
    }

    boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || (mode != null && excludedGameModes.contains(mode))) {
            return false;
        }
        if (player.isDead()) {
            return false;
        }
        return !afk.test(player.getUniqueId());
    }
}
