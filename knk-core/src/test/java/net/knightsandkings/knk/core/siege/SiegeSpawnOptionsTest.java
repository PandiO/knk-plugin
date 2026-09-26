package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions.SpawnOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: spawn options, availability and respawn fallback (DESIGN §6.6, MENU_TEMPLATES C.4). */
class SiegeSpawnOptionsTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final int KEEP = 10;
    private static final int TOWER = 11;
    private static final int WALL = 12;

    private static KnkLocation loc(double x) {
        return new KnkLocation(null, null, x, 64.0, 0.0, 0f, 0f, "world");
    }

    private static KnkSiegeTeam team(int id, SiegeTeamRole role, int alliance, KnkSiegeSpawnpoint... spawns) {
        return new KnkSiegeTeam(id, id, role, alliance, null, "T" + id, "RED", null, null, List.of(spawns));
    }

    private static KnkSiegeObjective objective(int id, boolean iv, int holder, boolean spawnWhenHeld) {
        return new KnkSiegeObjective(id, id, "Obj " + id, loc(id), null, 500, 2.5, iv, holder, spawnWhenHeld, SiegeGateState.OPEN);
    }

    private final KnkSiegeTeam defenders = team(DEF, SiegeTeamRole.DEFENDER, 1,
            new KnkSiegeSpawnpoint(2, 1, "Back", loc(2), 4), new KnkSiegeSpawnpoint(1, 0, "Main", loc(1), 4));
    private final KnkSiegeTeam attackers = team(ATT, SiegeTeamRole.ATTACKER, 2, new KnkSiegeSpawnpoint(3, 0, "Camp", loc(3), 4));
    private final KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, List.of(defenders, attackers),
            List.of(objective(KEEP, true, DEF, true), objective(TOWER, false, DEF, true), objective(WALL, false, DEF, false)),
            false);

    private SiegeObjectiveBoard board() {
        return new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), AllianceResolver.of(scenario));
    }

    @Test
    void heldSpawnableObjectivesComeFirstThenSpawnpointsInSortOrder() {
        List<SpawnOption> options = SiegeSpawnOptions.forTeam(defenders, board(), null);
        assertEquals(List.of("Obj 10", "Obj 11", "Main", "Back"), options.stream().map(SpawnOption::name).toList());
        assertEquals(List.of(SpawnKind.OBJECTIVE, SpawnKind.OBJECTIVE, SpawnKind.SPAWNPOINT, SpawnKind.SPAWNPOINT),
                options.stream().map(SpawnOption::kind).toList());
        assertTrue(options.get(2).current(), "with no choice, the default spawnpoint is current");
        assertEquals(1, options.stream().filter(SpawnOption::current).count());
    }

    @Test
    void theEnemyTeamSeesOnlyItsSpawnpointAndNoPicker() {
        List<SpawnOption> options = SiegeSpawnOptions.forTeam(attackers, board(), null);
        assertEquals(List.of("Camp"), options.stream().map(SpawnOption::name).toList());
        assertFalse(SiegeSpawnOptions.pickerWorthOpening(attackers, board()));
        assertTrue(SiegeSpawnOptions.pickerWorthOpening(defenders, board()));
    }

    @Test
    void aContestedObjectiveIsListedButNotAvailableAndRespawnFallsBack() {
        SiegeObjectiveBoard board = board();
        board.step(Map.of(TOWER, List.of(at(20, ATT, 1.0))));
        SpawnChoice tower = SpawnChoice.objective(TOWER);

        SpawnOption option = SiegeSpawnOptions.forTeam(defenders, board, tower).stream()
                .filter(o -> o.id() == TOWER).findFirst().orElseThrow();
        assertFalse(option.available());
        assertTrue(option.current());
        assertFalse(SiegeSpawnOptions.isAvailable(defenders, board, tower));
        assertEquals("Main", SiegeSpawnOptions.resolveRespawn(defenders, board, tower).orElseThrow().name());
    }

    @Test
    void aLostObjectiveFallsBackToTheDefaultSpawnpoint() {
        SiegeObjectiveBoard board = board();
        for (int i = 0; i < 200 && board.objective(TOWER).holderTeamId() == DEF; i++) {
            board.step(Map.of(TOWER, List.of(at(20, ATT, 1.0))));
        }
        assertEquals(ATT, board.objective(TOWER).holderTeamId());
        board.step(Map.of()); // nobody near any more: not contested

        assertEquals("Main", SiegeSpawnOptions.resolveRespawn(defenders, board, SpawnChoice.objective(TOWER)).orElseThrow().name());
        assertTrue(SiegeSpawnOptions.forTeam(attackers, board, null).stream().anyMatch(o -> o.id() == TOWER),
                "the capturing team can now spawn there");
    }

    @Test
    void aChosenSpawnpointIsUsedAndForeignChoicesFallBack() {
        SiegeObjectiveBoard board = board();
        assertEquals("Back", SiegeSpawnOptions.resolveRespawn(defenders, board, SpawnChoice.spawnpoint(2)).orElseThrow().name());
        assertEquals("Main", SiegeSpawnOptions.resolveRespawn(defenders, board, SpawnChoice.spawnpoint(3)).orElseThrow().name(),
                "another team's spawnpoint is never used");
        assertEquals("Main", SiegeSpawnOptions.resolveRespawn(defenders, board, SpawnChoice.objective(WALL)).orElseThrow().name(),
                "an objective without SpawnWhenHeld is not a spawn");
        assertEquals("Obj 10", SiegeSpawnOptions.resolveRespawn(defenders, board, SpawnChoice.objective(KEEP)).orElseThrow().name());
    }

    @Test
    void withoutABoardOnlySpawnpointsAreOffered() {
        assertEquals(List.of("Main", "Back"), SiegeSpawnOptions.forTeam(defenders, null, null).stream().map(SpawnOption::name).toList());
    }
}
