package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.towns.TownSummary;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;

/**
 * A13 (landmark-domain DESIGN LD13, PandiO/knk-workspace#985): a landmark's own region is a LANDMARK managed region:
 * WorldGuard parent = the parent domain's main region, priority parent + 10 (floor as a structure), no category flags,
 * and the startup repair restores it. A point or a LinkedDomain landmark owns no region and is left out.
 */
class LandmarkRegionKindTest {

    private static KnkLandmark landmark(int id, LandmarkAreaKind kind, String own, String parentRegion) {
        return new KnkLandmark(id, "L" + id, 1, "world", 0, 64, 0, 0f, 0f, parentRegion == null ? null : 1, null, null,
                parentRegion, kind, own, List.of(), false, 1.0, true);
    }

    @Test
    void theLandmarkTypeIsItsOwnKind_AtTheStructureFloor_WithoutFlags() {
        assertEquals(ManagedRegionKind.LANDMARK, ManagedRegionKind.fromDomainType("Landmark").orElseThrow());
        assertEquals(ManagedRegionKind.LANDMARK, ManagedRegionKind.fromDomainType("landmark", true).orElseThrow());
        assertEquals(ManagedRegionKind.LANDMARK, ManagedRegionKind.parse("LANDMARK").orElseThrow());
        assertEquals(30, ManagedRegionKind.LANDMARK.floorPriority());
        assertFalse(ManagedRegionKind.LANDMARK.isStructureTier(), "its parent need not be a district");
        ManagedRegionPolicy policy = new ManagedRegionPolicy();
        assertTrue(policy.flagRules(ManagedRegionKind.LANDMARK).isEmpty());
        assertEquals(30, policy.priorityFor(ManagedRegionKind.LANDMARK, 10), "a town's landmark: 10 + 10, held at the floor 30");
        assertEquals(40, policy.priorityFor(ManagedRegionKind.LANDMARK, 30), "a structure's landmark: 30 + 10");
        assertEquals(30, policy.priorityFor(ManagedRegionKind.LANDMARK, null), "top level: the floor");
    }

    @Test
    void ownRegionsBecomeSpecs_WithTheParentsMainRegion_OthersAreLeftOut() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.landmarks(List.of(
                landmark(7, LandmarkAreaKind.OWN_REGION, "domain_7", "town_1"),
                landmark(8, LandmarkAreaKind.OWN_REGION, "domain_8", null),
                landmark(9, LandmarkAreaKind.NONE, null, "town_1"),
                landmark(10, LandmarkAreaKind.LINKED_DOMAIN, null, "town_1"),
                landmark(11, LandmarkAreaKind.OWN_REGION, null, null)));

        assertEquals(List.of("domain_7", "domain_8"), result.specs().stream().map(ManagedRegionSpec::regionId).toList());
        ManagedRegionSpec inTown = result.specs().get(0);
        assertEquals(ManagedRegionKind.LANDMARK, inTown.kind());
        assertEquals("town_1", inTown.parentRegionId());
        assertEquals("world", inTown.worldName());
        assertNull(result.specs().get(1).parentRegionId(), "top level");
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).label().contains("Landmark#11"));
    }

    @Test
    void theRepairGivesALandmarkItsParentAndPriority_AndRestoresThem() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("town_1");
        wg.add("district_1");
        wg.add("domain_7");
        wg.add("domain_8").flags.put("pvp", "ALLOW");
        List<ManagedRegionSpec> specs = new java.util.ArrayList<>(List.of(
                new ManagedRegionSpec("town_1", ManagedRegionKind.TOWN, null, "town"),
                new ManagedRegionSpec("district_1", ManagedRegionKind.DISTRICT, "town_1", "district")));
        specs.addAll(DomainRegionSpecs.landmarks(List.of(
                landmark(7, LandmarkAreaKind.OWN_REGION, "domain_7", "district_1"),
                landmark(8, LandmarkAreaKind.OWN_REGION, "domain_8", "domain_7"))).specs());
        ManagedRegionReconciler reconciler = new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of());

        reconciler.reconcile(specs, wg);

        assertEquals("district_1", wg.get("domain_7").parent);
        assertEquals(30, wg.get("domain_7").priority, "district 20 + 10");
        assertEquals("domain_7", wg.get("domain_8").parent, "a landmark inside a landmark (LD23)");
        assertEquals(40, wg.get("domain_8").priority, "its parent landmark + 10");
        assertEquals("ALLOW", wg.get("domain_8").flags.get("pvp"), "no category flags: an admin's flag stays");

        wg.get("domain_7").parent = null;
        wg.get("domain_7").priority = 0;
        reconciler.reconcile(specs, wg);
        assertEquals("district_1", wg.get("domain_7").parent, "restored by the repair");
        assertEquals(30, wg.get("domain_7").priority);
    }

    @Test
    void theSpecSourceReadsTheLandmarks_AndAFailedReadIsOnlyAWarning() {
        List<KnkLandmark> rows = List.of(landmark(7, LandmarkAreaKind.OWN_REGION, "domain_7", "town_1"));
        DomainRegionSpecs.Result loaded = source(CompletableFuture.completedFuture(rows)).load().join();
        assertTrue(loaded.specs().stream().anyMatch(s -> s.regionId().equals("domain_7")
                && s.kind() == ManagedRegionKind.LANDMARK && "town_1".equals(s.parentRegionId())));
        assertTrue(loaded.specs().stream().anyMatch(s -> s.regionId().equals("town_1")));

        DomainRegionSpecs.Result failed = source(CompletableFuture.failedFuture(new RuntimeException("down"))).load().join();
        assertEquals(List.of("town_1"), failed.specs().stream().map(ManagedRegionSpec::regionId).toList(),
                "the rest of the hierarchy is still repaired");
        assertTrue(failed.warnings().stream().anyMatch(w -> w.startsWith("landmarks unavailable")), failed.warnings().toString());
    }

    private static <T> CompletableFuture<Page<T>> page(List<T> items) {
        return CompletableFuture.completedFuture(new Page<>(items, items.size(), 1, 100));
    }

    private static ManagedRegionSpecSource source(CompletableFuture<List<KnkLandmark>> landmarks) {
        TownsQueryApi towns = new TownsQueryApi() {
            public CompletableFuture<Page<TownSummary>> search(PagedQuery q) {
                return page(List.of(new TownSummary(1, "Rivia", "", "town_1")));
            }

            public CompletableFuture<TownDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        DistrictsQueryApi districts = new DistrictsQueryApi() {
            public CompletableFuture<Page<DistrictSummary>> search(PagedQuery q) {
                return page(List.of());
            }

            public CompletableFuture<DistrictDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        StructuresQueryApi structures = new StructuresQueryApi() {
            public CompletableFuture<Page<StructureSummary>> search(PagedQuery q) {
                return page(List.of());
            }

            public CompletableFuture<StructureDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        LandmarksApi api = new LandmarksApi() {
            public CompletableFuture<List<KnkLandmark>> list(String world) {
                return landmarks;
            }

            public CompletableFuture<Optional<KnkLandmark>> get(int id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            public CompletableFuture<KnkLandmark> create(LandmarkWrite landmark) {
                throw new UnsupportedOperationException();
            }

            public CompletableFuture<KnkLandmark> update(int id, LandmarkWrite landmark) {
                throw new UnsupportedOperationException();
            }

            public CompletableFuture<Void> delete(int id) {
                throw new UnsupportedOperationException();
            }
        };
        return new ManagedRegionSpecSource(towns, districts, structures, null, 100, false, api);
    }
}
