package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.DomainEntryRule;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;

/**
 * PandiO/knk-workspace#724: {@code /knk cache refresh} while NPCs are held at a district edge. The refresh clears the
 * region → domain map ("region domains"), road navigation sends its availability event at once ("navigation routes"),
 * and L2's re-warm sends a second event once the API's answers are in, {@link #WARM_TICKS} later. NPCs held at the edge
 * (followers by their lane check, a single NPC by its blocking element) must stay held through it, for entry and for
 * exit alike: the rules are read as on a server, from the map through {@link DomainEntryRule}. A crossing that still
 * happens in that gap (an NPC carried over the edge) is counted by the trespass guard.
 */
class NavGroupCacheRefreshTest {

    private static final String TOWN = "town_1";
    private static final String DISTRICT = "district_1000004";
    /** The district's east edge for the exit tests, its west edge for the entry tests. */
    private static final int EDGE = 50;
    /** Ticks the API takes to answer the re-warm (about half a second). */
    private static final int WARM_TICKS = 10;
    private static final GroupSettings COLUMN = GroupSettings.defaults().withFormation(Formation.column(2.0));

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();
    private final RegionDomainResolver map = new RegionDomainResolver();
    /** What the API would answer now: the current rules. */
    private final Map<String, DomainSnapshot> api = new HashMap<>();
    private final DomainAccessEvaluator evaluator = new DomainAccessEvaluator();
    /** The district lies east of the edge (entry tests) or west of it (exit tests). */
    private boolean exitCase;

    private static DomainSnapshot district(boolean allowEntry, boolean allowExit) {
        return new DomainSnapshot(1000004, "Residential District", "", DISTRICT, allowEntry, allowExit, "District",
            Set.of(), Set.of(), Set.of(), Set.of());
    }

    private boolean inDistrict(double x) {
        return exitCase ? x >= -30 && x < EDGE : x >= EDGE;
    }

    private void setUp(boolean exit) {
        this.exitCase = exit;
        sim.world.regions = (x, z) -> {
            Set<String> at = new HashSet<>(Set.of(TOWN));
            if (inDistrict(x)) {
                at.add(DISTRICT);
            }
            return at;
        };
        api.put(TOWN, new DomainSnapshot(1, "Town", "", TOWN, true, true, "Town", Set.of(), Set.of(), Set.of(),
            Set.of()));
        api.put(DISTRICT, district(true, true));
        warm();
        sim.rules.entry = subject -> DomainEntryRule.of(subject, evaluator, map::getLastKnownDomain,
            RoadRule.ALWAYS);
        // the router: BLOCKED with the line (the NPC side cuts it at the edge) while the district's rule stops it
        sim.road = (subject, from, to) -> blocked(subject)
            ? RoadPlan.blocked(List.of(from, to), "the district's rule", new BlockingElement(
                BlockingElement.Kind.DOMAIN, "1000004", "Residential District", Set.of(), Set.of(DISTRICT)), List.of(),
                this::blocked)
            : RoadPlan.found(List.of(from, to));
    }

    /** Whether the district's rule stops this subject's trip east, read from the map as the router would. */
    private boolean blocked(AccessSubject subject) {
        var rule = sim.rules.regionRule(subject);
        return exitCase ? rule.exitDenied().contains(DISTRICT) : !rule.test(DISTRICT);
    }

    /** The API's answers are in the map. */
    private void warm() {
        api.values().forEach(d -> map.registerDomain(GroupSim.WORLD, d));
    }

    /** The availability event, as road navigation's rule listener hands it to the NPCs and their groups. */
    private void event() {
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
    }

    /** The district's rule changes and the plugin hears of it (a warm map, then the event). */
    private void deny() {
        api.put(DISTRICT, exitCase ? district(true, false) : district(false, true));
        warm();
        event();
        assertFalse(map.holding());
    }

    /**
     * {@code /knk cache refresh}, as the plugin runs it: the map is cleared, the first event comes at once, the API
     * answers {@link #WARM_TICKS} later and the re-warm sends the second event. {@code lastGapTick} runs before the
     * gap's last tick.
     */
    private void refresh(Runnable lastGapTick) {
        map.clearRegionCache(); // "region domains"
        event(); // "navigation routes"
        long generation = map.holdGeneration(); // DomainCacheRewarm starts
        sim.run(WARM_TICKS - 1);
        lastGapTick.run();
        sim.run(1);
        warm(); // the re-warm's answers
        rewarmDone(generation);
    }

    /** {@code DomainCacheRewarm} once its answers are in: the guards look, the hold ends, then the event. */
    private void rewarmDone(long generation) {
        sim.service.lookBeforeRulesChange();
        sim.groups.lookBeforeRulesChange();
        assertTrue(map.releaseLastKnown(generation));
        event();
    }

    private String tail() {
        return sim.log.subList(Math.max(0, sim.log.size() - 10), sim.log.size()).toString();
    }

    /** A group walking east; the district's rule changes with part of it on each side; returns the held followers. */
    private List<SimMover> groupHeldAtTheEdge(boolean exit) {
        setUp(exit);
        sim.group(12, COLUMN).navigate(feet(200.5, 0.5), results);
        assertTrue(sim.runUntil(() -> sim.movers.get(0).position.x() > EDGE + 10, 3000));
        deny();
        sim.run(200);
        // entry: the ones still outside (west of the edge); exit: the ones still inside (also west of it)
        List<SimMover> held = sim.movers.stream().skip(1).filter(m -> m.position.x() < EDGE).toList();
        assertTrue(held.size() >= 5, "followers held at the edge: " + held.size());
        for (SimMover m : held) {
            assertFalse(m.isMoving(), m.name() + " stands at the edge, at " + m.position);
        }
        assertEquals(0, sim.metrics.total(Event.TRESPASS), tail());
        return held;
    }

    private void heldThroughTheRefresh(List<SimMover> held, String verb) {
        refresh(() -> { });
        for (int t = 0; t < 400; t++) {
            sim.tick();
            for (SimMover m : held) {
                assertTrue(m.position.x() < EDGE, m.name() + " " + verb + " the district after the refresh, at "
                    + m.position + ": " + tail());
            }
        }
        assertEquals(0, sim.metrics.total(Event.TRESPASS), tail());
    }

    @Test
    void followersHeldOutsideADistrictTheyMayNotEnterStayHeldThroughACacheRefresh() {
        heldThroughTheRefresh(groupHeldAtTheEdge(false), "entered");
    }

    @Test
    void followersHeldInsideADistrictTheyMayNotLeaveStayHeldThroughACacheRefresh() {
        heldThroughTheRefresh(groupHeldAtTheEdge(true), "left");
    }

    private SimMover npcHeldAtTheEdge(boolean exit, List<NavigationResult> got) {
        setUp(exit);
        SimMover npc = sim.spawn(feet(exit ? 0.5 : 10.5, 0.5));
        sim.service.navigate(npc.id(), feet(200.5, 0.5), got::add);
        assertTrue(sim.runUntil(() -> npc.position.x() > EDGE - 20, 2000));
        deny();
        sim.run(300);
        assertTrue(npc.position.x() < EDGE && npc.position.x() > EDGE - 4, "waits at the edge, at " + npc.position);
        assertFalse(npc.isMoving(), "stands still");
        assertTrue(got.stream().anyMatch(r -> r instanceof NavigationResult.Blocked), "reported blocked: " + got);
        return npc;
    }

    @Test
    void anNpcHeldAtADistrictItMayNotEnterStaysHeldThroughACacheRefresh() {
        List<NavigationResult> got = new ArrayList<>();
        heldThroughTheRefresh(List.of(npcHeldAtTheEdge(false, got)), "entered");
    }

    @Test
    void anNpcHeldInADistrictItMayNotLeaveStaysHeldThroughACacheRefresh() {
        List<NavigationResult> got = new ArrayList<>();
        heldThroughTheRefresh(List.of(npcHeldAtTheEdge(true, got)), "left");
    }

    @Test
    void theTrespassGuardCountsAFollowerCarriedIntoTheDistrictWhileTheMapIsBeingWarmedAgain() {
        List<SimMover> held = groupHeldAtTheEdge(false);
        SimMover first = held.stream().max((a, b) -> Double.compare(a.position.x(), b.position.x())).orElseThrow();

        refresh(() -> first.position = feet(EDGE + 1.5, 0.5)); // carried over the edge by water or a push
        sim.run(100);

        assertEquals(1, sim.metrics.total(Event.TRESPASS), "log: " + tail());
        assertTrue(sim.log.stream().anyMatch(l -> l.startsWith("TRESPASS " + first.name() + " entered " + DISTRICT)),
            "log: " + tail());
    }

    @Test
    void theTrespassGuardCountsAnNpcCarriedOutOfTheDistrictWhileTheMapIsBeingWarmedAgain() {
        List<NavigationResult> got = new ArrayList<>();
        SimMover npc = npcHeldAtTheEdge(true, got);

        refresh(() -> npc.position = feet(EDGE + 1.5, 0.5));
        sim.run(100);

        assertEquals(1, sim.metrics.total(Event.TRESPASS), "log: " + tail());
        assertTrue(sim.log.stream().anyMatch(l -> l.startsWith("TRESPASS " + npc.name() + " left " + DISTRICT)),
            "log: " + tail());
    }
}
