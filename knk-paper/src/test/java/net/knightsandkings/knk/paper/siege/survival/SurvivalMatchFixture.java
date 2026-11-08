package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.CaptureCalculator;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
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
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A running {@link SurvivalMatch} for the phase 5 tests (link S8b): the S5 test configuration (2 enemies in round 1,
 * +1 per round, 500 ms spawn pace, 2 s intermission), N mocked online players on team 1 with API user ids 100+n, a
 * manual clock, a spawner that hands out mocked zombies. The World mock is a field (CLAUDE.md).
 */
public final class SurvivalMatchFixture {

    public static final String CONFIG = """
        { "siegeScenarioId": 1, "siegeScenarioName": "Test", "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2,
          "allowRecapture": false, "intermissionMillis": 2000, "generatedAt": "2026-11-08T10:00:00Z",
          "endConditions": { "scenarioAllCaptured": false, "lobbyOverride": "Inherit" },
          "objectives": [ { "id": 1, "name": "Throne", "instaLose": false, "captureLocation": { "x": 50, "y": 64, "z": 50, "world": "w" } } ],
          "gates": [],
          "stuck": { "stuckAfterMillis": 30000, "lastEnemiesCount": 3, "respawnLimit": 2, "roundBackstopMillis": 300000, "meaningfulDistance": 1.0 },
          "spawn": { "favorNearPlayersPercent": 0, "nearZoneCount": 1, "zones": [
            { "id": 10, "name": "Outside", "weight": 1, "anchorLocation": { "x": 0, "y": 64, "z": 0, "world": "w" },
              "points": [ { "id": 1, "kind": "Spawn", "sortOrder": 0, "location": { "x": 0, "y": 64, "z": 0, "world": "w" } } ] } ] },
          "difficulty": { "id": 7, "name": "Normal",
            "countCurve": { "preset": "Linear", "base": 2, "step": 1 },
            "statCurve": { "preset": "Linear", "base": 1, "step": 0.5 },
            "statWeights": { "health": 1, "walkingSpeed": 1, "meleeDamage": 1, "rangedDamage": 1, "attackSpeed": 1 },
            "perPlayer": { "factor": 0, "applyToStats": false }, "maxAlive": 40, "statCap": 8, "countCap": 0,
            "spawnIntervalMillis": 500, "gateDamageFactor": 1, "captureContributionWeight": 1 },
          "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1, "unlockRound": 1 } ],
          %SPECIAL%
          "readiness": { "isReady": true } }
        """;

    /** A special profile (id 3, key wolves, name Wolves) played at wave 1 and every 5th wave. */
    public static final String SPECIAL_AT_WAVE_1 = """
        "specialWaves": { "profiles": [ { "id": 3, "key": "wolves", "name": "Wolves", "countOverride": 2, "countMultiplier": 1, "additive": false,
                                           "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1, "unlockRound": 1 } ] } ],
                          "entries": [ { "id": 1, "survivalSpecialWaveProfileId": 3, "kind": "Explicit", "wave": 1, "priority": 1 },
                                       { "id": 2, "survivalSpecialWaveProfileId": 3, "kind": "Recurring", "interval": 5, "startWave": null, "priority": 1 } ] },
        """;

    public final World world = mock(World.class);
    public final Plugin plugin = mock(Plugin.class);
    public final AtomicLong clock = new AtomicLong(1_000_000);
    public final Map<UUID, Player> online = new HashMap<>();
    public final Map<UUID, Integer> userIds = new LinkedHashMap<>();
    public final List<SurvivalEndReason> ended = new ArrayList<>();
    public final List<Mob> spawned = new ArrayList<>();
    public final SurvivalSpawner spawner = mock(SurvivalSpawner.class);
    public final MatchActorRegistry actors;
    public final NpcPlatform platform;
    public final KnkSiegeScenario scenario = SurvivalModeHooksTest.scenario(1);
    public final SiegeMatch siege = mock(SiegeMatch.class);
    public final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
    public final List<Player> players = new ArrayList<>();
    public final SurvivalConfiguration config;
    public final SurvivalMatch match;
    public OptionalLong apiMatchId = OptionalLong.empty();

    public SurvivalMatchFixture(int playerCount) throws Exception {
        this(playerCount, "");
    }

    public SurvivalMatchFixture(int playerCount, String specialJson) throws Exception {
        when(plugin.getName()).thenReturn("knk");
        when(plugin.namespace()).thenReturn("knk");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(world.getName()).thenReturn("w");
        actors = new MatchActorRegistry(plugin);
        platform = new NpcPlatform(plugin, NpcStatPipeline.vanilla(), new FakeMobAttributes(), null, () -> 0L, 96);
        config = SurvivalConfigurationMapper.map(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(CONFIG.replace("%SPECIAL%", specialJson), ConfigurationDto.class));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            Player p = player("player" + (i + 1));
            players.add(p);
            ids.add(p.getUniqueId());
            userIds.put(p.getUniqueId(), 100 + i + 1);
        }
        SiegeMatchRoster roster = new SiegeMatchRoster(Map.of(1, ids, 2, List.of()));
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(KnkSiegeConfiguration.legacyDefaults()), alliances);
        when(siege.roster()).thenReturn(roster);
        when(siege.alliances()).thenReturn(alliances);
        when(siege.board()).thenReturn(board);
        when(siege.scenario()).thenReturn(scenario);
        when(siege.startedAt()).thenReturn(Instant.parse("2026-11-08T12:00:00Z"));
        when(lobby.id()).thenReturn(1);
        when(lobby.key()).thenReturn("test");
        when(lobby.displayName()).thenReturn("Test lobby");
        when(lobby.apiMatchId()).thenAnswer(inv -> apiMatchId);
        when(lobby.userIdOf(any(UUID.class))).thenAnswer(inv -> {
            Integer id = userIds.get(inv.<UUID>getArgument(0));
            return id == null ? OptionalInt.empty() : OptionalInt.of(id);
        });
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

    private Player player(String name) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.getLocation()).thenReturn(new Location(world, 50, 64, 50));
        when(p.getFoodLevel()).thenReturn(20);
        online.put(id, p);
        return p;
    }

    public void advance(long millis) {
        clock.addAndGet(millis);
        match.tick();
    }

    /** Start the match and run into wave 1 with its first enemy spawned. */
    public void startIntoWave1() {
        match.start();
        advance(2_100);
    }

    /** Spawns the rest of the current round's queue (500 ms pace). */
    public void spawnAll() {
        for (int i = 0; i < 50 && !match.rounds().currentQueue().orElseThrow().isQueueExhausted(); i++) advance(500);
    }
}
