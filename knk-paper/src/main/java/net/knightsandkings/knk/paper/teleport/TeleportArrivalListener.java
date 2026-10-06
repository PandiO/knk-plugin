package net.knightsandkings.knk.paper.teleport;

import org.bukkit.Location;

/**
 * Told about every teleport the engine carried out (Linear KNG-42: {@link BackService} records where
 * the player stood before it, for {@code /back}). Registered through
 * {@link TeleportService#addArrivalListener}. Called on the main thread once {@code teleportAsync}
 * reported the arrival; must be quick. A throwing listener is logged and never changes the outcome.
 */
@FunctionalInterface
public interface TeleportArrivalListener {

    /**
     * @param plan the teleport that happened
     * @param from where the moved player stood just before it
     * @param to   where they were sent (after the safe-spot search)
     */
    void arrived(TeleportPlan plan, Location from, Location to);
}
