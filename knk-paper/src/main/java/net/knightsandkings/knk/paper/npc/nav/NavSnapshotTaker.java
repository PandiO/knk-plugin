package net.knightsandkings.knk.paper.npc.nav;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshotZip;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotParts;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.regions.access.DomainAccessFlags;

/**
 * {@code /knk npc nav snapshot <route>} (link B1, PandiO/knk-workspace#670; chain report decisions 33 and 35.2): one zip
 * in {@code plugins/KnightsAndKings/npc-snapshots/} with a saved route's road line as NPC navigation plans it, its
 * stretches, the road graph, the domains and the settings around it, and the region files its box touches, copied
 * after a save. Reads the plugin's caches and the world on the main thread; copies and zips on another thread. Changes
<<<<<<< HEAD
 * nothing on the server apart from the world save. Works without Citizens (only road navigation must run).
=======
 * nothing on the server apart from the world save. Needs {@code npc.navigation.enabled} and road navigation, not
 * Citizens.
>>>>>>> claude/npc-movement-b1
 */
final class NavSnapshotTaker {

    /** Where the zips go, under the plugin's folder. */
    static final String FOLDER = "npc-snapshots";
    /** Ticks between the world save and the copy, so the region files are written. */
    static final long COPY_DELAY_TICKS = 40;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** The plugin's caches the snapshot reads (any may be missing; the snapshot then says so in its notes). */
    record Sources(Supplier<NavigationService> navigation, Supplier<RegionDomainResolver> domains,
                   Supplier<GateManager> gates) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final NpcNavigationConfig config;
    private final Sources sources;
    private final Function<NavPoint, List<String>> regionsOuterToInner;
    private final Supplier<BukkitNavWorld> rulesWorld;
    private boolean running;

    /**
     * @param regionsOuterToInner the WorldGuard regions at a point, outermost first (the settings' region layer)
     * @param rulesWorld          the world the NPC rule checks read regions from (the start's regions for the plan)
     */
    NavSnapshotTaker(JavaPlugin plugin, NpcNavigationConfig config, Sources sources,
                     Function<NavPoint, List<String>> regionsOuterToInner, Supplier<BukkitNavWorld> rulesWorld) {
        this.rulesWorld = rulesWorld;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.sources = sources;
        this.regionsOuterToInner = regionsOuterToInner;
    }

    /** Takes a snapshot of the saved route {@code name}; answers {@code sender} along the way. Main thread. */
    void take(CommandSender sender, String name) {
        if (running) {
            sender.sendMessage("A snapshot is being written; try again when it is done.");
            return;
        }
        NavigationService nav = sources.navigation() == null ? null : sources.navigation().get();
        if (nav == null) {
            sender.sendMessage("Road navigation is not running (navigation.enabled): the snapshot needs the route's line.");
            return;
        }
        NpcRouteStore.Route route = new NpcRouteStore(plugin.getDataFolder(), logger).named(NpcRouteStore.key(name))
            .orElse(null);
        if (route == null || !route.complete()) {
            sender.sendMessage("No usable route " + name + ". /knk npc nav route list");
            return;
        }
        World world = Bukkit.getWorld(route.from().world());
        if (world == null || !route.from().world().equals(route.to().world())) {
            sender.sendMessage("Route " + name + " is not in one loaded world.");
            return;
        }
        BukkitNavWorld rules = rulesWorld.get();
        NavPoint fromFloor = route.from();
        Set<String> startRegions = rules.regionsAt(world.getName(), fromFloor.blockX(), fromFloor.blockY() + 1,
            fromFloor.blockZ());
        NpcAccessSubject subject = new NpcAccessSubject("snapshot", NpcAccessProfile.DEFAULT, world.getName(),
            startRegions);
        running = true;
        sender.sendMessage("Planning route " + NpcRouteStore.key(name) + " for a snapshot...");
        nav.planRoadFor(subject, route.from(), route.to()).whenComplete((plan, error) -> {
            try {
                if (error != null || plan == null) {
                    running = false;
                    sender.sendMessage("The route could not be planned: " + (error == null ? "no answer" : error));
                    return;
                }
                if (plan.line().isEmpty()) {
                    running = false;
                    sender.sendMessage("The route has no line (" + plan.status() + ": " + plan.reason() + ").");
                    return;
                }
                collect(sender, NpcRouteStore.key(name), route, world, nav, plan);
            } catch (RuntimeException e) {
                running = false;
                logger.log(Level.WARNING, "[NPC] snapshot of " + name + " failed", e);
                sender.sendMessage("The snapshot failed: " + e);
            }
        });
    }

    /** Main thread: everything from the caches and the world, then the save and the copy on another thread. */
    private void collect(CommandSender sender, String name, NpcRouteStore.Route route, World world,
                         NavigationService nav, RoadPlanner.RoadPlan plan) {
        List<String> notes = new ArrayList<>();
        SnapshotBox box = SnapshotBox.around(plan.line());
        NavigationSettings.Scope scope = new NavigationSettings.Scope(world.getName(),
            regionsOuterToInner.apply(route.from()), null, null);
        NavigationSettings settings = config.settings();
        double stretchBlocks = settings.value(NavigationSettings.STRETCH_BLOCKS, scope);
        double corner = settings.value(NavigationSettings.CORNER_CLEARANCE, scope);
        List<List<double[]>> stretches = SnapshotParts.stretches(plan.line(), plan.plazas(),
            new BukkitNavWorld(null), stretchBlocks, corner, notes);
        NavSnapshot.RouteLine line = SnapshotParts.route(name, route.from(), route.to(), plan, stretches,
            "handed out from the route's start by the route follower with stretch-blocks " + stretchBlocks
                + " and corner-clearance " + corner + ", as an NPC that keeps up gets them (no corridor steering; "
                + "a real NPC's handovers depend on where it stands: the trace's # waypoints: lines)");
        NavSnapshot.Roads roads = SnapshotParts.roads(nav.roadNetwork(world.getName()), box);
        List<NavSnapshot.Domain> domains = domains(world, box, notes);
        List<NavSnapshot.Setting> settingLines = new ArrayList<>();
        settings.describe(scope).forEach((key, resolved) -> settingLines.add(new NavSnapshot.Setting(key.name(),
            String.valueOf(resolved.value()), resolved.layer().name().toLowerCase(java.util.Locale.ROOT)
                + (resolved.scope() == null ? "" : " " + resolved.scope()))));
        Instant now = Instant.now();
        List<String> regionFileNames = box.regionFiles();
        Path worldFolder = world.getWorldFolder().toPath();
        // the save: the region files on disk hold the blocks as they are now (the copy waits a little, below)
        try {
            world.save();
            notes.add("world saved before the copy");
        } catch (RuntimeException e) {
            notes.add("world save failed (" + e + "): the region files are as last saved by the server");
        }
        String version = plugin.getPluginMeta().getVersion();
        sender.sendMessage("Route " + name + ": " + plan.line().size() + " points, " + stretches.size() + " stretches, "
            + roads.edges().size() + " road edges, " + domains.size() + " domains; box " + box.describe()
            + ". Copying " + regionFileNames + "...");
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            String answer;
            try {
                Map<String, Path> files = regionFiles(worldFolder, regionFileNames, notes);
                Map<String, Integer> counts = new LinkedHashMap<>();
                counts.put("route points", line.line().size());
                counts.put("stretches", stretches.size());
                counts.put("road nodes", roads.nodes().size());
                counts.put("road edges", roads.edges().size());
                counts.put("domains", domains.size());
                counts.put("settings", settingLines.size());
                counts.put("region files", files.size());
                NavSnapshot snapshot = new NavSnapshot(new NavSnapshot.Manifest(NavSnapshot.FORMAT, name,
                    world.getName(), box.min(), box.max(), now.toString(), version, counts,
                    new ArrayList<>(files.keySet()), notes), line, roads, domains, settingLines);
                Path folder = plugin.getDataFolder().toPath().resolve(FOLDER);
                Files.createDirectories(folder);
                Path zip = folder.resolve(name + "-" + FILE_TIME.format(now) + ".zip");
                try (OutputStream out = Files.newOutputStream(zip)) {
                    NavSnapshotZip.write(snapshot, files, out);
                }
                answer = "Snapshot written: " + plugin.getDataFolder().getName() + "/" + FOLDER + "/" + zip.getFileName()
                    + " (" + Files.size(zip) / 1024 + " KiB, " + files.size() + " region files"
                    + (notes.size() > 1 ? "; notes in manifest.json" : "") + ")";
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "[NPC] snapshot of " + name + " failed", e);
                answer = "The snapshot failed: " + e;
            }
            String done = answer;
            Bukkit.getScheduler().runTask(plugin, () -> {
                running = false;
                sender.sendMessage(done);
                logger.info("[NPC] " + done);
            });
        }, COPY_DELAY_TICKS);
    }

    /**
     * The region files named {@code names} under the world folder, wherever the server keeps them (Paper 26.x moved
     * the dimensions' folders; the block files are in a folder called {@code region}, the entity and POI files in
     * {@code entities} and {@code poi}, which are left out), by their path in the zip.
     */
    static Map<String, Path> regionFiles(Path worldFolder, List<String> names, List<String> notes) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!Files.isDirectory(worldFolder)) {
            notes.add("no world folder at " + worldFolder.getFileName());
            return out;
        }
        try (Stream<Path> walk = Files.walk(worldFolder, 6)) {
            walk.filter(p -> names.contains(p.getFileName().toString()))
                .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("region"))
                .forEach(p -> out.put(NavSnapshotZip.WORLD + worldFolder.relativize(p).toString().replace('\\', '/'), p));
        }
        for (String name : names) {
            if (out.keySet().stream().noneMatch(k -> k.endsWith("/" + name))) {
                notes.add("region file " + name + " not found (never saved: nothing built there yet)");
            }
        }
        long folders = out.keySet().stream().map(k -> k.substring(0, k.lastIndexOf('/'))).distinct().count();
        if (folders > 1) {
            notes.add("region files found in " + folders + " folders (the world folder holds several dimensions)");
        }
        return out;
    }

    /**
     * Every WorldGuard region in the world that touches the box, whole, with its domain from the plugin's cache and,
     * for a gate, its doors' states. Regions without a domain are kept too (a parent, a road region).
     */
    private List<NavSnapshot.Domain> domains(World world, SnapshotBox box, List<String> notes) {
        List<NavSnapshot.Domain> out = new ArrayList<>();
        RegionManager manager;
        try {
            manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        } catch (RuntimeException | LinkageError e) {
            notes.add("no WorldGuard regions: " + e);
            return out;
        }
        if (manager == null) {
            notes.add("WorldGuard has no region manager for " + world.getName());
            return out;
        }
        ProtectedCuboidRegion area = new ProtectedCuboidRegion("knk_snapshot_box",
            BlockVector3.at(box.min()[0], box.min()[1], box.min()[2]),
            BlockVector3.at(box.max()[0], box.max()[1], box.max()[2]));
        RegionDomainResolver resolver = sources.domains() == null ? null : sources.domains().get();
        GateManager gates = sources.gates() == null ? null : sources.gates().get();
        if (resolver == null) {
            notes.add("no domain cache: domain ids, names, types and AllowEntry/AllowExit are missing");
        }
        List<ProtectedRegion> touching = new ArrayList<>(manager.getApplicableRegions(area).getRegions());
        touching.sort(java.util.Comparator.comparingInt(ProtectedRegion::getPriority).thenComparing(ProtectedRegion::getId));
        for (ProtectedRegion region : touching) {
            if (region.getId().equals("__global__")) {
                continue;
            }
            var domain = resolver == null ? java.util.Optional.<RegionDomainResolver.DomainSnapshot>empty()
                : resolver.getDomainByRegionIdNoRefresh(world.getName(), region.getId());
            List<int[]> points = new ArrayList<>();
            String shape;
            if (region instanceof ProtectedPolygonalRegion polygon) {
                shape = "polygon";
                for (BlockVector2 p : polygon.getPoints()) {
                    points.add(new int[] {p.x(), p.z()});
                }
            } else {
                shape = region instanceof ProtectedCuboidRegion ? "cuboid" : region.getType().getName();
                BlockVector3 min = region.getMinimumPoint();
                BlockVector3 max = region.getMaximumPoint();
                points.add(new int[] {min.x(), min.y(), min.z()});
                points.add(new int[] {max.x(), max.y(), max.z()});
            }
            List<NavSnapshot.GateDoor> doors = new ArrayList<>();
            String type = domain.map(RegionDomainResolver.DomainSnapshot::domainType).orElse(null);
            if ("gate".equalsIgnoreCase(type) && gates != null) {
                int id = domain.get().id() == null ? Integer.MIN_VALUE : domain.get().id();
                for (CachedGateDoor door : gates.getAllGates().values()) {
                    if (door.getGateStructureId() == id || door.getId() == id) {
                        var state = door.getCurrentState();
                        doors.add(new NavSnapshot.GateDoor(door.getId(), door.getName(),
                            state == null ? null : state.name(),
                            state == net.knightsandkings.knk.core.domain.gates.AnimationState.OPEN));
                    }
                }
                if (doors.isEmpty()) {
                    notes.add("gate " + region.getId() + ": no door in the gate cache");
                }
            }
            out.add(new NavSnapshot.Domain(region.getId(),
                domain.map(RegionDomainResolver.DomainSnapshot::id).orElse(null),
                domain.map(RegionDomainResolver.DomainSnapshot::name).orElse(null), type, shape, points,
                region.getMinimumPoint().y(), region.getMaximumPoint().y(),
                region.getParent() == null ? null : region.getParent().getId(), region.getPriority(),
                flag(region, DomainAccessFlags.registered() ? DomainAccessFlags.entry() : null),
                flag(region, DomainAccessFlags.registered() ? DomainAccessFlags.exit() : null),
                domain.map(RegionDomainResolver.DomainSnapshot::allowEntry).orElse(null),
                domain.map(RegionDomainResolver.DomainSnapshot::allowExit).orElse(null), doors));
        }
        return out;
    }

    private static String flag(ProtectedRegion region, StateFlag flag) {
        if (flag == null) {
            return null;
        }
        StateFlag.State state = region.getFlag(flag);
        return state == null ? null : state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
