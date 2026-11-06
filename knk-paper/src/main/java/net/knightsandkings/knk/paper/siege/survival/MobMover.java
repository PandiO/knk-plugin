package net.knightsandkings.knk.paper.siege.survival;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives one mob to a destination with its own pathfinder (Paper {@code Mob#getPathfinder()}; checks report §8: the
 * v1 mover follows waypoints one {@code moveTo} at a time), with the optional lane override of NPC platform §6: the
 * zone's ordered lane points are visited first, then the destination. A lane point that can't be reached is skipped
 * when the zone's {@code laneAutoReroute} is on (the opt-in automatic fallback), otherwise the mover reports
 * {@link #blocked()} and the behaviour decides. {@code moveTo} returning no path {@code blockedRetries} times in a row
 * counts as blocked (§6 bounded retries). Main thread.
 */
public final class MobMover {

    private final Mob mob;
    private final SurvivalSettings settings;
    private final List<Location> lane;
    private final boolean laneAutoReroute;

    private String destinationId;
    private Location destination;
    private int laneIndex;
    private int sinceRepath;
    private int failures;
    private boolean blocked;

    public MobMover(Mob mob, SurvivalSettings settings, List<Location> lane, boolean laneAutoReroute) {
        this.mob = Objects.requireNonNull(mob, "mob");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lane = lane == null ? List.of() : List.copyOf(lane);
        this.laneAutoReroute = laneAutoReroute;
    }

    /** Sets (or keeps) the destination; a new destination id restarts the lane and the retry budget. */
    public void moveTo(String id, Location target) {
        if (destinationId == null || !destinationId.equals(id)) {
            destinationId = id;
            laneIndex = 0;
            failures = 0;
            blocked = false;
            sinceRepath = settings.repathTicks();
        }
        destination = target;
    }

    /** Stops moving (arrived, attacking through the combat goals, idle). */
    public void stop() {
        destinationId = null;
        destination = null;
        blocked = false;
        failures = 0;
        mob.getPathfinder().stopPathfinding();
    }

    public Optional<String> destinationId() {
        return Optional.ofNullable(destinationId);
    }

    /** True once the destination (or a binding lane point) failed more than the retry budget; cleared by a new destination. */
    public boolean blocked() {
        return blocked;
    }

    /** Clears the blocked flag after the behaviour consumed it. */
    public void acknowledgeBlocked() {
        blocked = false;
        failures = 0;
    }

    /** Called every tick while the mob should be walking. */
    public void tick() {
        if (destination == null || !mob.isValid()) return;
        Location next = currentWaypoint();
        if (next == null) return;
        World world = mob.getWorld();
        if (next.getWorld() != null && next.getWorld() != world) return;
        if (laneIndex < lane.size() && mob.getLocation().distanceSquared(next) <= settings.laneArriveDistance() * settings.laneArriveDistance()) {
            laneIndex++;
            sinceRepath = settings.repathTicks();
            next = currentWaypoint();
            if (next == null) return;
        }
        if (++sinceRepath < settings.repathTicks()) return;
        sinceRepath = 0;
        Pathfinder pathfinder = mob.getPathfinder();
        boolean ok = pathfinder.moveTo(next, 1.0);
        Pathfinder.PathResult path = ok ? pathfinder.getCurrentPath() : null;
        if (!ok || path == null || !path.canReachFinalPoint()) {
            onFailure();
        } else {
            failures = 0;
        }
    }

    private Location currentWaypoint() {
        if (laneIndex < lane.size()) return lane.get(laneIndex);
        return destination;
    }

    private void onFailure() {
        failures++;
        if (failures <= settings.blockedRetries()) return;
        if (laneIndex < lane.size()) {
            if (laneAutoReroute) {
                // Opt-in automatic fallback (NPC platform §6): leave the override, head for the destination itself.
                laneIndex = lane.size();
                failures = 0;
                return;
            }
        }
        blocked = true;
    }

    /** Whether the mob stands within {@code distance} of the destination (horizontal distance; the stand point is at floor level). */
    public boolean arrived(double distance) {
        if (destination == null) return false;
        Location me = mob.getLocation();
        if (destination.getWorld() != null && destination.getWorld() != me.getWorld()) return false;
        double dx = me.getX() - destination.getX();
        double dz = me.getZ() - destination.getZ();
        double dy = Math.abs(me.getY() - destination.getY());
        return dx * dx + dz * dz <= distance * distance && dy <= 3.0;
    }

    public int laneIndex() {
        return laneIndex;
    }

    public int failures() {
        return failures;
    }
}
