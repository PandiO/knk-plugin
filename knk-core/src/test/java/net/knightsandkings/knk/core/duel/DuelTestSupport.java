package net.knightsandkings.knk.core.duel;

import java.util.List;
import java.util.Set;

/** Shared fixtures for the core/duel tests. */
final class DuelTestSupport {
    private DuelTestSupport() {}

    static final int KIT_A = 11;
    static final int KIT_B = 12;

    static DuelArenaSettings settings(int maxOffers) {
        return withModes(maxOffers, Set.of(GearMode.values()), GearMode.OWN_GEAR_KEEP);
    }

    static DuelArenaSettings withModes(int maxOffers, Set<GearMode> modes, GearMode defaultMode) {
        return new DuelArenaSettings(7, maxOffers, 60, true, true, 10_000, 9, 50, 5,
                modes, defaultMode, List.of(KIT_A, KIT_B), 30, 10, 300, 180, 5, 3);
    }

    static StakeItem item(String ref, String material) {
        return new StakeItem(ref, material, 1);
    }

    static StakeContract coins(long challenger, long opponent) {
        return new StakeContract(GearMode.OWN_GEAR_KEEP, null, Stake.coins(challenger), Stake.coins(opponent));
    }

    static StakeContract friendly() {
        return new StakeContract(GearMode.OWN_GEAR_KEEP, null, Stake.NONE, Stake.NONE);
    }

    static <T extends DuelEffect> T only(List<DuelEffect> effects, Class<T> type) {
        List<T> matches = effects.stream().filter(type::isInstance).map(type::cast).toList();
        if (matches.size() != 1) throw new AssertionError("expected one " + type.getSimpleName() + " in " + effects);
        return matches.getFirst();
    }

    static boolean has(List<DuelEffect> effects, Class<? extends DuelEffect> type) {
        return effects.stream().anyMatch(type::isInstance);
    }
}
