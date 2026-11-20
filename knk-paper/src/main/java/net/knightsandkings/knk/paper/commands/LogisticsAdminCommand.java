package net.knightsandkings.knk.paper.commands;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.production.transport.LaneJob;
import net.knightsandkings.knk.core.production.transport.RoadConnection;
import net.knightsandkings.knk.paper.production.LaneModule;

/**
 * {@code /knk logistics} (PandiO/knk-workspace#681, DESIGN § 7.6, node {@value #PERMISSION}):
 * <ol>
 *   <li>{@code lanes measure}: reads the API's lane list now, measures each pair over the road network, reports the lanes
 *       and sets the road connections; the sender hears the result (A12, A13).</li>
 *   <li>{@code lanes status}: the last run, whether one is running, and the headless job's interval.</li>
 * </ol>
 * With {@code production.enabled: false} every form answers that production is off.
 */
public final class LogisticsAdminCommand {

    public static final String PERMISSION = ProductionAdminCommand.PERMISSION;
    /** Lines of the run's notes shown to the sender. */
    static final int SHOWN_NOTES = 10;

    private final Supplier<LaneModule> module;
    private final Plugin plugin;

    /** @param module the running lane measurer; null while {@code production.enabled} is off */
    public LogisticsAdminCommand(Supplier<LaneModule> module, Plugin plugin) {
        this.module = module;
        this.plugin = plugin;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "logistics",
                "Measure the transport lanes over the road network now, or show the last run (production.enabled)",
                "/knk logistics lanes <measure|status>",
                PERMISSION,
                List.of("/knk logistics lanes measure", "/knk logistics lanes status"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("lanes"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lanes")) {
            return filter(List.of("measure", "status"), args[1]);
        }
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        LaneModule lanes = module.get();
        if (lanes == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("lanes")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "measure" -> measure(sender, lanes);
            case "status" -> status(sender, lanes);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
        }
        return true;
    }

    private void measure(CommandSender sender, LaneModule lanes) {
        boolean already = lanes.job().isRunning();
        sender.sendMessage(ChatColor.GRAY + (already ? "A lane run is already going; you'll hear its result."
                : "Reading the lane list and measuring over the road network..."));
        lanes.measureNow("command by " + sender.getName()).thenAccept(summary -> reply(sender, () -> show(sender, summary)));
    }

    private void status(CommandSender sender, LaneModule lanes) {
        int interval = lanes.settings().intervalMinutes();
        sender.sendMessage(ChatColor.GRAY + "Lane measurer: " + ChatColor.WHITE
                + (interval > 0 ? "every " + interval + " min" : "only on /knk logistics lanes measure")
                + ChatColor.GRAY + ", " + lanes.settings().batch() + " pairs per run"
                + (lanes.job().isRunning() ? ChatColor.YELLOW + ", running now" : "") + ChatColor.GRAY
                + "; lanes known since start: " + ChatColor.WHITE + lanes.job().book().lanesKnown());
        LaneJob.Summary last = lanes.job().last();
        if (last == null) {
            sender.sendMessage(ChatColor.GRAY + "No run since the server started.");
            return;
        }
        show(sender, last);
    }

    static void show(CommandSender sender, LaneJob.Summary summary) {
        ChatColor color = summary.error() != null ? ChatColor.RED
                : summary.unreachable() > 0 || summary.skipped() > 0 ? ChatColor.YELLOW : ChatColor.GREEN;
        sender.sendMessage(color + "Lanes (" + summary.why() + ", " + ago(summary.at()) + "): " + summary.line());
        if (summary.due() == 0 && summary.error() == null) {
            sender.sendMessage(ChatColor.GRAY + "Nothing due: every lane is measured and fresh (or the API's production "
                    + "flag is off).");
        }
        List<String> notes = summary.notes();
        for (int i = 0; i < Math.min(SHOWN_NOTES, notes.size()); i++) {
            sender.sendMessage(ChatColor.GRAY + "  " + notes.get(i));
        }
        if (notes.size() > SHOWN_NOTES) {
            sender.sendMessage(ChatColor.GRAY + "  ... " + (notes.size() - SHOWN_NOTES) + " more in the server log.");
        }
        for (Map.Entry<Integer, RoadConnection> sent : summary.connectionsSent().entrySet()) {
            sender.sendMessage((sent.getValue() == RoadConnection.Connected ? ChatColor.GREEN : ChatColor.GOLD)
                    + "  Structure #" + sent.getKey() + " is now " + sent.getValue()
                    + (sent.getValue() == RoadConnection.Unconnected ? ": its automatic transport is held" : ""));
        }
    }

    private static String ago(Instant at) {
        if (at == null) {
            return "?";
        }
        long seconds = Math.max(0, Duration.between(at, Instant.now()).getSeconds());
        return seconds < 120 ? seconds + " s ago" : seconds / 60 + " min ago";
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    private void reply(CommandSender sender, Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }
}
