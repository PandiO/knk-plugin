package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#506: after {@code /knk cache refresh} forgot the region → domain map, the roads' regions are
 * asked about again and road navigation (and so the NPCs and their groups) hears of the change once more.
 */
class DomainCacheRewarmTest {

    private final Map<String, Set<String>> roads = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> warming = new HashMap<>();
    private final Map<String, Collection<String>> asked = new HashMap<>();
    private final List<Runnable> mainThread = new ArrayList<>();
    private int events;

    private DomainCacheRewarm hook(List<String> worlds) {
        return new DomainCacheRewarm(() -> worlds, world -> roads.get(world), (world, regions) -> {
            asked.put(world, regions);
            if ("broken".equals(world)) {
                throw new IllegalStateException("no API");
            }
            return warming.computeIfAbsent(world, w -> new CompletableFuture<>());
        }, mainThread::add, () -> events++, Logger.getLogger("test"));
    }

    private void runMainThread() {
        List<Runnable> tasks = List.copyOf(mainThread);
        mainThread.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void theRoadsRegionsAreWarmedAndNavigationHearsOnceMoreWhenAllAreBack() {
        roads.put("world", Set.of("residential", "keep"));
        roads.put("nether", Set.of("fortress"));

        hook(List.of("world", "nether", "end")).run();

        assertEquals(Set.of("residential", "keep"), Set.copyOf(asked.get("world")));
        assertEquals(Set.of("fortress"), Set.copyOf(asked.get("nether")));
        assertEquals(2, asked.size(), "a world without roads is not asked about");
        warming.get("world").complete(null);
        runMainThread();
        assertEquals(0, events, "not before every world's answer is back");

        warming.get("nether").complete(null);
        assertEquals(0, events, "on the main thread");
        runMainThread();
        assertEquals(1, events);
    }

    @Test
    void aFailedWarmUpStillSendsTheEvent() {
        roads.put("world", Set.of("residential"));
        roads.put("broken", Set.of("keep"));

        hook(List.of("broken", "world")).run();
        warming.get("world").completeExceptionally(new RuntimeException("timeout"));
        runMainThread();

        assertEquals(1, events);
    }

    @Test
    void withoutRoadRegionsThereIsNoSecondEvent() {
        hook(List.of("world")).run();
        runMainThread();

        assertEquals(0, events);
        assertEquals(Map.of(), asked);
    }
}
