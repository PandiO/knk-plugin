package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.DomainEntryRule;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.DomainAvailability.RoadRule;

/**
 * PandiO/knk-workspace#701: a domain's exit rule holds while NPCs walk, not only where a route is planned. The rules
 * are read as on a server, from a region → domain map through {@link DomainEntryRule}; the road answers as the router
 * does for an NPC standing in a region it may not leave (BLOCKED, the route as far as the rules allow). Exit is denied
 * mid-trip: a single NPC walking out stops at the edge, inside; in a group half inside, the followers inside stop at
 * the edge and the ones outside walk on with the leader. Once leaving is allowed again they walk on; {@code trespasses
 * 0}.
 */
class NavGroupExitRulesTest {

    private static final String TOWN = "town_1";
    private static final String OLD_TOWN = "district_old_town";
    /** The district ends at x = 50 (inside: -30 ≤ x < 50); the trips go east, out of it. */
    private static final int EDGE = 50;
    private static final GroupSettings COLUMN = GroupSettings.defaults().withFormation(Formation.column(2.0));

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();
    private final Map<String, DomainSnapshot> domains = new HashMap<>();
    private final DomainAccessEvaluator evaluator = new DomainAccessEvaluator();

    private static DomainSnapshot domain(int id, String name, String region, boolean allowExit, String type) {
        return new DomainSnapshot(id, name, "", region, true, allowExit, type, Set.of(), Set.of(), Set.of(), Set.of());
    }

    @BeforeEach
    void setUp() {
        sim.world.regions = (x, z) -> {
            Set<String> at = new HashSet<>(Set.of(TOWN));
            if (x >= -30 && x < EDGE) {
                at.add(OLD_TOWN);
            }
            return at;
        };
        domains.put(TOWN, domain(1, "Town", TOWN, true, "Town"));
        exit(true);
        sim.rules.entry = subject -> DomainEntryRule.of(subject, evaluator,
            (world, region) -> Optional.ofNullable(domains.get(region)), RoadRule.ALWAYS);
        // the router: from inside a region it may not leave, BLOCKED with the line (the NPC side cuts it at the edge)
        sim.road = (subject, from, to) -> mayNotLeave(subject)
            ? RoadPlan.blocked(List.of(from, to), "you may not leave Old Town", new BlockingElement(
                BlockingElement.Kind.DOMAIN, "7", "Old Town", Set.of(), Set.of(TOWN)), List.of(), this::mayNotLeave)
            : RoadPlan.found(List.of(from, to));
    }

    private boolean mayNotLeave(AccessSubject subject) {
        return sim.rules.regionRule(subject).exitDenied().contains(OLD_TOWN);
    }

    private void exit(boolean allowed) {
        domains.put(OLD_TOWN, domain(7, "Old Town", OLD_TOWN, allowed, "District"));
    }

    /** The availability event, as road navigation's rule listener hands it to the NPCs and their groups. */
    private void event() {
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
    }

    private String tail() {
        return sim.log.subList(Math.max(0, sim.log.size() - 10), sim.log.size()).toString();
    }

    @Test
    void anNpcWalkingOutStopsAtTheEdgeInsideAndWalksOnWhenLeavingIsAllowedAgain() {
        SimMover npc = sim.spawn(feet(0.5, 0.5));
        List<NavigationResult> got = new ArrayList<>();
        sim.service.navigate(npc.id(), feet(200.5, 0.5), got::add);
        assertTrue(sim.runUntil(() -> npc.position.x() > 30, 2000));

        exit(false);
        event();
        for (int t = 0; t < 400; t++) {
            sim.tick();
            assertTrue(npc.position.x() < EDGE, "left Old Town at " + npc.position + ": " + tail());
        }

        assertTrue(npc.position.x() > EDGE - 3, "waits at the edge, at " + npc.position);
        assertFalse(npc.isMoving(), "stands still");
        assertTrue(got.stream().anyMatch(r -> r instanceof NavigationResult.Blocked), "reported blocked: " + got);
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("closed by exit " + OLD_TOWN)), "log: " + tail());
        assertEquals(0, sim.metrics.total(Event.TRESPASS));

        exit(true);
        event();
        assertTrue(sim.runUntil(() -> got.stream().anyMatch(r -> r instanceof NavigationResult.Arrived), 3000),
            "arrives once leaving is allowed: " + got + " " + tail());
        assertTrue(npc.position.x() > 190);
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void aGroupHalfInsideKeepsTheInsideFollowersAtTheEdgeWhileTheRestWalksOn() {
        NavGroup group = sim.group(16, COLUMN);
        group.navigate(feet(200.5, 0.5), results);
        assertTrue(sim.runUntil(() -> sim.movers.get(0).position.x() > EDGE + 12, 3000));

        exit(false);
        event();
        List<SimMover> inside = sim.movers.stream().skip(1).filter(m -> m.position.x() < EDGE).toList();
        List<SimMover> outside = sim.movers.stream().skip(1).filter(m -> m.position.x() >= EDGE).toList();
        assertTrue(inside.size() >= 4 && outside.size() >= 3, "half inside: " + inside.size() + " inside, "
            + outside.size() + " outside");
        Map<SimMover, Double> was = new HashMap<>();
        outside.forEach(m -> was.put(m, m.position.x()));
        for (int t = 0; t < 400; t++) {
            sim.tick();
            for (SimMover m : inside) {
                assertTrue(m.position.x() < EDGE, m.name() + " left Old Town at " + m.position + ": " + tail());
            }
        }

        for (SimMover m : outside) {
            assertTrue(m.position.x() > was.get(m) + 20, m.name() + " was outside and walks on, at " + m.position);
        }
        for (SimMover m : inside) {
            assertFalse(m.isMoving(), m.name() + " stands at the edge");
        }
        double first = inside.stream().mapToDouble(m -> m.position.x()).max().orElseThrow();
        assertTrue(first > EDGE - 4, "the first of them waits at the edge, at x " + first + "; the rest queue behind");
        assertEquals(0, sim.metrics.total(Event.TRESPASS), "trespasses 0: " + tail());

        exit(true);
        event();
        assertTrue(sim.runUntil(results::ended, 4000), tail());

        assertInstanceOf(NavigationResult.Arrived.class, results.last());
        for (SimMover m : inside) {
            assertTrue(m.position.x() > 150, m.name() + " followed once leaving was allowed again, at " + m.position);
        }
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void theTrespassGuardCountsAFollowerCarriedOutOfARegionItMayNotLeave() {
        sim.group(8, COLUMN).navigate(feet(200.5, 0.5), results);
        assertTrue(sim.runUntil(() -> sim.movers.get(0).position.x() > EDGE + 6, 3000));
        exit(false);
        event();
        sim.run(40);
        assertEquals(0, sim.metrics.total(Event.TRESPASS));

        SimMover carried = sim.movers.stream().skip(1).filter(m -> m.position.x() < EDGE).reduce((a, b) -> b)
            .orElseThrow();
        carried.position = feet(EDGE + 1.5, 0.5); // carried out by water or a push
        sim.run(100);

        assertEquals(1, sim.metrics.total(Event.TRESPASS), "log: " + tail());
        assertTrue(sim.log.stream().anyMatch(l -> l.startsWith("TRESPASS " + carried.name() + " left " + OLD_TOWN)
            && l.contains("a follower of group")), "log: " + tail());
    }

    @Test
    void theProfilesBypassLetsTheGroupLeave() {
        NpcAccessProfile bypass = new NpcAccessProfile("guards", true, true, Set.of(), Set.of());
        NavGroup first = sim.group(6, COLUMN); // spawns and registers six movers; the group itself is not used
        sim.groups.disband(first);
        SimMover leader = sim.movers.get(0);
        List<net.knightsandkings.knk.core.npc.nav.NpcMover> followers = new ArrayList<>(sim.movers.subList(1, 6));
        for (SimMover m : sim.movers) {
            sim.service.unregister(m.id());
            sim.service.register(m, bypass, sim.moverSettings); // as the command registers a group's members
        }
        NavGroup group = sim.groups.create(leader, followers, bypass, COLUMN);
        exit(false);
        group.navigate(feet(200.5, 0.5), results);

        assertTrue(sim.runUntil(results::ended, 4000), tail());
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }
}
