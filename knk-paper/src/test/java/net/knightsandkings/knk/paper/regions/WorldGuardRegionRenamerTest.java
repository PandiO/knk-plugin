package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.RegionRenamePlan.Action;

/**
 * KNG-103: re-running District 17's region step drew {@code tempregion_worldtask_227}; renaming it to {@code domain_17}
 * failed because the district's own region already had that name, leaving an orphan behind. The rename now replaces the
 * domain's previous region when the API names it, and only then.
 */
class WorldGuardRegionRenamerTest {

    private static final String TEMP = "tempregion_worldtask_227";
    private static final String FINAL = "domain_17";
    private static final StateFlag PVP = new StateFlag("pvp", false);
    private static final StringFlag ACCESS = new StringFlag("knk-allow-entry");
    private static final StringFlag CREATED = new StringFlag("knk-creation-timestamp");

    private final Map<String, ProtectedRegion> regions = new LinkedHashMap<>();
    private RegionManager manager;
    private ProtectedRegion town;
    private boolean failOnAdd;

    @BeforeEach
    void setUp() {
        manager = mock(RegionManager.class);
        when(manager.getRegion(anyString())).thenAnswer(call -> regions.get(key(call.getArgument(0))));
        when(manager.getRegions()).thenAnswer(call -> Map.copyOf(regions));
        when(manager.removeRegion(anyString())).thenAnswer(call -> {
            ProtectedRegion removed = regions.remove(key(call.getArgument(0)));
            if (removed == null) {
                return Set.of();
            }
            // WorldGuard's default removal strategy unsets the parent of the removed region's children.
            for (ProtectedRegion region : regions.values()) {
                if (region.getParent() == removed) {
                    region.clearParent();
                }
            }
            return Set.of(removed);
        });
        doAnswer(call -> {
            ProtectedRegion region = call.getArgument(0);
            if (failOnAdd && region.getId().equals(FINAL)) {
                failOnAdd = false; // once: the rollback re-adds the old domain_17
                throw new IllegalStateException("disk full");
            }
            regions.put(region.getId(), region);
            return null;
        }).when(manager).addRegion(any(ProtectedRegion.class));

        town = add(cuboid("town_1", 0, 100));
        town.setPriority(10);
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static ProtectedCuboidRegion cuboid(String id, int from, int to) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(from, 0, from), BlockVector3.at(to, 255, to));
    }

    private ProtectedRegion add(ProtectedRegion region) {
        regions.put(region.getId(), region);
        return region;
    }

    /** The district as it was after its first creation: domain_17 with its managed and admin settings. */
    private ProtectedRegion existingDistrict() throws Exception {
        ProtectedRegion district = add(cuboid(FINAL, 10, 20));
        district.setParent(town);
        district.setPriority(20);
        district.setFlag(PVP, StateFlag.State.ALLOW);
        district.setFlag(ACCESS, "true");
        district.getOwners().addPlayer(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return district;
    }

    private ProtectedRegion drawnTemp() {
        ProtectedRegion temp = add(cuboid(TEMP, 30, 60));
        temp.setFlag(CREATED, "1760000000000");
        return temp;
    }

    @Test
    void reRunReplacesTheDomainsRegionInPlace() throws Exception {
        existingDistrict();
        ProtectedRegion structure = add(cuboid("domain_40", 12, 14));
        structure.setParent(regions.get(FINAL));
        drawnTemp();

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, FINAL);

        assertTrue(result.success(), result.detail());
        assertEquals(Action.REPLACE_TARGET, result.action());
        assertNull(regions.get(TEMP), "the temp name is gone");
        ProtectedRegion replaced = regions.get(FINAL);
        assertNotNull(replaced);
        // The new shape ...
        assertEquals(BlockVector3.at(30, 0, 30), replaced.getMinimumPoint());
        assertEquals(BlockVector3.at(60, 255, 60), replaced.getMaximumPoint());
        // ... with the district's own settings.
        assertSame(town, replaced.getParent());
        assertEquals(20, replaced.getPriority());
        assertEquals(StateFlag.State.ALLOW, replaced.getFlag(PVP));
        assertEquals("true", replaced.getFlag(ACCESS));
        assertTrue(replaced.getOwners().contains(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        // The district's structures stay under it.
        assertSame(replaced, structure.getParent());
        assertEquals(Set.of("town_1", FINAL, "domain_40"), regions.keySet());
    }

    @Test
    void anExistingTargetIsLeftAloneWhenTheApiNamesNoReplacement() throws Exception {
        ProtectedRegion legacy = existingDistrict();
        ProtectedRegion temp = drawnTemp();

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, null);

        assertFalse(result.success());
        assertTrue(result.detail().contains("already exists"), result.detail());
        assertSame(legacy, regions.get(FINAL));
        assertSame(temp, regions.get(TEMP));
    }

    @Test
    void anotherDomainsRegionIsNeverRemoved() throws Exception {
        ProtectedRegion other = add(cuboid("domain_5", 70, 80));
        drawnTemp();

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, "domain_5");

        assertTrue(result.success());
        assertEquals(Action.RENAME, result.action());
        assertSame(other, regions.get("domain_5"));
        assertNotNull(regions.get(FINAL));
        assertTrue(result.detail().contains("kept"), result.detail());
    }

    @Test
    void aPreviousTempRegionIsRemovedWithTheRename() {
        ProtectedRegion previous = add(cuboid("tempregion_worldtask_200", 10, 20));
        ProtectedRegion structure = add(cuboid("domain_40", 12, 14));
        try {
            structure.setParent(previous);
        } catch (ProtectedRegion.CircularInheritanceException e) {
            throw new AssertionError(e);
        }
        drawnTemp();

        WorldGuardRegionRenamer.Result result =
                WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, "tempregion_worldtask_200");

        assertTrue(result.success(), result.detail());
        assertEquals(Action.RENAME_AND_REMOVE_PREVIOUS, result.action());
        assertEquals(Set.of("town_1", FINAL, "domain_40"), regions.keySet());
        assertSame(regions.get(FINAL), structure.getParent());
    }

    @Test
    void aFirstFinalizeCopiesTheDrawnRegion() throws Exception {
        ProtectedRegion temp = drawnTemp();
        temp.setParent(town);
        temp.setPriority(5);
        temp.getMembers().addPlayer(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, null);

        assertTrue(result.success());
        ProtectedRegion renamed = regions.get(FINAL);
        assertSame(town, renamed.getParent());
        assertEquals(5, renamed.getPriority());
        assertEquals("1760000000000", renamed.getFlag(CREATED));
        assertTrue(renamed.getMembers().contains(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertNull(regions.get(TEMP));
    }

    @Test
    void aFailureHalfWayPutsEveryRegionBack() throws Exception {
        ProtectedRegion district = existingDistrict();
        ProtectedRegion structure = add(cuboid("domain_40", 12, 14));
        structure.setParent(district);
        ProtectedRegion temp = drawnTemp();
        failOnAdd = true;

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, FINAL);

        assertFalse(result.success());
        assertTrue(result.detail().contains("rolled back"), result.detail());
        assertSame(district, regions.get(FINAL));
        assertSame(temp, regions.get(TEMP));
        assertSame(district, structure.getParent());
    }

    @Test
    void aMissingSourceIsRefused() throws Exception {
        ProtectedRegion district = existingDistrict();

        WorldGuardRegionRenamer.Result result = WorldGuardRegionRenamer.rename(manager, TEMP, FINAL, FINAL);

        assertFalse(result.success());
        assertSame(district, regions.get(FINAL));
    }
}
