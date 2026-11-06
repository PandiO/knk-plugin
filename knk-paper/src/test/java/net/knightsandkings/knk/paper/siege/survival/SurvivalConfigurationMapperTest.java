package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.survival.end.AllCapturedOverride;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.scaling.ScalingPreset;
import net.knightsandkings.knk.core.survival.spawn.PrerequisitePolicy;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveEntry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalConfigurationMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String FULL = """
        {
          "generatedAt": "2026-11-06T10:00:00+00:00", "siegeScenarioId": 1, "siegeScenarioName": "Cinix under Attack!",
          "siegeLobbyId": 1, "enabled": true, "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2, "allowRecapture": false,
          "intermissionMillis": 10000,
          "endConditions": { "scenarioAllCaptured": false, "lobbyOverride": "On", "allCapturedEnabled": true, "instaLoseObjectiveIds": [1] },
          "objectives": [
            { "id": 1, "name": "Throneroom", "instaLose": true, "gateStructureId": null,
              "captureLocation": { "id": 47, "x": 1421.5, "y": 86, "z": -494.7, "yaw": 0, "pitch": 0, "world": "world_KNK-DEV" }, "targetOverride": "Standard" },
            { "id": 2, "name": "South Gate", "instaLose": false, "gateStructureId": 7,
              "captureLocation": { "id": 13, "x": 1421.4, "y": 49, "z": -450.3, "world": "world_KNK-DEV" }, "targetOverride": "Deny" }
          ],
          "gates": [ { "gateStructureId": 7, "name": "South", "isObjectiveGate": true, "targetOverride": "Standard" },
                     { "gateStructureId": 6, "name": "Keep", "isObjectiveGate": false, "targetOverride": "Allow" } ],
          "stuck": { "stuckAfterMillis": 20000, "lastEnemiesCount": 2, "respawnLimit": 1, "roundBackstopMillis": null, "meaningfulDistance": 2.0 },
          "spawn": { "favorNearPlayersPercent": 65, "nearZoneCount": 2, "zones": [
            { "id": 10, "name": "Outside", "weight": 2, "anchorLocation": { "x": 1484, "y": 47, "z": -400, "world": "world_KNK-DEV" },
              "areaRadius": null, "prerequisitePolicy": "All", "prerequisiteObjectiveIds": [], "minWave": 1, "maxWave": null,
              "forNormalWaves": true, "forSpecialWaves": true, "laneAutoReroute": false,
              "points": [ { "id": 1, "kind": "Spawn", "sortOrder": 1, "location": { "x": 1485, "y": 47, "z": -401, "world": "world_KNK-DEV" } },
                          { "id": 2, "kind": "Lane", "sortOrder": 0, "location": { "x": 1450, "y": 48, "z": -430, "world": "world_KNK-DEV" } },
                          { "id": 3, "kind": "Spawn", "sortOrder": 2, "location": { "x": 1486, "y": 47, "z": -402, "world": "world_KNK-DEV" } } ] },
            { "id": 11, "name": "Courtyard", "weight": 1, "anchorLocation": { "x": 1421, "y": 50, "z": -470, "world": "world_KNK-DEV" },
              "areaRadius": 4.0, "prerequisitePolicy": "Any", "prerequisiteObjectiveIds": [2], "minWave": 1, "maxWave": 9,
              "forNormalWaves": true, "forSpecialWaves": false, "laneAutoReroute": true, "points": [] } ] },
          "difficulty": { "id": 5, "name": "Normal", "isDefault": true,
            "countCurve": { "preset": "Hybrid", "base": 6, "step": 2, "multiplier": 1.1, "switchRound": 9 },
            "statCurve": { "preset": "Linear", "base": 1, "step": 0.1, "multiplier": 1.1, "switchRound": 9 },
            "statWeights": { "health": 1, "walkingSpeed": 0.3, "meleeDamage": 1, "rangedDamage": 1, "attackSpeed": 0.5 },
            "perPlayer": { "factor": 0.25, "applyToStats": false }, "maxAlive": 40, "statCap": 8, "countCap": 0,
            "repairRatePerSecond": 50, "gateDamageFactor": 1.5, "captureContributionWeight": 0.5, "spawnIntervalMillis": 500,
            "aggroRadius": 10, "leashDistance": null },
          "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 2, "unlockRound": 1 }, { "entityType": "skeleton", "weight": 1, "unlockRound": 3 } ],
          "specialWaves": { "profiles": [ { "id": 3, "key": "wolves", "name": "Wolves", "countOverride": 4, "countMultiplier": 1, "additive": false,
                                             "monsterTypes": [ { "entityType": "WOLF", "weight": 1, "unlockRound": 1 } ] } ],
                            "entries": [ { "id": 1, "survivalSpecialWaveProfileId": 3, "kind": "Recurring", "interval": 5, "startWave": null, "priority": 1 },
                                         { "id": 2, "survivalSpecialWaveProfileId": 3, "kind": "Explicit", "wave": 3, "priority": 1 } ] },
          "killRewards": { "coinsPerKill": 30, "xpPerKill": 3, "matchCurrencyPerKill": 70, "bonusMultiplier": 1.1 },
          "purchasePoints": [], "mysteryBoxPoints": [],
          "readiness": { "isReady": false, "errors": [ { "code": "mix.empty", "message": "no types" } ], "warnings": [ { "code": "end.wipe-only" } ] }
        }
        """;

    @Test
    void mapsEverySectionOntoTheCoreRecords() throws Exception {
        SurvivalConfiguration c = SurvivalConfigurationMapper.map(JSON.readValue(FULL, ConfigurationDto.class));
        assertEquals(1, c.scenarioId());
        assertEquals(1, c.lobbyId().orElseThrow());
        assertEquals(1, c.humanTeamId());
        assertEquals(2, c.npcTeamId());
        assertEquals(10_000, c.intermissionMillis());
        assertEquals(AllCapturedOverride.ON, c.endConditions().lobbyOverride());
        assertTrue(c.endConditions().allCapturedEnabled());

        assertEquals(2, c.objectives().size());
        assertTrue(c.objective(1).orElseThrow().instaLose());
        assertTrue(c.objective(1).orElseThrow().gateStructureId().isEmpty());
        assertEquals(TargetOverrideMode.DENY, c.objective(2).orElseThrow().override());
        assertEquals(7, c.objective(2).orElseThrow().gateStructureId().orElseThrow());
        assertEquals("world_KNK-DEV", c.objective(2).orElseThrow().capture().orElseThrow().world());
        assertEquals(2, c.objectiveOfGate(7).orElseThrow().id());
        assertEquals(TargetOverrideMode.ALLOW, c.gate(6).orElseThrow().override());
        assertTrue(c.gate(7).orElseThrow().objectiveGate());

        assertEquals(20_000, c.stuck().stuckAfterMillis());
        assertEquals(2, c.stuck().lastEnemiesCount());
        assertEquals(1, c.stuck().respawnLimit());
        assertFalse(c.stuck().backstopEnabled(), "null backstop = off");
        assertEquals(2.0, c.stuck().meaningfulDistance());

        assertEquals(ScalingPreset.HYBRID, c.difficulty().scaling().countCurve().preset());
        assertEquals(6, c.difficulty().scaling().countCurve().base());
        assertEquals(1, c.difficulty().scaling().statCurve().base(), "stat curve base forced to 1 (S30)");
        assertEquals(0.3, c.difficulty().scaling().statWeights().weight(ScaledStat.WALKING_SPEED));
        assertEquals(0.25, c.difficulty().scaling().perPlayer().factor());
        assertEquals(40, c.difficulty().scaling().maxAlive());
        assertEquals(8, c.difficulty().scaling().statCap());
        assertEquals(1.5, c.difficulty().gateDamageFactor());
        assertEquals(0.5, c.difficulty().captureContributionWeight());
        assertEquals(500, c.difficulty().spawnIntervalMillis());
        assertEquals(10, c.difficulty().aggroRadius().orElseThrow());
        assertTrue(c.difficulty().leashDistance().isEmpty());

        assertEquals(2, c.monsterMix().types().size());
        assertEquals("SKELETON", c.monsterMix().types().get(1).id(), "ids are upper-case entity type keys");
        assertEquals(3, c.monsterMix().types().get(1).unlockRound());
        assertEquals(1, c.monsterMix().eligible(1).size());

        assertEquals(4, c.specialProfiles().get("3").count(99));
        assertEquals(2, c.specialSchedule().entries().size());
        assertTrue(c.specialSchedule().entries().get(0) instanceof SpecialWaveEntry.Recurring r && r.interval() == 5 && r.startWave() == 5);
        assertEquals("3", c.specialSchedule().profileIdFor(3).orElseThrow());

        assertEquals(2, c.zones().size());
        SurvivalConfiguration.ZoneSpec outside = c.zone("10").orElseThrow();
        assertEquals(2, outside.spawnPoints().size());
        assertEquals(1, outside.lanePoints().size());
        assertEquals(1485, outside.spawnPoints().get(0).x(), "spawn points keep their sort order");
        assertTrue(outside.zone().isInitial());
        assertEquals(2.0, outside.zone().weight());
        SurvivalConfiguration.ZoneSpec courtyard = c.zone("11").orElseThrow();
        assertEquals(Set.of("2"), courtyard.zone().prerequisites());
        assertEquals(PrerequisitePolicy.ANY, courtyard.zone().policy());
        assertEquals(4.0, courtyard.areaRadius().orElseThrow());
        assertTrue(courtyard.usable());
        assertTrue(courtyard.acceptsWave(9, false));
        assertFalse(courtyard.acceptsWave(10, false));
        assertFalse(courtyard.acceptsWave(2, true));
        assertTrue(courtyard.laneAutoReroute());

        assertEquals(65, c.favorNearPlayersPercent());
        assertEquals(2, c.nearZoneCount());
        assertEquals(70, c.killRewards().matchCurrencyPerKill());
        assertFalse(c.readiness().ready());
        assertEquals("mix.empty: no types", c.readiness().errors().get(0));
        assertEquals("end.wipe-only", c.readiness().warnings().get(0));
        assertTrue(c.generatedAt().startsWith("2026-11-06"));
    }

    @Test
    void missingSectionsFallBackToTheDesignDefaults() throws Exception {
        SurvivalConfiguration c = SurvivalConfigurationMapper.map(JSON.readValue("{\"siegeScenarioId\": 4}", ConfigurationDto.class));
        assertEquals(4, c.scenarioId());
        assertEquals(1, c.minPlayers());
        assertEquals(10_000, c.intermissionMillis());
        assertFalse(c.endConditions().allCapturedEnabled());
        assertEquals(30_000, c.stuck().stuckAfterMillis());
        assertTrue(c.stuck().backstopEnabled());
        assertEquals(ScalingPreset.LINEAR, c.difficulty().scaling().countCurve().preset());
        assertEquals(6, c.difficulty().scaling().countCurve().base());
        assertEquals(40, c.difficulty().scaling().maxAlive());
        assertTrue(c.monsterMix().isEmpty());
        assertTrue(c.zones().isEmpty());
        assertTrue(c.readiness().ready(), "no readiness section = the API didn't object");
        assertEquals(0, c.favorNearPlayersPercent());
        assertEquals(30, c.killRewards().coinsPerKill().intValue());
        assertTrue(c.lobbyId().isEmpty());
    }

    @Test
    void enumNamesAreTolerant() {
        assertEquals(TargetOverrideMode.DENY, TargetOverrideMode.parse(" deny "));
        assertEquals(TargetOverrideMode.STANDARD, TargetOverrideMode.parse(null));
        assertEquals(TargetOverrideMode.STANDARD, TargetOverrideMode.parse("whatever"));
        assertEquals(AllCapturedOverride.OFF, SurvivalConfigurationMapper.parseOverride("OFF"));
        assertEquals(ScalingPreset.EXPONENTIAL, SurvivalConfigurationMapper.parsePreset("Exponential", ScalingPreset.LINEAR));
        assertEquals(ScalingPreset.LINEAR, SurvivalConfigurationMapper.parsePreset("nope", ScalingPreset.LINEAR));
    }
}
