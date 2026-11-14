package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioHost;
import net.knightsandkings.knk.core.scenario.ScenarioRegistry;
import net.knightsandkings.knk.core.scenario.ScenarioSuiteRun;
import net.knightsandkings.knk.paper.commands.CommandMetadata;
import net.knightsandkings.knk.paper.commands.SubcommandExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * {@code /knk scenario list} and {@code /knk scenario run <name|all>} (PandiO/knk-workspace#421).
 * Registered only when {@code testing.scenarios.enabled} is on, so a real server has no such
 * command. A run plays the scenarios one after the other on the server thread, one step of the
 * suite per tick, and writes the {@code KNK-SCENARIO} lines to the server log (which CI reads) and
 * to a player who started it. One run at a time.
 */
public final class ScenarioCommand implements SubcommandExecutor {

    public static final String NAME = "scenario";
    public static final String NODE = "knk.admin.scenario";

    private final Plugin plugin;
    private final ScenarioRegistry<ScenarioScene> registry;
    private final ScenarioHost<ScenarioScene> host;
    private final Logger logger;
    private BukkitRunnable running;

    public ScenarioCommand(Plugin plugin, ScenarioRegistry<ScenarioScene> registry, ScenarioHost<ScenarioScene> host) {
        this.plugin = plugin;
        this.registry = registry;
        this.host = host;
        this.logger = plugin.getLogger();
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(NAME, "Run in-plugin test scenarios (test servers only)",
            "/knk scenario list | run <name|all>", NODE,
            List.of("/knk scenario list", "/knk scenario run all", "/knk scenario run gate-open-close"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                sender.sendMessage(ChatColor.GOLD + "Scenarios (" + registry.all().size() + "):");
                for (Scenario<ScenarioScene> scenario : registry.all()) {
                    sender.sendMessage(ChatColor.YELLOW + scenario.name() + ChatColor.GRAY + " - " + scenario.description());
                }
            }
            case "run" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /knk scenario run <name|all>");
                    return true;
                }
                List<Scenario<ScenarioScene>> selected = registry.select(args[1]);
                if (selected.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Unknown scenario '" + args[1] + "'. See /knk scenario list.");
                    return true;
                }
                start(sender, selected);
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: " + metadata().usage());
        }
        return true;
    }

    /** Tab completion for the arguments after {@code scenario}. */
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("list");
            options.add("run");
        } else if (args.length == 2 && "run".equalsIgnoreCase(args[0])) {
            options.add(ScenarioRegistry.ALL);
            registry.all().forEach(scenario -> options.add(scenario.name()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }

    private void start(CommandSender sender, List<Scenario<ScenarioScene>> selected) {
        if (running != null) {
            sender.sendMessage(ChatColor.RED + "A scenario run is already in progress.");
            return;
        }
        ScenarioSuiteRun<ScenarioScene> suite = new ScenarioSuiteRun<>(selected, host, line -> {
            logger.info(line);
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage((line.contains(" FAIL ") ? ChatColor.RED : ChatColor.GRAY) + line);
            }
        });
        running = new BukkitRunnable() {
            @Override
            public void run() {
                boolean done;
                try {
                    done = suite.tick();
                } catch (RuntimeException | Error error) {
                    // ScenarioSuiteRun catches scenario errors itself; this guards the runner.
                    logger.severe("Scenario runner stopped: " + error);
                    done = true;
                }
                if (done) {
                    cancel();
                    running = null;
                }
            }
        };
        running.runTaskTimer(plugin, 1L, 1L);
        sender.sendMessage(ChatColor.GRAY + "Running " + selected.size() + " scenario(s); results follow in the log.");
    }
}
