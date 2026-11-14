package net.knightsandkings.knk.paper.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#485: a join drains the queue while the hub is connected (the poll is paused then). */
class NotificationJoinDrainTest {
    private final AtomicBoolean hubConnected = new AtomicBoolean();
    private final AtomicInteger drains = new AtomicInteger();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final NotificationJoinDrain listener =
        new NotificationJoinDrain(hubConnected::get, drains::incrementAndGet, scheduled::add);

    @Test
    void whileTheHubIsConnectedAJoinSchedulesOneDrain() {
        hubConnected.set(true);

        listener.onJoin(mock(PlayerJoinEvent.class));

        assertEquals(1, scheduled.size());
        assertEquals(0, drains.get(), "not before the delay");
        scheduled.get(0).run();
        assertEquals(1, drains.get());
    }

    @Test
    void whileTheHubIsDownThePollCoversIt() {
        hubConnected.set(false);

        listener.onJoin(mock(PlayerJoinEvent.class));

        assertEquals(0, scheduled.size());
    }
}
