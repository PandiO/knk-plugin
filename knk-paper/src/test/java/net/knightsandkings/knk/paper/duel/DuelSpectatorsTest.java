package net.knightsandkings.knk.paper.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;

/**
 * Arena duels rev. 6: "inside the arena" (P5's invite rule, the challenge tile) and "in the stands" (P6's sidebar and
 * boss bar) over the union of the regions: the arena's own region, its KNG-253 sub-regions and its areas' regions.
 */
class DuelSpectatorsTest {

    // Kept in a field: a Location only holds its World weakly (CLAUDE.md).
    private final World world = mock(World.class);
    private final World nether = mock(World.class);
    private final Map<Location, Set<String>> regions = new HashMap<>();
    private final KnkArena arena;
    private final DuelSpectators spectators;

    DuelSpectatorsTest() {
        when(world.getName()).thenReturn("arena");
        when(nether.getName()).thenReturn("nether");
        KnkArena base = DuelHarness.arena(true, false, false, false);
        KnkArenaArea stands = new KnkArenaArea(40, base.id(), KnkArenaArea.Kind.SPECTATOR, "stands", "Stands", 0, true,
            null, null, null, null, null, List.of(new KnkArenaAreaRegion(400, "ring_east", "arena"),
                new KnkArenaAreaRegion(401, "ring_west", "arena")));
        List<KnkArenaArea> areas = new java.util.ArrayList<>(base.areas());
        areas.add(stands);
        arena = new KnkArena(base.id(), base.key(), base.name(), base.wgRegionId(), base.worldName(), base.districtId(),
            base.location(), base.exitLocation(), base.duelEnabled(), base.maxQueuedMatches(), base.maxOffers(),
            base.offerSeconds(), base.allowCoinStakes(), base.allowItemStakes(), base.maxCoinStake(), base.maxItemStacks(),
            base.minStakeRatioPercent(), base.feePercent(), base.stakeMinAccountAgeHours(), base.stakeMinTitleBracketId(),
            base.maxStakedDuelsPerPairPerDay(), base.pairCooldownMinutes(), base.dailyCoinWinCap(), base.allowOwnGearKeep(),
            base.allowOwnGearDrop(), base.allowFixedKit(), base.allowKitChoice(), base.defaultGearMode(), base.warmupSeconds(),
            base.countdownSeconds(), base.fightTimeLimitSeconds(), base.maxWaitSeconds(), base.winnerLingerSeconds(),
            base.announceMatches(), base.announceFriendly(), base.eligibleKits(), areas, List.of("domain_7_sub_1"));
        spectators = new DuelSpectators(List::of, at -> regions.getOrDefault(at, Set.of()), () -> List.of(arena));
    }

    private Player at(World in, Set<String> here) {
        Location location = new Location(in, regions.size(), 64, 0);
        regions.put(location, here);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(location);
        return player;
    }

    @Test
    void insideIsTheArenaRegion_ItsSubRegions_OrAnyAreaRegion() {
        assertTrue(spectators.isInside(at(world, Set.of("ARENA_2")), arena));
        assertTrue(spectators.isInside(at(world, Set.of("domain_7_sub_1")), arena), "a KNG-253 sub-region of the arena");
        assertTrue(spectators.isInside(at(world, Set.of(DuelHarness.BATTLEGROUND_REGION_2)), arena), "a battleground part");
        assertTrue(spectators.isInside(at(world, Set.of("ring_west")), arena), "the stands");
        assertFalse(spectators.isInside(at(world, Set.of("town_1")), arena));
        assertFalse(spectators.isInside(at(nether, Set.of("arena_2", "ring_west")), arena), "another world");
    }

    @Test
    void theStandsAreEitherOfTheirParts_AndOnlyTheStands() {
        assertTrue(spectators.isSpectating(at(world, Set.of("arena_2", "ring_east")), arena));
        assertTrue(spectators.isSpectating(at(world, Set.of("ring_west")), arena));
        assertFalse(spectators.isSpectating(at(world, Set.of("arena_2", DuelHarness.LOBBY_REGION)), arena), "the lobby is no stand");
        assertEquals(arena.id(), spectators.spectatedBy(at(world, Set.of("ring_east"))).orElseThrow().id());
        assertEquals(arena.id(), spectators.arenaAt(at(world, Set.of(DuelHarness.LOBBY_REGION))).orElseThrow().id());
    }
}
