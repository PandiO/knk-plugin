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
        NpcNavigationConfig config = NpcNavigationConfig.from(plugin.getConfig().getConfigurationSection(NpcNavigationConfig.PATH));
        NpcNavigationModule module = new NpcNavigationModule(plugin, config, regionIds);
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
        if (metricsRegistrar != null) {
            module.metrics.setEnabled(true);
            metricsRegistrar.accept(module.metrics);
        }
        knk.registerSubcommand(new CommandMetadata("npc",
                "NPC navigation: status, NPC event audit, test NPCs (PandiO/knk-workspace#358)",
                "/knk npc <status|audit start|audit stop|audit report|test spawn <n> [radius]|test clear>", PERMISSION,
                List.of("/knk npc status", "/knk npc audit start", "/knk npc test spawn 50 24", "/knk npc audit report")),
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
        if (walkers != null) {
            walkers.clear();
        }
        CitizensBootstrap.stop(factory);
        factory = null;
    }

    private void publishSnapshot() {
        long start = System.nanoTime();
        metrics.publishStates(walkers == null ? Map.of() : walkers.states());
        metrics.observeMainThread((System.nanoTime() - start) / 1e9);
    }

    // ==================== /knk npc ====================

    boolean execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender).forEach(sender::sendMessage);
            case "audit" -> audit(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "report");
            case "test" -> test(sender, args);
            default -> sender.sendMessage("/knk npc <status|audit start|stop|report|test spawn <n> [radius]|test clear>");
        }
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "audit", "test"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("audit")) {
            return filter(List.of("start", "stop", "report", "reset"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return filter(List.of("spawn", "clear"), args[1]);
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
        if (walkers != null) {
            Map<String, Map<NpcNavState, Integer>> states = walkers.states();
            lines.add("test NPCs " + walkers.count() + (states.isEmpty() ? "" : " " + states));
        }
        lines.add("event audit " + (audit.isRunning() ? "running" : "stopped") + "; metrics "
            + (metrics.isEnabled() ? "on" : "off (metrics.enabled)"));
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
}
