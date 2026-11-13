package net.knightsandkings.knk.paper.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.paper.commands.CommandMetadata;
import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.npc.citizens.CitizensBootstrap;
import net.knightsandkings.knk.paper.regions.RegionIds;

/**
 * NPC navigation's wiring (PandiO/knk-workspace#358, Phase 1 #359): config, the engine adapter (Citizens, when
 * present), metrics, the NPC event audit and the {@code /knk npc} admin command. Keeps {@code KnKPlugin}'s part to a
 * start and a stop call.
 */
public final class NpcNavigationModule {

    public static final String PERMISSION = "knk.admin.npc";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final NpcNavigationConfig config;
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final NpcEventAudit audit;
    private final RegionIds regionIds;
    private NpcMoverFactory factory;
    private String unavailable;
    private NpcTestWalkers walkers;
    private BukkitTask snapshotTask;
    /** Whether the instruments are on the metrics endpoint ({@code metrics.enabled}). */
    private boolean metricsExported;
    // Route following (PandiO/knk-workspace#360): built on the first /knk npc nav use, so nothing runs before that.
    private java.util.function.Supplier<net.knightsandkings.knk.paper.navigation.NavigationService> navigation;
    private NpcNavigationService service;
    private NpcNavRunner runner;
    private BukkitTask navTask;
    private org.bukkit.event.Listener tickListener;
    /** Ends the subscription to road navigation's gate and availability events (PandiO/knk-workspace#361). */
    private Runnable ruleEvents;

    private NpcNavigationModule(JavaPlugin plugin, NpcNavigationConfig config, RegionIds regionIds) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.regionIds = regionIds;
        this.audit = new NpcEventAudit(plugin);
    }

    /**
     * Reads {@code npc.navigation}, starts the Citizens adapter when enabled and available, registers {@code /knk npc}
     * (always, so status explains why NPC navigation is off) and the metrics.
     *
     * @param metricsRegistrar adds a collector to the running metrics endpoint, or null when metrics are off
     * @param regionIds        WorldGuard region lookup for the status command's region layer, or null
     */
    public static NpcNavigationModule start(JavaPlugin plugin, KnkAdminCommand knk,
                                            java.util.function.Consumer<net.knightsandkings.knk.core.metrics.MetricsCollector> metricsRegistrar,
                                            RegionIds regionIds) {
        return start(plugin, knk, metricsRegistrar, regionIds, null);
    }

    /**
     * As above, with road navigation for route following ({@code /knk npc nav}, PandiO/knk-workspace#360).
     *
     * @param navigation the running road navigation service (asked on first use; null or a null answer: route
     *                   following reports unavailable)
     */
    public static NpcNavigationModule start(JavaPlugin plugin, KnkAdminCommand knk,
                                            java.util.function.Consumer<net.knightsandkings.knk.core.metrics.MetricsCollector> metricsRegistrar,
                                            RegionIds regionIds,
                                            java.util.function.Supplier<net.knightsandkings.knk.paper.navigation.NavigationService> navigation) {
        NpcNavigationConfig config = NpcNavigationConfig.from(plugin.getConfig().getConfigurationSection(NpcNavigationConfig.PATH));
        NpcNavigationModule module = new NpcNavigationModule(plugin, config, regionIds);
        module.navigation = navigation;
        for (String rejected : config.rejected()) {
            module.logger.warning("[NPC] config " + rejected);
        }
        if (!config.enabled()) {
            module.unavailable = NpcNavigationConfig.PATH + ".enabled is false";
        } else {
            Optional<String> reason = CitizensBootstrap.unavailableReason();
            if (reason.isPresent()) {
                module.unavailable = reason.get();
            } else {
                try {
                    module.factory = CitizensBootstrap.start(plugin).orElse(null);
                } catch (RuntimeException | LinkageError e) {
                    module.logger.log(Level.WARNING, "[NPC] Citizens adapter failed to start", e);
                    module.unavailable = "Citizens adapter failed: " + e;
                }
            }
        }
        if (module.factory != null) {
            module.walkers = new NpcTestWalkers(module.factory, config.settings(), module.metrics);
            module.snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, module::publishSnapshot, 20L, 20L);
            module.logger.info("[NPC] navigation: Citizens " + CitizensBootstrap.citizensVersion() + ", override scopes "
                + config.settings().scopeCounts());
        } else {
            module.logger.info("[NPC] navigation unavailable: " + module.unavailable);
        }
        // the counters also feed /knk npc nav stats, so they count whenever NPC navigation runs
        module.metrics.setEnabled(module.factory != null || metricsRegistrar != null);
        if (metricsRegistrar != null) {
            module.metricsExported = true;
            metricsRegistrar.accept(module.metrics);
        }
        knk.registerSubcommand(new CommandMetadata("npc",
                "NPC navigation: status, NPC event audit, test NPCs, route following (PandiO/knk-workspace#358)",
                "/knk npc <status|audit start|audit stop|audit report|test spawn <n> [radius]|test clear|nav ...>", PERMISSION,
                List.of("/knk npc status", "/knk npc audit start", "/knk npc test spawn 50 24", "/knk npc audit report",
                    "/knk npc nav from", "/knk npc nav to", "/knk npc nav spawn 50", "/knk npc nav stats")),
            module::execute, module::complete);
        return module;
    }

    public NpcNavigationMetrics metrics() {
        return metrics;
    }

    public void stop() {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }
        audit.stop();
        if (navTask != null) {
            navTask.cancel();
            navTask = null;
        }
        if (tickListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(tickListener);
            tickListener = null;
        }
        if (ruleEvents != null) {
            ruleEvents.run();
            ruleEvents = null;
        }
        if (runner != null) {
            runner.clear();
            runner = null;
            service = null;
        }
        if (walkers != null) {
            walkers.clear();
        }
        CitizensBootstrap.stop(factory);
        factory = null;
    }

    private void publishSnapshot() {
        long start = System.nanoTime();
        metrics.publishStates(allStates());
        metrics.observeMainThread((System.nanoTime() - start) / 1e9);
    }

    /** Test walkers and route followers together, by world and state. */
    private Map<String, Map<NpcNavState, Integer>> allStates() {
        Map<String, Map<NpcNavState, Integer>> out = new java.util.HashMap<>();
        if (walkers != null) {
            walkers.states().forEach((world, counts) -> out.put(world, new java.util.EnumMap<>(counts)));
        }
        if (service != null) {
            service.states().forEach((world, counts) -> counts.forEach((state, n) ->
                out.computeIfAbsent(world, w -> new java.util.EnumMap<>(NpcNavState.class)).merge(state, n, Integer::sum)));
        }
        return out;
    }

    /**
     * Builds route following on first use: the navigation service on the road router and the walk capture, the test
     * driver and its tick. Returns why it is unavailable, or null when it runs.
     */
    private String ensureNav() {
        if (factory == null) {
            return unavailable;
        }
        if (runner != null) {
            return null;
        }
        net.knightsandkings.knk.paper.navigation.NavigationService nav = navigation == null ? null : navigation.get();
        if (nav == null) {
            return "road navigation is not running (navigation.enabled)";
        }
        net.knightsandkings.knk.paper.navigation.NavigationService.Walk walk = nav.walk();
        if (walk == null
            || !(walk.preparer() instanceof net.knightsandkings.knk.paper.navigation.walk.WalkLegPreparer legs)) {
            return "walk paths are off (navigation.walk.enabled): local legs need their capture";
        }
        BukkitNavWorld world = new BukkitNavWorld(regionIds);
        NpcNavLog log = new NpcNavLog(plugin.getDataFolder().toPath(), logger);
        service = new NpcNavigationService(new NpcNavigationService.Ports(nav::planRoadFor,
            new NpcLegPreparer(legs, walk.searches(), task -> Bukkit.getScheduler().runTask(plugin, task),
                Bukkit::getWorld, logger), world, metrics, System::nanoTime, log::event, nav.accessRules()),
            config.maxPlansInFlight(), config.ruleChecksPerTick());
        // gate and availability events reach the NPCs through road navigation's listener port; an event may arrive
        // off the main thread (a cache refresh), the NPC service is main thread only
        NpcNavigationService npcs = service;
        ruleEvents = nav.addRuleChangeListener(new net.knightsandkings.knk.core.npc.nav.RuleChangeListener() {
            @Override
            public void gateChanged(int doorId) {
                onMainThread(() -> npcs.onGateChanged(doorId));
            }

            @Override
            public void availabilityChanged() {
                onMainThread(npcs::onAvailabilityChanged);
            }
        });
        runner = new NpcNavRunner(factory, service, config.settings(), metrics, world,
            new NpcRouteStore(plugin.getDataFolder(), logger), log, config.spawnsPerTick(), at -> {
                org.bukkit.World w = Bukkit.getWorld(at.world());
                return w == null ? List.of() : regionsOuterToInner(new org.bukkit.Location(w, at.x(), at.y(), at.z()));
            });
        navTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            runner.tick();
            service.tick();
            runner.sampleService();
        }, 1L, 1L);
        tickListener = new TickSampler();
        Bukkit.getPluginManager().registerEvents(tickListener, plugin);
        return null;
    }

    private void onMainThread(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }

    /** The server's tick durations, for the per-NPC cost against a baseline. Public for Bukkit's event executor. */
    public final class TickSampler implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onTickEnd(com.destroystokyo.paper.event.server.ServerTickEndEvent event) {
            if (runner != null) {
                runner.sampleTick(event.getTickDuration());
            }
        }
    }

    // ==================== /knk npc ====================

    boolean execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender).forEach(sender::sendMessage);
            case "audit" -> audit(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "report");
            case "test" -> test(sender, args);
            case "nav" -> nav(sender, args);
            default -> sender.sendMessage("/knk npc <status|audit start|stop|report|test spawn <n> [radius]|test clear|nav ...>");
        }
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "audit", "test", "nav"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("audit")) {
            return filter(List.of("start", "stop", "report", "reset"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return filter(List.of("spawn", "clear"), args[1]);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("nav")) {
            if (args.length == 2) {
                return filter(NAV_ACTIONS, args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("route")) {
                return filter(List.of("list", "save", "load", "delete"), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("route") && runner != null
                && (args[2].equalsIgnoreCase("load") || args[2].equalsIgnoreCase("delete"))) {
                return filter(runner.routes().names(), args[3]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("spawn")) {
                return filter(List.copyOf(config.accessProfiles().keySet()), args[3]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("stats")) {
                return filter(List.of("reset"), args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }

    List<String> status(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add(factory != null ? "NPC navigation: running on Citizens " + CitizensBootstrap.citizensVersion()
            : "NPC navigation: unavailable (" + unavailable + ")");
        lines.add("max plans in flight " + config.maxPlansInFlight() + ", override scopes " + config.settings().scopeCounts()
            + (config.rejected().isEmpty() ? "" : ", " + config.rejected().size() + " rejected config entries (see startup log)"));
        lines.add("spawns per tick " + config.spawnsPerTick() + ", rule checks per tick " + config.ruleChecksPerTick()
            + ", access profiles " + config.accessProfiles().keySet());
        if (walkers != null) {
            Map<String, Map<NpcNavState, Integer>> states = walkers.states();
            lines.add("test NPCs " + walkers.count() + (states.isEmpty() ? "" : " " + states));
        }
        if (runner != null) {
            lines.add("route NPCs " + runner.count() + (runner.pendingSpawns() == 0 ? "" : " (+" + runner.pendingSpawns()
                + " spawning)") + " " + service.states() + ", planning queue " + service.queued() + ", in flight "
                + service.plansInFlight());
        }
        lines.add("event audit " + (audit.isRunning() ? "running" : "stopped") + "; metrics "
            + (metricsExported ? "on" : "off (metrics.enabled)"));
        if (sender instanceof Player player) {
            List<String> regions = regionsOuterToInner(player.getLocation());
            NavigationSettings.Scope scope = new NavigationSettings.Scope(player.getWorld().getName(), regions, null, null);
            lines.add("settings here (" + player.getWorld().getName() + (regions.isEmpty() ? "" : ", regions outer to inner "
                + regions) + "):");
            config.settings().describe(scope).forEach((key, resolved) -> lines.add("  " + key.name() + " = " + resolved));
        }
        return lines;
    }

    /**
     * The WorldGuard regions at {@code location}, outermost first: by priority ascending (K&K's managed regions give
     * a domain a higher priority than its parent), then by id. The innermost region's override wins.
     */
    List<String> regionsOuterToInner(org.bukkit.Location location) {
        if (regionIds == null) {
            return List.of();
        }
        com.sk89q.worldguard.protection.ApplicableRegionSet set = regionIds.applicable(location);
        if (set == null) {
            return List.of();
        }
        return set.getRegions().stream()
            .filter(region -> !region.getId().equals("__global__"))
            .sorted(java.util.Comparator.comparingInt(com.sk89q.worldguard.protection.regions.ProtectedRegion::getPriority)
                .thenComparing(com.sk89q.worldguard.protection.regions.ProtectedRegion::getId))
            .map(com.sk89q.worldguard.protection.regions.ProtectedRegion::getId)
            .toList();
    }

    private void audit(CommandSender sender, String action) {
        switch (action) {
            case "start" -> sender.sendMessage("NPC event audit running on " + audit.start()
                + " event types; spawn or walk NPCs, then /knk npc audit report");
            case "stop" -> {
                audit.stop();
                sender.sendMessage("NPC event audit stopped (counts kept: /knk npc audit report)");
            }
            case "reset" -> {
                audit.reset();
                sender.sendMessage("NPC event audit counts cleared");
            }
            default -> {
                audit.report().forEach(sender::sendMessage);
                try {
                    sender.sendMessage("Written to npc-logs/" + audit.writeReport());
                } catch (java.io.IOException e) {
                    sender.sendMessage("Could not write the report: " + e.getMessage());
                }
            }
        }
    }

    private void test(CommandSender sender, String[] args) {
        if (walkers == null) {
            sender.sendMessage("NPC navigation unavailable: " + unavailable);
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("clear")) {
            sender.sendMessage("Removed " + walkers.clear() + " test NPCs");
            return;
        }
        if (!action.equals("spawn") || !(sender instanceof Player player)) {
            sender.sendMessage("/knk npc test spawn <n> [radius] (players only) | /knk npc test clear");
            return;
        }
        int n;
        int radius;
        try {
            n = Math.max(1, Math.min(500, Integer.parseInt(args.length > 2 ? args[2] : "10")));
            radius = Math.max(4, Math.min(64, Integer.parseInt(args.length > 3 ? args[3] : "16")));
        } catch (NumberFormatException e) {
            sender.sendMessage("Numbers please: /knk npc test spawn <n> [radius]");
            return;
        }
        int made = walkers.spawn(player.getLocation(), n, radius);
        sender.sendMessage("Spawned " + made + " test NPCs wandering within " + radius + " blocks (" + walkers.count()
            + " total). /knk npc test clear removes them.");
    }

    // ==================== /knk npc nav ====================

    static final List<String> NAV_ACTIONS = List.of("from", "to", "route", "baseline", "spawn", "goto", "patrol",
        "stats", "trace", "clear");
    static final String NAV_USAGE = "/knk npc nav <from|to|route list|save|load|delete <name>|baseline|spawn <n> [profile]"
        + "|goto|patrol|stats [reset]|trace [npc] [seconds]|clear>";

    /**
     * Route following for testing (in place of the spike's {@code /knk npcspike}):
     * <pre>
     * from | to                      set the route's ends at your feet (saved, restored after a restart)
     * route list|save|load|delete    named routes in npc-routes.yml
     * baseline                       store the current mean tick time as the no-NPC baseline
     * spawn &lt;n&gt; [profile]            n NPCs (a few per tick) that walk the route back and forth
     * goto | patrol                  every NPC walks to you once | walks the route again
     * stats [reset]                  the counters (also written to npc-logs/); reset zeroes them
     * trace [npc] [seconds]          per-tick trace of one NPC (nearest by default, 30 s) to npc-logs/
     * clear                          remove every route NPC
     * </pre>
     */
    private void nav(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!NAV_ACTIONS.contains(action)) {
            sender.sendMessage(NAV_USAGE);
            return;
        }
        String reason = ensureNav();
        if (reason != null) {
            sender.sendMessage("NPC navigation unavailable: " + reason);
            return;
        }
        switch (action) {
            case "from", "to" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return;
                }
                runner.setEnd(action.equals("from"), feetOf(player));
                sender.sendMessage("Route " + action + " set at " + NpcRouteStore.describe(feetOf(player)) + " (saved)");
            }
            case "route" -> navRoute(sender, args);
            case "baseline" -> sender.sendMessage(String.format(Locale.ROOT, "Baseline %.2f ms (%d route NPCs out)",
                runner.baseline(), runner.count()));
            case "spawn" -> navSpawn(sender, args);
            case "goto" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return;
                }
                sender.sendMessage(runner.goTo(feetOf(player)) + " NPCs walk to you. /knk npc nav patrol sends them back on the route.");
            }
            case "patrol" -> sender.sendMessage(runner.patrol() + " NPCs walk the route again.");
            case "stats" -> {
                if (args.length > 2 && args[2].equalsIgnoreCase("reset")) {
                    runner.resetCounters();
                    sender.sendMessage("Counters reset.");
                    return;
                }
                long[] examiner = CitizensBootstrap.examinerStats();
                List<String> report = runner.report(examiner[0], examiner[1]);
                report.forEach(sender::sendMessage);
                runner.logReport(report);
            }
            case "trace" -> {
                String name = args.length > 2 && !args[2].matches("\\d+") ? args[2] : null;
                int seconds = 30;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].matches("\\d+")) {
                        seconds = Math.max(1, Math.min(300, Integer.parseInt(args[i])));
                    }
                }
                sender.sendMessage(runner.startTrace(sender instanceof Player player ? feetOf(player) : null, name, seconds));
            }
            case "clear" -> sender.sendMessage("Removed " + runner.clear() + " route NPCs.");
            default -> sender.sendMessage(NAV_USAGE);
        }
    }

    private static net.knightsandkings.knk.core.npc.nav.NavPoint feetOf(Player player) {
        org.bukkit.Location l = player.getLocation();
        return new net.knightsandkings.knk.core.npc.nav.NavPoint(l.getWorld().getName(), l.getBlockX() + 0.5, l.getBlockY(),
            l.getBlockZ() + 0.5);
    }

    private void navSpawn(CommandSender sender, String[] args) {
        if (runner.from() == null || runner.to() == null) {
            sender.sendMessage("Set both ends first: /knk npc nav from, /knk npc nav to (or route load <name>)");
            return;
        }
        if (!runner.from().world().equals(runner.to().world())) {
            sender.sendMessage("Both ends must be in the same world.");
            return;
        }
        int n;
        try {
            n = Math.max(1, Math.min(500, Integer.parseInt(args.length > 2 ? args[2] : "1")));
        } catch (NumberFormatException e) {
            sender.sendMessage("Not a number: " + args[2]);
            return;
        }
        String profileId = args.length > 3 ? args[3] : net.knightsandkings.knk.core.npc.nav.NpcAccessProfile.DEFAULT.id();
        net.knightsandkings.knk.core.npc.nav.NpcAccessProfile profile = config.accessProfile(profileId);
        if (profile == null) {
            sender.sendMessage("No access profile " + profileId + " (npc.navigation.access-profiles): "
                + config.accessProfiles().keySet());
            return;
        }
        runner.spawn(n, profile);
        sender.sendMessage("Spawning " + n + " route NPCs, " + config.spawnsPerTick() + " per tick, access profile "
            + profile.id() + ". /knk npc nav stats shows them; /knk npc nav clear removes them.");
    }

    private void navRoute(CommandSender sender, String[] args) {
        NpcRouteStore routes = runner.routes();
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        if (action.equals("list")) {
            sender.sendMessage("Current: from " + NpcRouteStore.describe(runner.from()) + " -> to "
                + NpcRouteStore.describe(runner.to()));
            List<String> names = routes.names();
            if (names.isEmpty()) {
                sender.sendMessage("No saved routes. /knk npc nav route save <name>");
            }
            for (String name : names) {
                NpcRouteStore.Route route = routes.named(name).orElseThrow();
                sender.sendMessage(" " + name + ": " + NpcRouteStore.describe(route.from()) + " -> "
                    + NpcRouteStore.describe(route.to()));
            }
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("/knk npc nav route <save|load|delete> <name> | route list");
            return;
        }
        String name = NpcRouteStore.key(args[3]);
        switch (action) {
            case "save" -> {
                if (runner.from() == null || runner.to() == null) {
                    sender.sendMessage("Set both ends first: /knk npc nav from, /knk npc nav to");
                    return;
                }
                routes.saveNamed(name, new NpcRouteStore.Route(runner.from(), runner.to()));
                sender.sendMessage("Saved route " + name + ": " + NpcRouteStore.describe(runner.from()) + " -> "
                    + NpcRouteStore.describe(runner.to()));
            }
            case "load" -> {
                NpcRouteStore.Route route = routes.named(name).orElse(null);
                if (route == null || !route.complete()) {
                    sender.sendMessage("No usable route " + name + ". /knk npc nav route list");
                    return;
                }
                runner.useRoute(route);
                sender.sendMessage("Loaded route " + name + ": " + NpcRouteStore.describe(route.from()) + " -> "
                    + NpcRouteStore.describe(route.to()) + " (NPCs already out take it on their next trip)");
            }
            case "delete" -> sender.sendMessage(routes.delete(name) ? "Deleted route " + name : "No route " + name);
            default -> sender.sendMessage("/knk npc nav route <save|load|delete> <name> | route list");
        }
    }
}
