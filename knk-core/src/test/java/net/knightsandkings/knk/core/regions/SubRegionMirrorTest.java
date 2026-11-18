package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.regions.SubRegionMirror.DomainRegions;
import net.knightsandkings.knk.core.regions.SubRegionMirror.RegionState;
import net.knightsandkings.knk.core.regions.SubRegionMirror.Report;

/**
 * KNG-255: a domain's sub-regions are copies of its main region - priority, all flags, owners, members - with the main
 * region as parent; only their own bookkeeping flags stay.
 */
class SubRegionMirrorTest {

    /** In-memory WorldGuard: world/region (lower-case) -> state. Writes keep the region's own flags, as WorldGuard's store does. */
    private static final class FakeStore implements SubRegionMirror.Store {
        final Map<String, RegionState> regions = new LinkedHashMap<>();
        int writes;
        int saves;

        FakeStore region(String world, String id, RegionState state) {
            regions.put(key(world, id), state);
            return this;
        }

        RegionState get(String world, String id) {
            return regions.get(key(world, id));
        }

        @Override
        public Optional<RegionState> read(String world, String regionId) {
            return Optional.ofNullable(regions.get(key(world, regionId)));
        }

        @Override
        public void write(String world, String regionId, RegionState desired) {
            RegionState current = regions.get(key(world, regionId));
            Map<String, Object> flags = new HashMap<>(desired.flags());
            current.flags().forEach((name, value) -> {
                if (SubRegionMirror.OWN_FLAGS.contains(name)) {
                    flags.put(name, value);
                }
            });
            regions.put(key(world, regionId), new RegionState(desired.priority(), desired.parentId(), flags,
                desired.owners(), desired.members()));
            writes++;
        }

        @Override
        public void persist() {
            saves++;
        }

        private static String key(String world, String id) {
            return (world == null ? "" : world) + "/" + id.toLowerCase(Locale.ROOT);
        }
    }

    private final SubRegionMirror mirror = new SubRegionMirror();

    private static final RegionState HOUSE = new RegionState(30, "domain_10",
        Map.of("pvp", "ALLOW", "greeting", "Welcome", "knk-allow-exit", "DENY", "knk-creation-timestamp", "1700000000000"),
        Set.of("uuid:11111111-1111-1111-1111-111111111111"), Set.of("name:steve"));

    private static DomainRegions garrison(String... subs) {
        return new DomainRegions("GateStructure#11 'Garrison'", "world", "domain_11", List.of(subs));
    }

    @Test
    void aSubRegionBecomesACopyOfTheMainRegion_KeepingItsOwnTimestamp() {
        FakeStore store = new FakeStore()
            .region("world", "domain_11", HOUSE)
            .region("world", "domain_11_sub_4", new RegionState(0, null,
                Map.of("greeting", "Tower", "build", "DENY", "knk-creation-timestamp", "1800000000000"), Set.of(), Set.of()));

        Report report = mirror.run(List.of(garrison("domain_11_sub_4")), store);

        RegionState tower = store.get("world", "domain_11_sub_4");
        assertEquals(30, tower.priority());
        assertEquals("domain_11", tower.parentId());
        assertEquals(Map.of("pvp", "ALLOW", "greeting", "Welcome", "knk-allow-exit", "DENY",
            "knk-creation-timestamp", "1800000000000"), tower.flags());
        assertEquals(HOUSE.owners(), tower.owners());
        assertEquals(HOUSE.members(), tower.members());
        assertEquals(1, report.updated());
        assertEquals(1, store.saves);
    }

    @Test
    void aSecondRunChangesNothingAndDoesNotSave() {
        FakeStore store = new FakeStore()
            .region("world", "domain_11", HOUSE)
            .region("world", "domain_11_sub_4", new RegionState(0, null, Map.of(), Set.of(), Set.of()));
        mirror.run(List.of(garrison("domain_11_sub_4")), store);

        Report second = mirror.run(List.of(garrison("domain_11_sub_4")), store);

        assertEquals(0, second.updated());
        assertEquals(1, second.unchanged());
        assertEquals(1, store.saves);
        assertEquals(1, store.writes);
    }

    @Test
    void aMissingMainRegionLeavesTheSubRegionsAlone_AndAMissingSubRegionIsReported() {
        RegionState tower = new RegionState(5, null, Map.of("greeting", "Tower"), Set.of(), Set.of());
        FakeStore store = new FakeStore().region("world", "domain_11_sub_4", tower);

        Report noMain = mirror.run(List.of(garrison("domain_11_sub_4")), store);
        assertEquals(List.of("domain_11 (world)"), noMain.missingMainRegions());
        assertEquals(tower, store.get("world", "domain_11_sub_4"));

        store.region("world", "domain_11", HOUSE);
        Report noSub = mirror.run(List.of(garrison("domain_11_sub_4", "domain_11_sub_5")), store);
        assertEquals(List.of("domain_11_sub_5 (world)"), noSub.missingSubRegions());
        assertEquals(1, noSub.updated());
    }

    @Test
    void anAdminsFlagOnTheMainRegionReachesTheSubRegions_AndOneOnASubRegionIsReplaced() {
        FakeStore store = new FakeStore()
            .region("world", "domain_11", HOUSE)
            .region("world", "domain_11_sub_4", new RegionState(0, null, Map.of(), Set.of(), Set.of()));
        mirror.run(List.of(garrison("domain_11_sub_4")), store);

        Map<String, Object> mainFlags = new HashMap<>(HOUSE.flags());
        mainFlags.put("mob-spawning", "DENY");
        store.region("world", "domain_11", new RegionState(30, "domain_10", mainFlags, HOUSE.owners(), HOUSE.members()));
        RegionState tower = store.get("world", "domain_11_sub_4");
        Map<String, Object> towerFlags = new HashMap<>(tower.flags());
        towerFlags.put("pvp", "DENY");
        store.region("world", "domain_11_sub_4", new RegionState(30, "domain_11", towerFlags, tower.owners(), tower.members()));

        mirror.run(List.of(garrison("domain_11_sub_4")), store);

        assertEquals("DENY", store.get("world", "domain_11_sub_4").flags().get("mob-spawning"));
        assertEquals("ALLOW", store.get("world", "domain_11_sub_4").flags().get("pvp"));
    }

    @Test
    void theDomainsOfTheAccessRulesThatHaveSubRegions() {
        List<DomainRegions> domains = DomainRegions.of(List.of(
            new DomainAccessRule(11, "Garrison", "domain_11", true, false, "GateStructure", "world", List.of("domain_11_sub_4")),
            new DomainAccessRule(3, "Oakhaven", "domain_3", true, true, "Town", "world")));

        assertEquals(List.of(new DomainRegions("GateStructure#11 'Garrison'", "world", "domain_11", List.of("domain_11_sub_4"))),
            domains);
    }

    @Test
    void subRegionNames() {
        assertTrue(SubRegionMirror.isSubRegionName("domain_11_sub_4"));
        assertTrue(SubRegionMirror.isSubRegionName("DOMAIN_11_SUB_4"));
        assertFalse(SubRegionMirror.isSubRegionName("domain_11"));
        assertFalse(SubRegionMirror.isSubRegionName("tempregion_worldtask_4"));
        assertFalse(SubRegionMirror.isSubRegionName(null));
    }
}
