package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionRenamePlan.Action;
import net.knightsandkings.knk.core.regions.RegionRenamePlan.Plan;

/** KNG-103: re-running a domain's region step replaces its region instead of failing on the taken final name. */
class RegionRenamePlanTest {

    private static final String TEMP = "tempregion_worldtask_227";
    private static final String FINAL = "domain_17";

    @Test
    void firstFinalizeIsAPlainRename() {
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, null, true, false, false);

        assertEquals(Action.RENAME, plan.action());
        assertNull(plan.regionToRemove());
    }

    @Test
    void reRunReplacesTheDomainsOwnFinalRegion() {
        // KNG-103 repro: District 17 already lives in domain_17 and its region step drew tempregion_worldtask_227.
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, FINAL, true, true, true);

        assertEquals(Action.REPLACE_TARGET, plan.action());
        assertEquals(FINAL, plan.regionToRemove());
        assertTrue(plan.succeeds());
    }

    @Test
    void anExistingTargetTheApiDidNotNameIsStillRefused() {
        // A legacy v2 region that happens to be called domain_17 (managed-worldguard-regions.md §8 item 9).
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, null, true, true, false);

        assertEquals(Action.REFUSE, plan.action());
        assertTrue(plan.note().contains("already exists"));
    }

    @Test
    void anExistingTargetIsRefusedWhenTheDomainsPreviousRegionWasSomethingElse() {
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, "tempregion_worldtask_200", true, true, true);

        assertEquals(Action.REFUSE, plan.action());
        assertFalse(plan.succeeds());
    }

    @Test
    void aPreviousTempRegionIsRemovedAfterTheRename() {
        // The earlier finalize had failed, so the domain still lived in its first temp region.
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, "tempregion_worldtask_200", true, false, true);

        assertEquals(Action.RENAME_AND_REMOVE_PREVIOUS, plan.action());
        assertEquals("tempregion_worldtask_200", plan.regionToRemove());
    }

    @Test
    void aPreviousRegionThatIsNotKnkManagedIsKept() {
        // A v1 town region picked with "select", or another domain's final name: never removed.
        for (String previous : new String[] {"town_1", "domain_5", "__global__"}) {
            Plan plan = RegionRenamePlan.decide(TEMP, FINAL, previous, true, false, true);

            assertEquals(Action.RENAME, plan.action(), previous);
            assertNull(plan.regionToRemove(), previous);
            assertTrue(plan.note().contains("kept"), previous);
        }
    }

    @Test
    void aPreviousRegionThatIsGoneIsJustRenamedOver() {
        assertEquals(Action.RENAME, RegionRenamePlan.decide(TEMP, FINAL, FINAL, true, false, false).action());
        assertEquals(Action.RENAME, RegionRenamePlan.decide(TEMP, FINAL, "tempregion_worldtask_200", true, false, false).action());
    }

    @Test
    void namesAreComparedIgnoringCase() {
        assertEquals(Action.REPLACE_TARGET, RegionRenamePlan.decide(TEMP, FINAL, "Domain_17", true, true, true).action());
        assertEquals(Action.ALREADY_NAMED, RegionRenamePlan.decide("DOMAIN_17", FINAL, null, true, true, false).action());
    }

    @Test
    void replacingTheRegionBeingRenamedIsAPlainRename() {
        assertEquals(Action.RENAME, RegionRenamePlan.decide(TEMP, FINAL, TEMP, true, false, true).action());
        assertEquals(Action.REFUSE, RegionRenamePlan.decide(TEMP, FINAL, TEMP, true, true, true).action());
    }

    @Test
    void aMissingSourceRegionIsRefusedEvenWithAReplacement() {
        Plan plan = RegionRenamePlan.decide(TEMP, FINAL, FINAL, false, true, true);

        assertEquals(Action.REFUSE, plan.action());
        assertTrue(plan.note().contains("not found"));
    }

    @Test
    void finalNamesAreRecognised() {
        assertTrue(RegionRenamePlan.isFinalName("domain_17"));
        assertTrue(RegionRenamePlan.isFinalName("DOMAIN_1000006"));
        // Arena duels rev. 6: an arena area region's final name follows the same rules; v1's arena regions don't.
        assertTrue(RegionRenamePlan.isFinalName("arena_5_battleground_12"));
        assertTrue(RegionRenamePlan.isFinalName("ARENA_5_LOBBY_3"));
        assertTrue(RegionRenamePlan.isFinalName("arena_5_spectator_9"));
        assertFalse(RegionRenamePlan.isFinalName("arena_2,a-battleground"));
        assertFalse(RegionRenamePlan.isFinalName("arena_2"));
        assertFalse(RegionRenamePlan.isFinalName("arena_5_pit_3"));
        assertTrue(ArenaAreaRegionNames.isAreaRegionName(" arena_5_battleground_12 "));
        assertFalse(ArenaAreaRegionNames.isAreaRegionName("domain_5_sub_1"));
        assertFalse(ArenaAreaRegionNames.isAreaRegionName(null));
        assertFalse(RegionRenamePlan.isFinalName("domain_"));
        assertFalse(RegionRenamePlan.isFinalName("domain_17_old"));
        // KNG-255: a sub-region's final name
        assertTrue(RegionRenamePlan.isFinalName("domain_17_sub_4"));
        assertFalse(RegionRenamePlan.isFinalName("domain_17_sub_"));
        assertFalse(RegionRenamePlan.isFinalName("domain_17_sub_4_x"));
        assertFalse(RegionRenamePlan.isFinalName(TEMP));
        assertFalse(RegionRenamePlan.isFinalName(null));
    }

    @Test
    void aSubRegionReRunReplacesItsOwnFinalNameInPlace() {
        // KNG-255: the API names the sub-region's final name as replaced, like a main region's (KNG-103).
        RegionRenamePlan.Plan plan = RegionRenamePlan.decide("tempregion_worldtask_9", "domain_11_sub_4", "domain_11_sub_4",
            true, true, true);

        assertEquals(RegionRenamePlan.Action.REPLACE_TARGET, plan.action());
        assertEquals("domain_11_sub_4", plan.regionToRemove());
    }
}
