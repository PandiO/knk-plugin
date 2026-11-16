package net.knightsandkings.knk.paper.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;

/** The snapshot replay's fake API data and settings (link B1, PandiO/knk-workspace#670). */
class SnapshotReplayTest {

    private static AccessSubject subject(boolean bypass, boolean gateAdmin, Set<String> in) {
        return new AccessSubject() {
            @Override public String name() { return "npc"; }
            @Override public Optional<UUID> playerId() { return Optional.empty(); }
            @Override public boolean bypassesDomainRules() { return bypass; }
            @Override public boolean gateAdmin() { return gateAdmin; }
            @Override public boolean mayUseGates() { return true; }
            @Override public boolean mayOpenDoor(String world, int x, int y, int z) { return true; }
            @Override public String world() { return "world"; }
            @Override public Set<String> currentRegions() { return in; }
        };
    }

    private static NavSnapshot.Domain domain(String region, Boolean allowEntry, NavSnapshot.GateDoor... doors) {
        return new NavSnapshot.Domain(region, 1, region, doors.length > 0 ? "gate" : "district", "cuboid",
            List.of(new int[] {0, 0, 0}, new int[] {1, 1, 1}), 0, 1, null, 0, null, null, allowEntry, true,
            List.of(doors));
    }

    @Test
    void entryAsTheCacheHadItWithTheBypassAndTheRegionsTheSubjectIsIn() {
        AccessRules rules = SnapshotReplay.rules(Map.of("closed", domain("closed", false), "open", domain("open", true),
            "unknown", domain("unknown", null)), Map.of());
        assertFalse(rules.mayEnter(subject(false, false, Set.of())).test("closed"));
        assertTrue(rules.mayEnter(subject(false, false, Set.of())).test("open"));
        assertTrue(rules.mayEnter(subject(false, false, Set.of())).test("unknown"));
        assertTrue(rules.mayEnter(subject(false, false, Set.of())).test("not-in-the-snapshot"));
        assertTrue(rules.mayEnter(subject(true, false, Set.of())).test("closed"), "bypass");
        assertTrue(rules.mayEnter(subject(false, false, Set.of("closed"))).test("closed"), "already in it");
    }

    @Test
    void aGateDoorThatWasNotOpenBlocksUnlessTheSubjectIsAGateAdmin() {
        AccessRules rules = SnapshotReplay.rules(Map.of(), Map.of(7, false, 8, true));
        assertTrue(rules.gateBlocks(subject(false, false, Set.of()), 7));
        assertFalse(rules.gateBlocks(subject(false, true, Set.of()), 7));
        assertFalse(rules.gateBlocks(subject(false, false, Set.of()), 8));
        assertFalse(rules.gateBlocks(subject(false, false, Set.of()), 99), "a door the snapshot does not know");
    }

    @Test
    void theSampleLoadsWithItsLineSettingsAndBox() {
        SnapshotReplay replay = SnapshotReplay.fromResource("stairhouse-sample.zip");
        assertEquals("stairhouse-sample", replay.snapshot().manifest().route());
        assertEquals(4, replay.snapshot().domains().size());
        assertEquals(NpcMovementScene.CLIMB.length, replay.snapshot().route().line().size(), "the scene's climb line");
        List<String> rejected = new ArrayList<>();
        NavigationSettings settings = replay.settings(rejected);
        assertTrue(rejected.isEmpty(), rejected.toString());
        assertEquals(8.0, settings.value(NavigationSettings.STRETCH_BLOCKS, NavigationSettings.Scope.of("world")));
        assertEquals(true, settings.value(NavigationSettings.CROWD_AWARENESS, NavigationSettings.Scope.of("world")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(replay.snapshot().manifest().boxMin(), replay.frame());
        assertEquals(new ScenarioScene.Box(34, 46, 75),
            net.knightsandkings.knk.paper.scenario.scenarios.NpcSnapshotReplayScenario.boxOf(replay.snapshot()));
    }

    @Test
    void theDevelopersSnapshotTrimmedToTheStairHouseLoads() {
        SnapshotReplay replay = SnapshotReplay.fromResource("quarry-to-keep-stairhouse.zip");
        NavSnapshot s = replay.snapshot();
        assertEquals("quarry-to-keep", s.manifest().route());
        assertEquals("0.1.0+0f039203", s.manifest().pluginVersion());
        assertEquals(103, s.route().line().size());
        assertEquals(18, s.route().stretches().size());
        assertEquals(13, s.domains().size());
        assertTrue(s.domains().stream().filter(NavSnapshot.Domain::isGate).count() >= 6, "GateStructure domains");
        List<String> rejected = new ArrayList<>();
        NavigationSettings settings = replay.settings(rejected);
        assertTrue(rejected.isEmpty(), rejected.toString());
        assertEquals("elastic", settings.value(NavigationSettings.GROUP_FORMATION_KEEPING, NavigationSettings.Scope.of("w")));
        assertEquals(new ScenarioScene.Box(64, 61, 80),
            net.knightsandkings.knk.paper.scenario.scenarios.NpcSnapshotReplayScenario.boxOf(s));
        assertTrue(s.manifest().notes().stream().anyMatch(n -> n.contains("trimmed")), s.manifest().notes().toString());
    }
}
