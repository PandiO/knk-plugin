package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync.AccessFlags;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync.Report;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-56: the domain rules land on the WorldGuard regions, so they hold without the API. */
class AccessFlagSyncTest {

    /** In-memory WorldGuard: region id (lower-case) -> flags. */
    private static final class FakeStore implements AccessFlagSync.Store {
        final Map<String, AccessFlags> regions = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        int saves;
        boolean failSave;

        FakeStore region(String id, AccessFlags flags) {
            regions.put(id.toLowerCase(Locale.ROOT), flags);
            return this;
        }

        @Override
        public Optional<AccessFlags> read(String regionId) {
            return Optional.ofNullable(regions.get(regionId.toLowerCase(Locale.ROOT)));
        }

        @Override
        public void write(String regionId, AccessFlags flags) {
            if (regionId.equals("boom")) {
                throw new IllegalStateException("region is locked");
            }
            regions.put(regionId.toLowerCase(Locale.ROOT), flags);
            writes.add(regionId);
        }

        @Override
        public Collection<String> regionsWithAccessFlags() {
            return regions.entrySet().stream().filter(e -> !e.getValue().equals(AccessFlags.NONE)).map(Map.Entry::getKey).toList();
        }

        @Override
        public void persist() {
            if (failSave) {
                throw new IllegalStateException("disk full");
            }
            saves++;
        }
    }

    private final AccessFlagSync sync = new AccessFlagSync();

    private static DomainAccessRule rule(int id, String region, boolean entry, boolean exit) {
        return new DomainAccessRule(id, "Domain " + id, region, entry, exit, "District");
    }

    @Test
    void eachDomainsRulesAndNameAreWrittenOntoItsRegion() {
        FakeStore store = new FakeStore().region("domain_7", AccessFlags.NONE).region("domain_8", AccessFlags.NONE);

        Report report = sync.run(List.of(rule(7, "domain_7", false, true), rule(8, "domain_8", true, false)), store);

        assertEquals(new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Domain 7"), store.regions.get("domain_7"));
        assertEquals(new AccessFlags(AccessState.ALLOW, AccessState.DENY, "Domain 8"), store.regions.get("domain_8"));
        assertEquals(2, report.updated());
        assertEquals(1, store.saves);
    }

    @Test
    void aSecondRunChangesNothingAndDoesNotSave() {
        FakeStore store = new FakeStore().region("domain_7", AccessFlags.NONE);
        List<DomainAccessRule> rules = List.of(rule(7, "domain_7", false, true));
        sync.run(rules, store);

        Report again = sync.run(rules, store);

        assertEquals(0, again.updated());
        assertEquals(1, again.unchanged());
        assertEquals(1, store.saves);
    }

    @Test
    void reopeningADomainLiftsTheDeny() {
        FakeStore store = new FakeStore().region("domain_7", new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Domain 7"));

        sync.run(List.of(rule(7, "domain_7", true, true)), store);

        assertEquals(AccessState.ALLOW, store.regions.get("domain_7").entry());
    }

    @Test
    void aRegionNoDomainOwnsAnyMoreHasItsFlagsCleared() {
        FakeStore store = new FakeStore()
            .region("tempregion_worldtask_3", new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Old"))
            .region("domain_7", AccessFlags.NONE);

        Report report = sync.run(List.of(rule(7, "domain_7", true, true)), store);

        assertEquals(AccessFlags.NONE, store.regions.get("tempregion_worldtask_3"));
        assertEquals(1, report.cleared());
    }

    @Test
    void regionIdsMatchCaseInsensitivelyLikeWorldGuard() {
        FakeStore store = new FakeStore().region("Domain_7", new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Domain 7"));

        Report report = sync.run(List.of(rule(7, "domain_7", false, true)), store);

        assertEquals(0, report.cleared());
        assertEquals(1, report.unchanged());
    }

    @Test
    void aDomainWhoseRegionIsNotLoadedIsReportedNotCreated() {
        FakeStore store = new FakeStore();

        Report report = sync.run(List.of(rule(7, "domain_7", false, true)), store);

        assertEquals(List.of("domain_7"), report.missingRegions());
        assertTrue(store.regions.isEmpty());
    }

    @Test
    void oneFailingRegionDoesNotStopTheOthers() {
        FakeStore store = new FakeStore().region("boom", AccessFlags.NONE).region("domain_8", AccessFlags.NONE);

        Report report = sync.run(List.of(rule(7, "boom", false, true), rule(8, "domain_8", false, true)), store);

        assertEquals(1, report.failures().size());
        assertEquals(AccessState.DENY, store.regions.get("domain_8").entry());
    }

    @Test
    void aFailedSaveIsReported() {
        FakeStore store = new FakeStore().region("domain_7", AccessFlags.NONE);
        store.failSave = true;

        Report report = sync.run(List.of(rule(7, "domain_7", false, true)), store);

        assertTrue(report.persistFailed());
        assertFalse(report.summary().isBlank());
    }

    @Test
    void domainsWithoutARegionAreSkipped() {
        FakeStore store = new FakeStore();

        Report report = sync.run(List.of(rule(7, "", false, true), rule(8, null, false, true)), store);

        assertTrue(report.missingRegions().isEmpty());
        assertEquals(0, report.updated());
    }
}
