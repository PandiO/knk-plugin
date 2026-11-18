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
 * With {@code map.reporting.enabled: false} (the default, O27) every method returns at once and nothing is read or sent.
 * Call on the thread where {@link RegionOutlinePort} may read (the main thread on Paper).
 */
public final class MapReportingService {

    public static final String REGION_OUTLINES_METHOD = "ReportRegionOutlines";

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

    public MapReportingService(boolean enabled, RegionOutlinePort outlines, MapReportSender sender, Logger logger) {
        this.enabled = enabled;
        this.outlines = outlines;
        this.sender = sender;
        this.logger = logger;
    }

    public boolean enabled() {
        return enabled;
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
