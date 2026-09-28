package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportCooldownsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());

    @Test
    void cooldownRunsForTheGivenSecondsRoundedUp() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        cooldowns.start(ALICE, TeleportKind.SPAWN, 0, 30);

        assertEquals(30, cooldowns.remainingSeconds(ALICE, TeleportKind.SPAWN, 0));
        assertEquals(1, cooldowns.remainingSeconds(ALICE, TeleportKind.SPAWN, 29_001));
        assertTrue(cooldowns.isCoolingDown(ALICE, TeleportKind.SPAWN, 29_999));
        assertFalse(cooldowns.isCoolingDown(ALICE, TeleportKind.SPAWN, 30_000));
    }

    @Test
    void cooldownsArePerKind() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        cooldowns.start(ALICE, TeleportKind.SPAWN, 0, 30);

        assertFalse(cooldowns.isCoolingDown(ALICE, TeleportKind.WARP, 1_000));
    }

    @Test
    void zeroSecondsClearsTheCooldown() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        cooldowns.start(ALICE, TeleportKind.SPAWN, 0, 30);
        cooldowns.start(ALICE, TeleportKind.SPAWN, 1_000, 0);

        assertEquals(0, cooldowns.remainingSeconds(ALICE, TeleportKind.SPAWN, 1_000));
    }

    @Test
    void clearRemovesEveryKindForThePlayer() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        UUID bob = UUID.nameUUIDFromBytes("Bob".getBytes());
        cooldowns.start(ALICE, TeleportKind.SPAWN, 0, 30);
        cooldowns.start(ALICE, TeleportKind.WARP, 0, 30);
        cooldowns.start(bob, TeleportKind.SPAWN, 0, 30);

        cooldowns.clear(ALICE);

        assertFalse(cooldowns.isCoolingDown(ALICE, TeleportKind.SPAWN, 0));
        assertFalse(cooldowns.isCoolingDown(ALICE, TeleportKind.WARP, 0));
        assertTrue(cooldowns.isCoolingDown(bob, TeleportKind.SPAWN, 0));
    }

    @Test
    void purgeDropsExpiredEntries() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        cooldowns.start(ALICE, TeleportKind.SPAWN, 0, 1);
        cooldowns.start(ALICE, TeleportKind.WARP, 0, 60);

        cooldowns.purgeExpired(5_000);

        assertFalse(cooldowns.isCoolingDown(ALICE, TeleportKind.SPAWN, 5_000));
        assertTrue(cooldowns.isCoolingDown(ALICE, TeleportKind.WARP, 5_000));
    }
}
