package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.LocationTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#180: task chat runs on the main thread; other players' chat isn't held up. */
class WorldTaskChatListenerTest {
    private final AtomicInteger mainThreadCalls = new AtomicInteger();

    private WorldTaskChatListener.MainThread mainThread(boolean timeOut) {
        return new WorldTaskChatListener.MainThread() {
            @Override
            public boolean isMainThread() {
                return false;
            }

            @Override
            public <T> T call(Callable<T> call, long timeoutMillis) throws TimeoutException {
                mainThreadCalls.incrementAndGet();
                if (timeOut) {
                    throw new TimeoutException();
                }
                try {
                    return call.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static AsyncPlayerChatEvent chat(Player player, String message) {
        return new AsyncPlayerChatEvent(true, player, message, new HashSet<>());
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        return player;
    }

    @Test
    void chatOfAPlayerWithoutATaskDoesNotWaitForTheMainThread() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        registry.registerHandler(new LocationTaskHandler(mock(WorldTasksApi.class), mock(Plugin.class)));
        WorldTaskChatListener listener = new WorldTaskChatListener(registry, null, mainThread(false));
        AsyncPlayerChatEvent event = chat(player(), "save");

        listener.onPlayerChat(event);

        assertFalse(event.isCancelled());
        assertEquals(0, mainThreadCalls.get());
    }

    @Test
    void aTaskCommandIsRoutedOnTheMainThreadAndHiddenFromChat() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        LocationTaskHandler location = new LocationTaskHandler(mock(WorldTasksApi.class), mock(Plugin.class));
        registry.registerHandler(location);
        Player player = player();
        location.startTask(player, 5, "{}");
        WorldTaskChatListener listener = new WorldTaskChatListener(registry, null, mainThread(false));

        AsyncPlayerChatEvent pause = chat(player, "pause");
        listener.onPlayerChat(pause);
        AsyncPlayerChatEvent hello = chat(player, "hello everyone");
        listener.onPlayerChat(hello);

        assertTrue(pause.isCancelled());
        assertFalse(hello.isCancelled());
        assertEquals(2, mainThreadCalls.get());
    }

    @Test
    void aBusyMainThreadStillKeepsTheCommandOutOfChat() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        LocationTaskHandler location = new LocationTaskHandler(mock(WorldTasksApi.class), mock(Plugin.class));
        registry.registerHandler(location);
        Player player = player();
        location.startTask(player, 5, "{}");
        AsyncPlayerChatEvent event = chat(player, "save");

        new WorldTaskChatListener(registry, null, mainThread(true)).onPlayerChat(event);

        assertTrue(event.isCancelled());
    }
}
