package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.CaptureCalculator;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.SiegeActorKind;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;
import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.npc.FakeMobAttributes;
import net.knightsandkings.knk.paper.npc.NpcPlatform;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ZoneSpec;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lifecycle glue (DESIGN §3): PREPARING → wave 1 planned and spawned at the pace → every enemy dead → intermission
 * (record completed) → next wave with scaled stats; a death during a wave makes a ghost and, with nobody else alive,
 * a team wipe that ends the match and removes every mob. The World mock is a field (CLAUDE.md).
 */
class SurvivalMatchTest {

    private static final String CONFIG = """
        { "siegeScenarioId": 1, "siegeScenarioName": "Test", "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2,
          "allowRecapture": false, "intermissionMillis": 2000,
          "endConditions": { "scenarioAllCaptured": false, "lobbyOverride": "Inherit" },
          "objectives": [ { "id": 1, "name": "Throne", "instaLose": false, "captureLocation": { "x": 50, "y": 64, "z": 50, "world": "w" } } ],
          "gates": [],
          "stuck": { "stuckAfterMillis": 30000, "lastEnemiesCount": 3, "respawnLimit": 2, "roundBackstopMillis": 300000, "meaningfulDistance": 1.0 },
          "spawn": { "favorNearPlayersPercent": 0, "nearZoneCount": 1, "zones": [
            { "id": 10, "name": "Outside", "weight": 1, "anchorLocation": { "x": 0, "y": 64, "z": 0, "world": "w" },
              "points": [ { "id": 1, "kind": "Spawn", "sortOrder": 0, "location": { "x": 0, "y": 64, "z": 0, "world": "w" } } ] } ] },
          "difficulty": { "id": 1, "name": "Normal",
            "countCurve": { "preset": "Linear", "base": 2, "step": 1 },
            "statCurve": { "preset": "Linear", "base": 1, "step": 0.5 },
            "statWeights": { "health": 1, "walkingSpeed": 1, "meleeDamage": 1, "rangedDamage": 1, "attackSpeed": 1 },
            "perPlayer": { "factor": 0, "applyToStats": false }, "maxAlive": 40, "statCap": 8, "countCap": 0,
            "spawnIntervalMillis": 500, "gateDamageFactor": 1, "captureContributionWeight": 1 },
          "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1, "unlockRound": 1 } ],
          "readiness": { "isReady": true } }
        """;

    private final World world = mock(World.class);
    private final Plugin plugin = mock(Plugin.class);
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<SurvivalEndReason> ended = new ArrayList<>();
    private final List<Mob> spawned = new ArrayList<>();
    private final SurvivalSpawner spawner = mock(SurvivalSpawner.class);
    private final MatchActorRegistry actors;
    private final NpcPlatform platform;
    private final KnkSiegeScenario scenario = SurvivalModeHooksTest.scenario(1);
    private final SiegeMatch siege = mock(SiegeMatch.class);
    private final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
    private final Player player = player();
    private final SurvivalMatch match;

    SurvivalMatchTest() throws Exception {
        when(plugin.getName()).thenReturn("knk");
        when(plugin.namespace()).thenReturn("knk");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(world.getName()).thenReturn("w");
        actors = new MatchActorRegistry(plugin);
        platform = new NpcPlatform(plugin, NpcStatPipeline.vanilla(), new FakeMobAttributes(), null, () -> 0L, 96);
        SurvivalConfiguration config = SurvivalConfigurationMapper.map(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(CONFIG, ConfigurationDto.class));

        SiegeMatchRoster roster = new SiegeMatchRoster(Map.of(1, List.of(player.getUniqueId()), 2, List.of()));
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(KnkSiegeConfiguration.legacyDefaults()), alliances);
        when(siege.roster()).thenReturn(roster);
        when(siege.alliances()).thenReturn(alliances);
        when(siege.board()).thenReturn(board);
        when(siege.scenario()).thenReturn(scenario);
        when(lobby.id()).thenReturn(1);
        when(lobby.key()).thenReturn("test");

        when(spawner.spawn(any(PlannedEnemy.class), any(ZoneSpec.class), anyInt(), anyInt(), anyDouble(), anyMap()))
            .thenAnswer(inv -> {
                Zombie zombie = mock(Zombie.class);
                UUID id = UUID.randomUUID();
                when(zombie.getUniqueId()).thenReturn(id);
                when(zombie.isValid()).thenReturn(true);
                when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
                when(zombie.getLocation()).thenReturn(new Location(world, 0, 64, 0));
                when(zombie.getWorld()).thenReturn(world);
                when(zombie.getHealth()).thenReturn(20.0);
                PersistentDataContainer pdc = mock(PersistentDataContainer.class);
                when(zombie.getPersistentDataContainer()).thenReturn(pdc);
                actors.register(zombie, 1, 2, 1.0);
                spawned.add(zombie);
                return Optional.of(zombie);
            });

        SurvivalGhosts ghosts = new SurvivalGhosts(mock(PlayerVisibilityArbiter.class), new GhostTeam("siege:1:1", id -> true), p -> Optional.empty());
        SurvivalMatch.Services services = new SurvivalMatch.Services(spawner, platform, actors, null, null, online::get,
            clock::get, new java.util.Random(1), null);
        match = new SurvivalMatch(lobby, siege, config, SurvivalSettings.defaults(), services, ghosts, ended::add);
    }

    private Player player() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(true);
        when(p.getLocation()).thenReturn(new Location(world, 50, 64, 50));
        when(p.getFoodLevel()).thenReturn(20);
        online.put(id, p);
        return p;
    }

    private void advance(long millis) {
        clock.addAndGet(millis);
        match.tick();
    }

    @Test
    void wavesSpawnAtThePaceCompleteIntoAnIntermissionAndScale() {
        match.start();
        assertEquals(SurvivalSubstate.PREPARING, match.rounds().substate());
        match.tick();
        assertTrue(spawned.isEmpty());

        advance(2_100); // preparation over: wave 1 (2 enemies) starts, the first spawns at once
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, match.rounds().substate());
        assertEquals(1, match.rounds().currentRound());
        assertEquals(1, spawned.size());
        advance(100);
        assertEquals(1, spawned.size(), "spawn interval 500 ms not reached");
        advance(500);
        assertEquals(2, spawned.size());
        assertEquals(2, match.enemies().size());
        assertTrue(match.rounds().currentQueue().orElseThrow().isQueueExhausted());

        match.onMobDeath(spawned.get(0), player);
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, match.rounds().substate(), "one still alive");
        match.onMobDeath(spawned.get(1), null);
        assertEquals(SurvivalSubstate.INTERMISSION, match.rounds().substate());
        assertEquals(1, match.record().completed());
        assertEquals(1, match.record().reached());
        assertEquals(0, actors.actors(1).size(), "dead actors are unregistered");
        assertTrue(match.enemies().isEmpty());

        advance(2_100); // intermission over: round 2 with 3 enemies at ×1.5 stats
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, match.rounds().substate());
        assertEquals(2, match.rounds().currentRound());
        assertEquals(3, match.rounds().currentQueue().orElseThrow().plannedCount());
        verify(spawner).spawn(any(PlannedEnemy.class), any(ZoneSpec.class), eq(1), eq(2), eq(1.0),
            eq(Map.of(ScaledStat.HEALTH, 1.5, ScaledStat.WALKING_SPEED, 1.5, ScaledStat.MELEE_DAMAGE, 1.5,
                ScaledStat.RANGED_DAMAGE, 1.5, ScaledStat.ATTACK_SPEED, 1.5)));
        assertTrue(ended.isEmpty());
    }

    @Test
    void theLastLivingPlayerDyingInAWaveIsATeamWipe() {
        match.start();
        advance(2_100);
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, match.rounds().substate());
        assertEquals(1, spawned.size());

        match.onPlayerDeath(player);
        assertTrue(match.ghosts().isGhost(player.getUniqueId()));
        assertEquals(List.of(SurvivalEndReason.TEAM_WIPE), ended);
        assertTrue(match.ending());
        assertEquals(SurvivalSubstate.ENDING, match.rounds().substate());
        verify(spawned.get(0)).remove();
        assertEquals(0, actors.actors(1).size());
        assertEquals(1, match.record().reached());
        assertEquals(0, match.record().completed());
        match.cleanup();
        assertFalse(match.ghosts().isGhost(player.getUniqueId()));
    }

    @Test
    void aDeathDuringTheBreakIsAGhostButNoWipe() {
        match.start();
        advance(2_100);
        match.onMobDeath(spawned.get(0), player);
        advance(500);
        match.onMobDeath(spawned.get(1), player);
        assertEquals(SurvivalSubstate.INTERMISSION, match.rounds().substate());
        match.onPlayerDeath(player);
        assertTrue(match.ghosts().isGhost(player.getUniqueId()));
        assertTrue(ended.isEmpty(), "S41: deaths during the break never count toward a wipe");
        advance(2_100);
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, match.rounds().substate());
        assertFalse(match.ghosts().isGhost(player.getUniqueId()), "released at the next round start");
    }

    @Test
    void anNpcCaptureOfAnInstaLoseObjectiveEndsTheMatch() throws Exception {
        SurvivalConfiguration insta = SurvivalConfigurationMapper.map(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(CONFIG.replace("\"instaLose\": false", "\"instaLose\": true"), ConfigurationDto.class));
        SurvivalGhosts ghosts = new SurvivalGhosts(mock(PlayerVisibilityArbiter.class), new GhostTeam("siege:1:1", id -> true), p -> Optional.empty());
        SurvivalMatch.Services services = new SurvivalMatch.Services(spawner, platform, actors, null, null, online::get,
            clock::get, new java.util.Random(1), null);
        SurvivalMatch m = new SurvivalMatch(lobby, siege, insta, SurvivalSettings.defaults(), services, ghosts, ended::add);
        m.start();
        // The NPC team (2) captures objective 1 on the board: a weighted NPC presence until the points run out.
        UUID npc = UUID.randomUUID();
        for (int i = 0; i < 200 && siege.board().objective(1).holderTeamId() == 1; i++) {
            siege.board().step(Map.of(1, List.of(ObjectiveState.Presence.npc(npc, 2, 1.0, 50.0))));
        }
        assertEquals(2, siege.board().objective(1).holderTeamId());
        m.onCapture(siege.board().objective(1).captures().get(0));
        assertEquals(List.of(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED), ended);
    }
}
