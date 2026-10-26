package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.enchantment.FrozenPlayerTracker;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreezeMovementListenerTest {

    @Test
    void blocksMovementWhenPlayerIsFrozenAndPositionChanges() {
        UUID playerId = UUID.randomUUID();
        FrozenPlayerTracker tracker = new TestFrozenPlayerTracker(playerId, true);
        FreezeMovementListener listener = new FreezeMovementListener(tracker);

        Player player = testPlayer(playerId);
        World world = testWorld();
        Location from = new Location(world, 10, 64, 10, 0, 0);
        Location to = new Location(world, 11, 64, 10, 30, 10);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener.onPlayerMove(event);

        assertEquals(from, event.getTo());
    }

    @Test
    void allowsCameraRotationWhenBlockPositionDoesNotChange() {
        UUID playerId = UUID.randomUUID();
        FrozenPlayerTracker tracker = new TestFrozenPlayerTracker(playerId, true);
        FreezeMovementListener listener = new FreezeMovementListener(tracker);

        Player player = testPlayer(playerId);
        World world = testWorld();
        Location from = new Location(world, 10, 64, 10, 0, 0);
        Location to = new Location(world, 10, 64, 10, 180, 20);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener.onPlayerMove(event);

        assertEquals(to, event.getTo());
    }

    @Test
    void deathEndsTheFreeze() {
        UUID playerId = UUID.randomUUID();
        TestFrozenPlayerTracker tracker = new TestFrozenPlayerTracker(playerId, true);
        FreezeMovementListener listener = new FreezeMovementListener(tracker);
        EntityDeathEvent death = org.mockito.Mockito.mock(EntityDeathEvent.class);
        Player player = testPlayer(playerId);
        org.mockito.Mockito.when(death.getEntity()).thenReturn(player);

        listener.onDeath(death);

        assertEquals(playerId, tracker.unfrozen);
    }

    @Test
    void anUnfrozenTrackerReallyForgetsTheFreeze() {
        UUID playerId = UUID.randomUUID();
        FrozenPlayerTracker tracker = new FrozenPlayerTracker((Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class[]{Plugin.class}, (proxy, method, args) -> null));
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            var scheduler = org.mockito.Mockito.mock(org.bukkit.scheduler.BukkitScheduler.class);
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            org.mockito.Mockito.when(scheduler.scheduleSyncDelayedTask(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong())).thenReturn(42);

            tracker.freeze(testPlayer(playerId), 200);
            assertEquals(true, tracker.isFrozen(playerId));

            tracker.unfreeze(playerId);

            assertEquals(false, tracker.isFrozen(playerId));
            org.mockito.Mockito.verify(scheduler).cancelTask(42);
        }
    }

    /** PandiO/knk-workspace#155: the flight guard hears when a player's freeze starts and ends, once each. */
    @Test
    void thePlayerFreezeHookHearsStartAndEnd_AndAnOlderTimerDoesNotEndANewerFreeze() {
        UUID playerId = UUID.randomUUID();
        FrozenPlayerTracker tracker = new FrozenPlayerTracker((Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class[]{Plugin.class}, (proxy, method, args) -> null));
        java.util.List<String> heard = new java.util.ArrayList<>();
        tracker.setPlayerFreezeHook(new FrozenPlayerTracker.PlayerFreezeHook() {
            @Override
            public void frozen(UUID id) {
                heard.add("frozen");
            }

            @Override
            public void released(UUID id) {
                heard.add("released");
            }
        });
        java.util.List<Runnable> timers = new java.util.ArrayList<>();
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            var scheduler = org.mockito.Mockito.mock(org.bukkit.scheduler.BukkitScheduler.class);
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger(40);
            org.mockito.Mockito.when(scheduler.scheduleSyncDelayedTask(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    timers.add(inv.getArgument(1));
                    return ids.incrementAndGet();
                });

            tracker.freeze(testPlayer(playerId), 60);
            tracker.freeze(testPlayer(playerId), 60); // a second proc before the first ended
            assertEquals(java.util.List.of("frozen"), heard);

            timers.get(0).run(); // the first freeze's timer (cancelled in game) must not end the second
            assertEquals(java.util.List.of("frozen"), heard);
            assertEquals(true, tracker.isFrozen(playerId));

            timers.get(1).run();
            assertEquals(java.util.List.of("frozen", "released"), heard);
            assertEquals(false, tracker.isFrozen(playerId));

            tracker.unfreeze(playerId); // nothing left to end
            assertEquals(java.util.List.of("frozen", "released"), heard);
        }
    }

    @Test
    void quittingEndsTheFreeze() {
        UUID playerId = UUID.randomUUID();
        TestFrozenPlayerTracker tracker = new TestFrozenPlayerTracker(playerId, true);
        FreezeMovementListener listener = new FreezeMovementListener(tracker);
        org.bukkit.event.player.PlayerQuitEvent quit = org.mockito.Mockito.mock(org.bukkit.event.player.PlayerQuitEvent.class);
        org.mockito.Mockito.when(quit.getPlayer()).thenReturn(testPlayer(playerId));

        listener.onQuit(quit);

        assertEquals(playerId, tracker.unfrozen);
    }

    private static Player testPlayer(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return playerId;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static World testWorld() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class TestFrozenPlayerTracker extends FrozenPlayerTracker {
        private final UUID expectedPlayerId;
        private final boolean frozen;
        private UUID unfrozen;

        private TestFrozenPlayerTracker(UUID expectedPlayerId, boolean frozen) {
            super((Plugin) Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(),
                    new Class[]{Plugin.class},
                    (proxy, method, args) -> null
            ));
            this.expectedPlayerId = expectedPlayerId;
            this.frozen = frozen;
        }

        @Override
        public boolean isFrozen(UUID playerId) {
            return frozen && expectedPlayerId.equals(playerId);
        }

        @Override
        public void unfreeze(UUID playerId) {
            unfrozen = playerId;
        }
    }
}
