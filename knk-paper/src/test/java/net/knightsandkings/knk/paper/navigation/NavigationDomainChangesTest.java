package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainRegionQuery;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;

/**
 * PandiO/knk-workspace#533 and #593: entry to the Residential District removed in the web app with realtime on. The
 * wiring as {@code KnKPlugin} builds it: the router's own handler clears the region → domain map, then
 * {@link NavigationDomainChanges} asks for one {@link DomainCacheRewarm} over {@code refreshRegions}, which asks the
 * API about the roads' regions again and then sends road navigation's availability event (the one the NPCs and their
 * groups hear). The district's entry is covered by a gate structure region, as on the developer's server.
 */
class NavigationDomainChangesTest {

    private static final String WORLD = "world";
    private static final Set<String> ROADS = Set.of("town_1", "district_1000004", "gate_2000131");

    /** The API: the current rules, the gate's answer naming the district as its parent. */
    private static final class Api implements DomainsQueryApi {
        boolean districtEntry = true;
        final List<String> asked = new ArrayList<>();

        @Override
        public CompletableFuture<DomainRegionSummary> getByWorldGuardRegionId(String wgRegionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<HashMap<Integer, DomainRegionSummary>> searchDomainRegionDecisions(DomainRegionQuery query) {
            String region = query.wgRegionIds().iterator().next();
            asked.add(region);
            DomainRegionSummary town = new DomainRegionSummary(1, "Town", "", "town_1", true, true, "Town", List.of(), WORLD);
            DomainRegionSummary district = new DomainRegionSummary(1000004, "Residential District", "", "district_1000004",
                districtEntry, true, "District", List.of(town), WORLD);
            DomainRegionSummary gate = new DomainRegionSummary(2000131, "South Gate", "", "gate_2000131", true, true,
                "GateStructure", List.of(district), WORLD);
            HashMap<Integer, DomainRegionSummary> answer = new HashMap<>();
            switch (region) {
                case "town_1" -> answer.put(0, town);
                case "district_1000004" -> answer.put(0, district);
                default -> answer.put(0, gate);
            }
            return CompletableFuture.completedFuture(answer);
        }
    }

    private final Api api = new Api();
    private final RegionDomainResolver resolver = new RegionDomainResolver(null, null, null, api);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Coalescer coalescer = new Coalescer((delay, task) -> scheduled.add(task), java.time.Duration.ofMillis(500));
    private final EntityChangeRouter router = new EntityChangeRouter();
    private boolean npcNavigation = true;
    private int events;
    private NavigationDomainChanges changes;

    NavigationDomainChangesTest() {
        for (String type : NavigationDomainChanges.DOMAIN_TYPES) {
            router.on(type, (id, key, op) -> resolver.clearRegionCache()); // KnKPlugin's own handler, registered first
        }
        DomainCacheRewarm rewarm = new DomainCacheRewarm(() -> npcNavigation, () -> List.of(WORLD),
            world -> WORLD.equals(world) ? ROADS : Set.of(), resolver::refreshRegions, Runnable::run, () -> events++,
            Logger.getLogger("test"));
        changes = new NavigationDomainChanges(coalescer, rewarm).register(router);
        resolver.warmCache(WORLD, ROADS).join();
        api.asked.clear();
    }

    /** The coalescer's delay passes (the main thread runs what it scheduled). */
    private void delayPasses() {
        List<Runnable> tasks = List.copyOf(scheduled);
        scheduled.clear();
        tasks.forEach(Runnable::run);
    }

    private Boolean districtEntry() {
        return resolver.getDomainByRegionIdNoRefresh(WORLD, "district_1000004").orElseThrow().allowEntry();
    }

    @Test
    void aRealtimeDistrictChangeReWarmsTheRoadsRegionsAndThenTellsNavigation() {
        api.districtEntry = false;

        assertTrue(router.route("District", 1000004, null, "Updated"));
        assertTrue(resolver.getDomainByRegionIdNoRefresh(WORLD, "district_1000004").isEmpty(), "the map was cleared");
        assertEquals(0, events, "not before the coalescer's delay");
        delayPasses();

        assertEquals(Set.copyOf(ROADS), Set.copyOf(api.asked));
        assertFalse(districtEntry(), "the denial is in the map");
        assertTrue(resolver.getDomainByRegionIdNoRefresh(WORLD, "gate_2000131").orElseThrow().allowEntry());
        assertEquals(1, events, "then the availability event, once");
    }

    @Test
    void domainAccessChangedRefreshesTheMapInPlaceAndTellsNavigation() {
        api.districtEntry = false;
        int[] flagSyncs = {0};

        changes.withDomainAccess(() -> flagSyncs[0]++).run();
        assertTrue(districtEntry(), "the last known rule until the answer is in");
        delayPasses();

        assertEquals(1, flagSyncs[0], "the WorldGuard flag sync still runs");
        assertFalse(districtEntry());
        assertEquals(1, events);
    }

    @Test
    void oneSaveInTheWebAppGivesOneReWarm() {
        api.districtEntry = false;
        router.route("District", 1000004, null, "Updated");
        router.route("Structure", 2000131, null, "Updated");
        changes.withDomainAccess(null).run();
        delayPasses();

        assertEquals(ROADS.size(), api.asked.size(), "each road region asked once");
        assertEquals(1, events);
    }

    @Test
    void everyDomainTypeAsksForTheReWarm() {
        for (String type : List.of("Town", "District", "Structure", "DomainSubRegion")) {
            router.route(type, 1, null, "Updated");
            delayPasses();
        }

        assertEquals(4, events);
    }

    @Test
    void entryRestoredReachesTheMapTheSameWay() {
        api.districtEntry = false;
        router.route("District", 1000004, null, "Updated");
        delayPasses();
        api.districtEntry = true;
        router.route("District", 1000004, null, "Updated");
        delayPasses();

        assertTrue(districtEntry());
        assertEquals(2, events);
    }

    @Test
    void nothingIsAskedOrSentWhileNpcNavigationDoesNotRun() {
        npcNavigation = false;
        router.route("District", 1000004, null, "Updated");
        delayPasses();

        assertTrue(api.asked.isEmpty());
        assertEquals(0, events, "player routes are not changed by it");
    }
}
