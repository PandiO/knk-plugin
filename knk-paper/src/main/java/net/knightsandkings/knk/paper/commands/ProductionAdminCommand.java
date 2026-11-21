package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.paper.production.AreaOutlineRenderer;
import net.knightsandkings.knk.paper.production.SurveyModule;
import net.knightsandkings.knk.paper.production.SurveyRunner;
import net.knightsandkings.knk.paper.production.harvest.HarvestModule;

/**
 * {@code /knk production} (PandiO/knk-workspace#679, PRODUCTION_KINDS § 3.2-§ 3.3, node {@value #PERMISSION}):
 * <ol>
 *   <li>{@code survey <structure> [area]}: "survey now" (A10): the API puts the structure's areas (or one) at the head of
 *       the queue, the plugin reads the queue and the sender hears each area's result. 409 for a Configured structure
 *       (G5). {@code <structure>} is the structure's id, or its name when it is one word.</li>
 *   <li>{@code survey status}: the queue, the survey running now, the last results and the notable-change counters.</li>
 *   <li>{@code area show <structure> [area] [seconds]}: draws the structure's areas with particles for the sender
 *       (default 30 s).</li>
 *   <li>{@code harvest status}: player harvesting (PandiO/knk-workspace#134): the catalogue, blocks waiting to regenerate,
 *       reports waiting for the API; {@code harvest refresh} re-reads the harvest catalogue now.</li>
 * </ol>
 * With {@code production.enabled: false} every form answers that production is off.
 */
public final class ProductionAdminCommand {

    public static final String PERMISSION = "knk.admin.world.production";
    static final int DEFAULT_SHOW_SECONDS = 30;
    static final int MAX_SHOW_SECONDS = 300;

    private final Supplier<SurveyModule> module;
    private final AreaOutlineRenderer renderer;
    private final Plugin plugin;
    private Supplier<HarvestModule> harvest = () -> null;

    /** @param module the running surveys; null while {@code production.enabled} is off */
    public ProductionAdminCommand(Supplier<SurveyModule> module, AreaOutlineRenderer renderer, Plugin plugin) {
        this.module = module;
        this.renderer = renderer;
        this.plugin = plugin;
    }

    /** Player harvesting (#134): the running module, null while it is off. */
    public ProductionAdminCommand withHarvest(Supplier<HarvestModule> harvestModule) {
        this.harvest = harvestModule == null ? () -> null : harvestModule;
        return this;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "production",
                "Survey a production structure's yield areas now, show the survey queue, or draw an area (production.enabled)",
                "/knk production <survey <structure> [area]|survey status|area show <structure> [area] [seconds]|harvest <status|refresh>>",
                PERMISSION,
                List.of("/knk production survey 12", "/knk production survey status", "/knk production area show 12",
                        "/knk production harvest status"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("survey", "area", "harvest"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("survey")) {
            List<String> options = new ArrayList<>(List.of("status"));
            options.addAll(structureIds());
            return filter(options, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("harvest")) {
            return filter(List.of("status", "refresh"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("area")) {
            return filter(List.of("show"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("area")) {
            return filter(structureIds(), args[2]);
        }
        return List.of();
    }

    private List<String> structureIds() {
        SurveyModule surveys = module.get();
        if (surveys == null) {
            return List.of();
        }
        return surveys.areas().stream().map(a -> String.valueOf(a.structureId())).distinct().sorted().toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    public boolean execute(CommandSender sender, String[] args) {
        SurveyModule surveys = module.get();
        if (surveys == null) {
            sender.sendMessage(ChatColor.RED + "Production is off on this server (production.enabled: false).");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("harvest")) {
            harvest(sender, args);
        } else if (action.equals("survey") && args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            status(sender, surveys);
        } else if (action.equals("survey") && args.length >= 2) {
            survey(sender, surveys, args);
        } else if (action.equals("area") && args.length >= 3 && args[1].equalsIgnoreCase("show")) {
            show(sender, surveys, args);
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
        }
        return true;
    }

    private void harvest(CommandSender sender, String[] args) {
        HarvestModule module = harvest.get();
        if (module == null) {
            sender.sendMessage(ChatColor.RED + "Player harvesting is not running on this server.");
            return;
        }
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (what.equals("refresh")) {
            sender.sendMessage(ChatColor.GRAY + "Reading the harvest catalogue...");
            module.refreshCatalog().whenComplete((catalog, error) -> reply(sender, error != null
                    ? ChatColor.RED + "Catalogue not read: " + SurveyRunner.describe(error)
                    : ChatColor.GREEN + "Catalogue read: " + catalog.rules().size() + " rules, " + catalog.areas().size()
                            + " areas" + (catalog.featureEnabled() ? "" : ChatColor.YELLOW + " (the API's flag is off)")));
            return;
        }
        if (!what.equals("status")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk production harvest <status|refresh>");
            return;
        }
        for (String line : module.status()) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
    }

    private void survey(CommandSender sender, SurveyModule surveys, String[] args) {
        Integer structureId = structureId(surveys, args[1]);
        if (structureId == null) {
            sender.sendMessage(ChatColor.RED + "Unknown structure '" + args[1] + "': use its id (the web app shows it).");
            return;
        }
        Integer areaId = null;
        if (args.length >= 3) {
            areaId = parse(args[2]);
            if (areaId == null) {
                sender.sendMessage(ChatColor.RED + "The area must be an area id.");
                return;
            }
        }
        sender.sendMessage(ChatColor.GRAY + "Asking the API to survey structure #" + structureId
                + (areaId == null ? "" : " area #" + areaId) + "...");
        surveys.surveyNow(structureId, areaId, outcome -> reply(sender, (outcome.status() == SurveyRunner.Status.REPORTED
                ? ChatColor.GREEN : ChatColor.RED) + "Survey " + outcome.summary()))
                .whenComplete((requested, error) -> reply(sender, error != null
                        ? ChatColor.RED + "Survey not requested: " + SurveyRunner.describe(error)
                        : ChatColor.GRAY + "Queued " + ChatColor.WHITE + requested.size() + ChatColor.GRAY
                                + " area(s); you will hear each result here."));
    }

    private void status(CommandSender sender, SurveyModule surveys) {
        SurveyRunner runner = surveys.runner();
        String now = runner.currentDescription();
        sender.sendMessage(ChatColor.GRAY + "Surveys: " + ChatColor.WHITE + runner.queueSize() + ChatColor.GRAY
                + " queued, running: " + ChatColor.WHITE + (now == null ? "none" : now));
        sender.sendMessage(ChatColor.GRAY + "Reported since start: " + ChatColor.WHITE + runner.surveysReported()
                + ChatColor.GRAY + ", chunks captured: " + ChatColor.WHITE + runner.chunksCaptured()
                + ChatColor.GRAY + ", known areas: " + ChatColor.WHITE + surveys.areas().size()
                + ChatColor.GRAY + " (" + surveys.notableTracker().areaCount() + " counted for notable changes)");
        sender.sendMessage(ChatColor.GRAY + "Last queue read: " + ChatColor.WHITE + surveys.lastPoll());
        for (SurveyRunner.Outcome outcome : runner.recent().stream().limit(5).toList()) {
            sender.sendMessage((outcome.status() == SurveyRunner.Status.REPORTED ? ChatColor.GREEN : ChatColor.YELLOW)
                    + "- " + outcome.summary());
        }
    }

    private void show(CommandSender sender, SurveyModule surveys, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can see an area's outline.");
            return;
        }
        Integer structureId = structureId(surveys, args[2]);
        Integer areaId = args.length >= 4 ? parse(args[3]) : null;
        Integer seconds = args.length >= 5 ? parse(args[4]) : null;
        if (structureId == null || (args.length >= 4 && areaId == null) || (args.length >= 5 && seconds == null)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk production area show <structure id> [area id] [seconds]");
            return;
        }
        int duration = seconds == null ? DEFAULT_SHOW_SECONDS : Math.max(1, Math.min(MAX_SHOW_SECONDS, seconds));
        surveys.refreshAreas().whenComplete((ignored, error) -> reply(sender, () -> {
            List<AreaSpec> areas = surveys.areas().stream()
                    .filter(a -> a.structureId() == structureId && (areaId == null || a.areaId() == areaId)).toList();
            if (areas.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Structure #" + structureId + " has no " + (areaId == null ? "" : "such ")
                        + "production area" + (error != null ? " (the area list could not be read: "
                        + SurveyRunner.describe(error) + ")" : "") + ".");
                return;
            }
            List<AreaOutlineRenderer.Outline> outlines = new ArrayList<>();
            for (AreaSpec area : areas) {
                try {
                    AreaShape shape = surveys.shapeOf(area);
                    outlines.add(new AreaOutlineRenderer.Outline(shape, area.exclusive()));
                    boolean here = player.getWorld().getName().equals(area.worldName());
                    player.sendMessage(ChatColor.GRAY + "Area " + ChatColor.WHITE + area.label() + ChatColor.GRAY + ": "
                            + area.shape() + (area.exclusive() ? ", exclusive" : ", shared") + ", x " + shape.minX() + ".."
                            + shape.maxX() + ", y " + shape.minY() + ".." + shape.maxY() + ", z " + shape.minZ() + ".."
                            + shape.maxZ() + (area.enabled() ? "" : ChatColor.YELLOW + " (disabled)")
                            + (here ? "" : ChatColor.YELLOW + " (in " + area.worldName() + ")"));
                } catch (IllegalArgumentException e) {
                    player.sendMessage(ChatColor.RED + "Area " + area.label() + ": " + e.getMessage());
                }
            }
            if (!outlines.isEmpty()) {
                renderer.show(player, outlines, duration);
                player.sendMessage(ChatColor.GREEN + "Drawing " + outlines.size() + " outline(s) for " + duration + " s.");
            }
        }));
    }

    private static Integer structureId(SurveyModule surveys, String argument) {
        Integer id = parse(argument);
        if (id != null) {
            return id;
        }
        List<Integer> named = surveys.areas().stream()
                .filter(a -> a.structureName() != null && a.structureName().equalsIgnoreCase(argument))
                .map(AreaSpec::structureId).distinct().toList();
        return named.size() == 1 ? named.get(0) : null;
    }

    private static Integer parse(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void reply(CommandSender sender, String message) {
        reply(sender, () -> sender.sendMessage(message));
    }

    private void reply(CommandSender sender, Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }
}
