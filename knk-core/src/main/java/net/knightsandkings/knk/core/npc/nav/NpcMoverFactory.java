package net.knightsandkings.knk.core.npc.nav;

import java.util.Optional;

/** Creates movers on the running engine; absent when the engine adapter is unavailable (no Citizens). */
public interface NpcMoverFactory {

    /** Why NPC movement is unavailable, or empty when it works. */
    Optional<String> unavailableReason();

    /**
     * Spawn a mover at {@code at} with the given per-NPC settings (resolved for that NPC, see
     * {@link NavigationSettings}).
     */
    NpcMover spawn(String name, NavPoint at, MoverOptions options);

    /** Per-NPC engine options, resolved from the layered settings. */
    record MoverOptions(String pathfinder, int stationaryTicks, boolean collidable) {
    }
}
