package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.ReleaseDecision;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;

/**
 * PandiO/knk-workspace#269: a temp region the API released skips only the age check. It is removed only when it is the
 * release's own {@code tempregion_worldtask_<taskId>}, in exactly one world, not being drawn here, has a creation
 * timestamp, and a fresh lookup finds no domain or lootbox area using it; unsure means keep (or ask again).
 */
class TempRegionReleaseDecisionTest {

    private static final TempRegionRelease RELEASE = new TempRegionRelease(5, 97, "tempregion_worldtask_97", "knk_hub", "DraftDeleted");
    private static final String STAMP = "1762600000000";

    private final List<String> lookups = new ArrayList<>();

    private Function<String, Usage> lookup(Usage usage) {
        return regionId -> {
            lookups.add(regionId);
            return usage;
        };
    }

    private ReleaseDecision decide(TempRegionRelease release, int worlds, String stamp, boolean active, boolean known, Usage usage) {
        return TempRegionCleanupPolicy.decideRelease(release, worlds, stamp, active, id -> known, lookup(usage));
    }

    @Test
    void unusedReleasedRegion_isRemoved_withoutAnAgeCheck_afterAFreshLookup() {
        ReleaseDecision decision = decide(RELEASE, 1, STAMP, false, false, Usage.UNUSED);

        assertEquals(ReleaseDecision.REMOVE, decision);
        assertTrue(decision.remove());
        assertTrue(decision.settles());
        assertEquals(List.of("tempregion_worldtask_97"), lookups);
    }

    @Test
    void aRegionInUse_isKept_andSettled() {
        ReleaseDecision decision = decide(RELEASE, 1, STAMP, false, false, Usage.IN_USE);

        assertEquals(ReleaseDecision.KEEP_IN_USE, decision);
        assertFalse(decision.remove());
        assertTrue(decision.settles());
    }

    @Test
    void anUnreachableApi_waits() {
        ReleaseDecision decision = decide(RELEASE, 1, STAMP, false, false, Usage.UNKNOWN);

        assertEquals(ReleaseDecision.WAIT_USAGE_UNKNOWN, decision);
        assertFalse(decision.settles());
        assertEquals(ReleaseDecision.WAIT_USAGE_UNKNOWN,
                TempRegionCleanupPolicy.decideRelease(RELEASE, 1, STAMP, false, id -> false, id -> { throw new IllegalStateException(); }));
    }

    @Test
    void aMissingRegion_isSettledAsMissing_withoutALookup() {
        assertEquals(ReleaseDecision.MISSING, decide(RELEASE, 0, null, false, false, Usage.UNUSED));
        assertTrue(ReleaseDecision.MISSING.settles());
        assertTrue(lookups.isEmpty());
    }

    @Test
    void renamedRegions_areMissing_otherNamesAreNeverRemoved() {
        // Renamed to domain_<id> / lootbox_area_<id>: the temp name is gone, nothing is found.
        assertEquals(ReleaseDecision.MISSING, decide(RELEASE, 0, null, false, false, Usage.UNUSED));
        // A release naming another region, or another task's temp region, is never acted on.
        assertEquals(ReleaseDecision.KEEP_NOT_ITS_TEMP_REGION,
                decide(new TempRegionRelease(5, 97, "domain_12", null, "DraftDeleted"), 1, STAMP, false, false, Usage.UNUSED));
        assertEquals(ReleaseDecision.KEEP_NOT_ITS_TEMP_REGION,
                decide(new TempRegionRelease(5, 97, "tempregion_worldtask_98", null, "DraftDeleted"), 1, STAMP, false, false, Usage.UNUSED));
        assertEquals(ReleaseDecision.KEEP_NOT_ITS_TEMP_REGION, decide(null, 1, STAMP, false, false, Usage.UNUSED));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void theSameIdInSeveralWorlds_isKept() {
        assertEquals(ReleaseDecision.KEEP_IN_SEVERAL_WORLDS, decide(RELEASE, 2, STAMP, false, false, Usage.UNUSED));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void aTaskStillRunningHere_orARepairThatHasNotRun_waits() {
        assertEquals(ReleaseDecision.WAIT_TASK_ACTIVE, decide(RELEASE, 1, STAMP, true, false, Usage.UNUSED));
        assertEquals(ReleaseDecision.WAIT_KNOWN_DOMAIN_REGION, decide(RELEASE, 1, STAMP, false, true, Usage.UNUSED));
        assertFalse(ReleaseDecision.WAIT_TASK_ACTIVE.settles());
        assertFalse(ReleaseDecision.WAIT_KNOWN_DOMAIN_REGION.settles());
        assertTrue(lookups.isEmpty());
    }

    @Test
    void noOrInvalidCreationTimestamp_isKept_notDrawnByTheRegionTask() {
        assertEquals(ReleaseDecision.KEEP_NO_TIMESTAMP, decide(RELEASE, 1, null, false, false, Usage.UNUSED));
        assertEquals(ReleaseDecision.KEEP_NO_TIMESTAMP, decide(RELEASE, 1, " ", false, false, Usage.UNUSED));
        assertEquals(ReleaseDecision.KEEP_NO_TIMESTAMP, decide(RELEASE, 1, "yesterday", false, false, Usage.UNUSED));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void namesAreMatchedLikeWorldGuard_caseInsensitive() {
        var upper = new TempRegionRelease(5, 97, "TempRegion_WorldTask_97", null, "TaskCancelled");

        assertTrue(upper.namesItsOwnTaskRegion());
        assertEquals(ReleaseDecision.REMOVE, decide(upper, 1, STAMP, false, false, Usage.UNUSED));
    }
}
