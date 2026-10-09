package net.knightsandkings.knk.paper.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;
import net.knightsandkings.knk.core.domain.settings.KnkWorldSettings;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.towns.TownSummary;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.ports.api.GameSettingsCommandApi;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.settings.Announcements;
import net.knightsandkings.knk.core.settings.GroupOverrides;
import net.knightsandkings.knk.core.settings.RespawnPlanner;
import net.knightsandkings.knk.core.settings.WeatherRules;
import net.knightsandkings.knk.core.teleport.SpawnPoint;
import net.knightsandkings.knk.paper.teleport.SpawnDestinationResolver;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import net.kyori.adventure.text.Component;

/**
 * Applies the web app's global Game Settings in the running server (docs/specs/game-settings/DESIGN.md,
 * KNG-52): join/leave announcements, the join spawn and per-world game mode, the respawn policy, the
 * per-world time lock, weather rule and spawn point, per-group overrides of the join message, spawn and
 * respawn, and the server-list MOTD; reports the loaded worlds back to the API.
 * <p>
 * Threads: the settings, resolved references and towns are read on the main thread (join, respawn,
 * weather events) from fields refreshed in the background, so no event ever waits on the API. World
 * changes are made on the main thread only.
 * <p>
 * Until the API has answered once the settings come from {@link GameSettingsStore}'s cache file;
 * with neither, players get the API's default texts and the server's own spawn/respawn/time/weather.
 */
public class GameSettingsManager {

    private static final Logger LOGGER = Logger.getLogger(GameSettingsManager.class.getName());
    private static final String PREFIX = "[KnK GameSettings] ";
    /** References (a town's spawn can move) and the town list are re-read this often. */
    static final long LOOKUP_REFRESH_MILLIS = 5 * 60_000L;
    /** The loaded worlds are re-reported at least this often even when nothing changed. */
    static final long REPORT_HEARTBEAT_MILLIS = 10 * 60_000L;
    private static final int TOWN_PAGE_SIZE = 100;
    private static final int TOWN_MAX_PAGES = 50;

    private final Plugin plugin;
    private final GameSettingsQueryApi query;
    private final GameSettingsCommandApi command;
    private final Supplier<SpawnDestinationResolver> spawns;
    private final TownsQueryApi towns;
    private final GameSettingsConfig config;
    private final GameSettingsStore store;
    private final NamespacedKey timeLockKey;

    private volatile KnkGameSettings current;
    private volatile SpawnPoint joinSpawnPoint;
    private final Map<KnkSpawnReference, KnkLocation> resolved = new ConcurrentHashMap<>();
    private volatile long referencesResolvedAt;
    private volatile List<RespawnPlanner.TownSpot> townSpots = List.of();
    private volatile long townsLoadedAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean loadingTowns = new AtomicBoolean();
    private final AtomicBoolean reporting = new AtomicBoolean();
    private volatile List<KnkWorldRuntime> lastReported = List.of();
    private volatile long lastReportAt;
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private BukkitTask refreshTask;
    private BukkitTask reportTask;

    /**
     * @param command may be null: then the loaded worlds are not reported
     * @param spawns  the {@code /spawn} resolver (may supply null): resolves the join spawn and every other reference
     * @param towns   may be null: then {@link KnkRespawnPolicy.Mode#NEAREST_TOWN} finds no town
     */
    public GameSettingsManager(Plugin plugin, GameSettingsQueryApi query, GameSettingsCommandApi command,
                               Supplier<SpawnDestinationResolver> spawns, TownsQueryApi towns,
                               GameSettingsConfig config, GameSettingsStore store) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.command = command;
        this.spawns = spawns != null ? spawns : () -> null;
        this.towns = towns;
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.timeLockKey = new NamespacedKey(plugin, "game_settings_time_lock");
    }

    // ===== lifecycle =====

    /** Main thread, from onEnable: cached settings now, then the API in the background. */
    public void start() {
        store.load().ifPresent(cached -> {
            current = cached;
            LOGGER.info(PREFIX + "Using the cached settings (last edit " + cached.updatedAt() + ") until the API answers");
            applyAll();
        });
        long refreshTicks = 20L * config.refreshIntervalSeconds();
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 0L, refreshTicks);
        if (command != null) {
            long reportTicks = 20L * config.runtimeSyncIntervalSeconds();
            reportTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reportWorlds, 40L, reportTicks);
        }
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (reportTask != null) {
            reportTask.cancel();
        }
    }

    /** {@code /knk cache refresh}: read the settings, references and towns again now. */
    public void refreshNow() {
        referencesResolvedAt = 0;
        townsLoadedAt = 0;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refresh);
    }

    /** The settings in force; null until the API or the cache file supplied them. */
    public KnkGameSettings current() {
        return current;
    }

    // ===== reading =====

    private void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<KnkGameSettings> read;
        try {
            read = query.get();
        } catch (RuntimeException ex) {
            read = CompletableFuture.failedFuture(ex);
        }
        read.whenComplete((settings, ex) -> {
            try {
                if (ex != null || settings == null) {
                    failed("read", "Could not read the game settings; keeping the "
                        + (current != null ? "last known ones" : "server defaults"), ex);
                    return;
                }
                recovered("read", "Game settings read again");
                accept(settings);
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** New settings from the API (a read or a runtime report). Any thread. */
    void accept(KnkGameSettings settings) {
        boolean changed = !settings.equals(current);
        current = settings;
        long now = System.currentTimeMillis();
        SpawnDestinationResolver resolver = spawns.get();
        if (changed) {
            store.save(settings);
            LOGGER.info(PREFIX + "Applied game settings (last edit " + settings.updatedAt() + ", "
                + settings.worldSettings().size() + " world(s))");
            if (resolver != null) {
                resolver.invalidate();
            }
        }
        if (resolver != null) {
            resolver.resolve().thenAccept(point -> joinSpawnPoint = point);
        }
        if (changed || now - referencesResolvedAt > LOOKUP_REFRESH_MILLIS) {
            resolveReferences(settings, resolver);
        } else {
            onMainThread(this::applyAll);
        }
        if (usesNearestTown(settings) && now - townsLoadedAt > LOOKUP_REFRESH_MILLIS) {
            loadTowns();
        }
    }

    /** Every reference besides the join spawn: world spawns and configured respawn spots. */
    private void resolveReferences(KnkGameSettings settings, SpawnDestinationResolver resolver) {
        Set<KnkSpawnReference> references = new HashSet<>();
        for (KnkWorldSettings world : settings.worldSettings()) {
            if (world.worldSpawnReference() != null) {
                references.add(world.worldSpawnReference());
            }
            addRespawnReference(references, world.respawnPolicy());
        }
        addRespawnReference(references, settings.defaultRespawnPolicy());
        for (KnkGroupOverride group : settings.groupOverrides()) {
            if (group.joinSpawnReference() != null) {
                references.add(group.joinSpawnReference());
            }
            addRespawnReference(references, group.respawnPolicy());
        }

        List<CompletableFuture<Void>> lookups = new ArrayList<>();
        for (KnkSpawnReference reference : references) {
            CompletableFuture<SpawnPoint> point = resolver != null
                ? resolver.resolveReference(reference)
                : CompletableFuture.completedFuture(RespawnPlanner.isUsable(reference.snapshot())
                    ? new SpawnPoint(reference.snapshot(), reference.label(), SpawnPoint.Source.SNAPSHOT)
                    : SpawnPoint.worldSpawn());
            lookups.add(point.thenAccept(p -> {
                if (p != null && !p.isWorldSpawn()) {
                    resolved.put(reference, p.location());
                } else {
                    resolved.remove(reference);
                }
            }));
        }
        resolved.keySet().retainAll(references);
        CompletableFuture.allOf(lookups.toArray(new CompletableFuture[0])).whenComplete((ignored, ex) -> {
            referencesResolvedAt = System.currentTimeMillis();
            onMainThread(this::applyAll);
        });
    }

    private static void addRespawnReference(Set<KnkSpawnReference> references, KnkRespawnPolicy policy) {
        if (policy != null && policy.mode() == KnkRespawnPolicy.Mode.CONFIGURED_REFERENCE && policy.reference() != null) {
            references.add(policy.reference());
        }
    }

    private static boolean usesNearestTown(KnkGameSettings settings) {
        return settings.defaultRespawnPolicy().mode() == KnkRespawnPolicy.Mode.NEAREST_TOWN
            || settings.worldSettings().stream().anyMatch(w -> w.respawnPolicy().mode() == KnkRespawnPolicy.Mode.NEAREST_TOWN)
            || settings.groupOverrides().stream().anyMatch(g -> g.respawnPolicy() != null
                && g.respawnPolicy().mode() == KnkRespawnPolicy.Mode.NEAREST_TOWN);
    }

    /** Every town with a spawn point, for NEAREST_TOWN. Only while some world uses that policy. */
    private void loadTowns() {
        if (towns == null || !loadingTowns.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<TownSummary> summaries = new ArrayList<>();
                for (int page = 1; page <= TOWN_MAX_PAGES; page++) {
                    Page<TownSummary> result = towns.search(new PagedQuery(page, TOWN_PAGE_SIZE, null, null, false, Map.of())).join();
                    if (result == null || result.items() == null || result.items().isEmpty()) {
                        break;
                    }
                    summaries.addAll(result.items());
                    if (summaries.size() >= result.totalCount()) {
                        break;
                    }
                }
                List<RespawnPlanner.TownSpot> spots = new ArrayList<>();
                int withoutSpawn = 0;
                for (TownSummary summary : summaries) {
                    if (summary == null || summary.id() == null) {
                        continue;
                    }
                    TownDetail town = towns.getById(summary.id()).join();
                    TownDetail.Location l = town != null ? town.location() : null;
                    KnkLocation spot = l == null ? null : new KnkLocation(l.id(), l.name(), l.x(), l.y(), l.z(), l.yaw(), l.pitch(), l.world());
                    if (!RespawnPlanner.isUsable(spot)) {
                        withoutSpawn++;
                        continue;
                    }
                    spots.add(new RespawnPlanner.TownSpot(summary.id(), town.name(), town.wgRegionId(), spot));
                }
                townSpots = List.copyOf(spots);
                townsLoadedAt = System.currentTimeMillis();
                recovered("towns", "Towns read again");
                LOGGER.info(PREFIX + "Nearest-town respawn: " + spots.size() + " town(s) with a spawn point"
                    + (withoutSpawn > 0 ? ", " + withoutSpawn + " without" : ""));
            } catch (RuntimeException ex) {
                failed("towns", "Could not read the towns for nearest-town respawn; keeping the last list", ex);
            } finally {
                loadingTowns.set(false);
            }
        });
    }

    // ===== announcements, join, game mode =====

    /**
     * The join broadcast, or empty for none: the first of the player's groups with its own message
     * (DESIGN §3.8), else the global one. {@code {group}} is the group whose message it is, else the
     * player's first group.
     *
     * @param groups the player's groups in precedence order (UserSummary.permissionGroups); may be empty
     */
    public Optional<Component> joinMessage(String playerName, String title, List<PermissionGroupRef> groups) {
        KnkGameSettings settings = current;
        return announcement(GroupOverrides.joinAnnouncement(settings, groups), settings != null ? settings.joinAnnouncement() : null,
            Announcements.DEFAULT_JOIN, playerName, title, groups);
    }

    /**
     * The quit broadcast, or empty for none: the player's first group with its own leave message
     * (round 3), else the global one. {@code {group}} is that group, else the player's first group.
     */
    public Optional<Component> leaveMessage(String playerName, String title, List<PermissionGroupRef> groups) {
        KnkGameSettings settings = current;
        return announcement(GroupOverrides.leaveAnnouncement(settings, groups), settings != null ? settings.leaveAnnouncement() : null,
            Announcements.DEFAULT_LEAVE, playerName, title, groups);
    }

    private static Optional<Component> announcement(Optional<GroupOverrides.Pick<String>> group, String global, String fallback,
                                                    String playerName, String title, List<PermissionGroupRef> groups) {
        String template = group.map(GroupOverrides.Pick::value).orElse(global);
        String groupName = group.map(pick -> pick.group().name()).orElse(GroupOverrides.primaryGroupName(groups));
        return Announcements.render(template, fallback, playerName, groupName, title)
            .map(DisplayTextFormatter::toComponent);
    }

    /** The server-list MOTD, or empty to leave the server's own (DESIGN §3.9). Any thread. */
    public Optional<Component> motd(int online, int max) {
        KnkGameSettings settings = current;
        return Announcements.renderMotd(settings != null ? settings.motd() : null, online, max)
            .map(DisplayTextFormatter::toComponent);
    }

    /**
     * Where this player joins (and {@code /spawn}): their first group with a spawn override (DESIGN
     * §3.8), else the server spawn. Main thread; never waits on the API.
     */
    public Location joinSpawn(List<PermissionGroupRef> groups) {
        return groupSpawn(groups).map(GroupSpot::location).orElseGet(this::joinSpawn);
    }

    /**
     * Whether the player's groups have them join where they logged out (round 4): no join teleport, like
     * owners. {@link #joinSpawn(List)} then still answers the server spawn, for {@code /spawn} and a synced respawn.
     */
    public boolean joinsAtLastLocation(List<PermissionGroupRef> groups) {
        return GroupOverrides.joinsAtLastLocation(current, groups);
    }

    /**
     * The player's group spawn override as a {@code /spawn} destination, or null when their groups have
     * none (then {@code /spawn} uses the server spawn). Any thread.
     */
    public SpawnPoint groupSpawnPoint(List<PermissionGroupRef> groups) {
        KnkGameSettings settings = current;
        return GroupOverrides.joinSpawn(settings, groups)
            .map(pick -> {
                KnkLocation spot = resolved.getOrDefault(pick.value(), pick.value().snapshot());
                return RespawnPlanner.isUsable(spot) && Bukkit.getWorld(spot.world()) != null
                    ? new SpawnPoint(spot, pick.value().label(), SpawnPoint.Source.REFERENCE)
                    : null;
            })
            .orElse(null);
    }

    private record GroupSpot(Location location, String label) {
    }

    private Optional<GroupSpot> groupSpawn(List<PermissionGroupRef> groups) {
        KnkGameSettings settings = current;
        Optional<GroupOverrides.Pick<KnkSpawnReference>> pick = GroupOverrides.joinSpawn(settings, groups);
        if (pick.isEmpty()) {
            return Optional.empty();
        }
        KnkSpawnReference reference = pick.get().value();
        Optional<Location> spot = toBukkit(resolved.getOrDefault(reference, reference.snapshot()));
        if (spot.isEmpty()) {
            warnOnce("group-spawn:" + pick.get().group().id(), "Spawn of group " + pick.get().group().name() + " ("
                + reference.label() + ") has no usable location in a loaded world; using the server spawn");
        }
        return spot.map(location -> new GroupSpot(location, reference.label()));
    }

    /**
     * Where a regular player is put on join without a group override: the server spawn {@code /spawn}
     * uses, as last resolved, else the main world's spawn. Main thread; never waits on the API.
     */
    public Location joinSpawn() {
        SpawnDestinationResolver resolver = spawns.get();
        SpawnPoint point = joinSpawnPoint;
        if (resolver != null && point != null) {
            Location location = resolver.toLocation(point);
            if (location != null) {
                return location;
            }
        }
        KnkGameSettings settings = current;
        if (settings != null && point == null) {
            Optional<Location> snapshot = settings.customJoinSpawn().map(KnkSpawnReference::snapshot).flatMap(this::toBukkit);
            if (snapshot.isPresent()) {
                return snapshot.get();
            }
        }
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return main != null ? main.getSpawnLocation() : null;
    }

    /** The world's default game mode for regular players; SURVIVAL when unset or unknown. */
    public GameMode gameModeFor(World world) {
        KnkGameSettings settings = current;
        if (settings == null || world == null) {
            return GameMode.SURVIVAL;
        }
        String name = settings.world(world.getName()).map(KnkWorldSettings::defaultGameMode).orElse(null);
        if (name == null || name.isBlank()) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnOnce("gamemode:" + name, "Unknown default game mode '" + name + "' for world " + world.getName() + "; using SURVIVAL");
            return GameMode.SURVIVAL;
        }
    }

    // ===== respawn =====

    /**
     * Where a regular player who died respawns (DESIGN §3.3); empty leaves it to the server (bed,
     * anchor, world spawn). The player's first group with a respawn override wins over the death world's
     * policy (§3.8). Main thread, inside {@code PlayerRespawnEvent}; reads only what was resolved
     * beforehand.
     *
     * @param groups the player's groups in precedence order; may be empty
     */
    public Optional<Location> respawnLocation(Player player, List<PermissionGroupRef> groups) {
        KnkGameSettings settings = current;
        Location death = player.getLastDeathLocation() != null ? player.getLastDeathLocation() : player.getLocation();
        if (settings == null || death == null || death.getWorld() == null) {
            return Optional.empty();
        }
        World deathWorld = death.getWorld();
        KnkRespawnPolicy policy = GroupOverrides.respawnPolicy(settings, groups).map(GroupOverrides.Pick::value)
            .orElseGet(() -> settings.respawnPolicyFor(deathWorld.getName()));
        KnkLocation configured = null;
        if (policy.reference() != null) {
            configured = resolved.get(policy.reference());
            if (configured == null && RespawnPlanner.isUsable(policy.reference().snapshot())) {
                configured = policy.reference().snapshot();
            }
        }
        KnkLocation deathPoint = new KnkLocation(null, null, death.getX(), death.getY(), death.getZ(),
            death.getYaw(), death.getPitch(), deathWorld.getName());
        RespawnPlanner.Plan plan = RespawnPlanner.plan(policy, deathPoint, configured, townSpots, this::insideTownRegion);
        return switch (plan.kind()) {
            case SERVER_DEFAULT -> Optional.empty();
            case WORLD_SPAWN -> Optional.ofNullable(worldSpawnFor(deathWorld));
            case JOIN_SPAWN -> Optional.ofNullable(joinSpawn(groups));
            case LOCATION -> {
                Optional<Location> spot = toBukkit(plan.location());
                if (spot.isEmpty()) {
                    warnOnce("respawn-world:" + plan.location().world(), "Respawn spot (" + plan.reason() + ") is in world '"
                        + plan.location().world() + "', which isn't loaded");
                    yield policy.useWorldSpawnFallback() ? Optional.ofNullable(worldSpawnFor(deathWorld)) : Optional.empty();
                }
                yield spot;
            }
        };
    }

    /**
     * The "world spawn" a death in {@code deathWorld} respawns at: that world's spawn point, but the main
     * world's for a nether or End death - as vanilla, which never respawns anyone in those worlds.
     */
    private static Location worldSpawnFor(World deathWorld) {
        if (deathWorld.getEnvironment() == World.Environment.NORMAL || Bukkit.getWorlds().isEmpty()) {
            return deathWorld.getSpawnLocation();
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private boolean insideTownRegion(RespawnPlanner.TownSpot town, KnkLocation point) {
        if (town.wgRegionId() == null || town.wgRegionId().isBlank()) {
            return false;
        }
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            return false;
        }
        try {
            RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            ProtectedRegion region = regions != null ? regions.getRegion(town.wgRegionId()) : null;
            return region != null && region.contains(BlockVector3.at(point.x(), point.y(), point.z()));
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ===== worlds: time, weather, spawn =====

    /** Main thread. Idempotent: only writes what differs from the settings. */
    public void applyAll() {
        if (current == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            apply(world);
        }
    }

    /** Main thread: one world (also on WorldLoadEvent). */
    public void apply(World world) {
        KnkGameSettings settings = current;
        if (settings == null || world == null) {
            return;
        }
        Optional<KnkWorldSettings> worldSettings = settings.world(world.getName());
        try {
            applyTime(world, worldSettings.orElse(null));
            worldSettings.ifPresent(ws -> {
                applyWeather(world, ws.weather());
                applySpawn(world, ws);
            });
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, PREFIX + "Could not apply the settings of world " + world.getName(), ex);
        }
    }

    /**
     * The time lock stops the day/night cycle at {@code lockedTime}. The world remembers that this
     * plugin locked it, so unlocking (even after a restart) turns the cycle back on, and a world whose
     * cycle an admin stopped by hand is never touched.
     */
    private void applyTime(World world, KnkWorldSettings settings) {
        var data = world.getPersistentDataContainer();
        boolean lockedByUs = data.has(timeLockKey, PersistentDataType.BYTE);
        if (settings != null && settings.lockTime()) {
            if (!Boolean.FALSE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            }
            if (world.getTime() != settings.lockedTime()) {
                world.setTime(settings.lockedTime());
            }
            if (!lockedByUs) {
                data.set(timeLockKey, PersistentDataType.BYTE, (byte) 1);
                LOGGER.info(PREFIX + "Locked the time of " + world.getName() + " at " + settings.lockedTime());
            }
        } else if (lockedByUs) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            data.remove(timeLockKey);
            LOGGER.info(PREFIX + "Unlocked the time of " + world.getName());
        }
    }

    private void applyWeather(World world, KnkWeatherSettings weather) {
        WeatherRules.enforce(weather, KnkWeather.of(world.hasStorm(), world.isThundering()))
            .ifPresent(target -> setWeather(world, target));
    }

    private void applySpawn(World world, KnkWorldSettings settings) {
        KnkSpawnReference reference = settings.worldSpawnReference();
        if (reference == null) {
            return;
        }
        KnkLocation spot = resolved.getOrDefault(reference, reference.snapshot());
        if (!RespawnPlanner.isUsable(spot)) {
            return;
        }
        if (!spot.world().equalsIgnoreCase(world.getName())) {
            warnOnce("spawn-world:" + world.getName() + ":" + spot.world(), "World spawn of " + world.getName() + " ("
                + reference.label() + ") is in another world (" + spot.world() + "); not applied");
            return;
        }
        Location target = new Location(world, spot.x(), spot.y(), spot.z(),
            spot.yaw() != null ? spot.yaw() : 0f, spot.pitch() != null ? spot.pitch() : 0f);
        Location now = world.getSpawnLocation();
        if (now.distanceSquared(target) > 0.25 || Math.abs(now.getYaw() - target.getYaw()) > 0.5f) {
            world.setSpawnLocation(target);
            LOGGER.info(PREFIX + "Moved the spawn of " + world.getName() + " to " + reference.label());
        }
    }

    /** How often the settings are read and re-applied (seconds). */
    public int refreshIntervalSeconds() {
        return config.refreshIntervalSeconds();
    }

    /** The world's weather rule; null when it has none (or no settings are known). */
    public KnkWeatherSettings weatherFor(World world) {
        KnkGameSettings settings = current;
        return settings == null || world == null ? null : settings.world(world.getName()).map(KnkWorldSettings::weather).orElse(null);
    }

    /** Next tick: a weighted pick for a world whose natural weather change was cancelled. */
    public void pickWeightedWeather(World world) {
        onMainThread(() -> {
            KnkWeatherSettings weather = weatherFor(world);
            if (weather != null && weather.mode() == KnkWeatherSettings.Mode.WEIGHTED) {
                setWeather(world, WeatherRules.pickWeighted(weather, bound -> ThreadLocalRandom.current().nextInt(bound)));
            }
        });
    }

    /** Sets the weather with a vanilla-like duration. These changes reach the listener as PLUGIN, so they pass. */
    private static void setWeather(World world, KnkWeather weather) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        switch (weather) {
            case CLEAR -> {
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(random.nextInt(12_000, 180_000));
            }
            case RAIN -> {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(random.nextInt(12_000, 24_000));
            }
            case THUNDER -> {
                world.setStorm(true);
                world.setThundering(true);
                int ticks = random.nextInt(3_600, 15_600);
                world.setWeatherDuration(ticks);
                world.setThunderDuration(ticks);
            }
        }
    }

    // ===== runtime worlds =====

    /** Main thread timer (and on world load/unload): report the loaded worlds when they changed. */
    public void reportWorlds() {
        if (command == null || reporting.get()) {
            return;
        }
        List<KnkWorldRuntime> worlds = collectWorlds();
        long now = System.currentTimeMillis();
        if (worlds.equals(lastReported) && now - lastReportAt < REPORT_HEARTBEAT_MILLIS) {
            return;
        }
        reporting.set(true);
        CompletableFuture<KnkGameSettings> report;
        try {
            report = command.reportRuntimeWorlds(worlds);
        } catch (RuntimeException ex) {
            report = CompletableFuture.failedFuture(ex);
        }
        report.whenComplete((settings, ex) -> {
            try {
                if (ex != null) {
                    failed("report", "Could not report the loaded worlds (does the API accept the plugin key?)", ex);
                    return;
                }
                recovered("report", "Loaded worlds reported again");
                lastReported = worlds;
                lastReportAt = System.currentTimeMillis();
                if (settings != null) {
                    accept(settings);
                }
            } finally {
                reporting.set(false);
            }
        });
    }

    private static List<KnkWorldRuntime> collectWorlds() {
        List<World> worlds = Bukkit.getWorlds();
        List<KnkWorldRuntime> result = new ArrayList<>(worlds.size());
        for (int i = 0; i < worlds.size(); i++) {
            World world = worlds.get(i);
            result.add(new KnkWorldRuntime(world.getName(), world.getWorldFolder().getName(), world.getEnvironment().name(),
                true, world.getPlayers().size(), i == 0));
        }
        return List.copyOf(result);
    }

    // ===== helpers =====

    /** From a background callback; dropped once the plugin is disabled (shutdown). */
    private void onMainThread(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private Optional<Location> toBukkit(KnkLocation location) {
        if (!RespawnPlanner.isUsable(location)) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(location.world());
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, location.x(), location.y(), location.z(),
            location.yaw() != null ? location.yaw() : 0f, location.pitch() != null ? location.pitch() : 0f));
    }

    /** First failure of a kind is a warning, repeats are FINE until it works again. */
    private void failed(String kind, String message, Throwable ex) {
        if (failing.add(kind)) {
            LOGGER.log(Level.WARNING, PREFIX + message + (ex != null ? ": " + rootMessage(ex) : ""));
        } else {
            LOGGER.log(Level.FINE, PREFIX + message, ex);
        }
    }

    private void recovered(String kind, String message) {
        if (failing.remove(kind)) {
            LOGGER.info(PREFIX + message);
        }
    }

    private void warnOnce(String key, String message) {
        if (warnedOnce.add(key)) {
            LOGGER.warning(PREFIX + message);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
