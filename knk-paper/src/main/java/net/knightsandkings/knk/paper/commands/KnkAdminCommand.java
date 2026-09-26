package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.ports.api.HealthApi;
import net.knightsandkings.knk.core.ports.api.LocationsQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.cache.CacheManager;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root /knk command dispatcher using CommandRegistry.
 */
public class KnkAdminCommand implements CommandExecutor, TabCompleter {
    private final CommandRegistry registry = new CommandRegistry();
    private final HelpSubcommand helpSubcommand;
        private final Plugin plugin;
        private final EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess;
        private final ItemBlueprintsDataAccess itemBlueprintsDataAccess;
        private final MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;

        private volatile List<String> cachedKnkEnchantmentIds = List.of();
        private volatile List<String> cachedKnkEnchantmentKeys = List.of();
        private volatile List<String> cachedKnkEnchantmentDisplayNames = List.of();
        private volatile List<String> cachedVanillaEnchantmentTokens = List.of();
        private volatile List<String> cachedRegistryCustomEnchantmentTokens = List.of();
        private volatile long lastKnkIdRefreshMillis = 0L;
        private final AtomicBoolean knkIdRefreshInProgress = new AtomicBoolean(false);

        private static final long KNK_ID_REFRESH_INTERVAL_MS = 30_000L;
        private static final int KNK_ID_PAGE_SIZE = 100;

    public KnkAdminCommand(
            Plugin plugin, 
            HealthApi healthApi, 
            TownsQueryApi townsApi, 
            LocationsQueryApi locationsApi, 
            EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess,
            DistrictsQueryApi districtsApi, 
            StreetsQueryApi streetsApi, 
            CacheManager cacheManager,
            WorldTasksApi worldTasksApi,
            WorldTaskHandlerRegistry worldTaskHandlerRegistry,
            GateManager gateManager,
            GateStructuresApi gateStructuresApi,
            GateDoorsApi gateDoorsApi,
            UserManager userManager,
            UsersCommandApi usersCommandApi,
            UsersDataAccess usersDataAccess,
            PermissionGroupsQueryApi permissionGroupsQueryApi,
            RankHierarchy rankHierarchy,
            ModeService modeService,
            DistrictGateLoader districtGateLoader,
            GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler,
            String serverId,
            MenuService menuService,
            net.knightsandkings.knk.paper.user.UserAdminService userAdminService
    ) {
                this.plugin = plugin;
                this.enchantmentDefinitionsDataAccess = enchantmentDefinitionsDataAccess;
                this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
                this.minecraftMaterialRefsDataAccess = minecraftMaterialRefsDataAccess;
        this.helpSubcommand = new HelpSubcommand(registry);
        
        // Register health
        HealthCommand healthCommand = new HealthCommand(plugin, healthApi);
        registry.register(
                new CommandMetadata("health", "Check API backend health", "/knk health", "knk.admin.health"),
                (sender, args) -> healthCommand.onCommand(sender, null, "knk", new String[0])
        );
        
        // Register cache command
        if (cacheManager != null) {
            registry.register(
                new CommandMetadata("cache", "View cache statistics and health", "/knk cache", "knk.admin.cache"),
                (sender, args) -> {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('§', cacheManager.getHealthSummary()));
                    return true;
                }
            );
        }
        
        // Register towns
        TownsDebugCommand townsCommand = new TownsDebugCommand(plugin, townsApi);
        registry.register(
                new CommandMetadata("towns", "List or search towns", "/knk towns list [page] [size]", "knk.admin.towns",
                        List.of("/knk towns list", "/knk towns list 1 10")),
                (sender, args) -> townsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register town (alias for get by ID)
        registry.register(
                new CommandMetadata("town", "Get town details by ID", "/knk town <id>", "knk.admin.town",
                        List.of("/knk town 1")),
                (sender, args) -> {
                    String[] adjusted = new String[args.length + 1];
                    adjusted[0] = "town";
                    System.arraycopy(args, 0, adjusted, 1, args.length);
                    return townsCommand.onCommand(sender, null, "knk", adjusted);
                }
        );
        
        // Register districts
        DistrictsDebugCommand districtsCommand = new DistrictsDebugCommand(plugin, districtsApi);
        registry.register(
                new CommandMetadata("districts", "List or search districts", "/knk districts list [page] [size]", "knk.admin.districts",
                        List.of("/knk districts list", "/knk districts list 1 10")),
                (sender, args) -> districtsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register district (alias for get by ID)
        registry.register(
                new CommandMetadata("district", "Get district details by ID", "/knk district <id>", "knk.admin.district",
                        List.of("/knk district 1")),
                (sender, args) -> {
                    String[] adjusted = new String[args.length + 1];
                    adjusted[0] = "district";
                    System.arraycopy(args, 0, adjusted, 1, args.length);
                    return districtsCommand.onCommand(sender, null, "knk", adjusted);
                }
        );
        
        // Register locations
        LocationsDebugCommand locationsCommand = new LocationsDebugCommand(plugin, locationsApi);
        registry.register(
                new CommandMetadata("locations", "List or get locations", "/knk locations list <page> <size> | /knk locations <id>", "knk.admin.locations",
                        List.of("/knk locations list 1 10", "/knk locations 5")),
                (sender, args) -> locationsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register location here
        LocationDebugCommand locationHereCommand = new LocationDebugCommand((org.bukkit.plugin.java.JavaPlugin) plugin);
        registry.register(
                new CommandMetadata("location", "Show your current location", "/knk location here", "knk.admin.location",
                        List.of("/knk location here")),
                (sender, args) -> {
                    if (args.length == 0 || !args[0].equalsIgnoreCase("here")) {
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /knk location here");
                        return true;
                    }
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                        return true;
                    }
                    return locationHereCommand.onCommand(sender, null, "knk", new String[0]);
                }
        );

        // Held-item editing (rename/lore) plus the enchantment definition catalog, merged into
        // one /knk item tree per developer request (2026-09-23) - enchantmentsCommand itself is
        // unchanged, only reachable through a different path now (no more standalone
        // /knk enchantments; see ItemCommand's own javadoc for the sub-dispatch).
        EnchantmentDefinitionsDebugCommand enchantmentsCommand = new EnchantmentDefinitionsDebugCommand(plugin, enchantmentDefinitionsDataAccess);
        ItemCommand itemCommand = new ItemCommand(plugin, enchantmentsCommand);
        registry.register(
                new CommandMetadata(
                        "item",
                        "Rename/edit lore on your held item, or manage the enchantment definition catalog",
                        "/knk item rename <displayName> | /knk item lore <add <text>|set <line> <text>|remove <line>> | " +
                                "/knk item enchantments <list|vanilla|search|apply>",
                        "knk.admin",
                        List.of(
                                "/knk item rename &6&lFlametongue",
                                "/knk item lore add &7A blade wreathed in fire",
                                "/knk item lore set 1 &7Forged by dragons",
                                "/knk item lore remove 2",
                                "/knk item enchantments list 1 10",
                                "/knk item enchantments vanilla 1 10",
                                "/knk item enchantments search key minecraft:sharpness",
                                "/knk item enchantments apply 1 3",
                                "/knk item enchantments apply sharpness 3",
                                "/knk item enchantments apply poison 2"
                        )
                ),
                (sender, args) -> itemCommand.onCommand(sender, null, "knk", args)
        );

        ItemBlueprintsDebugCommand itemBlueprintsCommand = new ItemBlueprintsDebugCommand(
                plugin,
                itemBlueprintsDataAccess,
                minecraftMaterialRefsDataAccess,
                enchantmentDefinitionsDataAccess
        );
        registry.register(
                new CommandMetadata(
                        "itemblueprints",
                        "List/search item blueprints and give generated items",
                        "/knk itemblueprints list [page] [size] | /knk itemblueprints search <id|name|displayName> <value> [page] [size] | /knk itemblueprints give <id> [player]",
                        "knk.admin.itemblueprints",
                        List.of(
                                "/knk itemblueprints list 1 10",
                                "/knk itemblueprints search name Sword",
                                "/knk itemblueprints search displayName Excalibur",
                                "/knk itemblueprints give 1",
                                "/knk itemblueprints give 1 SomePlayer"
                        )
                ),
                (sender, args) -> itemBlueprintsCommand.onCommand(sender, null, "knk", args),
                "itemblueprint",
                "ib"
        );

        // InventoryMenu Phases 2 & 5 dev harness (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md) -
        // no real menu content exists yet to trigger this from, so this is the only way to open
        // a menu and verify assembly/layout/pagination/search-filter/async-rendering on a live server.
        if (menuService != null) {
            MenuDebugCommand menuCommand = new MenuDebugCommand(menuService);
            registry.register(
                    new CommandMetadata(
                            "menu",
                            "Open a menu template / page / search / filter through its sections (dev harness)",
                            "/knk menu open <key> | /knk menu page next|prev <sectionName> | "
                                    + "/knk menu search <sectionName> [clear] | /knk menu filter <sectionName> <facetKey> [clear]",
                            "knk.admin.menu",
                            List.of(
                                    "/knk menu open example.placeholder",
                                    "/knk menu page next content",
                                    "/knk menu page prev content",
                                    "/knk menu open example.search",
                                    "/knk menu search Content",
                                    "/knk menu filter Content Category",
                                    "/knk menu search Content clear"
                            )
                    ),
                    (sender, args) -> menuCommand.onCommand(sender, null, "knk", args)
            );
        }

        // Register streets
        StreetsDebugCommand streetsCommand = new StreetsDebugCommand(plugin, streetsApi);
        registry.register(
                new CommandMetadata("streets", "List or search streets", "/knk streets list [page] [size]", "knk.admin.streets",
                        List.of("/knk streets list", "/knk streets list 1 10")),
                (sender, args) -> streetsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register street (alias for get by ID)
        registry.register(
                new CommandMetadata("street", "Get street details by ID", "/knk street <id>", "knk.admin.street",
                        List.of("/knk street 1")),
                (sender, args) -> {
                    String[] adjusted = new String[args.length + 1];
                    adjusted[0] = "street";
                    System.arraycopy(args, 0, adjusted, 1, args.length);
                    return streetsCommand.onCommand(sender, null, "knk", adjusted);
                }
        );
        
        // Register task commands
        KnkTaskListCommand taskListCommand = new KnkTaskListCommand(plugin, worldTasksApi);
        registry.register(
                new CommandMetadata("tasks", "List world tasks by status", "/knk tasks [status]", "knk.tasks",
                        List.of("/knk tasks", "/knk tasks Pending", "/knk tasks Claimed")),
                (sender, args) -> taskListCommand.onCommand(sender, null, "knk", args)
        );
        
        KnkTaskClaimCommand taskClaimCommand = new KnkTaskClaimCommand(plugin, worldTasksApi, worldTaskHandlerRegistry, serverId);
        registry.register(
                new CommandMetadata("task-claim", "Claim a world task", "/knk task-claim <id|linkCode>", "knk.tasks",
                        List.of("/knk task-claim 1", "/knk task-claim ABC123")),
                (sender, args) -> taskClaimCommand.onCommand(sender, null, "knk", args)
        );

        // Dedicated ItemScan entry point (docs/specs/items/IMPLEMENTATION_PLAN.md §5.1) - a
        // faster second way in alongside the standard /knk task-claim flow above, both
        // ultimately invoking the exact same claim/handler-dispatch logic in
        // KnkTaskClaimCommand.onCommand - no duplicated business logic.
        registry.register(
                new CommandMetadata("itemscan", "Claim and scan a held item for an ItemScan WorldTask", "/knk itemscan claim <linkCode>", "knk.tasks",
                        List.of("/knk itemscan claim ABC123")),
                (sender, args) -> {
                    if (args.length < 2 || !args[0].equalsIgnoreCase("claim")) {
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemscan claim <linkCode>");
                        return true;
                    }
                    return taskClaimCommand.onCommand(sender, null, "knk", new String[]{args[1]});
                }
        );

        // Dedicated KitScan entry point (docs/specs/kits/DESIGN.md §6.2), same shape as itemscan
        // above: a shortcut into the same KnkTaskClaimCommand.onCommand /knk task-claim uses.
        registry.register(
                new CommandMetadata("kitscan", "Claim and scan your inventory for a KitScan WorldTask", "/knk kitscan claim <linkCode>", "knk.tasks",
                        List.of("/knk kitscan claim ABC123")),
                (sender, args) -> {
                    if (args.length < 2 || !args[0].equalsIgnoreCase("claim")) {
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /knk kitscan claim <linkCode>");
                        return true;
                    }
                    return taskClaimCommand.onCommand(sender, null, "knk", new String[]{args[1]});
                }
        );

        KnkTaskStatusCommand taskStatusCommand = new KnkTaskStatusCommand(plugin, worldTasksApi);
        registry.register(
                new CommandMetadata("task-status", "Check world task status", "/knk task-status <id|linkCode>", "knk.tasks",
                        List.of("/knk task-status 1", "/knk task-status ABC123")),
                (sender, args) -> taskStatusCommand.onCommand(sender, null, "knk", args)
        );

        GateCommand gateCommand = new GateCommand(gateManager, gateStructuresApi, gateDoorsApi, userManager, usersCommandApi, districtGateLoader, gateDoorRegionCaptureHandler);
        registry.register(
                new CommandMetadata("gate", "Control and inspect gate structures", "/knk gate <open|close|info|list|passthrough|admin>", null,
                        List.of("/knk gate list", "/knk gate info <name>", "/knk gate open <name>", "/knk gate passthrough <default|instant|teleport>")),
                (sender, args) -> gateCommand.onCommand(sender, null, "knk", args)
        );

        // Register user management (developer request 2026-09-25, extended with group/perm
        // 2026-09-25 same day) - null top-level permission, same as gate, since it gates
        // coins/gems/xp/group/perm on their own separate nodes internally rather than one
        // umbrella (see UserManagementCommand's own javadoc).
        UserManagementCommand userManagementCommand = new UserManagementCommand(userAdminService);
        registry.register(
                new CommandMetadata("user", "View or edit a player's coins/gems/XP/rank/permissions",
                        "/knk user <player> info | coins|gems set|add|remove <amount> <reason> | xp set|add|remove <amount> [reason] | group add|remove <groupName> [duration] | perm grant|revoke <node> [duration]", null,
                        List.of("/knk user Steve info", "/knk user Steve coins add 100 event prize", "/knk user Steve xp set 50 promoted for good behavior", "/knk user Steve gems remove 10 refund reversed",
                                "/knk user Steve group add Royal 2h", "/knk user Steve perm grant knk.mode.staff")),
                (sender, args) -> userManagementCommand.onCommand(sender, null, "knk", args)
        );

        // Register teleport-to-player (developer request, same round as group/perm/freeze/
        // staffchat/msg - rebuild of the one real, working part of v1's PlayerTeleportCommand).
        TeleportToPlayerCommand teleportToPlayerCommand = new TeleportToPlayerCommand(plugin, usersDataAccess, rankHierarchy);
        registry.register(
                new CommandMetadata("tp", "Teleport to an online player", "/knk tp <player>", "knk.admin.tp",
                        List.of("/knk tp Steve")),
                (sender, args) -> teleportToPlayerCommand.onCommand(sender, args)
        );

        // Register help
        registry.register(
                new CommandMetadata("help", "Show available commands or command details", "/knk help [command]", null,
                        List.of("/knk help", "/knk help towns")),
                (sender, args) -> helpSubcommand.execute(sender, args)
        );

        refreshVanillaEnchantmentTokens();
                refreshRegistryCustomEnchantmentTokens();
        scheduleKnkIdRefresh();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show help when no args
            return helpSubcommand.execute(sender, new String[0]);
        }

        String subcommandName = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        var registered = registry.get(subcommandName);
        if (registered.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unknown command: " + subcommandName);
            sender.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/knk help" + 
                    ChatColor.GRAY + " to see available commands");
            return true;
        }

        CommandRegistry.RegisteredCommand cmd = registered.get();
        
        // Check permission
        if (cmd.metadata().permission() != null && !sender.hasPermission(cmd.metadata().permission())) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        return cmd.executor().execute(sender, subArgs);
    }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
                try {
                if (args.length == 0) {
                        return Collections.emptyList();
                }

                if (args.length == 1) {
                        List<String> rootCommands = registry.listAvailable(sender).stream()
                                        .map(c -> c.metadata().name())
                                        .sorted()
                                        .toList();
                        return filterByPrefix(rootCommands, args[0]);
                }

                String root = args[0].toLowerCase(Locale.ROOT);
                if ("user".equals(root)) {
                        return completeUserSubcommand(Arrays.copyOfRange(args, 1, args.length));
                }
                if (!"item".equals(root)) {
                        return Collections.emptyList();
                }

                return completeItemSubcommand(Arrays.copyOfRange(args, 1, args.length));
                } catch (Exception ex) {
                        plugin.getLogger().warning("Tab completion failed for /knk: " + ex.getMessage());
                        return Collections.emptyList();
                }
        }

        // /knk item rename|lore|enchantments - itemArgs is args with "item" already stripped, so
        // itemArgs[0] is the second-level subcommand name.
        private List<String> completeItemSubcommand(String[] itemArgs) {
                if (itemArgs.length == 0) {
                        return Collections.emptyList();
                }
                if (itemArgs.length == 1) {
                        return filterByPrefix(List.of("rename", "lore", "enchantments"), itemArgs[0]);
                }

                String sub = itemArgs[0].toLowerCase(Locale.ROOT);

                if ("lore".equals(sub) && itemArgs.length == 2) {
                        return filterByPrefix(List.of("add", "set", "remove"), itemArgs[1]);
                }

                if (!"enchantments".equals(sub) && !"enchantment".equals(sub)) {
                        return Collections.emptyList();
                }

                // Merged in from the former standalone /knk enchantments command (2026-09-23) -
                // args here is itemArgs with "enchantments" also stripped, so args[0] is list/
                // vanilla/search/apply, exactly matching this logic's original indexing before
                // the merge.
                return completeEnchantmentsSubcommand(Arrays.copyOfRange(itemArgs, 1, itemArgs.length));
        }

        private List<String> completeEnchantmentsSubcommand(String[] args) {
                if (args.length == 0) {
                        return Collections.emptyList();
                }
                if (args.length == 1) {
                        return filterByPrefix(List.of("list", "vanilla", "search", "apply"), args[0]);
                }

                String enchantmentsSubcommand = args[0].toLowerCase(Locale.ROOT);

                if ("list".equals(enchantmentsSubcommand) || "vanilla".equals(enchantmentsSubcommand)) {
                        if (args.length == 2) {
                                return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[1]);
                        }
                        if (args.length == 3) {
                                return filterByPrefix(List.of("10", "25", "50", "100"), args[2]);
                        }
                        return Collections.emptyList();
                }

                if ("search".equals(enchantmentsSubcommand) && args.length == 2) {
                        return filterByPrefix(List.of("id", "key", "displayName"), args[1]);
                }

                if ("search".equals(enchantmentsSubcommand)) {
                        refreshKnkIdsIfStale();

                        if (args.length == 3) {
                                String searchField = args[1].toLowerCase(Locale.ROOT);
                                return switch (searchField) {
                                        case "id" -> filterByPrefix(cachedKnkEnchantmentIds, args[2]);
                                        case "key" -> filterByPrefix(mergeSuggestions(
                                                        cachedKnkEnchantmentKeys,
                                                        cachedRegistryCustomEnchantmentTokens,
                                                        cachedVanillaEnchantmentTokens
                                                ), args[2]);
                                        case "displayname", "display_name", "display-name" -> filterByPrefix(cachedKnkEnchantmentDisplayNames, args[2]);
                                        default -> Collections.emptyList();
                                };
                        }

                        if (args.length == 4) {
                                return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[3]);
                        }

                        if (args.length == 5) {
                                return filterByPrefix(List.of("10", "25", "50", "100"), args[4]);
                        }

                        return Collections.emptyList();
                }

                if ("apply".equals(enchantmentsSubcommand)) {
                        if (args.length == 2) {
                                refreshKnkIdsIfStale();

                                List<String> suggestions = new ArrayList<>();
                                suggestions.addAll(cachedKnkEnchantmentIds);
                                suggestions.addAll(cachedKnkEnchantmentKeys);
                                suggestions.addAll(cachedRegistryCustomEnchantmentTokens);
                                suggestions.addAll(cachedVanillaEnchantmentTokens);
                                return filterByPrefix(suggestions, args[1]);
                        }

                        // Suggest common levels when user is likely entering the optional level
                        if (args.length >= 3) {
                                String current = args[args.length - 1];
                                if (current.isBlank() || current.chars().allMatch(Character::isDigit)) {
                                        return filterByPrefix(List.of("1", "2", "3", "4", "5"), current);
                                }
                        }
                }

                return Collections.emptyList();
        }

        private void refreshKnkIdsIfStale() {
                long now = System.currentTimeMillis();
                if ((now - lastKnkIdRefreshMillis) < KNK_ID_REFRESH_INTERVAL_MS) {
                        return;
                }
                scheduleKnkIdRefresh();
        }

        private void scheduleKnkIdRefresh() {
                if (!knkIdRefreshInProgress.compareAndSet(false, true)) {
                        return;
                }

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                                KnkEnchantmentSuggestionData suggestionData = fetchAllKnkSuggestionData();
                                if (!suggestionData.ids().isEmpty()) {
                                        cachedKnkEnchantmentIds = suggestionData.ids();
                                }
                                if (!suggestionData.keys().isEmpty()) {
                                        cachedKnkEnchantmentKeys = suggestionData.keys();
                                }
                                if (!suggestionData.displayNames().isEmpty()) {
                                        cachedKnkEnchantmentDisplayNames = suggestionData.displayNames();
                                }
                                lastKnkIdRefreshMillis = System.currentTimeMillis();
                        } catch (Exception ignored) {
                        } finally {
                                knkIdRefreshInProgress.set(false);
                        }
                });
        }

        private KnkEnchantmentSuggestionData fetchAllKnkSuggestionData() {
                List<String> ids = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                List<String> displayNames = new ArrayList<>();
                int page = 1;

                while (true) {
                        CompletableFuture<Page<KnkEnchantmentDefinition>> future = enchantmentDefinitionsDataAccess.listAsync(page, KNK_ID_PAGE_SIZE);
                        Page<KnkEnchantmentDefinition> result = future.join();
                        if (result == null || result.items() == null || result.items().isEmpty()) {
                                break;
                        }

                        for (KnkEnchantmentDefinition definition : result.items()) {
                                if (definition != null && definition.id() != null) {
                                        ids.add(String.valueOf(definition.id()));
                                        if (definition.key() != null && !definition.key().isBlank()) {
                                                keys.add(definition.key());
                                        }
                                        if (definition.displayName() != null && !definition.displayName().isBlank()) {
                                                displayNames.add(definition.displayName());
                                        }
                                }
                        }

                        int totalPages = result.pageSize() > 0
                                        ? Math.max(1, (int) Math.ceil((double) result.totalCount() / result.pageSize()))
                                        : 1;
                        if (page >= totalPages) {
                                break;
                        }

                        page++;
                }

                List<String> sortedIds = ids.stream().distinct().sorted(Comparator.comparingInt(Integer::parseInt)).toList();
                List<String> sortedKeys = keys.stream().distinct().sorted().toList();
                List<String> sortedDisplayNames = displayNames.stream().distinct().sorted().toList();
                return new KnkEnchantmentSuggestionData(sortedIds, sortedKeys, sortedDisplayNames);
        }

        private void refreshVanillaEnchantmentTokens() {
                cachedVanillaEnchantmentTokens = getVanillaEnchantmentTokensInternal();
        }

        private void refreshRegistryCustomEnchantmentTokens() {
                cachedRegistryCustomEnchantmentTokens = EnchantmentRegistry.getInstance()
                                .getAll()
                                .stream()
                                .map(enchantment -> enchantment.id())
                                .filter(id -> id != null && !id.isBlank())
                                .flatMap(id -> {
                                        if (id.contains(":")) {
                                                return java.util.stream.Stream.of(id);
                                        }
                                        return java.util.stream.Stream.of(id, "knk:" + id);
                                })
                                .distinct()
                                .sorted()
                                .toList();
        }

        private List<String> getVanillaEnchantmentTokensInternal() {
                List<String> tokens = new ArrayList<>();

                try {
                        for (Enchantment enchantment : Registry.ENCHANTMENT) {
                                collectVanillaToken(tokens, enchantment);
                        }
                } catch (Throwable ignored) {
                        for (Enchantment enchantment : Enchantment.values()) {
                                collectVanillaToken(tokens, enchantment);
                        }
                }

                return tokens.stream().distinct().sorted().toList();
        }

        private void collectVanillaToken(List<String> tokens, Enchantment enchantment) {
                if (enchantment == null || enchantment.getKey() == null) {
                        return;
                }

                NamespacedKey key = enchantment.getKey();
                if (!"minecraft".equals(key.getNamespace())) {
                        return;
                }

                tokens.add(key.getKey());
                tokens.add(key.toString());
                tokens.add(key.getKey().replace('_', '-'));
        }

        // /knk user <player> info|coins|gems|xp <set|add|remove> <amount> - userArgs is args
        // with "user" already stripped, so userArgs[0] is the player name.
        private List<String> completeUserSubcommand(String[] userArgs) {
                if (userArgs.length == 0) {
                        return Collections.emptyList();
                }
                if (userArgs.length == 1) {
                        List<String> onlineNames = Bukkit.getOnlinePlayers().stream()
                                        .map(Player::getName)
                                        .toList();
                        return filterByPrefix(onlineNames, userArgs[0]);
                }
                if (userArgs.length == 2) {
                        return filterByPrefix(List.of("info", "coins", "gems", "xp"), userArgs[1]);
                }
                if (userArgs.length == 3 && !"info".equalsIgnoreCase(userArgs[1])) {
                        return filterByPrefix(List.of("set", "add", "remove"), userArgs[2]);
                }
                return Collections.emptyList();
        }

        private List<String> filterByPrefix(List<String> values, String rawPrefix) {
                String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
                return values.stream()
                                .filter(v -> v != null && v.toLowerCase(Locale.ROOT).startsWith(prefix))
                                .distinct()
                                .sorted()
                                .toList();
        }

        private List<String> mergeSuggestions(List<String>... groups) {
                Set<String> merged = new LinkedHashSet<>();
                for (List<String> group : groups) {
                        if (group == null) {
                                continue;
                        }
                        merged.addAll(group);
                }
                return new ArrayList<>(merged);
        }

        private record KnkEnchantmentSuggestionData(
                        List<String> ids,
                        List<String> keys,
                        List<String> displayNames
        ) {}
}
