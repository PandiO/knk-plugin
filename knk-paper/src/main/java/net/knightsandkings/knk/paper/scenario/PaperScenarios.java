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
import net.knightsandkings.knk.paper.scenario.scenarios.WalkTrailStairhouseScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcCornersWalkScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupAccessChangeScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.NpcGroupRealtimeEntryScenario;
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
            .register(new NpcCornersWalkScenario())
            .register(new NpcStuckLadderScenario())
            .register(new NpcGroupNarrowsScenario())
            .register(new NpcGroupGateFinishTripScenario())
            .register(new NpcGroupAccessChangeScenario())
            // entry removed through realtime under a gate region (PandiO/knk-workspace#593)
            .register(new NpcGroupRealtimeEntryScenario())
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
            .register(new PlayerRejoinInClosedGateScenario());
    }
}
