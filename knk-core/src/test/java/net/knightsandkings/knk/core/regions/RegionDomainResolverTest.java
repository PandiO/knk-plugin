package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.RegionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RegionDomainResolver, focused on the resolveRegions/getDomainByRegionId
 * staleness bug: WorldGuardRegionTracker.checkCacheStatus decides whether to proceed
 * synchronously with a transition using getDomainByRegionIdNoRefresh (age-tolerant), but
 * resolveRegions previously used the TTL-strict getDomainByRegionId, which silently discarded
 * (returned empty for) any entry older than cacheTtl. Once a region had been cached once, the
 * tracker never re-triggered a refresh (its own check doesn't look at age), so
 * enteredDomains/leftDomains came back permanently empty for that region after the first minute -
 * exactly the reported symptom of district gate loading never firing on entry. resolveRegions
 * must return a domain that's still present but stale, not silently drop it.
 */
class RegionDomainResolverTest {

    private static DomainSnapshot districtSnapshot(int id, String regionId) {
        return new DomainSnapshot(
            id, "TestDistrict", "A test district", regionId,
            true, true, "District",
            Set.of(), Set.of(), Set.of(), Set.of()
        );
    }

    @Test
    void resolveRegionsReturnsAFreshlyRegisteredDomain() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        RegionSnapshot snapshot = resolver.resolveRegions(Set.of("district_1000006"));

        assertEquals(1, snapshot.domains().size());
        assertTrue(snapshot.domains().contains(district));
    }

    @Test
    void resolveRegionsStillReturnsADomainAfterItsCacheEntryHasExpired() {
        // A negative TTL guarantees cachedAt.plus(ttl) is strictly before cachedAt itself, so the
        // entry reads as expired regardless of clock resolution - no sleep, no flakiness (a
        // zero-duration TTL isn't reliable here: on a coarse-resolution clock, Instant.now() at
        // check time can compare equal to cachedAt, which isBefore() treats as "not expired").
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        RegionSnapshot snapshot = resolver.resolveRegions(Set.of("district_1000006"));

        assertEquals(1, snapshot.domains().size());
        assertTrue(snapshot.domains().contains(district));
    }

    @Test
    void getDomainByRegionIdStillDiscardsAnExpiredEntry() {
        // Documents the intentionally different (TTL-strict) behavior of the other accessor,
        // used by isCached()/resolveRegionsFromApi() to decide whether an API refetch is due.
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        resolver.registerDomain(districtSnapshot(1000006, "district_1000006"));

        assertFalse(resolver.getDomainByRegionId("district_1000006").isPresent());
    }

    @Test
    void getDomainByRegionIdNoRefreshReturnsAnExpiredEntryRegardless() {
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        assertEquals(district, resolver.getDomainByRegionIdNoRefresh("district_1000006").orElse(null));
    }
}
