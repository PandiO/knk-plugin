package net.knightsandkings.knk.core.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Overview map, plugin side (PandiO/knk-workspace#735, OVERVIEW_MAP_DESIGN § 7.1, O15, O27, O28): reports the outlines
 * of the domain regions to the API over the game hub ({@code ReportRegionOutlines}), so the public map can draw them
 * while the game server is down. Bukkit-free; the region store is read through {@link RegionOutlinePort}.
 * <ol>
 *   <li>{@link #reportDomainRegions}: after every managed-region repair (startup and {@code /knk regions repair}) with
 *       every domain region; the set is remembered.</li>
 *   <li>{@link #regionRenamed}: after a world task's region was renamed (or redefined in place): the new region's
 *       outline, and the old names as removed.</li>
 *   <li>{@link #resendAll}: on every (re)connect of the game hub, the remembered set again (the startup repair can finish
 *       before the hub is connected, and the API may have restarted).</li>
 * </ol>
 * Since L4 (PandiO/knk-workspace#738, § 5 rule 6, O07, O21) it also builds the live player report
 * ({@code ReportMapPlayers}): {@link #collectPlayers} takes the snapshot through {@link OnlinePlayerSnapshotPort} every
 * {@code map.reporting.interval-ticks} (on the main thread on Paper), {@link #sendPlayers} sends it (off the main thread).
 * <p>
 * With {@code map.reporting.enabled: false} (the default, O27) every method returns at once and nothing is read or sent.
 * Call the outline methods on the thread where {@link RegionOutlinePort} may read (the main thread on Paper).
 */
public final class MapReportingService {

    public static final String REGION_OUTLINES_METHOD = "ReportRegionOutlines";

    /** The game hub method of the live player report (spec § 6.2.6). */
    public static final String PLAYERS_METHOD = "ReportMapPlayers";

    /** {@code map.reporting.interval-ticks} default: 40 ticks, 2 s (O27). */
    public static final int DEFAULT_INTERVAL_TICKS = 40;

    /** Bounds of {@code map.reporting.interval-ticks}: at most twice a second, at least once a minute. */
    static final int MIN_INTERVAL_TICKS = 10;
    static final int MAX_INTERVAL_TICKS = 1_200;

    /** A generous upper bound of one player's JSON in the report (uuid, world, numbers at their widest). */
    static final int PLAYER_CHARS = 260;

    /**
     * Upper bound of one hub message's JSON, below the API's SignalR receive limit (32 KB by default) with room for the
     * envelope. Larger reports go in several messages.
     */
    static final int MAX_MESSAGE_CHARS = 24_000;

    /** An outline larger than one message is not sent (logged); a drawn region has far fewer points. */
    static final int MAX_POINTS = 1_000;

    /** What one call sent. {@code missing}: regions not found; {@code skipped}: too large to send. */
    public record Summary(int outlines, int removed, int missing, int skipped, int messages, int failedMessages) {

        static final Summary NOTHING = new Summary(0, 0, 0, 0, 0, 0);

        public boolean allSent() {
            return failedMessages == 0;
        }
    }

    private final boolean enabled;
    private final RegionOutlinePort outlines;
    private final MapReportSender sender;
    private final Logger logger;
    private final Map<String, RegionRef> known = new LinkedHashMap<>();
    private final OnlinePlayerSnapshotPort players;
    private final int intervalTicks;
    /** Whether the last player report sent was empty (an empty report is sent once, not every interval). */
    private volatile boolean lastPlayersEmpty;

    public MapReportingService(boolean enabled, RegionOutlinePort outlines, MapReportSender sender, Logger logger) {
        this(enabled, outlines, null, DEFAULT_INTERVAL_TICKS, sender, logger);
    }

    /**
     * @param players       the online players, or null for no player report
     * @param intervalTicks {@code map.reporting.interval-ticks}; clamped to 10..1200
     */
    public MapReportingService(boolean enabled, RegionOutlinePort outlines, OnlinePlayerSnapshotPort players,
                               int intervalTicks, MapReportSender sender, Logger logger) {
        this.enabled = enabled;
        this.outlines = outlines;
        this.players = players;
        this.intervalTicks = clampInterval(intervalTicks);
        this.sender = sender;
        this.logger = logger;
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@code map.reporting.interval-ticks} as used (clamped to 10..1200). */
    public int intervalTicks() {
        return intervalTicks;
    }

    /** Whether the live player report runs: reporting on and a player source given. */
    public boolean playersEnabled() {
        return enabled && players != null;
    }

    static int clampInterval(int ticks) {
        return Math.max(MIN_INTERVAL_TICKS, Math.min(MAX_INTERVAL_TICKS, ticks));
    }

    /** A taken player snapshot, ready to send. {@code messages} are the hub arguments in order. */
    public record PlayersReport(int players, List<Map<String, Object>> messages) {
    }

    /**
     * Takes the player snapshot and builds the hub messages (main thread on Paper; cheap: one pass over the players).
     * Null when nothing is to be sent: reporting off, no player source, or no player online and the last report sent
     * was already empty. The API drops a player after three missed intervals (§ 5 rule 6), so the interval goes with
     * every message.
     */
    public PlayersReport collectPlayers() {
        if (!playersEnabled()) {
            return null;
        }
        List<OnlinePlayerPosition> online;
        try {
            online = players.snapshot();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "[KnK Map] could not read the online players", e);
            return null;
        }
        if (online == null) {
            online = List.of();
        }
        if (online.isEmpty() && lastPlayersEmpty) {
            return null;
        }
        long intervalMs = intervalTicks * 50L;
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> batch = new ArrayList<>();
        for (OnlinePlayerPosition position : online) {
            if (position == null || position.uuid() == null) {
                continue;
            }
            if (!batch.isEmpty() && (batch.size() + 1) * PLAYER_CHARS > MAX_MESSAGE_CHARS) {
                messages.add(playersMessage(intervalMs, batch));
                batch = new ArrayList<>();
            }
            batch.add(payload(position));
        }
        if (!batch.isEmpty() || messages.isEmpty()) {
            messages.add(playersMessage(intervalMs, batch));
        }
        return new PlayersReport(online.size(), messages);
    }

    /**
     * Sends a report built by {@link #collectPlayers} (any thread; the game hub send does not block). Returns false
     * when a message was not sent (hub not connected: the next interval sends a fresh snapshot, nothing is queued).
     */
    public boolean sendPlayers(PlayersReport report) {
        if (report == null || !playersEnabled()) {
            return false;
        }
        boolean all = true;
        for (Map<String, Object> message : report.messages()) {
            boolean sent;
            try {
                sent = sender.send(PLAYERS_METHOD, message);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "[KnK Map] sending the player report failed", e);
                sent = false;
            }
            all &= sent;
        }
        // Only a delivered empty report counts: after a failed one the next interval tries again.
        lastPlayersEmpty = all && report.players() == 0;
        return all;
    }

    private static Map<String, Object> playersMessage(long intervalMs, List<Map<String, Object>> batch) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("intervalMs", intervalMs);
        message.put("players", batch);
        return message;
    }

    /** One player as sent: positions to 0.1 block, yaw to a whole degree, mode as the API's wire value. */
    static Map<String, Object> payload(OnlinePlayerPosition p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", p.uuid().toString());
        map.put("world", p.world());
        map.put("x", round1(p.x()));
        map.put("y", round1(p.y()));
        map.put("z", round1(p.z()));
        map.put("yaw", Math.round(p.yaw()));
        map.put("afk", p.afk());
        map.put("mode", switch (p.mode()) {
            case STAFF -> "staff";
            case OWNER -> "owner";
            case NONE -> "none";
        });
        map.put("minigame", p.minigame());
        map.put("matchId", p.matchId());
        return map;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Replaces the remembered set with {@code regions} (every domain region) and sends their outlines. */
    public synchronized Summary reportDomainRegions(Collection<RegionRef> regions) {
        if (!enabled) {
            return Summary.NOTHING;
        }
        known.clear();
        List<RegionOutline> found = new ArrayList<>();
        int missing = 0;
        for (RegionRef ref : regions) {
            Optional<RegionOutline> outline = read(ref);
            if (outline.isPresent()) {
                RegionOutline value = outline.get();
                if (known.putIfAbsent(value.ref().key(), value.ref()) == null) {
                    found.add(value);
                }
            } else {
                missing++;
            }
        }
        Summary summary = send(found, List.of(), missing);
        log("domain regions", summary);
        return summary;
    }

    /**
     * A world task's region {@code oldRegionId} became {@code newRegionId} in {@code worldName}; {@code replacedRegionId}
     * (or null) is the domain's previous region it replaced (KNG-103). Sends the new outline and the old names as removed.
     */
    public synchronized Summary regionRenamed(String worldName, String oldRegionId, String newRegionId, String replacedRegionId) {
        if (!enabled || newRegionId == null || newRegionId.isBlank()) {
            return Summary.NOTHING;
        }
        RegionRef renamed = new RegionRef(worldName, newRegionId);
        List<RegionRef> removed = new ArrayList<>();
        for (String gone : new String[] {oldRegionId, replacedRegionId}) {
            if (gone == null || gone.isBlank() || gone.trim().equalsIgnoreCase(newRegionId.trim())) {
                continue;
            }
            RegionRef ref = new RegionRef(worldName, gone);
            if (removed.stream().noneMatch(r -> r.key().equals(ref.key()))) {
                removed.add(ref);
            }
        }
        removed.forEach(ref -> known.remove(ref.key()));
        Optional<RegionOutline> outline = read(renamed);
        outline.ifPresent(value -> known.put(value.ref().key(), value.ref()));
        Summary summary = send(outline.map(List::of).orElse(List.of()), removed, outline.isPresent() ? 0 : 1);
        log("region " + newRegionId, summary);
        return summary;
    }

    /** Sends the remembered regions' current outlines again (hub reconnect). Nothing before the first repair. */
    public synchronized Summary resendAll() {
        if (!enabled || known.isEmpty()) {
            return Summary.NOTHING;
        }
        return reportDomainRegions(new ArrayList<>(known.values()));
    }

    /** How many regions are remembered for {@link #resendAll}. */
    public synchronized int knownRegionCount() {
        return known.size();
    }

    private Optional<RegionOutline> read(RegionRef ref) {
        try {
            return outlines.outline(ref.worldName(), ref.regionId()).filter(o -> o.points().size() >= 3);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "[KnK Map] could not read the outline of " + ref.regionId(), e);
            return Optional.empty();
        }
    }

    private Summary send(List<RegionOutline> toSend, List<RegionRef> removed, int missing) {
        int skipped = 0;
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> batch = new ArrayList<>();
        int batchChars = removedChars(removed);
        int sentOutlines = 0;
        for (RegionOutline outline : toSend) {
            if (outline.points().size() > MAX_POINTS) {
                skipped++;
                logger.warning("[KnK Map] outline of " + outline.regionId() + " (" + outline.worldName() + ") has "
                        + outline.points().size() + " points, more than " + MAX_POINTS + "; not sent");
                continue;
            }
            int chars = estimateChars(outline);
            if (!batch.isEmpty() && batchChars + chars > MAX_MESSAGE_CHARS) {
                batches.add(batch);
                batch = new ArrayList<>();
                batchChars = 0;
            }
            batch.add(payload(outline));
            batchChars += chars;
            sentOutlines++;
        }
        if (!batch.isEmpty() || (batches.isEmpty() && !removed.isEmpty())) {
            batches.add(batch);
        }
        int failed = 0;
        for (int i = 0; i < batches.size(); i++) {
            Map<String, Object> argument = new LinkedHashMap<>();
            argument.put("outlines", batches.get(i));
            argument.put("removed", i == 0 ? removed.stream().map(MapReportingService::payload).toList() : List.of());
            boolean sent;
            try {
                sent = sender.send(REGION_OUTLINES_METHOD, argument);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "[KnK Map] sending region outlines failed", e);
                sent = false;
            }
            if (!sent) {
                failed++;
            }
        }
        return new Summary(sentOutlines, removed.size(), missing, skipped, batches.size(), failed);
    }

    private void log(String what, Summary summary) {
        if (summary.messages() == 0 && summary.missing() == 0) {
            return;
        }
        String line = "[KnK Map] Region outlines (" + what + "): " + summary.outlines() + " outline(s), " + summary.removed()
                + " removed, " + summary.missing() + " not found, " + summary.skipped() + " too large, "
                + summary.messages() + " message(s)";
        if (summary.allSent()) {
            logger.info(line);
        } else {
            logger.info(line + "; " + summary.failedMessages() + " not sent (game hub not connected): sent again when it connects");
        }
    }

    static Map<String, Object> payload(RegionOutline outline) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("wgRegionId", outline.regionId());
        map.put("worldName", outline.worldName());
        List<Map<String, Object>> points = new ArrayList<>(outline.points().size());
        for (OutlinePoint point : outline.points()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", point.x());
            p.put("z", point.z());
            points.add(p);
        }
        map.put("points", points);
        map.put("minY", outline.minY());
        map.put("maxY", outline.maxY());
        return map;
    }

    static Map<String, Object> payload(RegionRef ref) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("wgRegionId", ref.regionId());
        map.put("worldName", ref.worldName());
        return map;
    }

    /** A generous estimate of an outline's JSON length (every number at its widest). */
    static int estimateChars(RegionOutline outline) {
        return 120 + outline.regionId().length() + outline.worldName().length() + outline.points().size() * 32;
    }

    private static int removedChars(List<RegionRef> removed) {
        int chars = 0;
        for (RegionRef ref : removed) {
            chars += 60 + ref.regionId().length() + (ref.worldName() == null ? 4 : ref.worldName().length());
        }
        return chars;
    }
}
