package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harvest report entries waiting for the API (PandiO/knk-workspace#134, the L21 contract on #134): {@code POST
 * api/Harvest/reports} with one stable idempotency key per entry. Breaks of the same player, rule, area and fortune level
 * join one open entry until it is sent; a sent entry that got no answer is sent again with the same key (it books once,
 * {@code Replayed}), after a growing pause. Persisted on disk ({@link #encode}) so a restart neither loses nor doubles a
 * harvest. Main thread.
 */
public final class HarvestOutbox {

    /** One entry. {@code harvestedAt} is the first break's time (epoch ms). */
    public record Entry(String key, UUID player, int userId, int ruleId, int areaId, int breaks, int fortuneLevel,
                        long harvestedAt, int attempts, long nextAttemptAt) {

        Entry plusBreak() {
            return new Entry(key, player, userId, ruleId, areaId, breaks + 1, fortuneLevel, harvestedAt, attempts,
                    nextAttemptAt);
        }

        Entry retryAt(long at) {
            return new Entry(key, player, userId, ruleId, areaId, breaks, fortuneLevel, harvestedAt, attempts + 1, at);
        }

        String encode() {
            return key + '\t' + player + '\t' + userId + '\t' + ruleId + '\t' + areaId + '\t' + breaks + '\t' + fortuneLevel
                    + '\t' + harvestedAt + '\t' + attempts;
        }

        static Entry decode(String line) {
            String[] f = line.split("\t", -1);
            if (f.length != 9) {
                throw new IllegalArgumentException("expected 9 fields, got " + f.length);
            }
            try {
                return new Entry(f[0], UUID.fromString(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                        Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]), Long.parseLong(f[7]),
                        Integer.parseInt(f[8]), 0);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("broken entry line: " + e.getMessage(), e);
            }
        }
    }

    private record Group(UUID player, int ruleId, int areaId, int fortuneLevel) {
    }

    static final long FIRST_RETRY_MILLIS = 5_000;
    static final long MAX_RETRY_MILLIS = 300_000;

    private final String keyPrefix;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<Group, String> open = new LinkedHashMap<>();
    private final java.util.Set<String> inFlight = new java.util.HashSet<>();
    private boolean dirty;

    /** @param serverId part of every key ({@code harvest-<server>-<uuid>}) */
    public HarvestOutbox(String serverId) {
        String id = serverId == null || serverId.isBlank() ? "server" : serverId.replaceAll("[^A-Za-z0-9_.-]", "_");
        this.keyPrefix = "harvest-" + id + "-";
    }

    /** Records one break; returns the key of the entry it joined. */
    public String add(UUID player, int userId, int ruleId, int areaId, int fortuneLevel, long atMillis) {
        Group group = new Group(player, ruleId, areaId, Math.max(0, fortuneLevel));
        String key = open.get(group);
        Entry entry = key == null ? null : entries.get(key);
        if (entry != null && entry.userId() == userId) {
            entries.put(key, entry.plusBreak());
        } else {
            key = keyPrefix + UUID.randomUUID();
            entries.put(key, new Entry(key, player, userId, ruleId, areaId, 1, group.fortuneLevel(), atMillis, 0, 0));
            open.put(group, key);
        }
        dirty = true;
        return key;
    }

    /** Up to {@code max} entries ready to send now; they are closed for new breaks and marked in flight. */
    public List<Entry> take(int max, long nowMillis) {
        List<Entry> batch = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (batch.size() >= max) {
                break;
            }
            if (!inFlight.contains(entry.key()) && entry.nextAttemptAt() <= nowMillis) {
                batch.add(entry);
            }
        }
        for (Entry entry : batch) {
            inFlight.add(entry.key());
            open.values().remove(entry.key());
        }
        return batch;
    }

    /** The API answered the entry (Applied, Replayed or Rejected): it is done. */
    public void answered(String key) {
        inFlight.remove(key);
        if (entries.remove(key) != null) {
            dirty = true;
        }
        open.values().remove(key);
    }

    /** No answer (timeout, 5xx): sent again later with the same key. */
    public void failed(Collection<String> keys, long nowMillis) {
        for (String key : keys) {
            inFlight.remove(key);
            Entry entry = entries.get(key);
            if (entry != null) {
                long pause = Math.min(MAX_RETRY_MILLIS, FIRST_RETRY_MILLIS << Math.min(10, entry.attempts()));
                entries.put(key, entry.retryAt(nowMillis + pause));
                dirty = true;
            }
        }
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public int inFlight() {
        return inFlight.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public List<String> encode() {
        dirty = false;
        List<String> lines = new ArrayList<>();
        for (Entry entry : entries.values()) {
            lines.add(entry.encode());
        }
        return lines;
    }

    /** Loads saved entries (sent again at the next flush); returns how many. Broken lines go to {@code skipped}. */
    public int load(List<String> lines, List<String> skipped) {
        int loaded = 0;
        for (String line : lines) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                Entry entry = Entry.decode(line);
                entries.put(entry.key(), entry);
                loaded++;
            } catch (IllegalArgumentException e) {
                if (skipped != null) {
                    skipped.add(e.getMessage());
                }
            }
        }
        dirty = false;
        return loaded;
    }
}
