package net.knightsandkings.knk.paper.statistics;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.statistics.StatisticsContext;

/**
 * The game context of a player's statistics right now (DESIGN.md §F.1): the minigame's context ({@code siege},
 * {@code duel}: a minigame's context is its registry id) while they play one of its matches
 * ({@link net.knightsandkings.knk.core.minigame.Participation.Stage#PLAYING}), otherwise {@code open_world}. Membership is read live from the minigame
 * registry (arena duels DESIGN §11), which minigames join when they start.
 */
public final class StatisticsContextResolver {

    private final Function<UUID, Optional<StatisticsContext>> matchContext;

    /** Tests: {@code siege} while {@code inRunningMatch}. */
    public StatisticsContextResolver(Predicate<UUID> inRunningMatch) {
        Predicate<UUID> playing = inRunningMatch == null ? uuid -> false : inRunningMatch;
        this.matchContext = uuid -> playing.test(uuid) ? Optional.of(StatisticsContext.SIEGE) : Optional.empty();
    }

    private StatisticsContextResolver(Function<UUID, Optional<StatisticsContext>> matchContext) {
        this.matchContext = matchContext;
    }

    /** The context of the match the player plays in, from {@link MinigameRegistry#statisticsContextOf}. */
    public static StatisticsContextResolver of(MinigameRegistry minigames) {
        return new StatisticsContextResolver(minigames::statisticsContextOf);
    }

    public StatisticsContext contextOf(UUID playerId) {
        return playerId == null ? StatisticsContext.OPEN_WORLD : matchContext.apply(playerId).orElse(StatisticsContext.OPEN_WORLD);
    }

    public StatisticsContext contextOf(Player player) {
        return player == null ? StatisticsContext.OPEN_WORLD : contextOf(player.getUniqueId());
    }
}
