package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.paper.regions.access.DomainAccessFlags;

/**
 * A domain's area as the union of its WorldGuard regions (KNG-257, knk-workspace
 * {@code docs/specs/domain-subregions/DESIGN.md} §9.2): its main region and its sub-regions, found by the
 * {@code knk-domain-id} flag the access sync puts on every region of a domain. Containment checks (a door location
 * inside its gate, a structure inside its district) use it, so a point in a garrison's tower counts as inside the
 * garrison. A region without the flag is its own union, as before.
 */
public final class DomainRegionUnion {

    /** Columns sampled per side at most when a region is checked against a union of several regions. */
    static final int MAX_SAMPLES_PER_SIDE = 256;

    private DomainRegionUnion() {
    }

    /** {@code region} and every other region in {@code manager} of the same domain. */
    public static List<ProtectedRegion> of(RegionManager manager, ProtectedRegion region) {
        List<ProtectedRegion> union = new ArrayList<>();
        union.add(region);
        String domainId = domainIdOf(region);
        if (domainId == null || manager == null) {
            return union;
        }
        for (ProtectedRegion other : manager.getRegions().values()) {
            if (other != region && domainId.equals(domainIdOf(other))) {
                union.add(other);
            }
        }
        return union;
    }

    public static boolean contains(Collection<ProtectedRegion> union, BlockVector3 point) {
        for (ProtectedRegion region : union) {
            if (region.contains(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The points of {@code subject} that lie outside {@code union}: its corner points, and - when the union has several
     * regions, where corners alone can't tell (one corner in the tower, the other in the house, the wall between them
     * outside) - its block columns at its lowest and highest Y, sampled at most {@value #MAX_SAMPLES_PER_SIDE} per side.
     *
     * @param limit stop after this many
     */
    public static List<BlockVector3> outside(Collection<ProtectedRegion> union, ProtectedRegion subject,
                                             List<BlockVector3> corners, int limit) {
        List<BlockVector3> outside = new ArrayList<>();
        for (BlockVector3 corner : corners) {
            if (!contains(union, corner)) {
                outside.add(corner);
                if (outside.size() >= limit) {
                    return outside;
                }
            }
        }
        if (union.size() < 2 || subject == null) {
            return outside;
        }
        BlockVector3 min = subject.getMinimumPoint();
        BlockVector3 max = subject.getMaximumPoint();
        int stepX = step(max.x() - min.x() + 1);
        int stepZ = step(max.z() - min.z() + 1);
        for (int x = min.x(); x <= max.x(); x = next(x, stepX, max.x())) {
            for (int z = min.z(); z <= max.z(); z = next(z, stepZ, max.z())) {
                for (int y : new int[] {min.y(), max.y()}) {
                    BlockVector3 point = BlockVector3.at(x, y, z);
                    if (subject.contains(point) && !contains(union, point) && !outside.contains(point)) {
                        outside.add(point);
                        if (outside.size() >= limit) {
                            return outside;
                        }
                    }
                }
            }
        }
        return outside;
    }

    private static int step(int size) {
        return Math.max(1, (int) Math.ceil(size / (double) MAX_SAMPLES_PER_SIDE));
    }

    /** The next sample, always ending on the last column. */
    private static int next(int value, int step, int last) {
        if (value == last) {
            return last + 1;
        }
        return Math.min(value + step, last);
    }

    private static String domainIdOf(ProtectedRegion region) {
        if (DomainAccessFlags.domainId() == null) {
            return null;
        }
        String id = region.getFlag(DomainAccessFlags.domainId());
        return id == null || id.isBlank() ? null : id.trim();
    }
}
