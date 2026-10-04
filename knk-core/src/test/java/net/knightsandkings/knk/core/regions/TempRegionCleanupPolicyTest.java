package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Decision;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;

class TempRegionCleanupPolicyTest {

    private static final long CUTOFF = 1_000_000L;
    private static final String OLD = String.valueOf(CUTOFF - 1);
    private static final String TEMP = "tempregion_worldtask_97";

    private final List<String> lookups = new ArrayList<>();

    private Function<String, Usage> lookup(Usage usage) {
        return regionId -> {
            lookups.add(regionId);
            return usage;
        };
    }

    private Decision decide(String regionId, String timestamp, boolean known, Function<String, Usage> fresh) {
        return TempRegionCleanupPolicy.decide(regionId, timestamp, CUTOFF, id -> known, fresh);
    }

    @Test
    void oldUnusedTempRegion_isDeleted_afterAFreshLookup() {
        Decision decision = decide(TEMP, OLD, false, lookup(Usage.UNUSED));

        assertEquals(Decision.DELETE, decision);
        assertTrue(decision.delete());
        assertEquals(List.of(TEMP), lookups);
    }

    @Test
    void domainFoundByTheFreshLookup_isKept() {
        assertEquals(Decision.KEEP_IN_USE, decide(TEMP, OLD, false, lookup(Usage.IN_USE)));
    }

    @Test
    void unreachableApi_keepsTheRegion() {
        assertEquals(Decision.KEEP_USAGE_UNKNOWN, decide(TEMP, OLD, false, lookup(Usage.UNKNOWN)));
        assertEquals(Decision.KEEP_USAGE_UNKNOWN, decide(TEMP, OLD, false, regionId -> {
            throw new IllegalStateException("API down");
        }));
        assertEquals(Decision.KEEP_USAGE_UNKNOWN, decide(TEMP, OLD, false, regionId -> null));
    }

    @Test
    void regionTheLastRepairSawADomainUse_isKept_withoutAnApiCall() {
        assertEquals(Decision.KEEP_KNOWN_DOMAIN_REGION, decide(TEMP, OLD, true, lookup(Usage.UNUSED)));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void recentOrUndatedRegions_areKept_withoutAnApiCall() {
        assertEquals(Decision.KEEP_TOO_RECENT, decide(TEMP, String.valueOf(CUTOFF), false, lookup(Usage.UNUSED)));
        assertEquals(Decision.KEEP_NO_TIMESTAMP, decide(TEMP, null, false, lookup(Usage.UNUSED)));
        assertEquals(Decision.KEEP_INVALID_TIMESTAMP, decide(TEMP, "yesterday", false, lookup(Usage.UNUSED)));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void nonTemporaryRegions_areNeverDeleted() {
        assertEquals(Decision.NOT_TEMPORARY, decide("domain_11", OLD, false, lookup(Usage.UNUSED)));
        assertEquals(Decision.NOT_TEMPORARY, decide("town_1", OLD, false, lookup(Usage.UNUSED)));
        assertTrue(lookups.isEmpty());
    }
}
