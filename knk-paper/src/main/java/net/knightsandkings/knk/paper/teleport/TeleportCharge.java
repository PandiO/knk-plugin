package net.knightsandkings.knk.paper.teleport;

import java.util.concurrent.CompletableFuture;

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

    /** The teleport happened: tell the payer what it cost. Main thread. */
    void completed(Player subject);

    /**
     * The server's answer.
     *
     * @param denial      why not; null when allowed
     * @param destination where to go, from the server (fresher than a cached list); null = keep the plan's
     */
    record Authorization(TeleportDenial denial, Location destination) {
        public static Authorization allowed(Location destination) {
            return new Authorization(null, destination);
        }

        public static Authorization denied(TeleportDenial denial) {
            return new Authorization(denial, null);
        }

        public boolean isAllowed() {
            return denial == null;
        }
    }
}
