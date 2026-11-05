package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchCheckpointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PreviewDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Survival phase 1 (PandiO/knk-workspace#41, link S4): the wire shapes of knk-web-api's SiegeSurvivalDtos.cs. */
public class SiegeSurvivalDtosTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A frozen configuration as GET api/siege-survival/scenarios/100/configuration?lobbyId=7 returns it. */
    public static final String CONFIGURATION_JSON = """
            {"generatedAt":"2026-11-05T20:00:00Z","siegeScenarioId":100,"siegeScenarioName":"Siege of Cinix","siegeLobbyId":7,
             "enabled":true,"minPlayers":1,"humanTeamId":201,"npcTeamId":202,"allowRecapture":false,"intermissionMillis":10000,
             "equipment":{"mode":"Kits","selectedKits":[{"kitId":70,"kitName":"Swordsman","sortOrder":0}],"defaultKitId":70,"baselineLoadoutKitId":null},
             "endConditions":{"scenarioAllCaptured":false,"lobbyOverride":"On","allCapturedEnabled":true,"instaLoseObjectiveIds":[501]},
             "objectives":[{"id":501,"name":"Keep","instaLose":true,"gateStructureId":null,"captureLocation":{"id":1004,"name":"loc1004","x":0,"y":64,"z":0,"world":"world"},"targetOverride":"Standard"},
                           {"id":502,"name":"Gatehouse","instaLose":false,"gateStructureId":400,"captureLocation":{"id":1003,"x":0,"y":64,"z":0,"world":"world"},"targetOverride":"Deny"}],
             "gates":[{"gateStructureId":400,"name":"Main gate","isObjectiveGate":true,"targetOverride":"Allow"}],
             "stuck":{"stuckAfterMillis":30000,"lastEnemiesCount":3,"respawnLimit":2,"roundBackstopMillis":null,"meaningfulDistance":1.0},
             "spawn":{"favorNearPlayersPercent":65,"nearZoneCount":1,"zones":[
                {"id":30,"siegeScenarioId":100,"name":"Outside","sortOrder":0,"weight":1,"anchorLocationId":1002,"anchorLocation":{"id":1002,"x":100,"y":64,"z":0,"world":"world"},
                 "areaRadius":null,"prerequisitePolicy":"All","prerequisiteObjectiveIds":[],"minWave":1,"maxWave":null,"forNormalWaves":true,"forSpecialWaves":true,"laneAutoReroute":false,
                 "points":[{"id":31,"survivalSpawnZoneId":30,"kind":"Spawn","sortOrder":0,"name":null,"locationId":1002,"location":{"id":1002,"x":100,"y":64,"z":0,"world":"world"}},
                           {"id":33,"survivalSpawnZoneId":30,"kind":"Lane","sortOrder":0,"name":"Road","locationId":1003,"location":{"id":1003,"x":50,"y":64,"z":0,"world":"world"}}]},
                {"id":32,"siegeScenarioId":100,"name":"Courtyard","sortOrder":1,"weight":2,"anchorLocationId":1003,"anchorLocation":{"id":1003,"x":0,"y":64,"z":0,"world":"world"},
                 "areaRadius":6,"prerequisitePolicy":"Any","prerequisiteObjectiveIds":[502],"minWave":1,"maxWave":null,"forNormalWaves":true,"forSpecialWaves":false,"laneAutoReroute":true,"points":[]}]},
             "difficulty":{"id":10,"name":"Normal","isDefault":true,
                "countCurve":{"preset":"Linear","base":6,"step":2,"multiplier":1.1,"switchRound":9},
                "statCurve":{"preset":"Hybrid","base":1,"step":0.1,"multiplier":1.1,"switchRound":9},
                "statWeights":{"health":1,"walkingSpeed":0.3,"meleeDamage":1,"rangedDamage":1,"attackSpeed":0.5},
                "perPlayer":{"factor":0.25,"applyToStats":false},"maxAlive":40,"statCap":1.5,"countCap":0,
                "repairRatePerSecond":50,"gateDamageFactor":1,"captureContributionWeight":1,"spawnIntervalMillis":500,"aggroRadius":null,"leashDistance":32},
             "difficultyProfiles":[{"id":10,"name":"Normal","isDefault":true}],
             "monsterTypes":[{"entityType":"ZOMBIE","weight":2,"unlockRound":1},{"entityType":"SKELETON","weight":1,"unlockRound":3}],
             "specialWaves":{"profiles":[{"id":20,"key":"wolves","name":"Wolf pack","countOverride":null,"countMultiplier":1.5,"additive":false,"monsterTypes":[{"entityType":"WOLF","weight":1,"unlockRound":1}]}],
                             "entries":[{"id":25,"siegeScenarioId":100,"survivalSpecialWaveProfileId":20,"profileKey":"wolves","kind":"Recurring","interval":5,"startWave":null,"priority":1,"wave":null,"sortOrder":0},
                                        {"id":26,"siegeScenarioId":100,"survivalSpecialWaveProfileId":20,"profileKey":"wolves","kind":"Explicit","interval":null,"startWave":null,"priority":1,"wave":7,"sortOrder":1}]},
             "killRewards":{"coinsPerKill":30,"xpPerKill":3.3,"matchCurrencyPerKill":70,"bonusMultiplier":1.1},
             "purchasePoints":[{"id":36,"siegeScenarioId":100,"name":"Armory","sortOrder":0,"locationId":1001,"location":{"id":1001,"x":0,"y":64,"z":0,"world":"world"},"interactionRange":3,
                "offers":[{"id":37,"survivalPurchasePointId":36,"itemBlueprintId":80,"itemBlueprintName":"Iron sword","quantity":1,"price":500,"sortOrder":0}]}],
             "mysteryBoxPoints":[{"id":50,"name":"Box","location":{"id":1004,"x":0,"y":64,"z":0,"world":"world"},"interactionRange":3,"blockType":"minecraft:end_portal_frame","rollPrice":1000,"claimTimeoutMillis":10000,
                "pool":{"id":40,"siegeScenarioId":100,"name":"Weapons","variant":"Items","totalChancePercent":100,
                        "entries":[{"id":401,"survivalMysteryBoxPoolId":40,"sortOrder":0,"itemBlueprintId":80,"itemBlueprintName":"Iron sword","enchantmentDefinitionId":null,"enchantmentKey":null,"enchantmentLevel":null,
                                    "chancePercent":60.5,"chanceBasisPoints":6050,
                                    "enchantments":[{"id":4001,"enchantmentDefinitionId":90,"enchantmentKey":"minecraft:sharpness","level":2,"chancePercent":25,"chanceBasisPoints":2500}]},
                                   {"id":402,"survivalMysteryBoxPoolId":40,"sortOrder":1,"itemBlueprintId":81,"itemBlueprintName":"Bow","chancePercent":39.5,"chanceBasisPoints":3950,"enchantments":[]}]}},
                {"id":51,"name":"Book box","location":{"id":1004,"x":0,"y":64,"z":0,"world":"world"},"interactionRange":3,"blockType":"minecraft:end_portal_frame","rollPrice":1500,"claimTimeoutMillis":10000,
                "pool":{"id":41,"siegeScenarioId":100,"name":"Books","variant":"EnchantedBooks","totalChancePercent":100,
                        "entries":[{"id":411,"survivalMysteryBoxPoolId":41,"sortOrder":0,"itemBlueprintId":null,"enchantmentDefinitionId":90,"enchantmentKey":"minecraft:sharpness","enchantmentLevel":3,"chancePercent":70,"chanceBasisPoints":7000,"enchantments":[]},
                                   {"id":412,"survivalMysteryBoxPoolId":41,"sortOrder":1,"enchantmentDefinitionId":91,"enchantmentKey":"minecraft:power","enchantmentLevel":1,"chancePercent":30,"chanceBasisPoints":3000,"enchantments":[]}]}}],
             "readiness":{"siegeScenarioId":100,"siegeLobbyId":7,"difficultyProfileId":10,"isReady":true,"errors":[],
                          "warnings":[{"code":"scaling.walking-speed-fast","message":"fast","entityType":"SurvivalDifficultyProfile","entityId":10}]},
             "somethingNewer":{"ignored":true}}
            """;

    @Test
    void readsTheFrozenConfiguration() throws Exception {
        ConfigurationDto config = objectMapper.readValue(CONFIGURATION_JSON, ConfigurationDto.class);

        assertEquals(OffsetDateTime.parse("2026-11-05T20:00:00Z"), config.generatedAt());
        assertEquals(100, config.siegeScenarioId());
        assertEquals(7, config.siegeLobbyId());
        assertEquals(201, config.humanTeamId());
        assertEquals(202, config.npcTeamId());
        assertEquals("Kits", config.equipment().mode());
        assertEquals(70, config.equipment().defaultKitId());
        assertNull(config.equipment().baselineLoadoutKitId());
        assertEquals("Swordsman", config.equipment().selectedKits().get(0).kitName());

        assertEquals("On", config.endConditions().lobbyOverride());
        assertTrue(config.endConditions().allCapturedEnabled());
        assertEquals(List.of(501), config.endConditions().instaLoseObjectiveIds());
        assertEquals("Deny", config.objectives().get(1).targetOverride());
        assertEquals(1003, config.objectives().get(1).captureLocation().id());
        assertEquals("Allow", config.gates().get(0).targetOverride());
        assertTrue(config.gates().get(0).isObjectiveGate());

        assertNull(config.stuck().roundBackstopMillis());
        assertEquals(30000, config.stuck().stuckAfterMillis());
        assertEquals(65, config.spawn().favorNearPlayersPercent());
        assertEquals(2, config.spawn().zones().size());
        assertEquals("Lane", config.spawn().zones().get(0).points().get(1).kind());
        assertEquals(100.0, config.spawn().zones().get(0).anchorLocation().x());
        assertEquals("Any", config.spawn().zones().get(1).prerequisitePolicy());
        assertEquals(List.of(502), config.spawn().zones().get(1).prerequisiteObjectiveIds());
        assertEquals(6.0, config.spawn().zones().get(1).areaRadius());
        assertTrue(config.spawn().zones().get(1).laneAutoReroute());

        assertEquals("Hybrid", config.difficulty().statCurve().preset());
        assertEquals(1.0, config.difficulty().statCurve().base());
        assertEquals(0.3, config.difficulty().statWeights().walkingSpeed());
        assertEquals(0.25, config.difficulty().perPlayer().factor());
        assertFalse(config.difficulty().perPlayer().applyToStats());
        assertNull(config.difficulty().aggroRadius());
        assertEquals(32.0, config.difficulty().leashDistance());
        assertEquals(1, config.difficultyProfiles().size());

        assertEquals("SKELETON", config.monsterTypes().get(1).entityType());
        assertEquals(3, config.monsterTypes().get(1).unlockRound());
        assertEquals("wolves", config.specialWaves().profiles().get(0).key());
        assertEquals(1.5, config.specialWaves().profiles().get(0).countMultiplier());
        assertEquals("Recurring", config.specialWaves().entries().get(0).kind());
        assertEquals(5, config.specialWaves().entries().get(0).interval());
        assertEquals(7, config.specialWaves().entries().get(1).wave());

        assertEquals(new BigDecimal("3.3"), config.killRewards().xpPerKill());
        assertEquals(70, config.killRewards().matchCurrencyPerKill());
        assertEquals("Iron sword", config.purchasePoints().get(0).offers().get(0).itemBlueprintName());
        assertEquals(500, config.purchasePoints().get(0).offers().get(0).price());

        assertEquals("minecraft:end_portal_frame", config.mysteryBoxPoints().get(0).blockType());
        assertEquals("Items", config.mysteryBoxPoints().get(0).pool().variant());
        assertEquals(6050, config.mysteryBoxPoints().get(0).pool().entries().get(0).chanceBasisPoints());
        assertEquals(2500, config.mysteryBoxPoints().get(0).pool().entries().get(0).enchantments().get(0).chanceBasisPoints());
        assertEquals("EnchantedBooks", config.mysteryBoxPoints().get(1).pool().variant());
        assertEquals(3, config.mysteryBoxPoints().get(1).pool().entries().get(0).enchantmentLevel());
        assertEquals(1500, config.mysteryBoxPoints().get(1).rollPrice());

        assertTrue(config.readiness().isReady());
        assertEquals("scaling.walking-speed-fast", config.readiness().warnings().get(0).code());
        assertEquals(10, config.readiness().warnings().get(0).entityId());
    }

    @Test
    void readsThePreview() throws Exception {
        PreviewDto preview = objectMapper.readValue("""
                {"difficultyProfileId":10,"players":2,"rounds":[{"round":1,"enemyCount":7,"curveValue":1.0,"health":1.0,"walkingSpeed":1.0,"meleeDamage":1.0,"rangedDamage":1.0,"attackSpeed":1.0},
                 {"round":2,"enemyCount":10,"curveValue":1.1,"health":1.1,"walkingSpeed":1.03,"meleeDamage":1.1,"rangedDamage":1.1,"attackSpeed":1.05}]}
                """, PreviewDto.class);
        assertEquals(2, preview.rounds().size());
        assertEquals(10, preview.rounds().get(1).enemyCount());
        assertEquals(1.03, preview.rounds().get(1).walkingSpeed());
    }

    @Test
    void readsTheResultAndCheckpointContracts() throws Exception {
        MatchResultDto result = objectMapper.readValue("""
                {"siegeMatchId":41,"siegeLobbyId":7,"siegeScenarioId":100,"difficultyProfileId":10,"configurationGeneratedAt":"2026-11-05T20:00:00Z",
                 "startedAt":"2026-11-05T20:05:00Z","endedAt":"2026-11-05T20:40:00Z","endReason":"TeamWipe","reachedWave":10,"completedWave":9,
                 "earnedKills":120,"systemKills":3,
                 "players":[{"userId":3,"presentAtEnd":true,"leftAt":null,"kitId":70,"kills":60,"bonusKills":4,"deaths":2,"repairWork":1500.5,
                             "matchCurrencyEarned":4228,"matchCurrencySpent":3000,"finalMatchBalance":1228,"coinsAccrued":1812,"xpAccrued":181.2,"coinsSettled":1812,"xpSettled":181}],
                 "settlement":{"settlesRewards":true,"totalCoins":1812,"totalXp":181,"ledgerKey":"siege-match:41"}}
                """, MatchResultDto.class);
        assertEquals("TeamWipe", result.endReason());
        assertEquals(10, result.reachedWave());
        assertEquals(9, result.completedWave());
        assertEquals(new BigDecimal("181.2"), result.players().get(0).xpAccrued());
        assertEquals(181, result.players().get(0).xpSettled());
        assertEquals(1228L, result.players().get(0).finalMatchBalance());
        assertTrue(result.settlement().settlesRewards());
        assertEquals("siege-match:41", result.settlement().ledgerKey());

        MatchCheckpointDto checkpoint = objectMapper.readValue("""
                {"siegeMatchId":41,"siegeScenarioId":100,"difficultyProfileId":10,"capturedAt":"2026-11-05T20:20:00Z","substate":"WaveActive","wave":5,"completedWave":4,
                 "specialProfileKey":"wolves","aliveEnemies":12,"queuedEnemies":8,"npcHeldObjectiveIds":[502],"activeSpawnZoneIds":[30,32],
                 "doorRepairs":[{"gateDoorId":9,"destroyed":true,"repairWork":400,"repairTarget":1000,"repairers":2}],
                 "players":[{"userId":3,"ghost":true,"kitId":70,"matchBalance":770,"kills":10,"deaths":1,"pendingMysteryBoxRollId":"roll-1"}]}
                """, MatchCheckpointDto.class);
        assertEquals("WaveActive", checkpoint.substate());
        assertEquals("wolves", checkpoint.specialProfileKey());
        assertEquals(List.of(30, 32), checkpoint.activeSpawnZoneIds());
        assertTrue(checkpoint.doorRepairs().get(0).destroyed());
        assertTrue(checkpoint.players().get(0).ghost());
        assertEquals("roll-1", checkpoint.players().get(0).pendingMysteryBoxRollId());
    }
}
