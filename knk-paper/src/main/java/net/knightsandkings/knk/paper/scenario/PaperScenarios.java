package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.ScenarioRegistry;
import net.knightsandkings.knk.paper.scenario.scenarios.GateBlocksNeverSuffocateScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateClosingKeepsSidesScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateClosingMovesEntityOutScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateClosingMovesItemOutScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateClosingMovesVehicleScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateDeepDoorScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateOpenCloseScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateOpeningLeavesNeighboursScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateSideBySideScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GlobalRegionExplosionScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.LootboxAreaRenameScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.LootboxAreaReplaceScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.LootboxSpawnOnGroundScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.LootboxSpawnUnderRoofScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.LootboxSwitchedOffScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcCornersLineScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcCornersWalkScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupAccessChangeScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupGateFinishTripScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupNarrowsScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcStuckLadderScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupSpacingScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcKeepsToRoadScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcWalksRoadRouteScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerChatGroupColoursScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerCommandNeedsGrantScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerJoinLoadingHoldScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerMenuClickScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerRejoinInClosedGateScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerTpIntoClosedGateScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.TempRegionSweepScenario;

/**
 * Every scenario the plugin ships (PandiO/knk-workspace#421). Add a new one here; {@code run all}
 * runs them in this order, and CI runs {@code run all} after the Paper boot check.
 */
public final class PaperScenarios {

    private PaperScenarios() {
    }

    public static ScenarioRegistry<ScenarioScene> registry() {
        return new ScenarioRegistry<ScenarioScene>()
            .register(new GateOpenCloseScenario())
            .register(new GateClosingMovesEntityOutScenario())
            .register(new GlobalRegionExplosionScenario())
            // Backfill of the gate smoke test § 2 (PandiO/knk-workspace#511)
            .register(new GateClosingKeepsSidesScenario())
            .register(new GateDeepDoorScenario())
            .register(new GateClosingMovesItemOutScenario())
            .register(new GateClosingMovesVehicleScenario())
            .register(new GateSideBySideScenario())
            .register(new GateOpeningLeavesNeighboursScenario())
            .register(new GateBlocksNeverSuffocateScenario())
            // Backfill of the lootbox area region and settings pages smoke tests (PandiO/knk-workspace#511)
            .register(new LootboxAreaRenameScenario())
            .register(new LootboxAreaReplaceScenario())
            .register(new TempRegionSweepScenario())
            .register(new LootboxSpawnOnGroundScenario())
            .register(new LootboxSpawnUnderRoofScenario())
            .register(new LootboxSwitchedOffScenario())
            // NPC wall corners and stuck recovery (implementation plan 4b.2 item 18, PandiO/knk-workspace#521); the
            // walking ones need Citizens, which CI's paper-boot server has as a pinned build
            .register(new NpcCornersLineScenario())
            .register(new NpcCornersWalkScenario())
            .register(new NpcStuckLadderScenario())
            .register(new NpcGroupNarrowsScenario())
            .register(new NpcGroupGateFinishTripScenario())
            .register(new NpcGroupAccessChangeScenario())
            // NPC navigation on Citizens, on a fake road network (PandiO/knk-workspace#543)
            .register(new NpcWalksRoadRouteScenario())
            .register(new NpcKeepsToRoadScenario())
            .register(new NpcGroupSpacingScenario())
            // Scripted players: commands, chat, joins, menus and teleports (PandiO/knk-workspace#540)
            .register(new PlayerCommandNeedsGrantScenario())
            .register(new PlayerChatGroupColoursScenario())
            .register(new PlayerJoinLoadingHoldScenario())
            .register(new PlayerMenuClickScenario())
            .register(new PlayerTpIntoClosedGateScenario())
            .register(new PlayerRejoinInClosedGateScenario());
    }
}
