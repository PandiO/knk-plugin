package net.knightsandkings.knk.paper.scenario.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.ScenarioServices;

/** The report lines of the NPC movement scenarios (link M1, PandiO/knk-workspace#621). */
class NpcMovementReportTest {

    // kept in a field, never inline (knk-plugin CLAUDE.md: a Location holds its world weakly)
    private final World world = mock(World.class);

    private NpcMovementScene house() {
        when(world.getName()).thenReturn("world");
        return NpcMovementScene.stairHouse(new ScenarioScene("report-test", 0, world, 30_000, 100, 30_000,
            new ScenarioServices(null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null,
                () -> null, () -> null, () -> null, () -> null, () -> null)));
    }

    @Test
    void aWidthLineBecomesItsKindReasonAndBoxPosition() {
        NpcMovementScene house = house();

        assertEquals("fold (door) at 13 2 7", NpcMovementReport.shortChange(
            "group 1 (knk-sc-0, 20 NPCs) folds into a column: the path is too narrow for 2 abreast (door) at 30013 103 30007",
            house));
        assertEquals("narrow (edge) at 14 2 10", NpcMovementReport.shortChange(
            "group 1 (knk-sc-0, 9 NPCs) narrows to 2 abreast: the path is too narrow for 3 abreast (edge) at 30014 103 30010",
            house));
        assertEquals("reform at 14 2 12", NpcMovementReport.shortChange(
            "group 1 (knk-sc-0, 9 NPCs) takes up 3 abreast again at 30014 103 30012", house));
    }

    @Test
    void aWorldBlockBecomesABoxBlock() {
        assertEquals("knk-sc-5 at 14 2 6", NpcMovementReport.boxPosition("knk-sc-5 at 30014 103 30006", house()));
        assertEquals("-", NpcMovementReport.boxPosition("-", house()), "nobody stood still");
    }

    @Test
    void eachScenarioRunsWithCollisionOffAndOn() {
        assertEquals("npc-plaza-line", NpcMovementReport.name("npc-plaza-line", false));
        assertEquals("npc-plaza-line-collision-on", NpcMovementReport.name("npc-plaza-line", true));
        NavigationSettings.Scope scope = NavigationSettings.Scope.of("world");
        assertEquals(true, NpcMovementReport.settings(true).value(NavigationSettings.COLLIDABLE, scope));
        assertEquals(false, NpcMovementReport.settings(false).value(NavigationSettings.COLLIDABLE, scope));
        assertEquals(3, NpcMovementReport.settings(false).value(NavigationSettings.GROUP_RANKS_WIDTH, scope),
            "ranks of 3, as in the plan's numbers");
        assertTrue(new NpcStairhouseGroupScenario(true).name().endsWith(NpcMovementReport.COLLISION_SUFFIX));
    }
}
