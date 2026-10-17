package net.knightsandkings.knk.paper.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

/** KNG-113 (audit G2): a world loaded after enable gets its gate animation task; an unloaded one loses it. */
class GateWorldTasksTest {

    private final World world = mock(World.class);
    private final World hub = mock(World.class);
    private final List<String> started = new ArrayList<>();
    private final BukkitTask hubTask = mock(BukkitTask.class);

    private GateWorldTasks tasks() {
        when(world.getName()).thenReturn("world");
        when(hub.getName()).thenReturn("hub");
        return new GateWorldTasks(w -> {
            started.add(w.getName());
            return w == hub ? hubTask : mock(BukkitTask.class);
        });
    }

    @Test
    void aWorldLoadedLaterGetsOneTaskAndLosesItWhenItUnloads() {
        GateWorldTasks tasks = tasks();
        tasks.start(world);

        tasks.onWorldLoad(new WorldLoadEvent(hub));
        tasks.onWorldLoad(new WorldLoadEvent(hub));
        assertEquals(List.of("world", "hub"), started, "one task per world");

        tasks.onWorldUnload(new WorldUnloadEvent(hub));
        verify(hubTask).cancel();
        assertEquals(1, tasks.running());
    }
}
