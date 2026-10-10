package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.util.BlockKey;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Hand-openable doors and fence gates at cell level (KNG-51 {@code LAST_MILE_PATHFINDING.md} §6
 * "Doors", decision §11-2): the door blocks in the search box the mover may <em>not</em> open, decided
 * once per request on the main thread (paper: WorldGuard {@code USE}/{@code INTERACT} as WorldGuard
 * enforces them on a click). A cell whose feet or head block is such a door is blocked; every other
 * cell answers {@code 0} — the search adds the door's own cost ({@link MovementProfile#doorCost})
 * itself (L2-3).
 *
 * <p>The KnK domain part of the decision ("the door must also pass the domain entry rules") is not
 * repeated here: {@link DeniedRegionAccess} already blocks every cell — door cells included — in a
 * region the mover may not enter, and the two are composed with {@link CellAccess#all}. Immutable;
 * safe on any thread.
 */
public final class DoorCellAccess implements CellAccess {

    /** Every door may be opened. */
    public static final DoorCellAccess NONE = new DoorCellAccess(Set.of(), MovementProfile.PLAYER_HEADROOM);

    public static final String DENY_REASON = "you may not open this door";

    private final Set<Long> denied;
    private final int headroom;

    /**
     * @param deniedDoorBlocks packed {@link BlockKey}s of the door blocks the mover may not open (both halves of a door)
     * @param headroom         the mover's headroom ({@link MovementProfile#headroom}): feet..feet+headroom-1 are checked
     */
    public DoorCellAccess(Collection<Long> deniedDoorBlocks, int headroom) {
        this.denied = Set.copyOf(new HashSet<>(Objects.requireNonNull(deniedDoorBlocks, "deniedDoorBlocks")));
        if (headroom < 1) {
            throw new IllegalArgumentException("headroom must be at least 1: " + headroom);
        }
        this.headroom = headroom;
    }

    public Set<Long> deniedDoorBlocks() {
        return denied;
    }

    @Override
    public double extraCost(int x, int feetY, int z) {
        return isDenied(x, feetY, z) ? BLOCKED : 0.0;
    }

    @Override
    public Optional<String> denyReason(int x, int feetY, int z) {
        return isDenied(x, feetY, z) ? Optional.of(DENY_REASON) : Optional.empty();
    }

    private boolean isDenied(int x, int feetY, int z) {
        if (denied.isEmpty()) {
            return false;
        }
        for (int y = feetY; y < feetY + headroom; y++) {
            if (denied.contains(BlockKey.pack(x, y, z))) {
                return true;
            }
        }
        return false;
    }
}
