package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.DomainEntryRule;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;

/**
 * PandiO/knk-workspace#593 (Phase 4 developer test step 10, #506): entry to a district is removed while a column walks
 * in, with the rules read the way the server reads them, from the region → domain map through {@link DomainEntryRule},
 * and with the district's entry covered by a gate structure region (the developer's {@code town_1},
 * {@code district_1000004} and {@code gate_2000131}). Each test drives one way a change reaches the plugin:
 * <ol>
 *   <li>the realtime path (PandiO/knk-workspace#533): {@code EntityChanged} for the District clears the map, the roads'
 *       regions are asked about again, then the availability event;</li>
 *   <li>the realtime {@code DomainAccessChanged}: the regions are asked about again in place, then the event;</li>
 *   <li>{@code /knk cache refresh}: the map is cleared, the first event comes with the map empty, then the re-warm and
 *       the second event (L2's {@code DomainCacheRewarm}).</li>
 * </ol>
 * Followers outside when the plugin has the new rules stop at the edge, the ones inside walk on, {@code trespasses 0},
 * and everybody follows once entry is allowed again.
 */
class NavGroupDomainRulesTest {

    private static final String TOWN = "town_1";
    private static final String DISTRICT = "district_1000004";
    private static final String GATE = "gate_2000131";
    /** The district starts at x = 50; the gate region covers its first blocks (both are entered at the same step). */
    private static final int EDGE = 50;
    /** Ticks the API takes to answer the re-warm (about half a second). */
    private static final int WARM_TICKS = 10;

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();
    private final RegionDomainResolver map = new RegionDomainResolver();
    /** What the API would answer now: the current rules. */
    private final Map<String, DomainSnapshot> api = new HashMap<>();

    private static DomainSnapshot domain(int id, String name, String region, boolean allowEntry, String type) {
        return new DomainSnapshot(id, name, "", region, allowEntry, true, type, Set.of(), Set.of(), Set.of(), Set.of());
    }

    private void setUp(boolean gateRegionKnown) {
        sim.world.regions = (x, z) -> {
            Set<String> at = new HashSet<>(Set.of(TOWN));
            if (x >= EDGE) {
                at.add(DISTRICT);
            }
            if (x >= EDGE && x < EDGE + 6) {
                at.add(GATE);
            }
            return at;
        };
        api.put(TOWN, domain(1, "Town", TOWN, true, "Town"));
        api.put(DISTRICT, domain(1000004, "Residential District", DISTRICT, true, "District"));
        if (gateRegionKnown) {
            api.put(GATE, domain(2000131, "South Gate", GATE, true, "GateStructure")); // allows entry, always
        }
        warm();
        DomainAccessEvaluator evaluator = new DomainAccessEvaluator();
        sim.rules.entry = subject -> DomainEntryRule.of(subject, evaluator, map::getDomainByRegionIdNoRefresh,
            RoadRule.ALWAYS);
    }

    /** The API's answers are in the map (a warm-up or an in-place refresh of the roads' regions). */
    private void warm() {
        api.values().forEach(d -> map.registerDomain(GroupSim.WORLD, d));
    }

    private void entry(boolean allowed) {
        api.put(DISTRICT, domain(1000004, "Residential District", DISTRICT, allowed, "District"));
    }

    /** The availability event, as road navigation's rule listener hands it to the NPCs and their groups. */
    private void event() {
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
    }

    private SimMover leader() {
        return sim.movers.get(0);
    }

    private enum Path { REALTIME_ENTITY_CHANGED, REALTIME_DOMAIN_ACCESS, CACHE_REFRESH }

    /** The web app's change reaching the plugin by {@code path}, with the API's answer {@link #WARM_TICKS} later. */
    private void change(Path path) {
        switch (path) {
            case REALTIME_ENTITY_CHANGED -> map.clearRegionCache();
            case REALTIME_DOMAIN_ACCESS -> { }
            case CACHE_REFRESH -> {
                map.clearRegionCache();
                event(); // "navigation routes": the map is empty now
            }
        }
        sim.run(WARM_TICKS);
        warm();
        event(); // after the re-warm
    }

    private void entryRemovedMidColumn(Path path, boolean gateRegionKnown) {
        setUp(gateRegionKnown);
        NavGroup group = sim.group(20, GroupSettings.defaults().withFormation(Formation.column(2.0)));
        group.navigate(feet(200.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > EDGE + 16, 3000));

        entry(false);
        change(path);
        List<SimMover> inside = sim.movers.stream().skip(1).filter(m -> m.position.x() >= EDGE).toList();
        List<SimMover> outside = sim.movers.stream().skip(1).filter(m -> m.position.x() < EDGE).toList();
        assertTrue(inside.size() >= 3 && outside.size() >= 5, "part of the column is inside: " + inside.size());
        Map<SimMover, Double> was = new HashMap<>();
        inside.forEach(m -> was.put(m, m.position.x()));
        for (int t = 0; t < 400; t++) {
            sim.tick();
            for (SimMover m : outside) {
                assertTrue(m.position.x() < EDGE, m.name() + " entered the district at " + m.position + " (" + path + ")");
            }
        }

        for (SimMover m : inside) {
            assertTrue(m.position.x() > was.get(m) + 20, m.name() + " was inside and walks on, at " + m.position);
        }
        for (SimMover m : outside) {
            assertFalse(m.isMoving(), m.name() + " stands at the edge");
        }
        double first = outside.stream().mapToDouble(m -> m.position.x()).max().orElseThrow();
        assertTrue(first > EDGE - 4, "the first of them waits at the edge, at x " + first + "; the rest queue behind");
        assertEquals(0, sim.metrics.total(Event.TRESPASS), "trespasses 0 with the followers counted");

        entry(true);
        change(path);
        assertTrue(sim.runUntil(results::ended, 4000));

        assertInstanceOf(NavigationResult.Arrived.class, results.last());
        for (SimMover m : outside) {
            assertTrue(m.position.x() > 150, m.name() + " followed once entry was allowed again, at " + m.position);
        }
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void entryRemovedThroughTheRealtimeEntityChangeStopsTheOutsideFollowersAtTheGate() {
        entryRemovedMidColumn(Path.REALTIME_ENTITY_CHANGED, true);
    }

    @Test
    void entryRemovedThroughTheRealtimeDomainAccessChangeStopsTheOutsideFollowersAtTheGate() {
        entryRemovedMidColumn(Path.REALTIME_DOMAIN_ACCESS, true);
    }

    @Test
    void entryRemovedThroughACacheRefreshStopsTheOutsideFollowersAtTheGate() {
        entryRemovedMidColumn(Path.CACHE_REFRESH, true);
    }

    @Test
    void aGateRegionWithoutAKnownDomainDoesNotHideTheDistrictsDenial() {
        entryRemovedMidColumn(Path.REALTIME_ENTITY_CHANGED, false);
    }

    @Test
    void aStepIntoTheDistrictAndTheGateRegionAtOnceIsJudgedPerRegion() {
        setUp(true);
        entry(false);
        warm();
        DomainAccessEvaluator evaluator = new DomainAccessEvaluator();
        var outside = new net.knightsandkings.knk.core.npc.nav.NpcAccessSubject("npc", net.knightsandkings.knk.core.npc.nav.NpcAccessProfile.DEFAULT, GroupSim.WORLD, Set.of(TOWN));
        var rule = DomainEntryRule.of(outside, evaluator, map::getDomainByRegionIdNoRefresh, RoadRule.ALWAYS);

        assertTrue(rule.test(GATE), "the gate structure allows entry");
        assertFalse(rule.test(DISTRICT), "the district does not, whatever the gate region says");
        var inGate = new net.knightsandkings.knk.core.npc.nav.NpcAccessSubject("npc", net.knightsandkings.knk.core.npc.nav.NpcAccessProfile.DEFAULT, GroupSim.WORLD,
            Set.of(TOWN, GATE));
        assertFalse(DomainEntryRule.of(inGate, evaluator, map::getDomainByRegionIdNoRefresh, RoadRule.ALWAYS)
            .test(DISTRICT), "standing in the gate region is not standing in the district");
    }
}
