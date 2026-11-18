package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Kind;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiegeCompassTargetsTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final int KEEP = 1;
    private static final int GATE = 2;
    private static final int WALL = 3;
    private static final int OUTPOST = 4;

    private static KnkLocation where(int n) {
        return new KnkLocation(n, "L" + n, 10.0 * n, 64.0, 5.0, 0f, 0f, "siege_world");
    }

    private static KnkSiegeObjective objective(int id, String name, int holder, boolean iv, KnkLocation location) {
        return new KnkSiegeObjective(id, id, name, location, null, 100, 3.0, iv, holder, true, SiegeGateState.OPEN);
    }

    private static KnkSiegeTeam team(int id, SiegeTeamRole role, int alliance, String name, KnkLocation spawn) {
        List<KnkSiegeSpawnpoint> spawns = spawn == null ? List.of() : List.of(new KnkSiegeSpawnpoint(id * 10, 0, name + " camp", spawn, 5));
        return new KnkSiegeTeam(id, id, role, alliance, null, name, "WHITE", null, null, spawns);
    }

    /** Keep (IV), Gate, Wall held by the defenders; the Outpost held by the attackers from the start. */
    private static SiegeObjectiveBoard board(boolean outpostHeldByAttackers) {
        KnkSiegeScenario scenario = scenario(outpostHeldByAttackers);
        return new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), AllianceResolver.of(scenario));
    }

    private static KnkSiegeScenario scenario(boolean outpostHeldByAttackers) {
        return SiegeTestData.scenario(1, 5, 2,
            List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders", where(8)),
                team(ATT, SiegeTeamRole.ATTACKER, 2, "Attackers", where(9))),
            List.of(objective(KEEP, "Keep", DEF, true, where(1)),
                objective(GATE, "Gate", DEF, false, where(2)),
                objective(WALL, "Wall", DEF, false, where(3)),
                objective(OUTPOST, "Outpost", outpostHeldByAttackers ? ATT : DEF, false, where(4))),
            true, List.of());
    }

    private static List<String> labels(List<Target> targets) {
        return targets.stream().map(Target::label).toList();
    }

    private static List<Target> targets(SiegeObjectiveBoard board, boolean outpostHeld, int teamId) {
        KnkSiegeScenario scenario = scenario(outpostHeld);
        return SiegeCompassTargets.forTeam(scenario, board, AllianceResolver.of(scenario), teamId);
    }

    @Test
    void defendersGetTheirObjectivesFirstThenTheSpawn() {
        List<Target> out = targets(board(false), false, DEF);
        // front line first: nearest to the attackers' camp (x=90) first, the main objective (Keep) last
        assertEquals(List.of("Outpost", "Wall", "Gate", "Keep", "Defenders camp"), labels(out));
        assertTrue(out.subList(0, 4).stream().allMatch(t -> t.kind() == Kind.DEFEND));
        assertEquals(Kind.SPAWN, out.get(4).kind());
    }

    @Test
    void attackersGetTheObjectivesToCaptureFirst() {
        List<Target> out = targets(board(false), false, ATT);
        assertEquals(List.of("Outpost", "Wall", "Gate", "Keep", "Attackers camp"), labels(out));
        assertTrue(out.subList(0, 4).stream().allMatch(t -> t.kind() == Kind.CAPTURE));
    }

    @Test
    void anObjectiveHeldByTheAttackersIsOneTheyDefendAndTheyListItAfterTheCaptures() {
        List<Target> out = targets(board(true), true, ATT);
        assertEquals(List.of("Wall", "Gate", "Keep", "Outpost", "Attackers camp"), labels(out));
        assertEquals(Kind.DEFEND, out.get(3).kind());
        // and the defenders are told to take it back
        List<Target> defenders = targets(board(true), true, DEF);
        assertEquals(List.of("Wall", "Gate", "Keep", "Outpost", "Defenders camp"), labels(defenders));
        assertEquals(Kind.CAPTURE, defenders.get(3).kind());
    }

    @Test
    void anObjectiveUnderAttackMovesToTheTopForBothSides() {
        SiegeObjectiveBoard board = board(false);
        board.step(Map.of(WALL, List.of(at(1, ATT, 1.0))));

        for (int teamId : new int[] {DEF, ATT}) {
            List<Target> out = targets(board, false, teamId);
            assertEquals("Wall", out.get(0).label());
            assertTrue(out.get(0).underAttack());
            assertFalse(out.get(1).underAttack());
        }
    }

    @Test
    void underAttackObjectivesStillKeepTheTeamsOwnJobFirstAmongThemselves() {
        SiegeObjectiveBoard board = board(true);
        board.step(Map.of(GATE, List.of(at(1, ATT, 1.0)), OUTPOST, List.of(at(2, DEF, 1.0))));

        // the attackers' own capture (Gate) before the outpost they hold and the defenders are attacking
        assertEquals(List.of("Gate", "Outpost", "Wall", "Keep", "Attackers camp"), labels(targets(board, true, ATT)));
        // the defenders' own (Gate) before the outpost they are retaking
        assertEquals(List.of("Gate", "Outpost", "Wall", "Keep", "Defenders camp"), labels(targets(board, true, DEF)));
    }

    @Test
    void reportsHowMuchOfTheCaptureIsLeft() {
        SiegeObjectiveBoard board = board(false);
        assertEquals(100, targets(board, false, DEF).get(0).progressPercent());
        board.step(Map.of(KEEP, List.of(at(1, ATT, 1.0))));
        int left = targets(board, false, DEF).stream().filter(t -> t.label().equals("Keep")).findFirst().orElseThrow().progressPercent();
        assertTrue(left < 100 && left > 0, "capture in progress: " + left);
    }

    @Test
    void objectivesWithoutALocationAreSkipped() {
        KnkSiegeScenario noLocation = SiegeTestData.scenario(1, 5, 2,
            List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders", null), team(ATT, SiegeTeamRole.ATTACKER, 2, "Attackers", null)),
            List.of(objective(KEEP, "Keep", DEF, true, null), objective(GATE, "Gate", DEF, false, where(2))), true, List.of());
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(noLocation, new CaptureCalculator(CONFIG), AllianceResolver.of(noLocation));
        assertEquals(List.of("Gate"), labels(SiegeCompassTargets.forTeam(noLocation, board, AllianceResolver.of(noLocation), ATT)));
    }

    @Test
    void anUnknownTeamGetsNothing() {
        assertTrue(targets(board(false), false, 99).isEmpty());
    }

    @Test
    void keysAreStableAcrossReordering() {
        SiegeObjectiveBoard board = board(false);
        List<String> before = targets(board, false, DEF).stream().map(Target::key).sorted().toList();
        board.step(Map.of(WALL, List.of(at(1, ATT, 1.0))));
        assertEquals(before, targets(board, false, DEF).stream().map(Target::key).sorted().toList());
        assertTrue(before.contains("objective:" + WALL));
        assertTrue(before.contains("spawn:" + (DEF * 10)));
    }

    @Test
    void theMainObjectiveComesAfterTheFrontLineEvenWhenTheScenarioListsItFirst() {
        // Keep (main, instant victory) is the first objective of the scenario, yet both teams are sent to the objectives
        // between the attackers and the keep first
        for (int teamId : new int[] {DEF, ATT}) {
            List<Target> out = targets(board(false), false, teamId);
            assertEquals("Keep", out.get(3).label(), "main objective last of the objectives for team " + teamId);
            assertTrue(out.indexOf(out.stream().filter(t -> t.label().equals("Gate")).findFirst().orElseThrow()) < 3);
        }
    }

    @Test
    void theFrontLineIsTheNearestToWhereTheAttackersSpawn() {
        // attackers spawn at x=90: Outpost (x=40) is nearest, then Wall (30), Gate (20)
        List<String> order = labels(targets(board(false), false, ATT)).subList(0, 3);

        assertEquals(List.of("Outpost", "Wall", "Gate"), order);
    }

    @Test
    void withoutAttackerSpawnPositionsTheScenarioOrderHoldsApartFromTheMainObjectiveLast() {
        KnkSiegeScenario noSpawns = SiegeTestData.scenario(1, 5, 2,
            List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders", null), team(ATT, SiegeTeamRole.ATTACKER, 2, "Attackers", null)),
            List.of(objective(KEEP, "Keep", DEF, true, where(1)), objective(GATE, "Gate", DEF, false, where(2)),
                objective(WALL, "Wall", DEF, false, where(3))), true, List.of());
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(noSpawns, new CaptureCalculator(CONFIG), AllianceResolver.of(noSpawns));

        assertEquals(List.of("Gate", "Wall", "Keep"), labels(SiegeCompassTargets.forTeam(noSpawns, board, AllianceResolver.of(noSpawns), ATT)));
    }

    @Test
    void anObjectiveUnderAttackStillBeatsTheFrontLine() {
        SiegeObjectiveBoard board = board(false);
        board.step(Map.of(KEEP, List.of(at(1, ATT, 1.0))));

        assertEquals("Keep", targets(board, false, DEF).get(0).label());
        assertEquals("Keep", targets(board, false, ATT).get(0).label());
    }
}
