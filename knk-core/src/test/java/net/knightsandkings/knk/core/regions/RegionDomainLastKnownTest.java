package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * PandiO/knk-workspace#724: after a clear of the region → domain map ({@code /knk cache refresh}, a realtime domain
 * change) the NPC checks keep the last known answers ({@link RegionDomainResolver#getLastKnownDomain}) until the re-warm
 * releases them; every other lookup sees the cleared map.
 */
class RegionDomainLastKnownTest {

    private static final String WORLD = "world";

    private static DomainSnapshot district(String region, boolean allowEntry) {
        return new DomainSnapshot(4, "Residential District", "", region, allowEntry, true, "District", Set.of(),
            Set.of(), Set.of(), Set.of());
    }

    private final RegionDomainResolver map = new RegionDomainResolver();

    @Test
    void afterAClearTheNpcLookupKeepsTheLastKnownAnswerWhileOtherLookupsSeeTheClearedMap() {
        map.registerDomain(WORLD, district("district_4", false));

        map.clearRegionCache();

        assertTrue(map.holding());
        assertEquals(Optional.empty(), map.getDomainByRegionIdNoRefresh(WORLD, "district_4"), "players: cleared");
        assertFalse(map.getLastKnownDomain(WORLD, "district_4").orElseThrow().allowEntry(), "NPCs: the last known");
        assertFalse(map.getLastKnownDomain(null, "district_4").orElseThrow().allowEntry(), "world-blind too");
        assertEquals(Optional.empty(), map.getLastKnownDomain("other_world", "district_4"), "never another world's");
    }

    @Test
    void whileHeldTheLastKnownAnswerWinsOverANewOneUntilTheReleaseSwitchesToTheMap() {
        map.registerDomain(WORLD, district("district_4", false));
        map.clearRegionCache();
        long generation = map.holdGeneration();
        map.registerDomain(WORLD, district("district_4", true)); // the re-warm's answer arrives
        map.registerDomain(WORLD, district("district_5", true)); // a region nobody knew before

        assertFalse(map.getLastKnownDomain(WORLD, "district_4").orElseThrow().allowEntry(), "still the last known");
        assertTrue(map.getLastKnownDomain(WORLD, "district_5").orElseThrow().allowEntry(), "unknown before: the map");

        assertTrue(map.releaseLastKnown(generation));
        assertFalse(map.holding());
        assertTrue(map.getLastKnownDomain(WORLD, "district_4").orElseThrow().allowEntry(), "the new answer now");
    }

    @Test
    void aRegionThatLostItsDomainIsOpenOnlyOnceTheHoldEnds() {
        map.registerDomain(WORLD, district("district_4", false));
        map.clearRegionCache();
        long generation = map.holdGeneration();

        assertTrue(map.getLastKnownDomain(WORLD, "district_4").isPresent());
        map.releaseLastKnown(generation);
        assertEquals(Optional.empty(), map.getLastKnownDomain(WORLD, "district_4"));
    }

    @Test
    void aClearAfterTheReWarmStartedKeepsTheHoldForItsOwnReWarmAndTheFirstAnswersStayTheLastKnown() {
        map.registerDomain(WORLD, district("district_4", false));
        map.clearRegionCache(); // the cache refresh
        long first = map.holdGeneration();
        map.registerDomain(WORLD, district("district_4", true));
        map.clearRegionCache(); // a realtime change meanwhile
        long second = map.holdGeneration();

        assertFalse(map.releaseLastKnown(first), "the first re-warm does not end the second clear's hold");
        assertTrue(map.holding());
        assertFalse(map.getLastKnownDomain(WORLD, "district_4").orElseThrow().allowEntry(),
            "the answer from before the first clear");
        assertTrue(map.releaseLastKnown(second));
        assertFalse(map.holding());
    }

    @Test
    void aHoldNoReWarmReleasesEndsByItselfAfterAMinute() {
        AtomicLong now = new AtomicLong();
        map.holdClock(now::get);
        map.registerDomain(WORLD, district("district_4", false));
        map.clearRegionCache();

        now.addAndGet(RegionDomainResolver.LAST_KNOWN_HOLD.toNanos() - 1);
        assertTrue(map.holding());
        now.addAndGet(1);
        assertFalse(map.holding());
        assertEquals(Optional.empty(), map.getLastKnownDomain(WORLD, "district_4"));
    }

    @Test
    void withoutAClearTheNpcLookupIsTheMapsOwn() {
        assertFalse(map.holding());
        assertFalse(map.releaseLastKnown(map.holdGeneration()));
        map.registerDomain(WORLD, district("district_4", true));
        assertEquals(map.getDomainByRegionIdNoRefresh(WORLD, "district_4"), map.getLastKnownDomain(WORLD, "district_4"));
    }
}
