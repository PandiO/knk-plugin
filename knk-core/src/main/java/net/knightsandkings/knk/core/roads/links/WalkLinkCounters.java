package net.knightsandkings.knk.core.roads.links;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The walk-link counters of {@code /knk road status} (off-road navigation DESIGN § 9.3.5; measurements report
 * {@code 2026-11-17-offroad-navigation-measurements.md} § 3.3 budget 6: "later phases add links (L5)"), next to phase
 * 0's walk counters ({@code WalkMeasurements}). Recorded on the routing, walk and main threads; lock-free.
 */
public final class WalkLinkCounters {

    private final AtomicLong routes = new AtomicLong();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong routesWithLinks = new AtomicLong();
    private final AtomicLong linksOnRoutes = new AtomicLong();
    private final AtomicLong wanted = new AtomicLong();
    private final AtomicLong searched = new AtomicLong();
    private final AtomicLong skippedUnderLoad = new AtomicLong();
    private final AtomicLong notCaptured = new AtomicLong();
    private final AtomicLong searchNanos = new AtomicLong();
    private final AtomicLong maxSearchNanos = new AtomicLong();
    private final AtomicLong expansions = new AtomicLong();
    private final AtomicLong improvements = new AtomicLong();
    private final AtomicLong roadFirstRoad = new AtomicLong();
    private final AtomicLong roadFirstWalk = new AtomicLong();

    /** A route was planned with links: how many the A* was offered, how many the route takes. */
    public void routePlanned(int offeredLinks, int linksTaken) {
        routes.incrementAndGet();
        offered.addAndGet(offeredLinks);
        if (linksTaken > 0) {
            routesWithLinks.incrementAndGet();
            linksOnRoutes.addAndGet(linksTaken);
        }
    }

    /** Candidates worth a search after a route (before the concurrency limit). */
    public void wanted(int count) {
        wanted.addAndGet(count);
    }

    /** A wanted link was not searched: the link searches were at their limit (dropped first under load, § 9.3.4). */
    public void skippedUnderLoad() {
        skippedUnderLoad.incrementAndGet();
    }

    /** A link search could not run: its chunks were not loaded (or the box too large). */
    public void notCaptured() {
        notCaptured.incrementAndGet();
    }

    /** A link search ran. */
    public void searched(long nanos, int searchExpansions) {
        searched.incrementAndGet();
        searchNanos.addAndGet(Math.max(0, nanos));
        expansions.addAndGet(Math.max(0, searchExpansions));
        maxSearchNanos.accumulateAndGet(nanos, Math::max);
    }

    /** Found links asked a session for a better route. */
    public void improvementAsked() {
        improvements.incrementAndGet();
    }

    /** The road-first rule decided (direct mode, the developer's wish of 2026-11-18). */
    public void roadFirst(boolean road) {
        (road ? roadFirstRoad : roadFirstWalk).incrementAndGet();
    }

    public long searched() {
        return searched.get();
    }

    public long routesWithLinks() {
        return routesWithLinks.get();
    }

    public long roadFirstRoad() {
        return roadFirstRoad.get();
    }

    public long roadFirstWalk() {
        return roadFirstWalk.get();
    }

    public long skippedUnderLoadCount() {
        return skippedUnderLoad.get();
    }

    /** The status lines, with the cache's counters. */
    public List<String> lines(WalkLinkCache.Counters cache) {
        long n = searched.get();
        double meanMs = n == 0 ? 0 : searchNanos.get() / 1e6 / n;
        return List.of(
            String.format(Locale.ROOT, "walk links: %d routes planned, %d with links (%d links), %d offered, "
                    + "%d better routes asked", routes.get(), routesWithLinks.get(), linksOnRoutes.get(), offered.get(),
                improvements.get()),
            String.format(Locale.ROOT, "walk link searches: %d wanted, %d searched (mean %.1f ms, max %.1f ms, %d"
                    + " expansions), %d found, %d none, %d skipped under load, %d not captured", wanted.get(), n, meanMs,
                maxSearchNanos.get() / 1e6, expansions.get(), cache.found(), cache.none(), skippedUnderLoad.get(),
                notCaptured.get()),
            String.format(Locale.ROOT, "walk link cache: %d links, %d none, %d searching; dropped %d by block changes, "
                    + "%d by gate or rule changes, %d expired, %d evicted; road-first: %d road, %d walk",
                cache.cachedFound(), cache.cachedNone(), cache.pending(), cache.droppedByChange(), cache.droppedByRule(),
                cache.expired(), cache.evicted(), roadFirstRoad.get(), roadFirstWalk.get()));
    }
}
