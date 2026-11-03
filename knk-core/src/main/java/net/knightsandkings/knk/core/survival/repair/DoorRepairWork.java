package net.knightsandkings.knk.core.survival.repair;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The repair-work maths of one gate door (DESIGN §5, S07–S09, S48, S49), scoped per door.
 * <ul>
 *   <li>{@code P(t + dt) = min(target, P + r·n·dt)}; zero contributors hold progress; the ETA is {@code (target − P)/(r·n)}.</li>
 *   <li>Destroyed door: target is the effective maximum health H; completion starts the animated rebuild.</li>
 *   <li>Damaged standing door: target is the missing health {@code H − current}, recomputed live, so damage during
 *       the repair adds to the remaining work; completion sets full health in one step.</li>
 *   <li>A door destroyed during a repair carries P over to the destroyed-door repair; a door at full health cannot
 *       be repaired; a new destruction after a completed cycle starts at zero.</li>
 *   <li>Repair stops only on ownership loss of the gate's objective (S48), which also invalidates the remaining
 *       work; capture pressure never stops it. Taking damage never removes a contributor (that is the adapter's
 *       business: it counts the valid repairers it passes in).</li>
 *   <li>The completion is consumed exactly once: after it, {@link #advance} does nothing until the door is damaged
 *       or destroyed again.</li>
 * </ul>
 */
public final class DoorRepairWork {
    private final String doorId;
    private final double maxHealth;
    private final double ratePerSecondPerPlayer;
    private double currentHealth;
    private boolean destroyed;
    private double progress;
    private boolean defenderOwned = true;
    private boolean completed;

    /**
     * @param doorId                 the door
     * @param maxHealth              H, the door's effective maximum health (> 0)
     * @param ratePerSecondPerPlayer r, health-equivalent work per second per repairer for this difficulty (> 0)
     * @param currentHealth          the door's health now (0 = destroyed)
     */
    public DoorRepairWork(String doorId, double maxHealth, double ratePerSecondPerPlayer, double currentHealth) {
        if (doorId == null || doorId.isBlank()) throw new IllegalArgumentException("doorId is required");
        if (!(maxHealth > 0)) throw new IllegalArgumentException("maxHealth must be > 0");
        if (!(ratePerSecondPerPlayer > 0)) throw new IllegalArgumentException("rate must be > 0");
        this.doorId = doorId;
        this.maxHealth = maxHealth;
        this.ratePerSecondPerPlayer = ratePerSecondPerPlayer;
        doorHealthChanged(currentHealth);
    }

    public static DoorRepairWork destroyed(String doorId, double maxHealth, double rate) {
        return new DoorRepairWork(doorId, maxHealth, rate, 0);
    }

    public String doorId() {
        return doorId;
    }

    public double maxHealth() {
        return maxHealth;
    }

    public double rate() {
        return ratePerSecondPerPlayer;
    }

    public double currentHealth() {
        return currentHealth;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public boolean isDefenderOwned() {
        return defenderOwned;
    }

    /** Accumulated work P. */
    public double progress() {
        return progress;
    }

    /** The work needed: H for a destroyed door, the missing health for a standing one, 0 at full health. */
    public double target() {
        return destroyed ? maxHealth : Math.max(0, maxHealth - currentHealth);
    }

    public double remaining() {
        return Math.max(0, target() - progress);
    }

    /** 0..1 for boss bars. */
    public double fraction() {
        double target = target();
        return target <= 0 ? 1 : Math.min(1, progress / target);
    }

    /** Whether work can be done now: defender-owned, not at full health, no unconsumed completion. */
    public boolean canRepair() {
        return defenderOwned && target() > 0 && !completed;
    }

    /** The door's health changed (damage during the repair adds to the remaining work). 0 means destroyed. */
    public void doorHealthChanged(double health) {
        double clamped = Math.max(0, Math.min(maxHealth, health));
        if (clamped <= 0) {
            doorDestroyed();
            return;
        }
        boolean wasFull = !destroyed && currentHealth >= maxHealth;
        destroyed = false;
        currentHealth = clamped;
        if (wasFull && clamped < maxHealth) {
            // Fresh damage after a completed cycle (or on a fresh door): a new repair cycle from zero.
            progress = 0;
            completed = false;
        }
    }

    /** The door was destroyed: P carries over to the destroyed-door repair (target H); a fresh destruction starts at 0. */
    public void doorDestroyed() {
        if (destroyed) return;
        boolean wasFull = currentHealth >= maxHealth;
        destroyed = true;
        currentHealth = 0;
        if (wasFull || completed) {
            progress = 0;
        }
        completed = false;
    }

    /** The animated rebuild finished (S64): the door is whole again and the next destruction starts at zero. */
    public void rebuildComplete() {
        destroyed = false;
        currentHealth = maxHealth;
        progress = 0;
        completed = false;
    }

    /**
     * Ownership of the gate's objective changed (S48). Losing it stops the repair and invalidates the remaining
     * work; regaining it (recapture) allows a new cycle from zero.
     */
    public void objectiveOwnershipChanged(boolean defenderOwned) {
        if (this.defenderOwned && !defenderOwned) {
            progress = 0;
            completed = false;
        }
        this.defenderOwned = defenderOwned;
    }

    /**
     * Adds {@code r·n·dt} of work.
     *
     * @param contributors n, the valid active repairers (alive, in range, repairing, match members)
     * @param dtSeconds    elapsed seconds
     * @return the completion when P reaches the target in this step, exactly once per cycle
     */
    public Optional<RepairCompletion> advance(int contributors, double dtSeconds) {
        if (contributors < 0 || dtSeconds < 0) throw new IllegalArgumentException("contributors and dt must be >= 0");
        if (!canRepair()) return Optional.empty();
        double target = target();
        progress = Math.min(target, progress + ratePerSecondPerPlayer * contributors * dtSeconds);
        if (progress >= target) {
            completed = true;
            RepairCompletion.Kind kind = destroyed ? RepairCompletion.Kind.REBUILD_DESTROYED_DOOR : RepairCompletion.Kind.RESTORE_DAMAGED_DOOR;
            if (!destroyed) {
                // Full health in one step; the next damage starts a new cycle.
                currentHealth = maxHealth;
                progress = 0;
                completed = false;
            }
            return Optional.of(new RepairCompletion(doorId, kind, maxHealth));
        }
        return Optional.empty();
    }

    /** Remaining seconds with {@code contributors} repairers; empty with none (progress stays put) or nothing to do. */
    public OptionalDouble etaSeconds(int contributors) {
        if (contributors <= 0 || !canRepair()) return OptionalDouble.empty();
        return OptionalDouble.of(remaining() / (ratePerSecondPerPlayer * contributors));
    }

    /** Whether a destroyed-door completion is waiting for the rebuild to finish. */
    public boolean awaitingRebuild() {
        return completed && destroyed;
    }
}
