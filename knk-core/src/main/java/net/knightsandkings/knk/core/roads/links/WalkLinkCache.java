package net.knightsandkings.knk.core.roads.links;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;
import net.knightsandkings.knk.core.roads.walk.TerrainChangeListener;

/**
 * The cache of walk links (off-road navigation DESIGN § 5.2.3, decisions O17, O18): per world, mover and link id, the
 * link a walk search found ({@link Entry#found}) or that it found none ({@link Entry#none}), or that a search is
 * running ({@link #claim}). A found link expires after {@code walk-link-ttl-seconds}, a "none" after
 * {@code walk-link-retry-seconds}; a block change in a chunk of a found link's box drops it at once
 * ({@link #terrainChanged}, the block-change hook), and a gate or availability change drops every "none" of the world
 * ({@link #dropNone}: what was shut may be open now; a found link is judged again by the access policy at every route).
 *
 * <p>The mover is part of the key because a link was searched with that mover's cell access (gates, doors and regions
 * they may pass, LAST_MILE § 6), which another player may not share. At most {@link #maxEntries} entries; the least
 * recently used goes first. Thread-safe (one lock; every operation is a few map look-ups).
 */
public final class WalkLinkCache implements TerrainChangeListener {

    /** The default for {@code maxEntries}: about 4 096 links, a few hundred KB with their paths. */
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    /** A cache key: world, mover (a player's UUID) and link. */
    public record Key(String world, String mover, WalkLinkId link) {
        public Key {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(mover, "mover");
            Objects.requireNonNull(link, "link");
            world = world.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A cached answer.
     *
     * @param link    the link (null for "none" and for a running search)
     * @param expires clock millis after which the answer is gone
     * @param pending a search is running (no answer yet)
     */
    public record Entry(WalkLink link, long expires, boolean pending) {
        public boolean found() {
            return link != null;
        }

        public boolean none() {
            return link == null && !pending;
        }
    }

    /** Counters for {@code /knk road status} (DESIGN § 9.3.5, measurements report § 3.3 budget 6). */
    public record Counters(long found, long none, long droppedByChange, long droppedByRule, long expired, long evicted,
                           int cachedFound, int cachedNone, int pending) {
    }

    private final LongSupplier clockMillis;
    private final long ttlMillis;
    private final long retryMillis;
    private final long pendingMillis;
    private final int maxEntries;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    /** world + chunk → keys of found links whose box touches the chunk. */
    private final Map<String, Map<Long, Set<Key>>> byChunk = new HashMap<>();
    private long found;
    private long none;
    private long droppedByChange;
    private long droppedByRule;
    private long expired;
    private long evicted;

    /**
     * @param clockMillis   wall clock in milliseconds (tests pass their own)
     * @param ttlSeconds    {@code walk-link-ttl-seconds}: how long a found link is kept without a change (the backstop)
     * @param retrySeconds  {@code walk-link-retry-seconds}: how long "no link" is kept before a search may run again
     * @param maxEntries    the most entries kept
     */
    public WalkLinkCache(LongSupplier clockMillis, int ttlSeconds, int retrySeconds, int maxEntries) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        if (ttlSeconds < 1 || retrySeconds < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("walk-link-ttl-seconds, walk-link-retry-seconds and the cache size must be"
                + " at least 1 (got: " + ttlSeconds + ", " + retrySeconds + ", " + maxEntries + ")");
        }
        this.ttlMillis = ttlSeconds * 1000L;
        this.retryMillis = retrySeconds * 1000L;
        this.pendingMillis = 60_000L; // a search that never reports back stops blocking its key after a minute
        this.maxEntries = maxEntries;
    }

    /** The answer for a key, if one is cached and not expired. */
    public synchronized Optional<Entry> lookup(Key key) {
        Entry e = entries.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (clockMillis.getAsLong() >= e.expires()) {
            remove(key, e);
            if (!e.pending()) {
                expired++;
            }
            return Optional.empty();
        }
        return Optional.of(e);
    }

    /**
     * Marks a search for {@code key} as running; false (and nothing changes) when the key already has an answer or a
     * running search - one request per candidate (DESIGN § 5.2.2).
     */
    public synchronized boolean claim(Key key) {
        if (lookup(key).isPresent()) {
            return false;
        }
        put(key, new Entry(null, clockMillis.getAsLong() + pendingMillis, true));
        return true;
    }

    /** Gives up a running search (it was dropped before it ran): the key is free again. */
    public synchronized void release(Key key) {
        Entry e = entries.get(key);
        if (e != null && e.pending()) {
            remove(key, e);
        }
    }

    /** A search found {@code link} for {@code key}. */
    public synchronized void putFound(Key key, WalkLink link) {
        Objects.requireNonNull(link, "link");
        if (!link.id().equals(key.link())) {
            throw new IllegalArgumentException("key " + key + " holds " + link.id());
        }
        Entry old = entries.get(key);
        if (old != null) {
            remove(key, old);
        }
        put(key, new Entry(link, clockMillis.getAsLong() + ttlMillis, false));
        Map<Long, Set<Key>> chunks = byChunk.computeIfAbsent(key.world(), w -> new HashMap<>());
        for (long chunk : link.chunks()) {
            chunks.computeIfAbsent(chunk, c -> new HashSet<>()).add(key);
        }
        found++;
    }

    /** A search found no way for {@code key}. */
    public synchronized void putNone(Key key) {
        Entry old = entries.get(key);
        if (old != null) {
            remove(key, old);
        }
        put(key, new Entry(null, clockMillis.getAsLong() + retryMillis, false));
        none++;
    }

    /** The block-change hook (O18): drops every found link of the world whose box touches the chunk. */
    @Override
    public synchronized void terrainChanged(String world, int chunkX, int chunkZ) {
        Map<Long, Set<Key>> chunks = byChunk.get(world.toLowerCase(Locale.ROOT));
        if (chunks == null) {
            return;
        }
        Set<Key> keys = chunks.get(WalkLink.chunkKey(chunkX, chunkZ));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (Key key : new ArrayList<>(keys)) {
            Entry e = entries.get(key);
            if (e != null && e.found()) {
                remove(key, e);
                droppedByChange++;
            }
        }
    }

    /** A gate or availability change: every "none" of the world (any world when null) may be searched again. */
    public synchronized void dropNone(String world) {
        String w = world == null ? null : world.toLowerCase(Locale.ROOT);
        for (Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Entry> e = it.next();
            if (e.getValue().none() && (w == null || e.getKey().world().equals(w))) {
                it.remove();
                droppedByRule++;
            }
        }
    }

    /** Drops everything of a world (a new road network: node ids may mean other places). */
    public synchronized void clearWorld(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        for (Key key : new ArrayList<>(entries.keySet())) {
            if (key.world().equals(w)) {
                remove(key, entries.get(key));
            }
        }
        byChunk.remove(w);
    }

    public synchronized Counters counters() {
        int f = 0, n = 0, p = 0;
        for (Entry e : entries.values()) {
            if (e.found()) {
                f++;
            } else if (e.pending()) {
                p++;
            } else {
                n++;
            }
        }
        return new Counters(found, none, droppedByChange, droppedByRule, expired, evicted, f, n, p);
    }

    /** The found links cached for a world (any mover), for tests and status. */
    public synchronized List<WalkLink> foundLinks(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        List<WalkLink> out = new ArrayList<>();
        entries.forEach((k, e) -> {
            if (e.found() && k.world().equals(w)) {
                out.add(e.link());
            }
        });
        return out;
    }

    private void put(Key key, Entry entry) {
        entries.put(key, entry);
        while (entries.size() > maxEntries) {
            Map.Entry<Key, Entry> eldest = entries.entrySet().iterator().next();
            remove(eldest.getKey(), eldest.getValue());
            evicted++;
        }
    }

    private void remove(Key key, Entry entry) {
        entries.remove(key);
        if (entry != null && entry.found()) {
            Map<Long, Set<Key>> chunks = byChunk.get(key.world());
            if (chunks != null) {
                for (long chunk : entry.link().chunks()) {
                    Set<Key> keys = chunks.get(chunk);
                    if (keys != null) {
                        keys.remove(key);
                        if (keys.isEmpty()) {
                            chunks.remove(chunk);
                        }
                    }
                }
            }
        }
    }
}
