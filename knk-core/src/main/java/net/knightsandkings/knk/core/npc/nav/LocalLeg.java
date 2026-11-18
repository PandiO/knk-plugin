package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;

import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * What the engine's local path search may use for one leg: the subject's cell access (gates, denied regions, doors)
 * decided inside a captured box. Cells outside the box are blocked, because nothing was decided there. Immutable;
 * the access may be read from the engine's search threads.
 *
 * @param access     the subject's verdict per feet cell
 * @param allowDoors whether the engine may open doors on this leg (the access still vetoes single doors)
 */
public record LocalLeg(CellAccess access, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       boolean allowDoors) {

    public LocalLeg {
        Objects.requireNonNull(access, "access");
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Whether the engine's search may use the cell an NPC's feet would be in. */
    public boolean allows(int x, int feetY, int z) {
        return contains(x, feetY, z) && access.allows(x, feetY, z);
    }
}
