package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.GearMode;

import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** P1's arena (the gateway DTO) as P3's {@link DuelArenaSettings} (P3 report: "P4 maps it"). */
public final class DuelArenas {

    private DuelArenas() {
    }

    /** The arena's duel settings; empty when they don't pass P3's checks (the API validates the same rules). */
    public static Optional<DuelArenaSettings> settingsOf(KnkArena arena) {
        Set<GearMode> modes = EnumSet.noneOf(GearMode.class);
        for (KnkArena.DuelGearMode mode : KnkArena.DuelGearMode.values()) {
            if (arena.allows(mode)) modes.add(gearMode(mode));
        }
        List<Integer> kits = arena.eligibleKits().stream()
                .sorted(Comparator.comparingInt(KnkArenaKit::sequenceNumber))
                .map(KnkArenaKit::kitId)
                .toList();
        try {
            return Optional.of(new DuelArenaSettings(arena.id(), arena.maxOffers(), arena.offerSeconds(),
                    arena.allowCoinStakes(), arena.allowItemStakes(), arena.maxCoinStake(), arena.maxItemStacks(),
                    arena.minStakeRatioPercent(), arena.feePercent(), modes, gearMode(arena.defaultGearMode()), kits,
                    arena.warmupSeconds(), arena.countdownSeconds(), arena.fightTimeLimitSeconds(),
                    arena.maxWaitSeconds(), arena.winnerLingerSeconds(), arena.maxQueuedMatches()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static GearMode gearMode(KnkArena.DuelGearMode mode) {
        return GearMode.valueOf(mode.name());
    }
}
