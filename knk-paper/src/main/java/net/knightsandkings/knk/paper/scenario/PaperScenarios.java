package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.ScenarioRegistry;
import net.knightsandkings.knk.paper.scenario.scenarios.GateBlocksNeverSuffocateScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.ProductionAreaSurveyScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.RuralStructureRegionScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.TerritoryBorderCrossingScenario;
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
import net.knightsandkings.knk.paper.scenario.scenarios.PlazaLineRealPlazaScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.WalkTrailCursorScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.WalkTrailStairhouseScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcCornersWalkScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupAccessChangeScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupRealtimeEntryScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcExitDeniedLeaderScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupCacheRefreshHeldScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupExitDeniedScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupGateFinishTripScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupNarrowsScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcLandingRanksScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcLedgeCornerScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcStuckLadderScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupSpacingScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcPlazaLineScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcCollisionPolicyScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcDoorColumnScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcDoorwayCrowdScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGuardLedgePushScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcJaggedStraightScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcSnapshotReplayScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcKeepTowerReplayScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcOffRoadScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcStairhouseClimbScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcStairhouseGroupScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcStairhouseSinglesScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcKeepsToRoadScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcWalksRoadRouteScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerChatGroupColoursScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerCommandNeedsGrantScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.PlayerHealNeedsGrantScenario;
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
            .register(new WalkTrailStairhouseScenario())
            // off-road navigation phase 6.2 (PandiO/knk-workspace#711): the walk leg's cursor and anchored particles
            .register(new WalkTrailCursorScenario())
            // off-road navigation phase 7 step 1 (PandiO/knk-workspace#712): plaza lines on real plazas of Cinix
            .register(PlazaLineRealPlazaScenario.brink())
            .register(PlazaLineRealPlazaScenario.cinixSouthSquare())
            .register(PlazaLineRealPlazaScenario.cinixNorthernGateSquare())
            .register(new NpcCornersWalkScenario())
            .register(new NpcStuckLadderScenario())
            .register(new NpcGroupNarrowsScenario())
            .register(new NpcGroupGateFinishTripScenario())
            .register(new NpcGroupAccessChangeScenario())
            // entry removed through realtime under a gate region (PandiO/knk-workspace#593)
            .register(new NpcGroupRealtimeEntryScenario())
            // exit denied mid-trip, for an NPC alone and for a group half inside (PandiO/knk-workspace#701)
            .register(new NpcExitDeniedLeaderScenario())
            .register(new NpcGroupExitDeniedScenario())
            .register(new NpcGroupCacheRefreshHeldScenario())
            // the two spots of the developer's L3 retest in their real geometry (PandiO/knk-workspace#592)
            .register(new NpcLedgeCornerScenario())
            .register(new NpcLandingRanksScenario())
            // NPC navigation on Citizens, on a fake road network (PandiO/knk-workspace#543)
            .register(new NpcWalksRoadRouteScenario())
            .register(new NpcKeepsToRoadScenario())
            .register(new NpcGroupSpacingScenario())
            // NPC movement evidence in report mode, collision off and on (plan 4c.5, link M1, PandiO/knk-workspace#621)
            .register(new NpcStairhouseSinglesScenario(false))
            .register(new NpcStairhouseSinglesScenario(true))
            .register(new NpcStairhouseGroupScenario(false))
            .register(new NpcStairhouseGroupScenario(true))
            .register(new NpcStairhouseClimbScenario())
            .register(new NpcSnapshotReplayScenario())
            // link C1 (PandiO/knk-workspace#700): off-road legs with the walk search
            .register(new NpcOffRoadScenario(net.knightsandkings.knk.paper.scenario.NpcOffRoadScene.Kind.DETOUR))
            .register(new NpcOffRoadScenario(net.knightsandkings.knk.paper.scenario.NpcOffRoadScene.Kind.UPSTAIRS))
            .register(new NpcOffRoadScenario(net.knightsandkings.knk.paper.scenario.NpcOffRoadScene.Kind.RAVINE))
            .register(new NpcKeepTowerReplayScenario())
            // link H1 (PandiO/knk-workspace#779): a ladder and a roof hatch on an NPC's off-road leg
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.NpcLadderHatchScenario())
            // link G1 (PandiO/knk-workspace#814): the road goal by walk reachability for NPCs
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.NpcRoadGoalDeadEndScenario())
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.NpcKeepHallReplayScenario())
            .register(new NpcJaggedStraightScenario(false))
            .register(new NpcJaggedStraightScenario(true))
            .register(new NpcDoorwayCrowdScenario(false))
            .register(new NpcDoorwayCrowdScenario(true))
            .register(new NpcPlazaLineScenario(false))
            .register(new NpcPlazaLineScenario(true))
            // collision on: who pushes whom, players pushing a guard on a wall walk, a column through a door (plan 4c.4
            // item 7, link M7, PandiO/knk-workspace#627); they use scripted players (#540)
            .register(new NpcCollisionPolicyScenario())
            .register(new NpcGuardLedgePushScenario())
            .register(new NpcDoorColumnScenario())
            // Scripted players: commands, chat, joins, menus and teleports (PandiO/knk-workspace#540)
            .register(new PlayerCommandNeedsGrantScenario())
            // KnK takes the plain /heal over from WorldGuard (PandiO/knk-workspace#580). Not last: the run's end would
            // then land on the retry of player-tp-into-closed-gate's teleport audit (PandiO/knk-workspace#586).
            .register(new PlayerHealNeedsGrantScenario())
            .register(new PlayerChatGroupColoursScenario())
            .register(new PlayerJoinLoadingHoldScenario())
            .register(new PlayerMenuClickScenario())
            .register(new PlayerTpIntoClosedGateScenario())
            .register(new PlayerRejoinInClosedGateScenario())
            // Territory (PandiO/knk-workspace#689): the lookup on a border crossing
            .register(new TerritoryBorderCrossingScenario())
            // P2c (PandiO/knk-workspace#677): rural structure regions top level, the territory lookup
            .register(new RuralStructureRegionScenario())
            // P4b (PandiO/knk-workspace#679): a yield-area survey of real blocks from chunk snapshots
            .register(new ProductionAreaSurveyScenario())
            // P6 (PandiO/knk-workspace#74): a display double chest keeps its tagged sample from a hopper
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.StorageDisplayDoubleChestScenario())
            // P7b (PandiO/knk-workspace#684): kit leftovers and offline kits to the reward inbox; claim with partial space
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.PlayerKitOverflowInboxScenario())
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.PlayerInboxClaimScenario())
            // Production P10b (PandiO/knk-workspace#687): a sale in the sell menu and a purchase after the confirmation.
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.PlayerShopSellAndBuyScenario())
            // P8 (PandiO/knk-workspace#134): harvest and regenerate in a production area; a felled tree restored by snapshot
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.PlayerHarvestRegenerateScenario())
            .register(new net.knightsandkings.knk.paper.scenario.scenarios.PlayerHarvestTreeScenario());
    }
}
