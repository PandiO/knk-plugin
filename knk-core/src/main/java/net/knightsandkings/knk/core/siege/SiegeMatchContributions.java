package net.knightsandkings.knk.core.siege;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * What each member contributed to one running match beyond kills and captures (closed-alpha test run 1,
 * PandiO/knk-workspace#165 and #166), reported with {@code complete}; the API decides the rewards:
 * <ul>
 *   <li><b>Gate damage</b>: the effective gate HP the member took off the match's gates (direct hits and their
 *   share of fires they lit), summed over the whole match - also across a leave and rejoin.</li>
 *   <li><b>Activity</b>: the member's <em>match window</em> runs from first joining the match to its end (or
 *   their last leave); time away within the rejoin window stays in it. Their <em>active</em> time is the part of
 *   it they were present and not AFK. AFK comes from the statistics AFK tracker as "AFK since": automatic AFK
 *   starts at the last activity, so the idle window before the threshold is AFK time here too.</li>
 * </ul>
 * Active time is kept as intervals: an interval opens at the first observation of a present, non-AFK member and
 * closes at their leave, at the match end, or at the "AFK since" instant once AFK is seen. Observations come once a
 * second (the siege tick), so a return from AFK is counted from the next tick. Pure; main thread only.
 */
public final class SiegeMatchContributions {

    /** One member's figures for the {@code complete} payload. */
    public record Contribution(double gateDamage, long activeSeconds, long matchSeconds) {
        public static final Contribution NONE = new Contribution(0, 0, 0);
    }

    private static final class Entry {
        Integer userId;
        Instant firstJoined;
        Instant windowEnd;
        boolean present;
        Instant activeSince;
        long activeMillis;
        double gateDamage;
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** A member is in the match from {@code now} on (match start, rejoin, admin join). */
    public void joined(UUID playerId, Integer userId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        Entry e = entries.computeIfAbsent(playerId, id -> new Entry());
        if (userId != null) e.userId = userId;
        if (e.firstJoined == null) e.firstJoined = now;
        e.present = true;
        e.windowEnd = null;
        e.activeSince = null; // counted from the next observation
    }

    /**
     * The once-a-second check of a present member. {@code afkSince} null: active now; otherwise AFK since then
     * (closes the open interval there, never before it opened).
     */
    public void observe(UUID playerId, Instant now, Instant afkSince) {
        Entry e = entries.get(playerId);
        if (e == null || !e.present || now == null) return;
        if (afkSince != null) {
            closeInterval(e, earlier(now, afkSince));
        } else if (e.activeSince == null) {
            e.activeSince = now;
        }
    }

    /** A member left the match (leave, quit - also when their place is kept for a rejoin - or kick). */
    public void left(UUID playerId, Instant now, Instant afkSince) {
        Entry e = entries.get(playerId);
        if (e == null || !e.present || now == null) return;
        closeInterval(e, afkSince == null ? now : earlier(now, afkSince));
        e.present = false;
        e.windowEnd = now;
    }

    /** The match ended at {@code end}: every present member's interval and window close there. */
    public void close(Instant end, Function<UUID, Instant> afkSince) {
        Objects.requireNonNull(end, "end");
        for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
            if (!entry.getValue().present) continue;
            Instant since = afkSince == null ? null : afkSince.apply(entry.getKey());
            left(entry.getKey(), end, since);
        }
    }

    /**
     * Effective gate HP {@code loss} the member dealt to one of the match's gates. Ignored for players who never
     * joined this match; NaN and non-positive losses are ignored.
     */
    public void addGateDamage(UUID playerId, double loss) {
        Entry e = entries.get(playerId);
        if (e == null || !(loss > 0) || Double.isInfinite(loss)) return;
        e.gateDamage += loss;
    }

    public boolean contains(UUID playerId) {
        return entries.containsKey(playerId);
    }

    /**
     * The member's figures. The window ends at their leave or the {@link #close match end}; for a member still
     * present (no close yet) it is measured up to {@code now}, with the open interval counted up to it.
     */
    public Contribution of(UUID playerId, Instant now) {
        Entry e = playerId == null ? null : entries.get(playerId);
        if (e == null || e.firstJoined == null) return Contribution.NONE;
        Instant windowEnd = e.windowEnd != null ? e.windowEnd : now;
        if (windowEnd == null) return new Contribution(e.gateDamage, 0, 0);
        long active = e.activeMillis;
        if (e.activeSince != null && windowEnd.isAfter(e.activeSince)) {
            active += Duration.between(e.activeSince, windowEnd).toMillis();
        }
        long window = Math.max(0, Duration.between(e.firstJoined, windowEnd).toMillis());
        long windowSeconds = window / 1000;
        return new Contribution(e.gateDamage, Math.min(active / 1000, windowSeconds), windowSeconds);
    }

    /** The user ids the entries were joined with, for reports keyed by user id. */
    public Map<Integer, UUID> playersByUserId() {
        Map<Integer, UUID> map = new HashMap<>();
        entries.forEach((playerId, e) -> {
            if (e.userId != null) map.put(e.userId, playerId);
        });
        return map;
    }

    private static void closeInterval(Entry e, Instant at) {
        if (e.activeSince == null) return;
        if (at.isAfter(e.activeSince)) {
            e.activeMillis += Duration.between(e.activeSince, at).toMillis();
        }
        e.activeSince = null;
    }

    private static Instant earlier(Instant a, Instant b) {
        return b.isBefore(a) ? b : a;
    }
}
