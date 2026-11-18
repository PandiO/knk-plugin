package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;

/** Off-road phase 2 (#705): the walk-link cache - TTL, retry, one search per candidate, the block-change hook (A05, O18). */
class WalkLinkCacheTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final WalkLinkCache cache = new WalkLinkCache(clock::get, 600, 300, 100);
    private final WalkLink link = CourtyardNetwork.courtyardLink(List.of(), List.of());
    private final WalkLinkCache.Key key = new WalkLinkCache.Key("World", "player-1", link.id());

    @Test
    void aFoundLinkIsKeptForTheTtl() {
        cache.putFound(key, link);
        assertTrue(cache.lookup(key).orElseThrow().found());
        clock.addAndGet(599_999);
        assertTrue(cache.lookup(key).isPresent());
        clock.addAndGet(1);
        assertTrue(cache.lookup(key).isEmpty(), "the TTL is the backstop");
        assertEquals(1, cache.counters().expired());
    }

    @Test
    void noneIsKeptForTheRetryTime() {
        cache.putNone(key);
        assertTrue(cache.lookup(key).orElseThrow().none());
        clock.addAndGet(300_000);
        assertTrue(cache.lookup(key).isEmpty());
    }

    @Test
    void aCandidateIsSearchedOnceAtATime() {
        assertTrue(cache.claim(key));
        assertFalse(cache.claim(key), "a search runs");
        assertTrue(cache.lookup(key).orElseThrow().pending());
        cache.release(key);
        assertTrue(cache.claim(key), "a dropped search frees the key");
        cache.putFound(key, link);
        assertFalse(cache.claim(key), "an answer is cached");
    }

    @Test
    void aBlockChangeInTheLinksBoxDropsItAndNothingElse() {
        // A05: the link runs x 0..20, z 0..1 (the path's box widened by a block: chunks -1..1 by -1..0)
        WalkLinkCache.Key other = new WalkLinkCache.Key("world", "player-2", link.id());
        cache.putFound(key, link);
        cache.putFound(other, link);
        cache.terrainChanged("world", 5, 5); // far away
        assertTrue(cache.lookup(key).isPresent());
        cache.terrainChanged("other_world", 0, 0);
        assertTrue(cache.lookup(key).isPresent(), "another world");
        cache.terrainChanged("WORLD", 1, 0);
        assertTrue(cache.lookup(key).isEmpty(), "a change in the link's box drops it");
        assertTrue(cache.lookup(other).isEmpty(), "for every player");
        assertEquals(2, cache.counters().droppedByChange());
        assertTrue(cache.claim(key), "the next route searches again");
    }

    @Test
    void theLinksChunksAreItsBoxWidenedByOneBlock() {
        assertTrue(link.chunks().contains(WalkLink.chunkKey(0, 0)));
        assertTrue(link.chunks().contains(WalkLink.chunkKey(-1, -1)), "the box reaches x -1, z -1");
        assertTrue(link.chunks().contains(WalkLink.chunkKey(1, 0)), "x 20 is in chunk 1");
        assertFalse(link.chunks().contains(WalkLink.chunkKey(2, 0)));
        assertFalse(link.chunks().contains(WalkLink.chunkKey(0, 1)), "z up to 2 stays in chunk 0");
    }

    @Test
    void aGateOrRuleChangeDropsTheNonesOnly() {
        WalkLinkCache.Key shut = new WalkLinkCache.Key("world", "player-1", WalkLinkId.of(5, 2));
        cache.putFound(key, link);
        cache.putNone(shut);
        cache.dropNone("world");
        assertTrue(cache.lookup(shut).isEmpty());
        assertTrue(cache.lookup(key).isPresent(), "a found link is judged again by the policy at every route");
        assertEquals(1, cache.counters().droppedByRule());
    }

    @Test
    void aNewNetworkClearsTheWorld() {
        cache.putFound(key, link);
        cache.clearWorld("world");
        assertTrue(cache.lookup(key).isEmpty());
        assertTrue(cache.foundLinks("world").isEmpty());
        cache.terrainChanged("world", 0, 0); // nothing left to drop
        assertEquals(0, cache.counters().droppedByChange());
    }

    @Test
    void theLeastRecentlyUsedGoesFirst() {
        WalkLinkCache small = new WalkLinkCache(clock::get, 600, 600, 2);
        WalkLinkCache.Key k1 = new WalkLinkCache.Key("world", "a", WalkLinkId.of(1, 2));
        WalkLinkCache.Key k2 = new WalkLinkCache.Key("world", "a", WalkLinkId.of(1, 3));
        WalkLinkCache.Key k3 = new WalkLinkCache.Key("world", "a", WalkLinkId.of(1, 4));
        small.putNone(k1);
        small.putNone(k2);
        small.lookup(k1);
        small.putNone(k3);
        assertTrue(small.lookup(k2).isEmpty(), "k2 was used least recently");
        assertTrue(small.lookup(k1).isPresent());
        assertEquals(1, small.counters().evicted());
    }

    @Test
    void aLinkIsStoredUnderItsOwnId() {
        assertThrows(IllegalArgumentException.class,
            () -> cache.putFound(new WalkLinkCache.Key("world", "p", WalkLinkId.of(1, 2)), link));
        assertThrows(IllegalArgumentException.class, () -> new WalkLinkCache(clock::get, 0, 1, 1));
    }

    @Test
    void theCountersReadAsStatusLines() {
        cache.putFound(key, link);
        cache.putNone(new WalkLinkCache.Key("world", "p", WalkLinkId.of(5, 2)));
        WalkLinkCounters counters = new WalkLinkCounters();
        counters.routePlanned(1, 1);
        counters.searched(2_000_000, 120);
        counters.roadFirst(true);
        List<String> lines = counters.lines(cache.counters());
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("walk links: 1 routes planned, 1 with links (1 links)"), lines.get(0));
        assertTrue(lines.get(1).contains("1 searched (mean 2.0 ms"), lines.get(1));
        assertTrue(lines.get(2).startsWith("walk link cache: 1 links, 1 none"), lines.get(2));
        assertTrue(lines.get(2).endsWith("road-first: 1 road, 0 walk"), lines.get(2));
    }
}
