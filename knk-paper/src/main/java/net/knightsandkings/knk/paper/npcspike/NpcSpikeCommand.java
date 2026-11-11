package net.knightsandkings.knk.paper.npcspike;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import net.citizensnpcs.api.ai.PathfinderType;
import net.knightsandkings.knk.paper.commands.CommandMetadata;

/**
 * #243 spike (throwaway, never merged): {@code /knk npcspike}.
 *
 * <pre>
 * from | to                         set the route ends at your feet
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

    public NpcSpikeCommand(CitizensSpike spike) {
        this.spike = spike;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata("npcspike",
            "#243 Citizens spike: spawn player-shaped NPCs on K&K-planned routes and measure them (throwaway)",
            "/knk npcspike <from|to|baseline|spawn|set|stats|reset|clear>", PERMISSION,
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
                sender.sendMessage("Route " + sub + " set; access subject: " + player.getName());
            }
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
            return List.of("from", "to", "baseline", "spawn", "set", "stats", "reset", "clear").stream()
                .filter(option -> option.startsWith(prefix)).toList();
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
