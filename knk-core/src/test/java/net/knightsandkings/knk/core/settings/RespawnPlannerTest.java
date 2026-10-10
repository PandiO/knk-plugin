package net.knightsandkings.knk.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy.Mode;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.settings.RespawnPlanner.Kind;
import net.knightsandkings.knk.core.settings.RespawnPlanner.Plan;
import net.knightsandkings.knk.core.settings.RespawnPlanner.TownSpot;

/** docs/specs/game-settings/DESIGN.md §3.3 - where a player respawns (KNG-52). */
class RespawnPlannerTest {

    private static KnkLocation at(String world, double x, double z) {
        return new KnkLocation(null, null, x, 64.0, z, 0f, 0f, world);
    }

    private static final KnkLocation DEATH = at("world", 0, 0);
    private static final TownSpot NEAR = new TownSpot(1, "Kardenna", "town_1", at("world", 100, 0));
    private static final TownSpot FAR = new TownSpot(2, "Brink", "town_2", at("world", 0, 500));
    private static final TownSpot NETHER = new TownSpot(3, "Ashfall", "town_3", at("world_nether", 1, 1));
    private static final List<TownSpot> TOWNS = List.of(FAR, NEAR, NETHER);

    private static KnkRespawnPolicy policy(Mode mode, Double max, boolean fallback) {
        KnkSpawnReference reference = mode == Mode.CONFIGURED_REFERENCE
            ? new KnkSpawnReference(KnkSpawnReference.SourceType.TOWN, 4, "Town: Kardenna", null)
            : null;
        return new KnkRespawnPolicy(mode, reference, max, fallback);
    }

    @Test
    void worldSpawnPolicyForcesTheWorldSpawn_BedsIgnored() {
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.WORLD_SPAWN, null, true), DEATH, null, TOWNS, null).kind());
        // The fallback flag doesn't matter: the world spawn is the policy itself (D1).
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.WORLD_SPAWN, null, false), DEATH, null, TOWNS, null).kind());
        assertEquals(Kind.SERVER_DEFAULT, RespawnPlanner.plan(null, DEATH, null, TOWNS, null).kind());
    }

    @Test
    void serverDefaultPolicyLeavesItToTheServer_LikeStaff() {
        assertEquals(Kind.SERVER_DEFAULT, RespawnPlanner.plan(policy(Mode.SERVER_DEFAULT, null, true), DEATH, null, TOWNS, null).kind());
        assertEquals(Mode.SERVER_DEFAULT, Mode.parse("ServerDefault"));
    }

    @Test
    void joinSpawnPolicyIsSyncedWithTheJoinSpawn() {
        Plan plan = RespawnPlanner.plan(policy(Mode.JOIN_SPAWN, null, true), DEATH, null, TOWNS, null);
        assertEquals(Kind.JOIN_SPAWN, plan.kind());
        assertNull(plan.location());
        assertEquals(Mode.JOIN_SPAWN, Mode.parse("JoinSpawn"));
    }

    @Test
    void configuredReferenceUsesTheResolvedSpot() {
        KnkLocation spot = at("world", 10, -3);
        Plan plan = RespawnPlanner.plan(policy(Mode.CONFIGURED_REFERENCE, null, true), DEATH, spot, TOWNS, null);
        assertEquals(Kind.LOCATION, plan.kind());
        assertSame(spot, plan.location());
        assertEquals("Town: Kardenna", plan.reason());
    }

    @Test
    void configuredReferenceWithoutASpotFallsBack() {
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.CONFIGURED_REFERENCE, null, true), DEATH, null, TOWNS, null).kind());
        assertEquals(Kind.SERVER_DEFAULT, RespawnPlanner.plan(policy(Mode.CONFIGURED_REFERENCE, null, false), DEATH, null, TOWNS, null).kind());
        KnkLocation broken = new KnkLocation(null, null, Double.NaN, 64.0, 0.0, 0f, 0f, "world");
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.CONFIGURED_REFERENCE, null, true), DEATH, broken, TOWNS, null).kind());
    }

    @Test
    void nearestTownIsInTheDeathWorld() {
        Plan plan = RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, null, true), DEATH, null, TOWNS, null);
        assertEquals(Kind.LOCATION, plan.kind());
        assertSame(NEAR.location(), plan.location());

        Plan nether = RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, null, true), at("WORLD_NETHER", 0, 0), null, TOWNS, null);
        assertSame(NETHER.location(), nether.location());
    }

    @Test
    void nearestTownDistanceIsHorizontal() {
        TownSpot high = new TownSpot(4, "Peak", null, new KnkLocation(null, null, 120.0, 250.0, 0.0, 0f, 0f, "world"));
        assertSame(NEAR.location(), RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, null, true), DEATH, null,
            List.of(high, NEAR), null).location());
        TownSpot closeButHigh = new TownSpot(5, "Spire", null, new KnkLocation(null, null, 50.0, 300.0, 0.0, 0f, 0f, "world"));
        assertSame(closeButHigh.location(), RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, null, true), DEATH, null,
            List.of(NEAR, closeButHigh), null).location());
    }

    @Test
    void maxDistanceExcludesFarTowns() {
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, 50.0, true), DEATH, null, TOWNS, null).kind());
        assertEquals(Kind.SERVER_DEFAULT, RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, 50.0, false), DEATH, null, TOWNS, null).kind());
        assertSame(NEAR.location(), RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, 150.0, true), DEATH, null, TOWNS, null).location());
        // 0 means no limit, like null
        assertSame(NEAR.location(), RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, 0.0, true), DEATH, null, TOWNS, null).location());
    }

    @Test
    void dyingInsideATownRespawnsThereEvenWhenAnotherIsCloserOrOutOfRange() {
        RespawnPlanner.RegionCheck insideFar = (town, point) -> town == FAR;
        Plan plan = RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, 50.0, true), DEATH, null, TOWNS, insideFar);
        assertSame(FAR.location(), plan.location());
        assertEquals("nearest town Brink", plan.reason());
    }

    @Test
    void townsWithoutUsableSpawnsAreSkipped() {
        TownSpot noSpawn = new TownSpot(6, "Ghost", null, null);
        TownSpot noWorld = new TownSpot(7, "Nowhere", null, new KnkLocation(null, null, 1.0, 1.0, 1.0, 0f, 0f, " "));
        assertEquals(Kind.WORLD_SPAWN, RespawnPlanner.plan(policy(Mode.NEAREST_TOWN, null, true), DEATH, null,
            List.of(noSpawn, noWorld), null).kind());
        assertNull(RespawnPlanner.nearestTown(policy(Mode.NEAREST_TOWN, null, true), DEATH, null, null));
    }

    @Test
    void usableNeedsAWorldAndFiniteCoordinatesInsideTheBorder() {
        assertFalse(RespawnPlanner.isUsable(null));
        assertFalse(RespawnPlanner.isUsable(new KnkLocation(null, null, null, 64.0, 0.0, null, null, "world")));
        assertFalse(RespawnPlanner.isUsable(new KnkLocation(null, null, 31_000_000.0, 64.0, 0.0, null, null, "world")));
        assertFalse(RespawnPlanner.isUsable(new KnkLocation(null, null, 0.0, Double.POSITIVE_INFINITY, 0.0, null, null, "world")));
    }
}
