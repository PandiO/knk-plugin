package net.knightsandkings.knk.paper.teleport;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * The paid (or server-authorized) part of a teleport (docs/specs/teleport/DESIGN.md §3.4 step 3,
 * §3.7.3). {@link TeleportService} calls {@link #authorize} after the warmup and the guards, just
 * before it looks for a safe spot - never earlier, so a warmup that gets cancelled costs nothing.
 * If the teleport then doesn't happen for any reason, the engine calls {@link #refund}; when it
 * does, {@link #completed}.
 */
public interface TeleportCharge {

    /**
     * Ask the server to allow (and charge) the teleport. Completes on any thread and never
     * exceptionally - a failure to reach the server is a denial.
     */
    CompletableFuture<Authorization> authorize();

    /** The authorized teleport didn't happen: give back what was charged (no-op when nothing was). Any thread. */
    void refund(String why);

    /**
     * The plugin is shutting down while this charge may be in flight: make sure the attempt ends up
     * not charged - refund what was charged, void the key when a charge may still be on its way, and
     * never send a charge after this. Any thread; the future completes (never exceptionally) once
     * the server answered or the refund was given up on. By default the same as {@link #refund}.
     */
    default CompletableFuture<Void> abandon(String why) {
        refund(why);
        return CompletableFuture.completedFuture(null);
    }

    /** The teleport happened: tell the payer what it cost. Main thread. */
    void completed(Player subject);

    /**
     * The server's answer.
     *
     * @param denial      why not; null when allowed
     * @param destinationSource where to go, from the server (fresher than a cached list); answers null
     *                    to keep the plan's. Read through {@link #destination()} on the main thread only
     *                    (it may look up a Bukkit world), never where the answer arrives.
     */
    record Authorization(TeleportDenial denial, Supplier<Location> destinationSource) {
        public static Authorization allowed(Location destination) {
            return new Authorization(null, () -> destination);
        }

        public static Authorization allowed(Supplier<Location> destination) {
            return new Authorization(null, destination);
        }

        public static Authorization denied(TeleportDenial denial) {
            return new Authorization(denial, () -> null);
        }

        public boolean isAllowed() {
            return denial == null;
        }

        /** The server's destination, or null to keep the plan's. Main thread. */
        public Location destination() {
            return destinationSource != null ? destinationSource.get() : null;
        }
    }
}
