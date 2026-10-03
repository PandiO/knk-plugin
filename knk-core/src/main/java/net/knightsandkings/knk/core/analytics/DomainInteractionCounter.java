package net.knightsandkings.knk.core.analytics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.DomainInteraction;

/**
 * Domain interactions of one window plus distinct players of the local day (KNG-34 link 7,
 * DESIGN.md D11): region entries/exits by WorldGuard region id, discoveries by domain id. The day's
 * player set lives in memory only and is cleared by {@link #newDay()}; what leaves the plugin is the
 * set's size. The API keeps the highest size it has seen for the day, so after a restart (empty set)
 * the stored count never goes down. Not thread-safe: {@link WorldAnalyticsWindow} guards it.
 */
public final class DomainInteractionCounter {

    public static final String ENTER = "enter";
    public static final String LEAVE = "leave";
    public static final String DISCOVER = "discover";

    private record Key(Integer domainId, String regionId, String kind) {
    }

    private final Map<Key, int[]> windowCounts = new LinkedHashMap<>();
    private final Map<Key, Set<UUID>> dayPlayers = new HashMap<>();

    /** A WorldGuard region entry ({@link #ENTER}) or exit ({@link #LEAVE}). */
    public void region(String regionId, String kind, UUID player) {
        if (regionId == null || regionId.isBlank()) {
            return;
        }
        add(new Key(null, regionId, kind), player);
    }

    /** A domain discovery. */
    public void discovered(int domainId, UUID player) {
        if (domainId <= 0) {
            return;
        }
        add(new Key(domainId, null, DISCOVER), player);
    }

    private void add(Key key, UUID player) {
        int[] count = windowCounts.computeIfAbsent(key, k -> new int[1]);
        if (count[0] < Integer.MAX_VALUE) {
            count[0]++;
        }
        if (player != null) {
            dayPlayers.computeIfAbsent(key, k -> new HashSet<>()).add(player);
        }
    }

    /** Forgets the day's players (a new local day started). */
    public void newDay() {
        dayPlayers.clear();
    }

    public int size() {
        return windowCounts.size();
    }

    public boolean isEmpty() {
        return windowCounts.isEmpty();
    }

    /** This window's rows (with the day's distinct players so far) and clears the window counts. */
    public List<DomainInteraction> drain() {
        List<DomainInteraction> out = new ArrayList<>(windowCounts.size());
        windowCounts.forEach((key, count) -> {
            Set<UUID> players = dayPlayers.get(key);
            out.add(new DomainInteraction(key.domainId(), key.regionId(), key.kind(), count[0], players == null ? 0 : players.size()));
        });
        windowCounts.clear();
        return out;
    }
}
