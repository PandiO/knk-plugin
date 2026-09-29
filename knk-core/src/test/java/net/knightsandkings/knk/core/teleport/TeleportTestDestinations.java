package net.knightsandkings.knk.core.teleport;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/** Destinations for the teleport tests. */
public final class TeleportTestDestinations {

    private TeleportTestDestinations() {
    }

    public static KnkTeleportDestination open(int id, String name, String type) {
        return new KnkTeleportDestination(id, name, type, "world", 10.5, 64, -3.5, 90f, 0f, 0, null, null, false,
            true, true, true, null, null);
    }

    public static KnkTeleportDestination priced(int id, String name, int gems, boolean canAfford) {
        return new KnkTeleportDestination(id, name, "Town", "world", 0, 64, 0, 0f, 0f, gems, null, null, false,
            canAfford, true, canAfford, canAfford ? null : KnkTeleportDestination.INSUFFICIENT_GEMS,
            canAfford ? null : "You don't have enough gems to teleport to this location!");
    }

    public static KnkTeleportDestination titleLocked(int id, String name, int gems, boolean canAfford) {
        return new KnkTeleportDestination(id, name, "Town", "world", 0, 64, 0, 0f, 0f, gems, "Knight", null, false,
            false, false, canAfford, "TitleTooLow", "Reach title Knight to unlock");
    }
}
