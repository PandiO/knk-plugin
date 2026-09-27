package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxRegions;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code /knk lootbox area create|list|info|delete} (docs/specs/lootboxes/DESIGN.md §3.4, D17): spawn areas from a
 * WorldEdit selection without a trip through the web app's region world task. The area row in the API stays the
 * source of truth; limits are tuned in the web app.
 * <ul>
 *   <li>{@code create <name>}: region {@code lootbox_<lower-cased name>} from the selection, stretched to the world's
 *       full build height, then {@code POST LootboxSpawnAreas/in-game}. Refused without an API call for a bad name, an
 *       existing area or region, or no selection. If the API refuses or is down, the region is removed again.</li>
 *   <li>{@code delete <name>}: repeat within 10 s to confirm. Removes the row and its boxes; the WG region only when
 *       its id starts with {@code lootbox_} (a borrowed town/district region is never deleted).</li>
 * </ul>
 * Main thread; the API calls run async and report back through {@code mainThread}.
 */
public final class LootboxAreaCommand {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    static final Duration CONFIRM_WINDOW = Duration.ofSeconds(10);

    private record PendingDelete(int areaId, Instant expiresAt) {
    }

    private final Supplier<KnkLootboxRuntimeConfig> config;
    private final ActiveLootboxCache cache;
    private final LootboxRegions regions;
    private final LootboxesCommandApi commandApi;
    private final Function<Player, Integer> userIdOf;
    private final Function<String, World> worldByName;
    private final Runnable refresh;
    private final IntConsumer boxRemoved;
    private final Executor mainThread;
    private final Clock clock;
    private final Map<String, PendingDelete> pendingDeletes = new HashMap<>();

    public LootboxAreaCommand(
            Supplier<KnkLootboxRuntimeConfig> config,
            ActiveLootboxCache cache,
            LootboxRegions regions,
            LootboxesCommandApi commandApi,
            Function<Player, Integer> userIdOf,
            Function<String, World> worldByName,
            Runnable refresh,
            IntConsumer boxRemoved,
            Executor mainThread,
            Clock clock
    ) {
        this.config = config;
        this.cache = cache;
        this.regions = regions;
        this.commandApi = commandApi;
        this.userIdOf = userIdOf;
        this.worldByName = worldByName;
        this.refresh = refresh;
        this.boxRemoved = boxRemoved;
        this.mainThread = mainThread;
        this.clock = clock;
    }

    public void execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "create" -> create(sender, rest);
            case "list" -> list(sender);
            case "info" -> info(sender, rest);
            case "delete" -> delete(sender, rest);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox area create <name> | list | info <name> | delete <name>");
        }
    }

    // ===== create =====

    void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can create an area (it uses your WorldEdit selection).");
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox area create <name>");
            return;
        }
        String name = args[0];
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(ChatColor.RED + "Area names are 1-32 letters, digits, _ or -.");
            return;
        }
        if (config.get().areaByName(name).isPresent()) {
            sender.sendMessage(ChatColor.RED + "An area named " + name + " already exists.");
            return;
        }
        World world = player.getWorld();
        String regionId = LootboxRegions.areaRegionId(name);
        if (regions.regionExists(world, regionId)) {
            sender.sendMessage(ChatColor.RED + "WorldGuard region " + regionId + " already exists - pick another name, or remove it"
                    + " first (/rg remove " + regionId + ").");
            return;
        }
        LootboxRegions.CreateResult created = regions.createFullHeightFromSelection(player, regionId);
        switch (created.status()) {
            case NO_SELECTION -> {
                sender.sendMessage(ChatColor.RED + "Make a WorldEdit selection first (//wand).");
                return;
            }
            case FAILED -> {
                sender.sendMessage(ChatColor.RED + (created.message() != null ? created.message() : "Could not create the region."));
                return;
            }
            default -> {
                // CREATED: now the area row.
            }
        }

        sender.sendMessage(ChatColor.GRAY + "Region " + regionId + " created; saving the area...");
        commandApi.createAreaInGame(userIdOf.apply(player), name, world.getName(), regionId)
                .whenComplete((area, ex) -> mainThread.execute(() -> {
                    if (ex != null || area == null) {
                        regions.removeRegion(world, regionId); // No orphan region without an area row.
                        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
                        String why;
                        if (rejected != null && rejected.is(LootboxRejectedException.NAME_TAKEN)) {
                            why = "An area named " + name + " already exists.";
                        } else if (rejected != null && rejected.is(LootboxRejectedException.REGION_IN_USE)) {
                            why = "Another area already uses region " + regionId + ".";
                        } else if (rejected != null) {
                            why = rejected.getMessage();
                        } else {
                            why = "The API didn't answer (" + (ex == null ? "no result" : LootboxRejectedException.unwrap(ex).getMessage()) + ").";
                        }
                        sender.sendMessage(ChatColor.RED + why + " The region was removed again.");
                        return;
                    }
                    refresh.run();
                    sender.sendMessage(ChatColor.GREEN + "Area " + area.name() + " created (max " + area.maxActive() + " boxes, every "
                            + area.spawnIntervalSeconds() + " s, ≥" + area.minOnlinePlayers() + " online). Tune it in the web app.");
                }));
    }

    // ===== list / info =====

    void list(CommandSender sender) {
        List<KnkLootboxArea> areas = config.get().areas();
        if (areas.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No lootbox areas yet. Select one with //wand and run /knk lootbox area create <name>.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Lootbox areas (" + areas.size() + "):");
        for (KnkLootboxArea area : areas) {
            sender.sendMessage(ChatColor.WHITE + area.name() + " "
                    + (area.enabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled")
                    + ChatColor.GRAY + " | " + area.world() + "/" + area.wgRegionId()
                    + " | boxes " + cache.countInArea(area.id()) + "/" + area.maxActive());
        }
    }

    void info(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox area info <name>");
            return;
        }
        Optional<KnkLootboxArea> found = config.get().areaByName(args[0]);
        if (found.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No lootbox area named " + args[0] + ".");
            return;
        }
        KnkLootboxArea area = found.get();
        sender.sendMessage(ChatColor.GOLD + "Area " + area.name() + " " + (area.enabled() ? ChatColor.GREEN + "(enabled)" : ChatColor.RED + "(disabled)"));
        sender.sendMessage(ChatColor.GRAY + "Region: " + ChatColor.WHITE + area.world() + "/" + area.wgRegionId() + bounds(area));
        sender.sendMessage(ChatColor.GRAY + "Boxes: " + ChatColor.WHITE + cache.countInArea(area.id()) + "/" + area.maxActive()
                + ChatColor.GRAY + " | every " + ChatColor.WHITE + area.spawnIntervalSeconds() + " s"
                + ChatColor.GRAY + " at " + ChatColor.WHITE + trim(area.spawnChancePercent()) + "%"
                + ChatColor.GRAY + " | ≥" + ChatColor.WHITE + area.minOnlinePlayers() + ChatColor.GRAY + " online");
        sender.sendMessage(ChatColor.GRAY + "Min distance from players: " + ChatColor.WHITE + area.minDistanceFromPlayers()
                + ChatColor.GRAY + " | lifetime " + ChatColor.WHITE + area.lifetimeMinutes() + " min");
        sender.sendMessage(ChatColor.GRAY + "Excluded regions: " + ChatColor.WHITE
                + (area.excludedRegionIds().isEmpty() ? "none" : String.join(", ", area.excludedRegionIds())));
        sender.sendMessage(ChatColor.GRAY + "Types: " + ChatColor.WHITE + types(area));
    }

    private String bounds(KnkLootboxArea area) {
        World world = worldByName.apply(area.world());
        if (world == null) {
            return ChatColor.GRAY + " (world not loaded here)";
        }
        Optional<LootboxSpawnPlanner.Bounds> bounds = regions.bounds(world, area.wgRegionId());
        return bounds.map(b -> ChatColor.GRAY + " x " + b.minX() + ".." + b.maxX() + ", z " + b.minZ() + ".." + b.maxZ())
                .orElse(ChatColor.RED + " (region missing!)");
    }

    private String types(KnkLootboxArea area) {
        if (area.allowedTypeIds().isEmpty()) {
            return "every enabled type";
        }
        KnkLootboxRuntimeConfig current = config.get();
        return area.allowedTypeIds().stream()
                .map(id -> current.typeById(id).map(KnkLootboxType::name).orElse("#" + id + " (disabled)"))
                .collect(Collectors.joining(", "));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // ===== delete =====

    void delete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox area delete <name>");
            return;
        }
        Optional<KnkLootboxArea> found = config.get().areaByName(args[0]);
        if (found.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No lootbox area named " + args[0] + ".");
            return;
        }
        KnkLootboxArea area = found.get();
        Instant now = clock.instant();
        String key = sender.getName().toLowerCase(Locale.ROOT);
        PendingDelete pending = pendingDeletes.get(key);
        if (pending == null || pending.areaId() != area.id() || now.isAfter(pending.expiresAt())) {
            pendingDeletes.put(key, new PendingDelete(area.id(), now.plus(CONFIRM_WINDOW)));
            boolean ownRegion = area.wgRegionId() != null && area.wgRegionId().startsWith(LootboxRegions.AREA_REGION_PREFIX);
            sender.sendMessage(ChatColor.YELLOW + "This deletes area " + area.name() + " and its active boxes"
                    + (ownRegion ? " and removes region " + area.wgRegionId() : " (region " + area.wgRegionId() + " is kept)")
                    + ". Repeat the command within " + CONFIRM_WINDOW.toSeconds() + " s to confirm.");
            return;
        }
        pendingDeletes.remove(key);

        Integer actor = sender instanceof Player player ? userIdOf.apply(player) : null;
        commandApi.deleteAreaInGame(actor, area.id()).whenComplete((result, ex) -> mainThread.execute(() -> {
            if (ex != null || result == null) {
                LootboxRejectedException rejected = LootboxRejectedException.find(ex);
                sender.sendMessage(ChatColor.RED + "Could not delete area " + area.name() + ": "
                        + (rejected != null ? rejected.getMessage() : ex == null ? "no result" : LootboxRejectedException.unwrap(ex).getMessage()));
                return;
            }
            result.removedSpawnIds().forEach(boxRemoved::accept);
            String regionNote;
            String regionId = result.wgRegionId();
            if (regionId != null && regionId.startsWith(LootboxRegions.AREA_REGION_PREFIX)) {
                World world = worldByName.apply(result.world());
                boolean removed = world != null && regions.removeRegion(world, regionId);
                regionNote = removed ? ", region " + regionId + " removed" : ", region " + regionId + " not found on this server";
            } else {
                regionNote = ", region " + regionId + " kept";
            }
            refresh.run();
            sender.sendMessage(ChatColor.GREEN + "Area " + result.name() + " deleted (" + result.removedSpawnIds().size() + " boxes removed"
                    + regionNote + ").");
        }));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 1) {
            return LootboxCommand.filter(List.of("create", "list", "info", "delete"), args[0]);
        }
        if (args.length == 2 && ("info".equalsIgnoreCase(args[0]) || "delete".equalsIgnoreCase(args[0]))) {
            return LootboxCommand.filter(config.get().areas().stream().map(KnkLootboxArea::name).toList(), args[1]);
        }
        return List.of();
    }
}
