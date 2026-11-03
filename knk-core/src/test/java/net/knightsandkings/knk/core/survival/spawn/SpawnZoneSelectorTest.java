package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S50: the "favor spawning close to players" slider; §3c respawn nearest the players. */
class SpawnZoneSelectorTest {
    private final SpawnZone far = SpawnZone.initial("far", 1, new SurvivalPoint(100, 64, 0));
    private final SpawnZone mid = SpawnZone.initial("mid", 1, new SurvivalPoint(40, 64, 0));
    private final SpawnZone near = SpawnZone.initial("near", 1, new SurvivalPoint(5, 64, 0));
    private final List<SpawnZone> active = List.of(far, mid, near);
    private final List<SurvivalPoint> players = List.of(new SurvivalPoint(0, 64, 0), new SurvivalPoint(2, 64, 2));

    private Map<String, Integer> shares(SpawnZoneSelector selector, List<SurvivalPoint> livingPlayers, int draws) {
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(5);
        for (int i = 0; i < draws; i++) counts.merge(selector.select(active, livingPlayers, random).orElseThrow().id(), 1, Integer::sum);
        return counts;
    }

    @Test
    void zeroPercentSpreadsByZoneWeightOnly() {
        Map<String, Integer> counts = shares(new SpawnZoneSelector(0), players, 3000);
        for (SpawnZone zone : active) {
            double share = counts.getOrDefault(zone.id(), 0) / 3000.0;
            assertTrue(share > 0.28 && share < 0.39, zone.id() + ": " + share);
        }
    }

    @Test
    void sixtyFivePercentGoesToTheNearestZoneAndTheRestToTheOthers() {
        Map<String, Integer> counts = shares(new SpawnZoneSelector(65), players, 4000);
        double nearShare = counts.getOrDefault("near", 0) / 4000.0;
        assertTrue(nearShare > 0.61 && nearShare < 0.69, "≈ 65 %: " + nearShare);
        assertTrue(counts.getOrDefault("far", 0) > 400, "far zones keep spawning: " + counts);
        assertTrue(counts.getOrDefault("mid", 0) > 400, counts.toString());
    }

    @Test
    void nearSetCanHoldSeveralZones() {
        Map<String, Integer> counts = shares(new SpawnZoneSelector(80, 2), players, 4000);
        double nearAndMid = (counts.getOrDefault("near", 0) + counts.getOrDefault("mid", 0)) / 4000.0;
        assertTrue(nearAndMid > 0.76 && nearAndMid < 0.84, "≈ 80 %: " + nearAndMid);
        assertTrue(counts.getOrDefault("far", 0) > 500);
    }

    @Test
    void withoutLivingPlayersOrWithOneZoneItIsWeightOnly() {
        SpawnZoneSelector selector = new SpawnZoneSelector(100);
        Map<String, Integer> counts = shares(selector, List.of(), 3000);
        assertEquals(3, counts.size(), "ghosts are ignored, so nobody is near: weight only " + counts);
        assertEquals(near, selector.select(List.of(near), players, new Random()).orElseThrow());
        assertTrue(selector.select(List.of(), players, new Random()).isEmpty());
    }

    @Test
    void weightsApplyWithinEachShare() {
        SpawnZone heavyFar = SpawnZone.initial("heavy-far", 9, new SurvivalPoint(100, 64, 0));
        List<SpawnZone> zones = List.of(heavyFar, mid, near);
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(11);
        SpawnZoneSelector selector = new SpawnZoneSelector(50);
        for (int i = 0; i < 4000; i++) counts.merge(selector.select(zones, players, random).orElseThrow().id(), 1, Integer::sum);
        double heavyAmongFar = counts.get("heavy-far") / (double) (counts.get("heavy-far") + counts.getOrDefault("mid", 0));
        assertTrue(heavyAmongFar > 0.85, "9:1 within the far share: " + heavyAmongFar);
    }

    @Test
    void stuckRespawnPrefersTheNearestActiveZone() {
        SpawnZoneSelector selector = new SpawnZoneSelector(0);
        assertEquals(near, selector.selectNearestPlayers(active, players, new Random()).orElseThrow());
        assertEquals(far, selector.selectNearestPlayers(active, List.of(new SurvivalPoint(120, 64, 0)), new Random()).orElseThrow());
        assertTrue(selector.selectNearestPlayers(active, List.of(), new Random(1)).isPresent(), "no positions: weighted fallback");
        assertEquals(List.of(near, mid, far), SpawnZoneSelector.byDistanceToPlayers(active, players));
    }
}
