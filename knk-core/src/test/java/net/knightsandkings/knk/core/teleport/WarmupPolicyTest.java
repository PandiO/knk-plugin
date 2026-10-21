package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarmupPolicyTest {

    private final WarmupPolicy policy = new WarmupPolicy(TeleportSettings.defaults());

    @Test
    void playerTeleportsWaitTheNormalWarmup() {
        assertEquals(5, policy.warmupSeconds(TeleportKind.SPAWN, false, false));
        assertEquals(5, policy.warmupSeconds(TeleportKind.WARP, false, false));
    }

    @Test
    void shortWarmupNodeWaitsTheShortWarmup() {
        assertEquals(3, policy.warmupSeconds(TeleportKind.REQUEST, true, false));
    }

    @Test
    void staffTeleportsAndTheBypassAreInstant() {
        assertEquals(0, policy.warmupSeconds(TeleportKind.STAFF, false, false));
        assertEquals(0, policy.warmupSeconds(TeleportKind.SPAWN, true, true));
    }

    @Test
    void anArenaLobbyTeleportWaitsLikeAPlayerOneAndTheRuntimesOwnMovesNever() {
        assertEquals(5, policy.warmupSeconds(TeleportKind.ARENA, false, false));
        assertEquals(3, policy.warmupSeconds(TeleportKind.ARENA, true, false));
        assertEquals(0, policy.warmupSeconds(TeleportKind.ARENA_SYSTEM, false, false));
    }

    @Test
    void arenaKindsHaveNoCooldown() {
        assertEquals(false, TeleportKind.ARENA.usesCooldown());
        assertEquals(false, TeleportKind.ARENA_SYSTEM.usesCooldown());
        assertEquals(false, TeleportKind.STAFF.usesCooldown());
        assertEquals(true, TeleportKind.SPAWN.usesCooldown());
        assertEquals(true, TeleportKind.ARENA_SYSTEM.isSystem());
        assertEquals(false, TeleportKind.ARENA.isSystem());
    }

    @Test
    void shortWarmupIsNeverLongerThanTheNormalOne() {
        WarmupPolicy misconfigured = new WarmupPolicy(new TeleportSettings(2, 8, 30, 10, 3));

        assertEquals(2, misconfigured.warmupSeconds(TeleportKind.SPAWN, true, false));
    }

    @Test
    void negativeSettingsAreClampedToZero() {
        TeleportSettings settings = new TeleportSettings(-1, -1, -5, -2, -3);

        assertEquals(0, settings.warmupSeconds());
        assertEquals(0, settings.cooldownSeconds());
        assertEquals(0, settings.safeSearchRadius());
        assertEquals(0, new WarmupPolicy(settings).warmupSeconds(TeleportKind.SPAWN, false, false));
    }
}
