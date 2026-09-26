package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportBackSettings;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.listeners.BackDeathListener;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /back} (docs/specs/teleport Phase 7): stored on death, 5 minutes, single use, not after a
 * siege death, and through the engine's warmup, guards and safe-spot check.
 */
class BackServiceTest {

    private long now = 1_000_000;
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final UUID worldId = UUID.nameUUIDFromBytes("world".getBytes());
    private final World world = world();
    private boolean worldLoaded = true;
    /** Flat world (solid below y 64) with a lava pool where |x| <= 1 and |z| <= 1. */
    private final BlockProbe flatWithLavaPool = new BlockProbe() {
        @Override public boolean isPassable(int x, int y, int z) { return y >= 64; }
        @Override public boolean isSolid(int x, int y, int z) { return y < 64; }
        @Override public boolean isHazard(int x, int y, int z) { return y == 63 && Math.abs(x) <= 1 && Math.abs(z) <= 1; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    };
    private final TeleportService engine = new TeleportService(Runnable::run,
        (player, node) -> CompletableFuture.completedFuture(granted.getOrDefault(player.getUniqueId(), Set.of()).contains(node)),
        TeleportSettings.defaults(), () -> now, w -> flatWithLavaPool);
    private final BackService back = new BackService(engine, Runnable::run,
        (player, node) -> CompletableFuture.completedFuture(granted.getOrDefault(player.getUniqueId(), Set.of()).contains(node)),
        id -> worldLoaded && id.equals(worldId) ? world : null);
    private final BackDeathListener deaths = new BackDeathListener(back);

    private final Player alice = player("Alice", new Location(world, 200.5, 64, 200.5));
    private final Location cave = new Location(world, 100.5, 64, 100.5, 45, 10);

    private World world() {
        World mocked = mock(World.class);
        when(mocked.getName()).thenReturn("world");
        when(mocked.getUID()).thenReturn(worldId);
        when(mocked.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        return mocked;
    }

    private static Player player(String name, Location location) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
        when(player.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(true));
        return player;
    }

    private void grant(Player player, String... nodes) {
        granted.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>()).addAll(Set.of(nodes));
    }

    /** {@code player} dies where they stand now, through both steps of the death listener. */
    private void die(Player player, Location where) {
        when(player.getLocation()).thenReturn(where);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        deaths.onDeathEarly(event);
        deaths.onDeath(event);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 300.5));
    }

    private static TeleportOutcome done(CompletableFuture<TeleportOutcome> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("outcome not completed", ex);
        }
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
        back.purgeExpired();
    }

    // ===== recording =====

    @Test
    void deathIsStored_AndBackReturnsThereAfterTheWarmup() {
        die(alice, cave);

        CompletableFuture<TeleportOutcome> result = back.start(alice);
        assertTrue(engine.isWarmingUp(alice.getUniqueId()), "a player teleport: warmup first");
        advance(4_900);
        assertFalse(result.isDone());

        advance(100);
        assertTrue(done(result).isTeleported());
        verify(alice).teleportAsync(eq(cave), eq(TeleportCause.COMMAND));
    }

    @Test
    void backIsSingleUsePerDeath() {
        grant(alice, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        die(alice, cave);

        assertTrue(done(back.start(alice)).isTeleported());

        TeleportOutcome again = done(back.start(alice));
        assertEquals(BackService.NO_DEATH, again.code());
        die(alice, cave);
        assertTrue(done(back.start(alice)).isTeleported(), "a new death gives a new /back");
    }

    @Test
    void backExpiresFiveMinutesAfterTheDeath() {
        die(alice, cave);
        assertEquals(300, back.secondsLeft(alice.getUniqueId()));

        advance(299_000);
        assertEquals(1, back.secondsLeft(alice.getUniqueId()));
        advance(1_000);

        assertEquals(0, back.secondsLeft(alice.getUniqueId()));
        TeleportOutcome outcome = done(back.start(alice));
        assertEquals(TeleportOutcome.Status.DENIED, outcome.status());
        assertEquals(BackService.NO_DEATH, outcome.code());
    }

    @Test
    void aBackStartedInTimeStillArrivesAfterTheDeadline() {
        die(alice, cave);
        advance(298_000);

        CompletableFuture<TeleportOutcome> result = back.start(alice);
        advance(5_000);

        assertTrue(done(result).isTeleported());
    }

    @Test
    void siegeDeathsAreNotStored_AndForgetAnOlderDeath() {
        die(alice, cave);
        back.registerDeathExclusion(player -> player.getUniqueId().equals(alice.getUniqueId()));

        die(alice, new Location(world, 5.5, 64, 5.5));

        assertEquals(BackService.NO_DEATH, done(back.start(alice)).code());
    }

    @Test
    void siegeVerdictIsTakenBeforeTheSiegeHandlesTheDeath() {
        boolean[] inMatch = {true};
        back.registerDeathExclusion(player -> inMatch[0]);
        when(alice.getLocation()).thenReturn(cave);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(alice);

        deaths.onDeathEarly(event);
        inMatch[0] = false; // the siege's HIGHEST handler ended the match
        deaths.onDeath(event);

        assertEquals(BackService.NO_DEATH, done(back.start(alice)).code());
    }

    @Test
    void aBrokenExclusionFailsClosed() {
        back.registerDeathExclusion(player -> { throw new IllegalStateException("siege down"); });

        die(alice, cave);

        assertEquals(BackService.NO_DEATH, done(back.start(alice)).code());
    }

    @Test
    void aCancelledDeathIsNotStored() {
        when(alice.getLocation()).thenReturn(cave);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(alice);
        when(event.isCancelled()).thenReturn(true);

        deaths.onDeathEarly(event);
        deaths.onDeath(event);

        assertEquals(BackService.NO_DEATH, done(back.start(alice)).code());
    }

    @Test
    void disabledBackRecordsNothingAndRefuses() {
        TeleportSettings d = TeleportSettings.defaults();
        engine.updateSettings(new TeleportSettings(d.warmupSeconds(), d.warmupShortSeconds(), d.cooldownSeconds(),
            d.combatTagSeconds(), d.safeSearchRadius(), d.request(), d.destinationsCacheSeconds(),
            new TeleportBackSettings(false, 300)));

        die(alice, cave);

        assertFalse(back.isEnabled());
        assertEquals(BackService.DISABLED, done(back.start(alice)).code());
        assertEquals(0, back.secondsLeft(alice.getUniqueId()));
    }

    @Test
    void onlyHoldersOfTheNodeAreToldAboutBack() {
        Player bob = player("Bob", new Location(world, 0.5, 64, 0.5));
        grant(alice, TeleportNodes.BACK);

        die(alice, cave);
        die(bob, cave);

        verify(alice).sendMessage(contains("within 5 minutes"));
        verify(bob, never()).sendMessage(anyString());
    }

    // ===== engine: warmup, guards, safe spot =====

    @Test
    void aCancelledWarmupKeepsTheDeathForAnotherTry() {
        die(alice, cave);
        CompletableFuture<TeleportOutcome> first = back.start(alice);
        assertEquals(BackService.IN_PROGRESS, done(back.start(alice)).code(), "one /back at a time");

        engine.cancelWarmup(alice.getUniqueId(), WarmupCancelReason.MOVED);

        assertEquals(TeleportOutcome.Status.CANCELLED, done(first).status());
        CompletableFuture<TeleportOutcome> second = back.start(alice);
        advance(5_000);
        assertTrue(done(second).isTeleported());
    }

    @Test
    void guardsApply_AndARefusalKeepsTheDeath() {
        die(alice, cave);
        engine.combatTags().tag(alice.getUniqueId(), now);

        TeleportOutcome refused = done(back.start(alice));
        assertEquals(TeleportDenial.COMBAT, refused.code());

        advance(10_000);
        CompletableFuture<TeleportOutcome> later = back.start(alice);
        advance(5_000);
        assertTrue(done(later).isTeleported());
    }

    @Test
    void restrictionsApply() {
        engine.registerRestriction(check -> check.kind() == TeleportKind.BACK
            ? Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, "You're in a siege match.")) : Optional.empty());
        die(alice, cave);

        assertEquals(TeleportDenial.SIEGE, done(back.start(alice)).code());
    }

    @Test
    void cooldownAppliesAfterABack() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);
        die(alice, cave);
        assertTrue(done(back.start(alice)).isTeleported());

        die(alice, cave);

        assertEquals(TeleportDenial.COOLDOWN, done(back.start(alice)).code());
    }

    @Test
    void aLavaDeathLandsOnTheNearestSafeGround() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);
        die(alice, new Location(world, 0.5, 64, 0.5, 0, 0));

        assertTrue(done(back.start(alice)).isTeleported());

        verify(alice, never()).teleportAsync(eq(new Location(world, 0.5, 64, 0.5, 0, 0)), any(TeleportCause.class));
        verify(alice).teleportAsync(eq(new Location(world, -1.5, 64, 0.5, 0, 0)), eq(TeleportCause.COMMAND));
    }

    @Test
    void aVoidDeathIsRefusedAsUnsafe_AndStaysAvailable() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);
        die(alice, new Location(world, 40.5, -100, 40.5));

        TeleportOutcome outcome = done(back.start(alice));

        assertEquals(TeleportDenial.UNSAFE, outcome.code());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        assertTrue(back.secondsLeft(alice.getUniqueId()) > 0);
    }

    @Test
    void aDeathInAWorldThatIsGoneFails() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);
        die(alice, cave);
        worldLoaded = false;

        assertEquals(TeleportOutcome.Status.FAILED, done(back.start(alice)).status());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void secondsAreDescribedInMinutesWhenWhole() {
        assertEquals("5 minutes", BackService.describeSeconds(300));
        assertEquals("1 minute", BackService.describeSeconds(60));
        assertEquals("90 seconds", BackService.describeSeconds(90));
    }
}
