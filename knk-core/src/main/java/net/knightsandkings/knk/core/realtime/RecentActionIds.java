package net.knightsandkings.knk.core.realtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ids of the staff actions this server already carried out (kick/ban from the web app, PandiO/knk-workspace#574,
 * DESIGN §6.10): the API sends each action once per connected server, so a repeat is a duplicate and is skipped. Kept
 * in memory and bounded (the oldest id is forgotten first); a restart forgets them, which is safe because the actions
 * themselves are idempotent (a kick needs the player online, a ban on a banned profile changes nothing). Thread-safe.
 */
public final class RecentActionIds {

    public static final int DEFAULT_CAPACITY = 1000;

    private final Map<String, Boolean> seen;

    public RecentActionIds() {
        this(DEFAULT_CAPACITY);
    }

    public RecentActionIds(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** True the first time an id is offered (act on it), false for a repeat or a null/blank id. */
    public synchronized boolean firstTime(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return false;
        }
        return seen.putIfAbsent(actionId.trim(), Boolean.TRUE) == null;
    }

    public synchronized int size() {
        return seen.size();
    }
}
