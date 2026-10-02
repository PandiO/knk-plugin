package net.knightsandkings.knk.paper.roads;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadSurveyCreate;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.Rdp;
import net.knightsandkings.knk.core.roads.route.CoverageCheck;
import net.knightsandkings.knk.core.roads.survey.ProfileLearner;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.core.roads.survey.SurveyStats;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.kyori.adventure.text.Component;

/**
 * {@code /knk road survey start [profile] | stop | cancel | save [name] | merge <profile> | discard} and
 * {@code /knk road record start | stop [street] | cancel} (DESIGN §5.3, §5.10; plan Phase 3
 * "RoadSurveyService"). Every {@code sample-period-ticks} each walking admin's {@link RoadSurveySession}
 * gets a tick (world reads on the main thread, at most 15 columns). On stop the learner runs on the
 * profile's stored stats + this walk and the chat shows the proposal with clickable
 * <b>Save / Merge into … / Discard</b> (R26); Save creates or updates the profile, stores the survey
 * (with the breadcrumb, {@code X-Acting-User-Id} = the admin's knk user id) and one Survey seed every
 * {@code breadcrumb-seed-spacing} blocks, and lists the breadcrumb points the current network misses.
 */
public final class RoadSurveyService implements Listener {
    /** Recorded stretches are simplified with this tolerance before upload. */
    public static final double RECORD_EPSILON = 0.75;
    public static final double RECORD_DEFAULT_WIDTH = 2;

    /** A finished walk waiting for Save / Merge / Discard. */
    record Review(RoadSurveySession session, SurveyStats walk, RoadProfile target, SurveyStats merged, ProposedProfile learned) {
    }

    /** A {@code /knk road record} walk: floor blocks in order. */
    static final class Recording {
        final String world;
        final CrossSectionSampler sampler;
        final List<int[]> points = new ArrayList<>();

        Recording(String world, CrossSectionSampler sampler) {
            this.world = world;
            this.sampler = sampler;
        }
    }

    private final Plugin plugin;
    private final NavigationConfig config;
    private final RoadNetworkQueryApi queryApi;
    private final RoadNetworkCommandApi commandApi;
    private final RoadNetworkCache cache;
    private final RegionIds regionIds;
    private final Executor mainThread;
    private final Function<Player, Integer> cachedUserId;
    private final PassabilityRules rules;
    private final Map<UUID, RoadSurveySession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Review> reviews = new ConcurrentHashMap<>();
    private final Map<UUID, Recording> recordings = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    private long tick;

    public RoadSurveyService(Plugin plugin, NavigationConfig config, RoadNetworkQueryApi queryApi, RoadNetworkCommandApi commandApi,
                             RoadNetworkCache cache, RegionIds regionIds, Executor mainThread, Function<Player, Integer> cachedUserId) {
        this.plugin = plugin;
        this.config = Objects.requireNonNull(config, "config");
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.regionIds = regionIds;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.cachedUserId = cachedUserId == null ? p -> null : cachedUserId;
        this.rules = config.passabilityRules(ChunkSnapshotSurfaceGrid.bukkitCollidable());
    }

    // ===== lifecycle =====

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = config.survey().samplePeriodTicks();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, period, period);
    }

    /** Shutdown: every walk is discarded (nothing to spool), reviews and recordings dropped. */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (UUID id : sessions.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(RoadMessages.warn("Server stopping: your survey walk was discarded."));
            }
        }
        sessions.clear();
        reviews.clear();
        recordings.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        reviews.remove(id);
        recordings.remove(id);
    }

    public int activeCount() {
        return sessions.size();
    }

    public boolean isSurveying(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ===== ticking =====

    /** Main thread. */
    void tickAll() {
        tick += config.survey().samplePeriodTicks();
        for (Map.Entry<UUID, RoadSurveySession> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                sessions.remove(entry.getKey());
                continue;
            }
            RoadSurveySession session = entry.getValue();
            if (!player.getWorld().getName().equals(session.world())) {
                sessions.remove(entry.getKey());
                player.sendMessage(RoadMessages.warn("You changed world: the survey walk was cancelled."));
                continue;
            }
            Location at = player.getLocation();
            World world = player.getWorld();
            RoadSurveySession.Live live = session.tick(tick, at.getX(), at.getY(), at.getZ(), at.getYaw(), player.isOnGround(),
                player.isFlying(), player.isGliding(), player.isInsideVehicle(), player.isSwimming(), player.isInWater(),
                (x, y, z) -> world.getBlockAt(x, y, z).getType().name());
            player.sendActionBar(Component.text(liveText(live), RoadMessages.HIGHLIGHT));
        }
        for (Map.Entry<UUID, Recording> entry : recordings.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.getWorld().getName().equals(entry.getValue().world)) {
                recordings.remove(entry.getKey());
                continue;
            }
            Location at = player.getLocation();
            World world = player.getWorld();
            // The floor block the admin walks on (smoke test 2026-10-02: floor(y − ε) − 1 put every recorded
            // point one block into the road, so the overlay drew recordings through the road blocks).
            int floorY = entry.getValue().sampler.floorY((x, y, z) -> world.getBlockAt(x, y, z).getType().name(),
                at.getBlockX(), at.getY(), at.getBlockZ());
            int[] floor = {at.getBlockX(), floorY, at.getBlockZ()};
            List<int[]> points = entry.getValue().points;
            if (points.isEmpty() || distance(points.get(points.size() - 1), floor) >= 1) {
                points.add(floor);
            }
            player.sendActionBar(Component.text("Recording: " + points.size() + " points, " + RoadMessages.distance(Rdp.length(points))
                + " - /knk road record stop [street]", RoadMessages.HIGHLIGHT));
        }
    }

    static String liveText(RoadSurveySession.Live live) {
        String gate = switch (live.verdict()) {
            case SAMPLE -> "";
            case FLYING -> " (flying - not sampling)";
            case RIDING -> " (riding - not sampling)";
            case SWIMMING -> " (swimming - not sampling)";
            case NOT_ON_GROUND -> " (airborne)";
            case STANDING_STILL -> " (walk to sample)";
        };
        return "Survey: " + live.samples() + " samples · " + live.topMaterials() + " · " + live.width() + gate;
    }

    // ===== survey start / stop / cancel =====

    /** Starts a walk; {@code profileName} null = learn a new profile (named on save). */
    public void start(Player player, String profileName) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            player.sendMessage(RoadMessages.warn("You are already surveying: /knk road survey stop first."));
            return;
        }
        if (reviews.containsKey(id)) {
            player.sendMessage(RoadMessages.warn("Finish the previous walk first: Save, Merge or Discard it."));
            return;
        }
        String world = player.getWorld().getName();
        CrossSectionSampler sampler = new CrossSectionSampler(rules, player.getWorld().getMinHeight(), player.getWorld().getMaxHeight(),
            config.survey().crossSectionHalfWidth());
        if (profileName == null || profileName.isBlank()) {
            sessions.put(id, new RoadSurveySession(id, world, null, null, sampler));
            player.sendMessage(RoadMessages.good("Survey started - learning a new profile. Walk the road for a few minutes (on foot, on the ground), then /knk road survey stop."));
            return;
        }
        queryApi.profiles().whenComplete((profiles, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(player, "load the profiles", ex)) {
                return;
            }
            Optional<RoadProfile> match = RoadAdminCommand.findProfile(profiles, profileName);
            if (!player.isOnline() || sessions.containsKey(id)) {
                return;
            }
            sessions.put(id, new RoadSurveySession(id, world, match.orElse(null), match.isPresent() ? null : profileName, sampler));
            player.sendMessage(RoadMessages.good(match.isPresent()
                ? "Survey started - refining profile \"" + match.get().name() + "\" (" + match.get().sampleCount() + " samples so far). Walk, then /knk road survey stop."
                : "Survey started - learning new profile \"" + profileName + "\". Walk the road, then /knk road survey stop."));
        }));
    }

    /** Ends the walk and shows the proposal with Save / Merge / Discard. */
    public void stop(Player player) {
        RoadSurveySession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            player.sendMessage(RoadMessages.warn("You are not surveying (/knk road survey start)."));
            return;
        }
        if (session.sampleCount() == 0) {
            player.sendMessage(RoadMessages.warn("No samples taken - nothing to learn. Walk on the road, on the ground, on foot."));
            return;
        }
        SurveyStats walk = session.stats();
        review(player, session, walk, session.profile().orElse(null));
    }

    public void cancel(Player player) {
        boolean had = sessions.remove(player.getUniqueId()) != null | reviews.remove(player.getUniqueId()) != null;
        player.sendMessage(had ? RoadMessages.info("Survey cancelled; nothing saved.") : RoadMessages.warn("You are not surveying."));
    }

    /** Learns on (target's stored stats + walk) and shows the review; a bad stored stats version is shown, never overwritten. */
    private void review(Player player, RoadSurveySession session, SurveyStats walk, RoadProfile target) {
        SurveyStats stored;
        try {
            stored = target != null && target.hasStats() ? SurveyStats.fromJson(target.statsJson()) : SurveyStats.empty();
        } catch (RuntimeException e) {
            player.sendMessage(RoadMessages.bad("Profile \"" + target.name() + "\" has stored statistics this plugin can't read (" + e.getMessage()
                + "); not overwriting them. Save this walk as a new profile instead: /knk road survey save <name>"));
            reviews.put(player.getUniqueId(), new Review(session, walk, null, walk, new ProfileLearner().learn(walk)));
            return;
        }
        SurveyStats merged = stored.merge(walk);
        ProposedProfile learned;
        try {
            learned = new ProfileLearner().learn(merged);
        } catch (RuntimeException e) {
            player.sendMessage(RoadMessages.bad("The learner could not make a profile from this walk: " + e.getMessage()));
            return;
        }
        reviews.put(player.getUniqueId(), new Review(session, walk, target, merged, learned));
        showReview(player, session, walk, target, learned);
    }

    private void showReview(Player player, RoadSurveySession session, SurveyStats walk, RoadProfile target, ProposedProfile learned) {
        String name = target != null ? target.name() : session.newProfileName().orElse(null);
        player.sendMessage(RoadMessages.prefixed(Component.text("Survey done: " + walk.samples() + " samples" + (target != null
            ? " → profile \"" + target.name() + "\" would have " + learned.sampleCount() + " samples" : ""), RoadMessages.HIGHLIGHT)));
        player.sendMessage(RoadMessages.field("width", learned.widthMin() + ".." + learned.widthMax() + " blocks"));
        for (RoadMaterialRole role : RoadMaterialRole.values()) {
            List<ProposedProfile.Material> of = learned.withRole(role);
            if (of.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (ProposedProfile.Material m : of) {
                if (line.length() > 0) {
                    line.append(", ");
                }
                line.append(m.material()).append(' ').append(RoadMessages.percent(m.centreShare()));
                if (m.ambiguous()) {
                    line.append("?");
                }
            }
            player.sendMessage(RoadMessages.field(role.apiName(), line.toString()));
        }
        player.sendMessage(Component.text("? = proposed ambiguous (also seen off the road). ", RoadMessages.INFO));
        Component actions = Component.text("", RoadMessages.INFO);
        if (target != null) {
            actions = actions.append(RoadMessages.command("[Save into \"" + target.name() + "\"]", "/knk road survey save"));
        } else if (name != null) {
            actions = actions.append(RoadMessages.command("[Save as \"" + name + "\"]", "/knk road survey save"));
        } else {
            actions = actions.append(RoadMessages.suggest("[Save as new profile…]", "/knk road survey save <name>"));
        }
        actions = actions.append(Component.text("  ")).append(RoadMessages.suggest("[Merge into…]", "/knk road survey merge <profile>"))
            .append(Component.text("  ")).append(RoadMessages.command("[Discard]", "/knk road survey discard"));
        player.sendMessage(actions);
        List<String> others = cache.profiles().stream().filter(p -> target == null || p.id() != target.id()).map(RoadProfile::name).limit(6).toList();
        if (!others.isEmpty()) {
            player.sendMessage(Component.text("Other profiles: " + String.join(", ", others), RoadMessages.INFO));
        }
    }

    // ===== save / merge / discard =====

    /** Save into the review's target, or as a new profile named {@code newName} (or the name given at start). */
    public void save(Player player, String newName) {
        Review review = reviews.get(player.getUniqueId());
        if (review == null) {
            player.sendMessage(RoadMessages.warn("Nothing to save: stop a survey walk first."));
            return;
        }
        if (review.target() != null) {
            RoadProfileUpsert upsert = RoadProfileUpsert.of(review.target(), review.learned(), review.merged().toJson());
            commandApi.updateProfile(review.target().id(), upsert).whenComplete((saved, ex) -> mainThread.execute(() -> {
                if (RoadAdminCommand.failed(player, "update profile \"" + review.target().name() + "\"", ex)) {
                    return;
                }
                storeSurvey(player, review, saved);
            }));
            return;
        }
        String name = newName != null && !newName.isBlank() ? newName.trim() : review.session().newProfileName().orElse(null);
        if (name == null) {
            player.sendMessage(RoadMessages.usage("/knk road survey save <name> (this walk learns a new profile - give it a name)"));
            return;
        }
        RoadProfileUpsert upsert = RoadProfileUpsert.of(name, RoadClass.ROAD, 1.0, List.of(), review.learned(), review.merged().toJson());
        commandApi.createProfile(upsert).whenComplete((created, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(player, "create profile \"" + name + "\"", ex)) {
                return;
            }
            player.sendMessage(RoadMessages.info("Profile #" + created.id() + " \"" + name + "\" created as class Road ×1.0 - change class, cost and scope in the web app."));
            storeSurvey(player, review, created);
        }));
    }

    /** Re-targets the review at another profile (its stored stats + this walk) and saves. */
    public void mergeInto(Player player, String profileName) {
        Review review = reviews.get(player.getUniqueId());
        if (review == null) {
            player.sendMessage(RoadMessages.warn("Nothing to merge: stop a survey walk first."));
            return;
        }
        queryApi.profiles().whenComplete((profiles, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(player, "load the profiles", ex)) {
                return;
            }
            Optional<RoadProfile> target = RoadAdminCommand.findProfile(profiles, profileName);
            if (target.isEmpty()) {
                player.sendMessage(RoadMessages.bad("No profile '" + profileName + "' (see /knk road profile list)."));
                return;
            }
            review(player, review.session(), review.walk(), target.get());
            save(player, null);
        }));
    }

    public void discard(Player player) {
        player.sendMessage(reviews.remove(player.getUniqueId()) != null ? RoadMessages.info("Walk discarded; nothing saved.")
            : RoadMessages.warn("Nothing to discard."));
    }

    /** After the profile PUT/POST: the survey row (+ breadcrumb), its seeds, the coverage check. */
    private void storeSurvey(Player player, Review review, RoadProfile profile) {
        reviews.remove(player.getUniqueId());
        RoadSurveySession session = review.session();
        Integer actingUserId = cachedUserId.apply(player);
        if (actingUserId == null) {
            player.sendMessage(RoadMessages.warn("Your knk user id isn't cached yet; the survey is sent without it and the API may refuse it (rejoin and try again)."));
        }
        RoadSurveyCreate create = new RoadSurveyCreate(session.world(), OptionalInt.of(profile.id()), session.startedAt(),
            OffsetDateTime.now(ZoneOffset.UTC), review.walk().samples(), session.breadcrumb(), review.walk().toJson());
        commandApi.createSurvey(create, actingUserId).whenComplete((survey, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                player.sendMessage(RoadMessages.bad("Profile \"" + profile.name() + "\" saved, but the survey was not stored: " + RoadMessages.describeError(ex)));
                return;
            }
            player.sendMessage(RoadMessages.good("Saved: profile \"" + profile.name() + "\" now has " + profile.sampleCount() + " samples; survey #" + survey.id() + " stored with "
                + session.breadcrumb().size() + " breadcrumb points."));
            createSeeds(player, session, survey);
            coverage(player, session);
            cache.refreshMeta(session.world());
        }));
    }

    /** One Survey seed every {@code breadcrumb-seed-spacing} blocks of walked road. */
    private void createSeeds(Player player, RoadSurveySession session, RoadSurvey survey) {
        List<RoadBreadcrumbPoint> seeds = seedPoints(session.breadcrumb(), config.survey().breadcrumbSeedSpacing());
        if (seeds.isEmpty()) {
            return;
        }
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (RoadBreadcrumbPoint p : seeds) {
            calls.add(commandApi.createSeed(RoadSeedCreate.survey(session.world(), p.x(), p.y(), p.z(), survey.id())));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).whenComplete((v, ex) -> mainThread.execute(() -> {
            long ok = calls.stream().filter(f -> !f.isCompletedExceptionally()).count();
            player.sendMessage(ex == null ? RoadMessages.info(ok + " survey seed(s) added; the next build of these tiles starts from your walk.")
                : RoadMessages.warn(ok + "/" + calls.size() + " survey seeds added: " + RoadMessages.describeError(ex)));
        }));
    }

    /** Every {@code spacing} blocks along the breadcrumb (walk distance), the first point included. */
    static List<RoadBreadcrumbPoint> seedPoints(List<RoadBreadcrumbPoint> breadcrumb, int spacing) {
        List<RoadBreadcrumbPoint> seeds = new ArrayList<>();
        double since = spacing;
        RoadBreadcrumbPoint last = null;
        for (RoadBreadcrumbPoint p : breadcrumb) {
            if (!p.onRoad()) {
                continue;
            }
            if (last != null) {
                since += Math.sqrt(Math.pow(p.x() - last.x(), 2) + Math.pow(p.y() - last.y(), 2) + Math.pow(p.z() - last.z(), 2));
            }
            if (since >= spacing) {
                seeds.add(p);
                since = 0;
            }
            last = p;
        }
        return seeds;
    }

    /** Breadcrumb points more than 2 blocks from any edge of the current network (empty network = nothing to say). */
    private void coverage(Player player, RoadSurveySession session) {
        var snapshot = cache.snapshot(session.world());
        if (snapshot.isEmpty()) {
            player.sendMessage(RoadMessages.info("No roads built here yet - /knk road build here (or radius <r>) uses this walk as seeds."));
            return;
        }
        List<CoverageCheck.Miss> misses = CoverageCheck.misses(session.samples(), snapshot);
        if (misses.isEmpty()) {
            player.sendMessage(RoadMessages.good("Every walked point lies on a built road."));
            return;
        }
        Component line = Component.text("Walked points with no built road within 2 blocks (" + misses.size() + "): ", RoadMessages.WARN);
        int n = 0;
        for (CoverageCheck.Miss miss : misses) {
            if (n++ >= 8) {
                line = line.append(Component.text("…", RoadMessages.INFO));
                break;
            }
            line = line.append(RoadMessages.teleport(miss.x(), miss.y(), miss.z())).append(Component.text(" " + miss.floor().toLowerCase(Locale.ROOT) + " ", RoadMessages.INFO));
        }
        player.sendMessage(line);
        player.sendMessage(Component.text("Rebuild the tile after saving, add the missing material to a profile, or record the stretch: /knk road record start", RoadMessages.INFO));
    }

    // ===== record =====

    public void startRecord(Player player) {
        UUID id = player.getUniqueId();
        if (recordings.containsKey(id)) {
            player.sendMessage(RoadMessages.warn("Already recording: /knk road record stop [street] | cancel."));
            return;
        }
        recordings.put(id, new Recording(player.getWorld().getName(), new CrossSectionSampler(rules,
            player.getWorld().getMinHeight(), player.getWorld().getMaxHeight(), config.survey().crossSectionHalfWidth())));
        player.sendMessage(RoadMessages.good("Recording a stretch from here - walk (or climb) to its end, then /knk road record stop [street]. "
            + "The recorded edge survives rebuilds and may be vertical (a ladder, a water lift)."));
    }

    public void cancelRecord(Player player) {
        player.sendMessage(recordings.remove(player.getUniqueId()) != null ? RoadMessages.info("Recording cancelled.") : RoadMessages.warn("You are not recording."));
    }

    /** Uploads the walked stretch as a Recorded edge (DESIGN §5.10), tagged with the WorldGuard regions along it. */
    public void stopRecord(Player player, OptionalInt streetId) {
        Recording recording = recordings.remove(player.getUniqueId());
        if (recording == null) {
            player.sendMessage(RoadMessages.warn("You are not recording (/knk road record start)."));
            return;
        }
        List<int[]> geometry = Rdp.simplify(recording.points, RECORD_EPSILON);
        if (geometry.size() < 2 || Rdp.length(geometry) < 1) {
            player.sendMessage(RoadMessages.warn("Too short to record - walk at least a couple of blocks."));
            return;
        }
        List<String> regions = new ArrayList<>();
        World world = Bukkit.getWorld(recording.world);
        if (regionIds != null && world != null) {
            java.util.TreeSet<String> ids = new java.util.TreeSet<>();
            for (int[] p : geometry) {
                ids.addAll(regionIds.at(world, p[0], p[1] + 1, p[2]));
            }
            regions.addAll(ids);
        }
        RoadEdgeRecord record = new RoadEdgeRecord(recording.world, geometry, OptionalDouble.of(Rdp.length(recording.points)),
            RECORD_DEFAULT_WIDTH, OptionalInt.empty(), streetId, List.of(), List.of(), regions);
        commandApi.recordEdge(record).whenComplete((edge, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(player, "record the stretch", ex)) {
                return;
            }
            player.sendMessage(RoadMessages.good("Recorded edge #" + edge.id() + " (" + RoadMessages.distance(edge.length()) + ", " + geometry.size()
                + " points" + (streetId.isPresent() ? ", street #" + streetId.getAsInt() : "") + "). It joins the network at the next tile refresh."));
            cache.refreshTiles(recording.world);
        }));
    }

    private static double distance(int[] a, int[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    /** Package-private for tests. */
    Map<UUID, RoadSurveySession> sessions() {
        return sessions;
    }
}
