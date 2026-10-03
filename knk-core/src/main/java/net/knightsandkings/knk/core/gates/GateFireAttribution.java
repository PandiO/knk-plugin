package net.knightsandkings.knk.core.gates;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who set a gate door's burning blocks on fire (KNG-34, DESIGN.md §F.8): one igniter per burning
 * block, keyed by gate id and block position. A fire tick's effective HP loss is split evenly over the
 * blocks burning in that tick and summed per igniter; the share of an unattributed block is credited
 * to nobody. Re-igniting a block hands it to the newest igniter (or makes it unattributed).
 * <p>
 * Bookkeeping only - the fire itself (expiry, damage per block) stays in the gate fire system, which
 * calls {@link #split} with the blocks it actually burned this tick. Main thread only.
 */
public final class GateFireAttribution {

    /**
     * The player credited for a burning block. The user id is stored at ignition so an igniter who has
     * logged off is still credited; 0 when it wasn't known then.
     */
    public record Igniter(UUID playerId, int userId) {
        public Igniter {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    private final Map<Integer, Map<Long, Igniter>> igniters = new HashMap<>();

    /** Packs a block position into the key {@link #ignite} and {@link #split} use. */
    public static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    /** A block of {@code gateId} was (re-)ignited; {@code igniter} null = unattributed (TNT without a source, dispensers). */
    public void ignite(int gateId, long blockKey, Igniter igniter) {
        if (igniter == null) {
            Map<Long, Igniter> blocks = igniters.get(gateId);
            if (blocks != null) {
                blocks.remove(blockKey);
                if (blocks.isEmpty()) {
                    igniters.remove(gateId);
                }
            }
            return;
        }
        igniters.computeIfAbsent(gateId, id -> new HashMap<>()).put(blockKey, igniter);
    }

    /**
     * Splits one tick's effective loss of {@code gateId} over {@code burningBlocks} (the blocks that
     * burned this tick) and returns each igniter's share. Igniters of blocks that no longer burn are
     * forgotten. Empty when nothing is attributed or the loss is not positive.
     */
    public Map<Igniter, Double> split(int gateId, Collection<Long> burningBlocks, double loss) {
        Map<Long, Igniter> blocks = igniters.get(gateId);
        if (blocks == null) {
            return Map.of();
        }
        if (burningBlocks == null || burningBlocks.isEmpty()) {
            igniters.remove(gateId);
            return Map.of();
        }
        Set<Long> burning = burningBlocks instanceof Set<Long> set ? set : new HashSet<>(burningBlocks);
        blocks.keySet().retainAll(burning);
        if (blocks.isEmpty()) {
            igniters.remove(gateId);
            return Map.of();
        }
        if (!(loss > 0) || Double.isInfinite(loss)) {
            return Map.of();
        }
        double share = loss / burning.size();
        Map<Igniter, Double> credit = new LinkedHashMap<>();
        for (Long block : burning) {
            Igniter igniter = blocks.get(block);
            if (igniter != null) {
                credit.merge(igniter, share, Double::sum);
            }
        }
        return credit;
    }

    /** The gate stopped burning (extinguished, destroyed, opened): its igniters are forgotten. */
    public void clear(int gateId) {
        igniters.remove(gateId);
    }

    /** The igniter of one block, or null. */
    public Igniter igniterOf(int gateId, long blockKey) {
        Map<Long, Igniter> blocks = igniters.get(gateId);
        return blocks == null ? null : blocks.get(blockKey);
    }

    /** Attributed blocks of a gate (tests, diagnostics). */
    public int attributedBlocks(int gateId) {
        Map<Long, Igniter> blocks = igniters.get(gateId);
        return blocks == null ? 0 : blocks.size();
    }
}
