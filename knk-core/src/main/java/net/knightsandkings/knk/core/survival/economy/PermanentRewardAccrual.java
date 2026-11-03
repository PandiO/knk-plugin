package net.knightsandkings.knk.core.survival.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Permanent per-kill rewards accumulated during the match and settled once at its end (S32, S62, §9): exact
 * values per player, rounded down once at settlement so 3.3 XP per kill is not lost to per-kill rounding. Ghosts
 * still in the match qualify; leavers forfeit; aborts settle nothing.
 */
public final class PermanentRewardAccrual {

    /** A player's settled amounts (whole coins and XP). */
    public record Settlement(long coins, long xp) { }

    private final Map<UUID, BigDecimal> coins = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> xp = new LinkedHashMap<>();
    private int earnedKills;

    public void add(UUID player, KillReward reward) {
        if (reward.isNone()) return;
        coins.merge(player, reward.coins(), BigDecimal::add);
        xp.merge(player, reward.xp(), BigDecimal::add);
        if (reward.coins().signum() > 0 || reward.xp().signum() > 0) earnedKills++;
    }

    public BigDecimal coins(UUID player) {
        return coins.getOrDefault(player, BigDecimal.ZERO);
    }

    public BigDecimal xp(UUID player) {
        return xp.getOrDefault(player, BigDecimal.ZERO);
    }

    public int earnedKills() {
        return earnedKills;
    }

    /** The player left the match: their accrual is forfeited (S62). */
    public void forfeit(UUID player) {
        coins.remove(player);
        xp.remove(player);
    }

    /**
     * Settles once for the players still in the match (ghosts included), rounding down once per player.
     *
     * @param playersInMatch the members at match end; anyone else is forfeited
     */
    public Map<UUID, Settlement> settle(Collection<UUID> playersInMatch) {
        Set<UUID> members = Set.copyOf(playersInMatch);
        Map<UUID, Settlement> result = new LinkedHashMap<>();
        for (UUID player : members) {
            long c = coins(player).setScale(0, RoundingMode.FLOOR).longValueExact();
            long x = xp(player).setScale(0, RoundingMode.FLOOR).longValueExact();
            if (c > 0 || x > 0) result.put(player, new Settlement(c, x));
        }
        return Map.copyOf(result);
    }
}
