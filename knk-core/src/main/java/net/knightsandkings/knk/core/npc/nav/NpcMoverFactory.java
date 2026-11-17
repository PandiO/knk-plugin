package net.knightsandkings.knk.core.npc.nav;

import java.util.Optional;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/** Creates movers on the running engine; absent when the engine adapter is unavailable (no Citizens). */
public interface NpcMoverFactory {

    /** Why NPC movement is unavailable, or empty when it works. */
    Optional<String> unavailableReason();

    /**
     * Spawn a mover at {@code at} with the given per-NPC settings (resolved for that NPC, see
     * {@link NavigationSettings}).
     */
    NpcMover spawn(String name, NavPoint at, MoverOptions options);

    /**
     * Per-NPC engine options, resolved from the layered settings. {@code dry}: the engine's own path search (without a
     * {@link LocalLeg}) never routes through or over water or lava; for the test walkers (PandiO/knk-workspace#490),
     * whose paths otherwise crossed ponds.
     */
    record MoverOptions(String pathfinder, int stationaryTicks, boolean collidable, boolean dry) {
        public MoverOptions(String pathfinder, int stationaryTicks, boolean collidable) {
            this(pathfinder, stationaryTicks, collidable, false);
        }
    }
}
