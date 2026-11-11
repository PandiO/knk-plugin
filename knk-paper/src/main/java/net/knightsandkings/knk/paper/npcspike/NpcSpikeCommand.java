package net.knightsandkings.knk.paper.npcspike;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.citizensnpcs.api.ai.PathfinderType;
import net.knightsandkings.knk.paper.commands.CommandMetadata;

/**
 * #243 spike (throwaway, never merged): {@code /knk npcspike}.
 *
 * <pre>
 * from | to                         set the route ends at your feet (saved, restored after a restart)
 * route save|load|delete &lt;name&gt;     keep named routes in npcspike-routes.yml; route list
 * baseline                          store the current mean MSPT as the no-NPC baseline
 * spawn &lt;n&gt; [dense|hop] [player|mannequin]
 * set &lt;key&gt; &lt;value&gt;                pathfinder citizens|citizens_async, inflight N, hop N, stationary N,
 *                                   recheck N (every N ticks, 0 = off; hop: check the way ahead against the current
 *                                   entry rules, replan when it enters a denied region; dense: replan; default 40),
 *                                   noroute wait|line (no road route: wait and ask again, or straight-line hops),
 *                                   doors on|off, collide on|off
 * stats [label]                     print the counters; with a label also log one CSV row
 * reset                             zero the counters (keeps the NPCs)
 * clear                             remove every spike NPC
 * </pre>
 */
public final class NpcSpikeCommand {

    public static final String PERMISSION = "knk.admin.npcspike";

    private final CitizensSpike spike;
    private final Plugin plugin;

    public NpcSpikeCommand(CitizensSpike spike, Plugin plugin) {
        this.spike = spike;
        this.plugin = plugin;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata("npcspike",
            "#243 Citizens spike: spawn player-shaped NPCs on K&K-planned routes and measure them (throwaway)",
            "/knk npcspike <from|to|route|baseline|spawn|set|stats|reset|clear>", PERMISSION,
            List.of("/knk npcspike from", "/knk npcspike to", "/knk npcspike baseline", "/knk npcspike spawn 10 hop",
                "/knk npcspike stats 10-hop"));
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(metadata().usage());
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "from", "to" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                if (sub.equals("from")) {
                    spike.from = player.getLocation().toBlockLocation().add(0.5, 0, 0.5);
                } else {
                    spike.to = player.getLocation().toBlockLocation().add(0.5, 0, 0.5);
                }
                spike.subject = player.getUniqueId();
                spike.routes.saveCurrent(spike.from, spike.to, spike.subject);
                Location set = sub.equals("from") ? spike.from : spike.to;
                plugin.getLogger().info("[NpcSpike] " + player.getName() + " set " + sub + " at "
                    + SpikeRouteStore.describe(set));
                sender.sendMessage("Route " + sub + " set at " + SpikeRouteStore.describe(set) + " (saved); access subject: "
                    + player.getName());
            }
            case "route" -> route(sender, args);
            case "baseline" -> {
                spike.metrics.baselineMs = spike.metrics.tickMs.mean();
                sender.sendMessage(String.format(Locale.ROOT, "Baseline %.2f ms (%d spike NPCs present)",
                    spike.metrics.baselineMs, spike.count()));
            }
            case "spawn" -> spawn(sender, args);
            case "set" -> set(sender, args);
            case "stats" -> {
                spike.report().forEach(sender::sendMessage);
                if (args.length > 1) {
                    spike.metricsCsvLog(args[1]);
                    sender.sendMessage("CSV row logged to the server log.");
                }
            }
            case "reset" -> {
                spike.resetCounters();
                sender.sendMessage("Counters reset.");
            }
            case "clear" -> {
                int n = spike.count();
                spike.clear();
                sender.sendMessage("Removed " + n + " spike NPCs.");
            }
            default -> sender.sendMessage(metadata().usage());
        }
        return true;
    }

    private void route(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (action.equals("list")) {
            sender.sendMessage("Current: from " + SpikeRouteStore.describe(spike.from) + " -> to "
                + SpikeRouteStore.describe(spike.to));
            List<String> names = spike.routes.names();
            if (names.isEmpty()) {
                sender.sendMessage("No saved routes. /knk npcspike route save <name>");
            }
            for (String name : names) {
                SpikeRouteStore.Route r = spike.routes.named(name).orElseThrow();
                sender.sendMessage(" " + name + ": " + SpikeRouteStore.describe(r.from()) + " -> "
                    + SpikeRouteStore.describe(r.to()));
            }
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("/knk npcspike route <save|load|delete> <name> | route list");
            return;
        }
        String name = SpikeRouteStore.key(args[2]);
        switch (action) {
            case "save" -> {
                if (spike.from == null || spike.to == null) {
                    sender.sendMessage("Set both ends first: /knk npcspike from, /knk npcspike to");
                    return;
                }
                spike.routes.saveNamed(name, spike.from, spike.to, spike.subject);
                sender.sendMessage("Saved route " + name + ": " + SpikeRouteStore.describe(spike.from) + " -> "
                    + SpikeRouteStore.describe(spike.to));
            }
            case "load" -> {
                SpikeRouteStore.Route r = spike.routes.named(name).orElse(null);
                if (r == null || r.from() == null || r.to() == null) {
                    sender.sendMessage("No usable route " + name + " (unknown, or a world is not loaded). /knk npcspike route list");
                    return;
                }
                spike.from = r.from();
                spike.to = r.to();
                // the saved subject if online, else whoever loads it
                if (r.subject() != null && Bukkit.getPlayer(r.subject()) != null) {
                    spike.subject = r.subject();
                } else if (sender instanceof Player player) {
                    spike.subject = player.getUniqueId();
                } else {
                    spike.subject = r.subject();
                }
                spike.routes.saveCurrent(spike.from, spike.to, spike.subject);
                Player who = spike.subject == null ? null : Bukkit.getPlayer(spike.subject);
                sender.sendMessage("Loaded route " + name + ": " + SpikeRouteStore.describe(spike.from) + " -> "
                    + SpikeRouteStore.describe(spike.to) + "; access subject: " + (who == null ? "offline" : who.getName())
                    + " (NPCs already out keep their trip; clear and spawn to use it)");
            }
            case "delete" -> sender.sendMessage(spike.routes.delete(name) ? "Deleted route " + name : "No route " + name);
            default -> sender.sendMessage("/knk npcspike route <save|load|delete> <name> | route list");
        }
    }

    private void spawn(CommandSender sender, String[] args) {
        if (spike.from == null || spike.to == null) {
            sender.sendMessage("Set both ends first: /knk npcspike from, /knk npcspike to");
            return;
        }
        if (!spike.from.getWorld().equals(spike.to.getWorld())) {
            sender.sendMessage("Both ends must be in the same world.");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args.length > 1 ? args[1] : "10");
        } catch (NumberFormatException e) {
            sender.sendMessage("Not a number: " + args[1]);
            return;
        }
        CitizensSpike.Mode mode = args.length > 2 && args[2].equalsIgnoreCase("dense")
            ? CitizensSpike.Mode.DENSE : CitizensSpike.Mode.HOP;
        if (!spike.walkAvailable()) {
            sender.sendMessage("The spike needs navigation.walk.enabled: true (its capture builds the access).");
            return;
        }
        EntityType type = EntityType.PLAYER;
        if (args.length > 3 && args[3].equalsIgnoreCase("mannequin")) {
            try {
                type = EntityType.valueOf("MANNEQUIN");
            } catch (IllegalArgumentException e) {
                sender.sendMessage("This server has no MANNEQUIN entity type.");
                return;
            }
        }
        int made = spike.spawn(Math.max(0, Math.min(n, 500)), mode, type);
        sender.sendMessage("Spawned " + made + " " + type + " NPCs in " + mode + " mode (" + spike.count() + " total).");
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("/knk npcspike set <pathfinder|inflight|hop|stationary|recheck|noroute|doors|collide> <value>");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        String value = args[2].toLowerCase(Locale.ROOT);
        try {
            switch (key) {
                case "pathfinder" -> spike.pathfinderType = PathfinderType.valueOf(value.toUpperCase(Locale.ROOT));
                case "inflight" -> spike.maxInFlight = Math.max(1, Integer.parseInt(value));
                case "hop" -> spike.hopSpacing = Math.max(4, Double.parseDouble(value));
                case "stationary" -> spike.stationaryTicks = Integer.parseInt(value);
                case "recheck" -> spike.recheckTicks = Math.max(0, Integer.parseInt(value));
                case "noroute" -> {
                    if (!value.equals("wait") && !value.equals("line")) {
                        throw new IllegalArgumentException(value);
                    }
                    spike.noRouteWait = value.equals("wait");
                }
                case "doors" -> spike.openDoors = value.equals("on");
                case "collide" -> spike.collidable = value.equals("on");
                default -> {
                    sender.sendMessage("Unknown setting " + key);
                    return;
                }
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Bad value " + value + " for " + key);
            return;
        }
        sender.sendMessage("Set " + key + " = " + value + " (applies to NPCs spawned from now on; inflight, hop, recheck,"
            + " noroute and doors apply at once)");
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("from", "to", "route", "baseline", "spawn", "set", "stats", "reset", "clear").stream()
                .filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("route")) {
            return List.of("list", "save", "load", "delete");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("route")
            && (args[1].equalsIgnoreCase("load") || args[1].equalsIgnoreCase("delete"))) {
            return spike.routes.names();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) {
            return List.of("hop", "dense");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("spawn")) {
            return List.of("player", "mannequin");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return List.of("pathfinder", "inflight", "hop", "stationary", "recheck", "noroute", "doors", "collide");
        }
        return List.of();
    }
}
