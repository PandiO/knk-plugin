package net.knightsandkings.knk.paper.statistics;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * The game context of a player's statistics right now (DESIGN.md §F.1): {@code siege} while they are
 * a member of a running Siege match, otherwise {@code open_world}. The Siege service is created after
 * statistics start (and is null when Siege is disabled), so it is looked up on every call.
 */
public final class StatisticsContextResolver {

    private final Predicate<UUID> inRunningMatch;

    public StatisticsContextResolver(Predicate<UUID> inRunningMatch) {
        this.inRunningMatch = inRunningMatch == null ? uuid -> false : inRunningMatch;
    }

    /** Siege membership read from {@code siegeService.runningMatchOf} - null-safe when Siege is off. */
    public static StatisticsContextResolver siege(Supplier<SiegeService> siegeService) {
        return new StatisticsContextResolver(uuid -> {
            SiegeService service = siegeService.get();
            return service != null && service.runningMatchOf(uuid).isPresent();
        });
    }

    public StatisticsContext contextOf(UUID playerId) {
        return playerId != null && inRunningMatch.test(playerId) ? StatisticsContext.SIEGE : StatisticsContext.OPEN_WORLD;
    }

    public StatisticsContext contextOf(Player player) {
        return player == null ? StatisticsContext.OPEN_WORLD : contextOf(player.getUniqueId());
    }
}
