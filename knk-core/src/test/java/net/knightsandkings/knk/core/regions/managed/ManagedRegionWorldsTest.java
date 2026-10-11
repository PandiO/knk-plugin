package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

/**
 * KNG-112: a hub world and a gameplay world each have a region {@code town_1}, backing two different towns. The repair
 * reads and changes each world's own region, and a parent is looked up in its child's world.
 */
class ManagedRegionWorldsTest {

    /** One {@link FakeWorldGuard} per world, as WorldGuard has one region manager per world. */
    private static class Worlds implements ManagedRegionStore {
        final Map<String, FakeWorldGuard> byWorld = new LinkedHashMap<>();

        FakeWorldGuard world(String name) {
            return byWorld.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new FakeWorldGuard());
        }

        @Override
        public Optional<ActualRegionState> read(String regionId) {
            return byWorld.values().stream().map(wg -> wg.read(regionId)).flatMap(Optional::stream).findFirst();
        }

        @Override
        public Optional<ActualRegionState> read(String worldName, String regionId) {
            if (worldName == null) {
                return read(regionId);
            }
            FakeWorldGuard wg = byWorld.get(worldName.toLowerCase(Locale.ROOT));
            return wg == null ? Optional.empty() : wg.read(regionId);
        }

        @Override
        public boolean supportsFlag(String flagName) {
            return true;
        }

        @Override
        public void apply(String regionId, RegionChange change) {
            throw new AssertionError("a region of a known world is changed in that world");
        }

        @Override
        public void apply(String worldName, String regionId, RegionChange change) throws RegionStoreException {
            world(worldName).apply(regionId, change);
        }

        @Override
        public void persist() {
        }
    }

    @Test
    void eachWorldsRegionIsRepairedInItsOwnWorld() {
        Worlds worlds = new Worlds();
        worlds.world("hub").add("town_1");
        worlds.world("hub").add("district_2");
        worlds.world("gameplay").add("town_1");
        worlds.world("gameplay").add("district_2").parent = null;

        DomainRegionSpecs.Result specs = DomainRegionSpecs.build(
                List.of(new TownSummary(1, "Hubtown", "", "town_1", "hub"),
                        new TownSummary(5, "Oakhaven", "", "town_1", "gameplay")),
                List.of(new DistrictSummary(2, "Square", "", "district_2", 1, "Hubtown")),
                List.of(), Map.of());
        RepairReport report = new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of())
                .reconcile(specs.specs(), worlds);

        assertEquals(0, report.count(RepairReport.Outcome.SKIPPED), "two worlds' town_1 are two regions, not a conflict");
        assertEquals("town_1", worlds.world("hub").get("district_2").parent, "the hub district's parent is the hub town");
        assertNull(worlds.world("gameplay").get("district_2").parent, "the gameplay world's district_2 is not a domain");
        assertTrue(worlds.world("gameplay").get("town_1").priority > 0, "the gameplay town is repaired too");
        assertTrue(report.entries().stream().anyMatch(e -> e.regionId().equals("town_1 (gameplay)")));
    }

    @Test
    void aDomainWithoutAWorldTakesItsParentsWorld() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(
                List.of(new TownSummary(1, "Hubtown", "", "town_1", "hub")),
                List.of(new DistrictSummary(2, "Square", "", "district_2", 1, "Hubtown")),
                List.of(new StructureSummary(3, "Gate", "", "domain_3", 1, 1, "Street", 2, "Square"),
                        new StructureSummary(4, "Mine", "", "domain_4", 1, 1, "Street", 2, "Square", "mining")),
                Map.of());

        assertEquals(List.of("hub", "hub", "hub", "mining"),
                result.specs().stream().map(ManagedRegionSpec::worldName).toList());
    }

    @Test
    void aSpecWithoutAWorldStillFindsItsRegionInAnyWorld() {
        Worlds worlds = new Worlds() {
            @Override
            public void apply(String worldName, String regionId, RegionChange change) throws RegionStoreException {
                world(worldName == null ? "gameplay" : worldName).apply(regionId, change);
            }
        };
        worlds.world("gameplay").add("town_9");

        RepairReport report = new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of())
                .reconcile(List.of(new ManagedRegionSpec("town_9", ManagedRegionKind.TOWN, null, "Town#9")), worlds);

        assertEquals(1, report.count(RepairReport.Outcome.CHANGED));
        assertEquals("town_9", report.entries().get(0).regionId(), "no world: logged by id alone, as before");
    }
}
