package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeConfigDto;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeLobbyMode;
import net.knightsandkings.knk.core.domain.siege.SiegeNonMemberGateView;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 4: runtime-config JSON to the knk-core records. The fixture
 * {@code siege/runtime-config-dev-2026-09-25.json} is a real response of
 * {@code GET /api/siege-lobbies/runtime-config} from the dev DB (lobby 1 {@code test-cinix} with
 * scenario 1 "[TEST] Siege of Cinix"), unedited apart from pretty-printing.
 */
class SiegeMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsTheLiveDevSample() throws IOException {
        KnkSiegeRuntimeConfig config = SiegeMapper.toCore(readFixture());

        assertEquals(Instant.parse("2026-09-25T19:22:58.5111715Z"), config.generatedAt());
        assertEquals(1, config.lobbies().size());

        KnkSiegeLobby lobby = config.lobbyByKey("test-cinix").orElseThrow();
        assertEquals(1, lobby.id());
        assertEquals(SiegeLobbyMode.CONTINUOUS, lobby.mode());
        assertEquals(300, lobby.matchmakingSeconds());
        assertEquals(900, lobby.cooldownSeconds());
        assertEquals(2, lobby.voteCandidateCount());
        assertTrue(lobby.allowRandomVote());
        assertTrue(lobby.skippedScenarios().isEmpty());
        assertEquals(1, lobby.rotation().size());
        assertEquals(1, lobby.rotation().get(0).weight());

        KnkSiegeScenario scenario = lobby.scenario(1).orElseThrow();
        assertEquals("[TEST] Siege of Cinix", scenario.name());
        assertEquals(5, scenario.townId());
        assertEquals("Cinix", scenario.townName());
        assertEquals("town_1", scenario.townWgRegionId());
        assertEquals(List.of(6, 7, 8), scenario.districts().stream().map(d -> d.id()).toList());
        assertEquals("district_1000006", scenario.districts().get(2).wgRegionId());
        assertEquals(62, scenario.hubLocation().id());
        assertEquals("world_KNK-DEV", scenario.hubLocation().world());
        assertEquals(1443.4, scenario.hubLocation().x());
        assertEquals(2, scenario.playersMin());
        assertEquals(20, scenario.playersMax());
        assertNull(scenario.minTitleBracketId());
        assertNull(scenario.minTitleExperience());
        assertEquals(300, scenario.matchLength().minSeconds());
        assertEquals(75, scenario.matchLength().perPlayerSeconds());
        assertEquals(1800, scenario.matchLength().maxSeconds());
        assertEquals(100, scenario.rewards().coinWin());
        assertEquals(1, scenario.rewards().gemWin());
        assertEquals(5, scenario.rewards().expCapture());
        assertTrue(scenario.lockdownScenarioArea());
        assertFalse(scenario.allowRecapture());
        assertTrue(scenario.enchantDropsEnabled());
    }

    @Test
    void mapsResolvedTeamIdentitiesFromTheLiveSample() throws IOException {
        KnkSiegeScenario scenario = SiegeMapper.toCore(readFixture()).lobbies().get(0).rotation().get(0).scenario();

        KnkSiegeTeam defenders = scenario.team(1).orElseThrow();
        assertEquals(SiegeTeamRole.DEFENDER, defenders.role());
        assertEquals(1, defenders.allianceGroup());
        assertEquals(1, defenders.clanId());
        // Clan-sourced identity arrives resolved: the clan's name, colour and banner.
        assertEquals("[TEST] Cinix Garrison", defenders.name());
        assertEquals("GOLD", defenders.chatColor());
        assertEquals("YELLOW", defenders.bannerDesign().baseColor());
        assertEquals(List.of("minecraft:border", "minecraft:rhombus"),
                defenders.bannerDesign().layers().stream().map(l -> l.patternKey()).toList());
        assertEquals("Hold the Keep!", defenders.startMessage());
        assertEquals("Keep Square", defenders.defaultSpawnpoint().orElseThrow().name());
        assertEquals(4.0, defenders.spawnpoints().get(0).safeZoneRadius());

        KnkSiegeTeam raiders = scenario.team(2).orElseThrow();
        assertEquals(SiegeTeamRole.ATTACKER, raiders.role());
        assertEquals(2, raiders.allianceGroup());
        assertNull(raiders.clanId());
        assertEquals("Raiders", raiders.name());
        assertEquals("RED", raiders.chatColor());
        assertEquals("minecraft:skull", raiders.bannerDesign().layers().get(0).patternKey());
        assertEquals(64, raiders.defaultSpawnpoint().orElseThrow().location().id());

        assertEquals(1, scenario.firstDefender().orElseThrow().id());
    }

    @Test
    void mapsObjectivesAndGatesWithServerResolvedDefaults() throws IOException {
        KnkSiegeScenario scenario = SiegeMapper.toCore(readFixture()).lobbies().get(0).rotation().get(0).scenario();

        KnkSiegeObjective keep = scenario.objective(1).orElseThrow();
        assertTrue(keep.instantVictory());
        assertNull(keep.gateStructureId());
        assertEquals(65, keep.captureLocation().id());
        assertEquals(500, keep.capturePoints());
        assertEquals(2.5, keep.captureRadius());
        assertEquals(1, keep.initialHolderTeamId()); // first-Defender default, applied server-side
        assertTrue(keep.spawnWhenHeld());
        assertEquals(SiegeGateState.OPEN, keep.gateStateOnCapture());

        KnkSiegeObjective southGate = scenario.objective(2).orElseThrow();
        assertFalse(southGate.instantVictory());
        assertEquals(13, southGate.gateStructureId());
        // No own location: the capture point is the gate structure's location (39), resolved server-side.
        assertEquals(39, southGate.captureLocation().id());
        assertEquals(1421.3857603075921, southGate.captureLocation().x());
        assertEquals(1, scenario.nonInstantVictoryObjectiveCount());
        assertEquals(List.of(keep), scenario.instantVictoryObjectives());

        assertEquals(List.of(13, 14), scenario.gateStructureIds());
        KnkSiegeGate gate13 = scenario.gate(13).orElseThrow();
        assertEquals("South Gate", gate13.name());
        assertEquals(1, gate13.initialOwnerTeamId());
        assertEquals(SiegeGateState.CLOSED, gate13.initialState());
        assertTrue(gate13.damageable());
        assertTrue(gate13.objectiveGate());
        assertFalse(scenario.gate(14).orElseThrow().objectiveGate());
    }

    @Test
    void mapsTheLiveConfigurationValues() throws IOException {
        KnkSiegeConfiguration c = SiegeMapper.toCore(readFixture()).configuration();

        assertEquals(5, c.captureAttackBase());
        assertEquals(2, c.captureAttackPerExtra());
        assertEquals(5, c.captureAttackPerExtraInstantVictory());
        assertEquals(6, c.captureDefendBase());
        assertEquals(3, c.captureDefendPerExtra());
        assertEquals(6, c.captureDefendPerExtraInstantVictory());
        assertEquals(0.4, c.sideCaptureReduction());
        assertEquals(30, c.voteCloseSecondsBeforeStart());
        assertEquals(25, c.drawSecondsBeforeStart());
        assertEquals(15, c.hubSecondsBeforeStart());
        assertEquals(10, c.teamSplitSecondsBeforeStart());
        assertEquals(List.of(290, 60, 30, 15), c.matchmakingAnnouncementMarks());
        // Kill thresholds and the streak threshold are separate fields.
        assertEquals(List.of(5, 10, 15), c.killAnnouncementThresholds());
        assertEquals(3, c.killStreakAnnounceAbove());
        assertEquals(1.5, c.headshotMultiplier());
        assertEquals(List.of("/siege", "/msg", "/r", "/staffchat", "/menu"), c.allowedCommands());
        assertEquals(20, c.spawnPickerDelayTicks());
        assertEquals(30, c.enchantDropChancePerMille());
        assertEquals(17, c.allowedEnchantmentKeys().size());
        assertTrue(c.allowedEnchantmentKeys().contains("minecraft:sharpness"));
        assertEquals(1, c.enchantLevelMin());
        assertEquals(2, c.enchantLevelMax());
        assertEquals(10, c.maxBooksAlive());
        assertEquals(SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW, c.nonMemberGateView());
    }

    @Test
    void mapsSkippedScenariosAndFillsMissingValuesWithDefaults() throws IOException {
        String json = """
                {
                  "generatedAt": "2026-09-25T19:22:58",
                  "configuration": { "captureAttackBase": 7, "nonMemberGateView": "PassThroughOnly" },
                  "lobbies": [
                    {
                      "id": 2, "name": "Forms", "key": "test-forms", "mode": "Scheduled",
                      "rotation": [
                        { "weight": 3, "scenario": { "id": 4, "name": "Bare", "teams": [
                          { "id": 9, "role": "Mercenary", "name": "Odd" } ],
                          "objectives": [ { "id": 5, "name": "O" } ],
                          "gates": [ { "gateStructureId": 13 } ] } },
                        { "weight": 1, "scenario": null }
                      ],
                      "skippedScenarios": [
                        { "siegeScenarioId": 2, "name": "[TEST] Siege of Cinix (forms)",
                          "errors": [ { "code": "TEAM_NO_SPAWNPOINT", "message": "Team has no spawnpoint",
                                        "entityType": "SiegeTeam", "entityId": 4 } ] }
                      ]
                    }
                  ]
                }
                """;

        KnkSiegeRuntimeConfig config = SiegeMapper.toCore(objectMapper.readValue(json, RuntimeConfigDto.class));

        // A local datetime (no offset) is read as UTC, like the rest of the client.
        assertEquals(Instant.parse("2026-09-25T19:22:58Z"), config.generatedAt());
        KnkSiegeConfiguration c = config.configuration();
        assertEquals(7, c.captureAttackBase());
        assertEquals(6, c.captureDefendBase());
        assertEquals(List.of(290, 60, 30, 15), c.matchmakingAnnouncementMarks());
        assertEquals(SiegeNonMemberGateView.PASS_THROUGH_ONLY, c.nonMemberGateView());

        KnkSiegeLobby lobby = config.lobbies().get(0);
        assertEquals(SiegeLobbyMode.SCHEDULED, lobby.mode());
        assertEquals(300, lobby.matchmakingSeconds());
        assertEquals(900, lobby.cooldownSeconds());
        assertEquals(2, lobby.voteCandidateCount());
        assertEquals(1, lobby.rotation().size(), "an entry without its scenario is dropped");
        assertEquals(3, lobby.rotation().get(0).weight());

        KnkSiegeScenario bare = lobby.rotation().get(0).scenario();
        assertEquals(2, bare.playersMin());
        assertEquals(50, bare.playersMax());
        assertEquals(300, bare.matchLength().minSeconds());
        assertEquals(SiegeTeamRole.ATTACKER, bare.teams().get(0).role());
        assertEquals("WHITE", bare.teams().get(0).chatColor());
        assertEquals(500, bare.objectives().get(0).capturePoints());
        assertEquals(2.5, bare.objectives().get(0).captureRadius());
        assertEquals(SiegeGateState.OPEN, bare.objectives().get(0).gateStateOnCapture());
        assertEquals(SiegeGateState.CLOSED, bare.gates().get(0).initialState());
        assertTrue(bare.gates().get(0).damageable());

        assertEquals(1, lobby.skippedScenarios().size());
        assertEquals(2, lobby.skippedScenarios().get(0).scenarioId());
        assertEquals("TEAM_NO_SPAWNPOINT", lobby.skippedScenarios().get(0).errors().get(0).code());
        assertEquals(4, lobby.skippedScenarios().get(0).errors().get(0).entityId());
    }

    @Test
    void mapsReadiness() throws IOException {
        // Live response of GET /api/siege-scenarios/1/readiness with the Minecraft server down.
        String json = """
                {"siegeScenarioId":1,"isReady":true,"spatialChecksRun":false,"errors":[],"warnings":[
                  {"code":"SPATIAL_CHECKS_UNAVAILABLE","message":"Couldn't check that locations are inside the town: the Minecraft server or knk-plugin isn't reachable. Start it and re-check readiness.","entityType":null,"entityId":null}]}
                """;

        KnkSiegeReadiness readiness = SiegeMapper.toCore(objectMapper.readValue(json, ReadinessDto.class));

        assertEquals(1, readiness.scenarioId());
        assertTrue(readiness.ready());
        assertFalse(readiness.spatialChecksRun());
        assertTrue(readiness.errors().isEmpty());
        assertEquals("SPATIAL_CHECKS_UNAVAILABLE", readiness.warnings().get(0).code());
        assertNull(readiness.warnings().get(0).entityId());
        assertNull(SiegeMapper.toCore((ReadinessDto) null));
    }

    private RuntimeConfigDto readFixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("siege/runtime-config-dev-2026-09-25.json")) {
            assertNotNull(in, "fixture missing");
            return objectMapper.readValue(in, RuntimeConfigDto.class);
        }
    }
}
