package net.knightsandkings.knk.core.navigation.model;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/**
 * Keeps the navigation model current (destination engine DESIGN § 7.1 to § 7.3): loads the snapshot at start-up,
 * replaces the model on every successful API refresh, fills in the domain places an older API does not send with a
 * paced background lookup, and saves the snapshot off the main thread when the content changed.
 *
 * <ol>
 *   <li><b>Start-up</b> ({@link #loadSnapshot}): the file's model, if it is readable and of this schema; a corrupt or
 *       newer file is logged and ignored (§ 7.3), never fatal.</li>
 *   <li><b>Refresh</b> ({@link #refresh}): the domain search, the Locations and the compass settings; all three must
 *       answer, else the model is kept as it is (an outage only means no refresh, § 7.1). The answer replaces the
 *       model whole, so renamed and deleted domains are renamed or gone (A14).</li>
 *   <li><b>Paced fallback</b> ({@link #backfillTick}): while the API's search does not carry each domain's Location (an
 *       API from before PandiO/knk-workspace#103), {@link #lookupsPerTick} places per tick are looked up one by one;
 *       a place a refresh did not send keeps what the fallback found before. With the new API nothing is queued.</li>
 *   <li><b>Save</b>: when no place is waiting and the content differs from the file, or the file's {@code asOf} is
 *       more than {@link #ASOF_RESAVE} behind (so the age a restart sees stays within a day), on {@code io}.</li>
 * </ol>
 * Thread-safe; no method blocks on the API.
 */
public final class NavigationModelRefresher {

    private static final Logger LOGGER = Logger.getLogger(NavigationModelRefresher.class.getName());

    /** A confirmed but unchanged model is written again once its file is this far behind. */
    public static final Duration ASOF_RESAVE = Duration.ofDays(1);

    /** The default pace of the fallback: a few lookups per second (§ 7.2) with one tick a second. */
    public static final int DEFAULT_LOOKUPS_PER_TICK = 2;

    /** Where the model's content comes from: the web API through the data accesses. */
    public interface Source {
        /** Every domain of the domain search (all pages). */
        CompletableFuture<List<KnkDomainSummary>> domains();

        /** Every Location of the Location search (all pages). */
        CompletableFuture<List<KnkLocation>> locations();

        /** The navigation compass settings. */
        CompletableFuture<CompassSettings> compass();

        /**
         * The landmarks (off-road phase 5.1, PandiO/knk-workspace#708, E16): {@code GET api/landmarks}, only with
         * {@code navigation.landmarks} on. Default: none (the section stays empty). A failed read keeps the landmarks of
         * the current model, so an API from before #708 (404) or a hiccup never drops them, and never fails the
         * domain/Location/compass refresh.
         */
        default CompletableFuture<List<Landmark>> landmarks() {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** The per-domain fallback (today's {@code DomainLocationResolver}). */
    public interface PlaceLookup {
        /** Whether this domain type can be looked up at all; others are left unknown. */
        boolean supports(String domainType);

        /**
         * The domain's own Location; empty when it has none (or no longer exists). Completes exceptionally when the
         * lookup failed: the place is tried again after the next refresh.
         */
        CompletableFuture<Optional<KnkLocation>> lookup(DomainPlace domain);
    }

    private final Source source;
    private final PlaceLookup places;
    private final NavigationModelHolder holder;
    private final NavigationModelSnapshot snapshot;
    private final Executor io;
    private final Clock clock;
    private final int lookupsPerTick;

    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Object queueLock = new Object();
    private final Set<Integer> queue = new LinkedHashSet<>();
    private final Set<Integer> failedThisRound = new LinkedHashSet<>();
    private int inFlight;
    private volatile NavigationModel saved;
    private final AtomicBoolean saveScheduled = new AtomicBoolean();

    /**
     * @param snapshot       the file; null keeps the model in memory only (tests)
     * @param io             where snapshot writes run (off the main thread)
     * @param lookupsPerTick how many places the fallback looks up per {@link #backfillTick} (at least 1)
     */
    public NavigationModelRefresher(Source source, PlaceLookup places, NavigationModelHolder holder,
                                    NavigationModelSnapshot snapshot, Executor io, Clock clock, int lookupsPerTick) {
        this.source = Objects.requireNonNull(source, "source");
        this.places = Objects.requireNonNull(places, "places");
        this.holder = Objects.requireNonNull(holder, "holder");
        this.snapshot = snapshot;
        this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lookupsPerTick = Math.max(1, lookupsPerTick);
    }

    public NavigationModelHolder holder() {
        return holder;
    }

    // ==================== start-up ====================

    /** Reads the snapshot into the holder (start-up; a small file). Never throws. */
    public NavigationModelSnapshot.Loaded loadSnapshot() {
        if (snapshot == null) {
            return new NavigationModelSnapshot.Loaded(NavigationModelSnapshot.Loaded.Status.MISSING, null, null);
        }
        NavigationModelSnapshot.Loaded loaded = snapshot.read();
        switch (loaded.status()) {
            case LOADED -> {
                holder.replace(loaded.model());
                saved = loaded.model();
                queueUnknownPlaces(loaded.model());
            }
            case MISSING -> { }
            case CORRUPT, NEWER_SCHEMA -> LOGGER.warning("[Navigation] Ignoring the navigation model snapshot "
                + snapshot.file() + " (" + loaded.status() + ": " + loaded.detail() + "); the next good refresh writes a new one");
        }
        return loaded;
    }

    // ==================== refresh ====================

    /** As {@link #refresh(boolean)} keeping the places the fallback found. */
    public CompletableFuture<Boolean> refresh() {
        return refresh(false);
    }

    /**
     * Asks the API for the model's content and replaces the model with it. Completes with whether the content
     * changed; false too when another refresh runs or the API did not answer (logged, model kept). Never awaited on
     * the main thread.
     *
     * @param forgetLookedUpPlaces true ({@code /knk cache refresh}): places the fallback found earlier are looked up
     *                             again instead of being carried over (only matters with an older API)
     */
    public CompletableFuture<Boolean> refresh(boolean forgetLookedUpPlaces) {
        if (!refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<List<KnkDomainSummary>> domains;
        CompletableFuture<List<KnkLocation>> locations;
        CompletableFuture<CompassSettings> compass;
        CompletableFuture<List<Landmark>> landmarks;
        try {
            domains = source.domains();
            locations = source.locations();
            compass = source.compass();
            landmarks = landmarksOrCurrent(source);
        } catch (RuntimeException e) {
            refreshing.set(false);
            LOGGER.log(Level.WARNING, "[Navigation] Could not start the navigation model refresh", e);
            return CompletableFuture.completedFuture(false);
        }
        return domains.thenCombine(locations, (d, l) -> build(d, l, null))
            .thenCombine(compass, (model, c) -> new NavigationModel(model.asOf(), model.domains(), model.locations(), c,
                model.landmarks()))
            .thenCombine(landmarks, (model, l) -> new NavigationModel(model.asOf(), model.domains(), model.locations(),
                model.compass(), l))
            .handle((model, error) -> {
                try {
                    if (error != null || model == null) {
                        LOGGER.warning("[Navigation] Navigation model refresh failed, keeping the last model: "
                            + (error == null ? "no answer" : rootMessage(error)));
                        return false;
                    }
                    return apply(model, forgetLookedUpPlaces);
                } finally {
                    refreshing.set(false);
                }
            });
    }

    /** The source's landmarks; on a failure (or none sent) the current model's, with a warning. */
    private CompletableFuture<List<Landmark>> landmarksOrCurrent(Source source) {
        CompletableFuture<List<Landmark>> asked;
        try {
            asked = source.landmarks();
        } catch (RuntimeException e) {
            asked = CompletableFuture.failedFuture(e);
        }
        if (asked == null) {
            asked = CompletableFuture.completedFuture(null);
        }
        return asked.handle((landmarks, error) -> {
            if (error == null && landmarks != null) {
                return landmarks;
            }
            NavigationModel current = holder.current();
            if (error != null) {
                LOGGER.warning("[Navigation] Landmarks not refreshed, keeping the last ones: " + rootMessage(error));
            }
            return current == null ? List.<Landmark>of() : current.landmarks();
        });
    }

    private boolean apply(NavigationModel fresh, boolean forgetLookedUpPlaces) {
        NavigationModel next = forgetLookedUpPlaces ? fresh : fresh.withKnownPlacesOf(holder.current());
        boolean first = holder.current() == null;
        boolean changed = holder.replace(next);
        synchronized (queueLock) {
            failedThisRound.clear();
        }
        int queued = queueUnknownPlaces(holder.current());
        if (first || changed) {
            LOGGER.info("[Navigation] Navigation model " + (first ? "loaded" : "updated") + ": " + next.domains().size()
                + " domain(s), " + next.locations().size() + " Location(s)"
                + (queued > 0 ? ", " + queued + " domain place(s) to look up one by one (the API's search sends no Locations)" : ""));
        }
        maybeSave();
        return changed;
    }

    /** The model from the API's answers (compass settings filled in by the caller). */
    NavigationModel build(List<KnkDomainSummary> domains, List<KnkLocation> locations, CompassSettings compass) {
        List<DomainPlace> places = new ArrayList<>();
        for (KnkDomainSummary d : domains == null ? List.<KnkDomainSummary>of() : domains) {
            if (d == null || d.id() == null || d.name() == null || d.name().isBlank() || d.domainType() == null) {
                continue;
            }
            ModelPoint spawn = d.locationSent() ? ModelPoint.of(d.location()).orElse(null) : null;
            places.add(new DomainPlace(d.id(), d.name(), d.domainType(), d.navigationDefault(), d.roadAccess(),
                d.wgRegionId(), d.worldName(), spawn, d.locationSent()));
        }
        List<ModelPoint> points = new ArrayList<>();
        for (KnkLocation l : locations == null ? List.<KnkLocation>of() : locations) {
            ModelPoint.of(l).ifPresent(points::add);
        }
        return new NavigationModel(clock.instant(), places, points, compass, List.of());
    }

    // ==================== paced fallback (§ 7.2) ====================

    /** How many places wait for the fallback. */
    public int queuedPlaces() {
        synchronized (queueLock) {
            return queue.size() + inFlight;
        }
    }

    /**
     * Looks up the next {@link #lookupsPerTick} places (call once a second, off the main thread). Starts nothing while
     * the previous tick's lookups are still out.
     */
    public void backfillTick() {
        List<DomainPlace> batch = new ArrayList<>();
        NavigationModel model = holder.current();
        synchronized (queueLock) {
            if (inFlight > 0 || queue.isEmpty() || model == null) {
                return;
            }
            var it = queue.iterator();
            while (it.hasNext() && batch.size() < lookupsPerTick) {
                int id = it.next();
                it.remove();
                model.domain(id).filter(d -> !d.placeKnown()).ifPresent(batch::add);
            }
            inFlight += batch.size();
        }
        if (batch.isEmpty()) {
            maybeSave();
            return;
        }
        for (DomainPlace domain : batch) {
            CompletableFuture<Optional<KnkLocation>> lookup;
            try {
                lookup = places.lookup(domain);
            } catch (RuntimeException e) {
                lookup = CompletableFuture.failedFuture(e);
            }
            lookup.whenComplete((location, error) -> {
                if (error != null || location == null) {
                    LOGGER.fine("[Navigation] Could not look up the place of domain " + domain.id() + ": "
                        + (error == null ? "no answer" : rootMessage(error)));
                    synchronized (queueLock) {
                        failedThisRound.add(domain.id());
                    }
                } else {
                    ModelPoint spawn = location.flatMap(ModelPoint::of).orElse(null);
                    holder.update(m -> m.domain(domain.id()).filter(d -> !d.placeKnown()).isPresent()
                        ? m.withPlace(domain.id(), spawn) : m);
                }
                boolean done;
                synchronized (queueLock) {
                    inFlight--;
                    done = inFlight == 0 && queue.isEmpty();
                }
                if (done) {
                    maybeSave();
                }
            });
        }
    }

    private int queueUnknownPlaces(NavigationModel model) {
        if (model == null) {
            return 0;
        }
        synchronized (queueLock) {
            for (DomainPlace d : model.unknownPlaces()) {
                if (!failedThisRound.contains(d.id()) && places.supports(d.domainType())) {
                    queue.add(d.id());
                }
            }
            queue.removeIf(id -> model.domain(id).map(DomainPlace::placeKnown).orElse(true));
            return queue.size();
        }
    }

    // ==================== saving ====================

    /** Schedules a write when the model is complete (no place queued) and differs from the file. */
    void maybeSave() {
        if (snapshot == null) {
            return;
        }
        synchronized (queueLock) {
            if (!queue.isEmpty() || inFlight > 0) {
                return;
            }
        }
        NavigationModel model = holder.current();
        if (model == null || !needsSave(model) || !saveScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            io.execute(this::saveNow);
        } catch (RuntimeException e) {
            saveScheduled.set(false);
            LOGGER.log(Level.WARNING, "[Navigation] Could not schedule the navigation model snapshot write", e);
        }
    }

    private boolean needsSave(NavigationModel model) {
        NavigationModel file = saved;
        return file == null || !file.sameContent(model) || model.asOf().isAfter(file.asOf().plus(ASOF_RESAVE));
    }

    private void saveNow() {
        try {
            NavigationModel model = holder.current();
            if (model == null || !needsSave(model)) {
                return;
            }
            snapshot.write(model);
            saved = model;
            LOGGER.fine("[Navigation] Navigation model snapshot written: " + model);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Navigation] Could not write the navigation model snapshot " + snapshot.file(), e);
        } finally {
            saveScheduled.set(false);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
