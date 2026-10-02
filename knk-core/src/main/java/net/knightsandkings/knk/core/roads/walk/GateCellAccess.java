package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.GateAvailability;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Gate doors at cell level (KNG-51 {@code LAST_MILE_PATHFINDING.md} §6 "Gates"): a cell whose floor,
 * feet or head block lies in a gate door's closed footprint ({@link GateCells#doorAt}, the same blocks
 * {@link WalkGrid#gateDoor} tags) takes that door's verdict from the road router's
 * {@link GateAvailability#decide} — open / destroyed / pass-through / siege-carried → {@code 0},
 * opening / closing / jammed / siege-locked / closed → {@link CellAccess#BLOCKED}. A door that the
 * availability does not know is open, as on the road (Phase 2d decision).
 *
 * <p>Thread-safety: the verdicts are decided up front ({@link #decideAll}, main thread, every door
 * whose footprint lies in the search box) and stored in an immutable map; {@link #extraCost} only
 * reads it and the immutable {@link GateCells}. A door id missing from the map is decided as open.
 */
public final class GateCellAccess implements CellAccess {

    private final GateCells gates;
    private final int headroom;
    private final Map<Integer, String> blockedReasons;

    /**
     * @param gates          the world's gate-door footprints
     * @param headroom       the mover's headroom ({@link MovementProfile#headroom}); floor..floor+headroom is checked
     * @param blockedReasons door id → why it blocks, for the doors that block (any other door is open)
     */
    public GateCellAccess(GateCells gates, int headroom, Map<Integer, String> blockedReasons) {
        this.gates = Objects.requireNonNull(gates, "gates");
        if (headroom < 1) {
            throw new IllegalArgumentException("headroom must be at least 1: " + headroom);
        }
        this.headroom = headroom;
        this.blockedReasons = Map.copyOf(Objects.requireNonNull(blockedReasons, "blockedReasons"));
    }

    /**
     * The verdicts of {@code doorIds} from the router's gate rule — the blocked ones with their
     * reason ("the West Gate is closed"). Call where the {@link GateAvailability}'s ports may be read
     * (paper: the main thread).
     */
    public static Map<Integer, String> decideAll(GateAvailability availability, Iterable<Integer> doorIds) {
        Objects.requireNonNull(availability, "availability");
        Map<Integer, String> blocked = new HashMap<>();
        for (int doorId : doorIds) {
            EdgeVerdict verdict = availability.decide(doorId);
            if (verdict.isBlocked()) {
                blocked.put(doorId, verdict.message() == null ? "gate " + doorId + " is closed" : verdict.message());
            }
        }
        return blocked;
    }

    @Override
    public double extraCost(int x, int feetY, int z) {
        return blockingDoor(x, feetY, z).isPresent() ? BLOCKED : 0.0;
    }

    @Override
    public Optional<String> denyReason(int x, int feetY, int z) {
        OptionalInt door = blockingDoor(x, feetY, z);
        return door.isPresent() ? Optional.of(blockedReasons.get(door.getAsInt())) : Optional.empty();
    }

    private OptionalInt blockingDoor(int x, int feetY, int z) {
        if (blockedReasons.isEmpty()) {
            return OptionalInt.empty();
        }
        for (int y = feetY - 1; y < feetY + headroom; y++) {
            OptionalInt door = gates.doorAt(x, y, z);
            if (door.isPresent() && blockedReasons.containsKey(door.getAsInt())) {
                return door;
            }
        }
        return OptionalInt.empty();
    }
}
