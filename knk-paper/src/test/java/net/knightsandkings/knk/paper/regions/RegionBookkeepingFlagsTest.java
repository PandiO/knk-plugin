package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * KNG-258: the bookkeeping flags are found by name. After a restart WorldGuard hands back a flag it doesn't know as
 * another object, so a lookup with the plugin's own flag object would find nothing.
 */
class RegionBookkeepingFlagsTest {

    private final ProtectedRegion region = new ProtectedCuboidRegion("domain_11_sub_4", BlockVector3.at(0, 0, 0), BlockVector3.at(1, 1, 1));

    @Test
    void aFlagLoadedAsAnotherObjectIsReadAndReplaced() {
        // As loaded from the region file: same name, different object.
        region.setFlag(new StringFlag(RegionBookkeepingFlags.ORPHANED_SINCE), "1000");

        assertEquals("1000", RegionBookkeepingFlags.get(region, RegionBookkeepingFlags.ORPHANED_SINCE));

        RegionBookkeepingFlags.set(region, RegionBookkeepingFlags.ORPHANED_SINCE, "2000");
        assertEquals("2000", RegionBookkeepingFlags.get(region, RegionBookkeepingFlags.ORPHANED_SINCE));
        assertEquals(1, region.getFlags().size(), "no second copy under another object");

        RegionBookkeepingFlags.set(region, RegionBookkeepingFlags.ORPHANED_SINCE, null);
        assertNull(RegionBookkeepingFlags.get(region, RegionBookkeepingFlags.ORPHANED_SINCE));
        assertEquals(0, region.getFlags().size());
    }
}
