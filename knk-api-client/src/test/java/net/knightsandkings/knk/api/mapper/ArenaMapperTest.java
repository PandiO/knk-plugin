package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaDto;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;

/** Arena duels KNG-210 (areas rev. 6): GET api/Arenas (knk-web-api Dtos/ArenaDtos.cs) to the core records. */
class ArenaMapperTest {

    private final ObjectMapper json = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private List<KnkArena> read(String body) throws Exception {
        return ArenaMapper.toCore(json.readValue(body, new TypeReference<List<ArenaDto>>() {}));
    }

    @Test
    void aFullArenaMapsEveryField() throws Exception {
        List<KnkArena> arenas = read("""
            [{"id":5,"name":"Arena","key":"arena_2","description":"d","wgRegionId":"arena_2","worldName":"world",
              "districtId":10,"streetId":3,"houseNumber":0,"locationId":null,
              "exitLocationId":null,"exitLocation":null,
              "isDuelEnabled":true,"maxQueuedMatches":2,"maxOffers":4,"offerSeconds":45,
              "allowCoinStakes":true,"allowItemStakes":false,"maxCoinStake":5000,"maxItemStacks":3,
              "minStakeRatioPercent":0,"feePercent":10,"stakeMinAccountAgeHours":72,"stakeMinTitleBracketId":3,
              "maxStakedDuelsPerPairPerDay":1,"pairCooldownMinutes":30,"dailyCoinWinCap":0,
              "allowOwnGearKeep":true,"allowOwnGearDrop":true,"allowFixedKit":true,"allowKitChoice":true,
              "defaultGearMode":"KIT_CHOICE","warmupSeconds":20,"countdownSeconds":5,"fightTimeLimitSeconds":0,
              "maxWaitSeconds":60,"winnerLingerSeconds":3,"announceMatches":false,"announceFriendly":false,
              "eligibleKits":[{"arenaId":5,"kitId":8,"sequenceNumber":1,"kitName":"Archer"},
                              {"arenaId":5,"kitId":7,"sequenceNumber":0,"kitName":"Knight"}],
              "areas":[{"id":21,"arenaId":5,"kind":"BATTLEGROUND","key":"bg2","name":"Pit 2","sortOrder":1,"isEnabled":true,
                        "spawnALocation":{"id":1,"world":"world"},"spawnBLocation":{"id":2,"world":"world"},
                        "regions":[{"id":210,"wgRegionId":"arena_5_battleground_210","worldName":"world"},
                                   {"id":211,"wgRegionId":"arena_5_battleground_211","worldName":"world"}]},
                       {"id":20,"arenaId":5,"kind":"battleground","key":"bg1","sortOrder":0,"isEnabled":false,"regions":[]},
                       {"id":30,"arenaId":5,"kind":"SPECTATOR","key":"stands","name":"Stands","sortOrder":0,"isEnabled":true,
                        "arrivalLocation":{"id":3,"world":"world"},"teleportMinTitleBracketId":4,"teleportPriceGems":10,
                        "regions":[{"id":300,"wgRegionId":"arena_5_spectator_300","worldName":"world"}]},
                       {"id":40,"arenaId":5,"kind":"LOBBY","key":"lobby","isEnabled":true,
                        "arrivalLocation":{"id":100,"name":"lobby","x":1.5,"y":64,"z":2.5,"yaw":90,"pitch":0,"world":"world"},
                        "regions":[{"id":400,"wgRegionId":"arena_5_lobby_400","worldName":"world"}]},
                       {"id":50,"arenaId":5,"kind":"SWORDS","key":"x"}],
              "subRegions":[{"id":9,"wgRegionId":"domain_5_sub_9"}]}]
            """);

        KnkArena arena = arenas.get(0);
        assertEquals("arena_2", arena.key());
        assertEquals(100, arena.lobbyLocation().id());
        assertNull(arena.exitLocation());
        assertEquals(4, arena.maxOffers());
        assertFalse(arena.allowItemStakes());
        assertEquals(10, arena.feePercent());
        assertEquals(72, arena.stakeMinAccountAgeHours());
        assertEquals(3, arena.stakeMinTitleBracketId());
        assertEquals(KnkArena.DuelGearMode.KIT_CHOICE, arena.defaultGearMode());
        assertEquals(0, arena.fightTimeLimitSeconds());
        assertFalse(arena.announceMatches());
        // kits by sequence number, battlegrounds by sort order
        assertEquals(List.of(7, 8), arena.eligibleKits().stream().map(k -> k.kitId()).toList());
        assertEquals("Knight", arena.defaultKit().orElseThrow().kitName());
        assertEquals(List.of(20, 21), arena.battlegrounds().stream().map(b -> b.id()).toList());
        assertEquals(List.of(21), arena.usableBattlegrounds().stream().map(b -> b.id()).toList());
        assertEquals(List.of("arena_5_battleground_210", "arena_5_battleground_211"), arena.usableBattlegrounds().get(0).regionIds());
        assertEquals("bg1", arena.battlegrounds().get(0).displayName());
        var stands = arena.spectateTarget().orElseThrow();
        assertEquals((Integer) 4, stands.teleportMinTitleBracketId());
        assertEquals((Integer) 10, stands.teleportPriceGems());
        assertEquals(3, stands.arrival().id());
        // an unknown kind is dropped
        assertEquals(4, arena.areas().size());
        assertEquals(List.of("arena_2", "domain_5_sub_9"), arena.ownRegionIds());
    }

    @Test
    void missingSettingsTakeTheDesignDefaults() throws Exception {
        KnkArena arena = read("[{\"id\":1,\"key\":\"a\",\"name\":\"A\",\"wgRegionId\":\"a\"}]").get(0);

        assertTrue(arena.duelEnabled());
        assertEquals(3, arena.maxQueuedMatches());
        assertEquals(3, arena.maxOffers());
        assertEquals(60, arena.offerSeconds());
        assertEquals(10_000, arena.maxCoinStake());
        assertEquals(9, arena.maxItemStacks());
        assertEquals(50, arena.minStakeRatioPercent());
        assertEquals(5, arena.feePercent());
        assertNull(arena.stakeMinAccountAgeHours());
        assertEquals(3, arena.maxStakedDuelsPerPairPerDay());
        assertEquals(10, arena.pairCooldownMinutes());
        assertEquals(25_000, arena.dailyCoinWinCap());
        assertTrue(arena.allowOwnGearKeep());
        assertFalse(arena.allowOwnGearDrop());
        assertEquals(KnkArena.DuelGearMode.OWN_GEAR_KEEP, arena.defaultGearMode());
        assertEquals(30, arena.warmupSeconds());
        assertEquals(10, arena.countdownSeconds());
        assertEquals(300, arena.fightTimeLimitSeconds());
        assertEquals(180, arena.maxWaitSeconds());
        assertEquals(5, arena.winnerLingerSeconds());
        assertTrue(arena.announceMatches() && arena.announceFriendly());
        assertTrue(arena.battlegrounds().isEmpty());
    }

    @Test
    void rowsWithoutAnIdAndAnUnknownGearModeAreTolerated() throws Exception {
        List<KnkArena> arenas = read("[{\"key\":\"x\"},{\"id\":2,\"key\":\"b\",\"defaultGearMode\":\"SWORDS\"}]");

        assertEquals(1, arenas.size());
        assertEquals(KnkArena.DuelGearMode.OWN_GEAR_KEEP, arenas.get(0).defaultGearMode());
        assertTrue(ArenaMapper.toCore((List<ArenaDto>) null).isEmpty());
    }
}
