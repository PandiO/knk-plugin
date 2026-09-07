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
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.cache.CacheManager;
import net.knightsandkings.knk.paper.user.UserManager;
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
            UserManager userManager,
            UsersCommandApi usersCommandApi,
            DistrictGateLoader districtGateLoader,
            String serverId
    ) {
                this.plugin = plugin;
                this.enchantmentDefinitionsDataAccess = enchantmentDefinitionsDataAccess;
                this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
                this.minecraftMaterialRefsDataAccess = minecraftMaterialRefsDataAccess;
        this.helpSubcommand = new HelpSubcommand(registry);
        
        // Register health
        HealthCommand healthCommand = new HealthCommand(plugin, healthApi);
        registry.register(
                new CommandMetadata("health", "Check API backend health", "/knk health", "knk.admin"),
                (sender, args) -> healthCommand.onCommand(sender, null, "knk", new String[0])
        );
        
        // Register cache command
        if (cacheManager != null) {
            registry.register(
                new CommandMetadata("cache", "View cache statistics and health", "/knk cache", "knk.admin"),
                (sender, args) -> {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('§', cacheManager.getHealthSummary()));
                    return true;
                }
            );
        }
        
        // Register towns
        TownsDebugCommand townsCommand = new TownsDebugCommand(plugin, townsApi);
        registry.register(
                new CommandMetadata("towns", "List or search towns", "/knk towns list [page] [size]", "knk.admin",
                        List.of("/knk towns list", "/knk towns list 1 10")),
                (sender, args) -> townsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register town (alias for get by ID)
        registry.register(
                new CommandMetadata("town", "Get town details by ID", "/knk town <id>", "knk.admin",
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
                new CommandMetadata("districts", "List or search districts", "/knk districts list [page] [size]", "knk.admin",
                        List.of("/knk districts list", "/knk districts list 1 10")),
                (sender, args) -> districtsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register district (alias for get by ID)
        registry.register(
                new CommandMetadata("district", "Get district details by ID", "/knk district <id>", "knk.admin",
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
                new CommandMetadata("locations", "List or get locations", "/knk locations list <page> <size> | /knk locations <id>", "knk.admin",
                        List.of("/knk locations list 1 10", "/knk locations 5")),
                (sender, args) -> locationsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register location here
        LocationDebugCommand locationHereCommand = new LocationDebugCommand((org.bukkit.plugin.java.JavaPlugin) plugin);
        registry.register(
                new CommandMetadata("location", "Show your current location", "/knk location here", "knk.admin",
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

        // Register enchantment definitions
        EnchantmentDefinitionsDebugCommand enchantmentsCommand = new EnchantmentDefinitionsDebugCommand(plugin, enchantmentDefinitionsDataAccess);
        registry.register(
                new CommandMetadata(
                        "enchantments",
                        "List/search enchantment definitions and apply to held item",
                        "/knk enchantments list [page] [size] | /knk enchantments vanilla [page] [size] | /knk enchantments search <id|key|displayName> <value> [page] [size] | /knk enchantments apply <id|vanillaName|customKey> [level]",
                        "knk.admin",
                        List.of(
                                "/knk enchantments list 1 10",
                                "/knk enchantments vanilla 1 10",
                                "/knk enchantments search key minecraft:sharpness",
                                "/knk enchantments apply 1 3",
                                "/knk enchantments apply sharpness 3",
                                "/knk enchantments apply poison 2"
                        )
                ),
                (sender, args) -> enchantmentsCommand.onCommand(sender, null, "knk", args),
                "enchantment"
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
                        "knk.admin",
                        List.of(
                                "/knk itemblueprints list 1 10",
                                "/knk itemblueprints search name Sword",
                                "/knk itemblueprints search displayName Excalibur",
                                "/knk itemblueprints give 1",
                                "/knk itemblueprints give 1 SomePlayer"
                        )
                ),
                (sender, args) -> itemBlueprintsCommand.onCommand(sender, null, "knk", args),
                "itemblueprint"
        );
        
        // Register streets
        StreetsDebugCommand streetsCommand = new StreetsDebugCommand(plugin, streetsApi);
        registry.register(
                new CommandMetadata("streets", "List or search streets", "/knk streets list [page] [size]", "knk.admin",
                        List.of("/knk streets list", "/knk streets list 1 10")),
                (sender, args) -> streetsCommand.onCommand(sender, null, "knk", args)
        );
        
        // Register street (alias for get by ID)
        registry.register(
                new CommandMetadata("street", "Get street details by ID", "/knk street <id>", "knk.admin",
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
        
        KnkTaskStatusCommand taskStatusCommand = new KnkTaskStatusCommand(plugin, worldTasksApi);
        registry.register(
                new CommandMetadata("task-status", "Check world task status", "/knk task-status <id|linkCode>", "knk.tasks",
                        List.of("/knk task-status 1", "/knk task-status ABC123")),
                (sender, args) -> taskStatusCommand.onCommand(sender, null, "knk", args)
        );

        GateCommand gateCommand = new GateCommand(gateManager, gateStructuresApi, userManager, usersCommandApi, districtGateLoader);
        registry.register(
                new CommandMetadata("gate", "Control and inspect gate structures", "/knk gate <open|close|info|list|passthrough|admin>", null,
                        List.of("/knk gate list", "/knk gate info <name>", "/knk gate open <name>", "/knk gate passthrough <default|instant|teleport>")),
                (sender, args) -> gateCommand.onCommand(sender, null, "knk", args)
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
                if (!"enchantments".equals(root) && !"enchantment".equals(root)) {
                        return Collections.emptyList();
                }

                if (args.length == 2) {
                        return filterByPrefix(List.of("list", "vanilla", "search", "apply"), args[1]);
                }

                String enchantmentsSubcommand = args[1].toLowerCase(Locale.ROOT);

                if ("list".equals(enchantmentsSubcommand) || "vanilla".equals(enchantmentsSubcommand)) {
                        if (args.length == 3) {
                                return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[2]);
                        }
                        if (args.length == 4) {
                                return filterByPrefix(List.of("10", "25", "50", "100"), args[3]);
                        }
                        return Collections.emptyList();
                }

                if ("search".equals(enchantmentsSubcommand) && args.length == 3) {
                        return filterByPrefix(List.of("id", "key", "displayName"), args[2]);
                }

                if ("search".equals(enchantmentsSubcommand)) {
                        refreshKnkIdsIfStale();

                        if (args.length == 4) {
                                String searchField = args[2].toLowerCase(Locale.ROOT);
                                return switch (searchField) {
                                        case "id" -> filterByPrefix(cachedKnkEnchantmentIds, args[3]);
                                        case "key" -> filterByPrefix(mergeSuggestions(
                                                        cachedKnkEnchantmentKeys,
                                                        cachedRegistryCustomEnchantmentTokens,
                                                        cachedVanillaEnchantmentTokens
                                                ), args[3]);
                                        case "displayname", "display_name", "display-name" -> filterByPrefix(cachedKnkEnchantmentDisplayNames, args[3]);
                                        default -> Collections.emptyList();
                                };
                        }

                        if (args.length == 5) {
                                return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[4]);
                        }

                        if (args.length == 6) {
                                return filterByPrefix(List.of("10", "25", "50", "100"), args[5]);
                        }

                        return Collections.emptyList();
                }

                if ("apply".equals(enchantmentsSubcommand)) {
                        if (args.length == 3) {
                                refreshKnkIdsIfStale();

                                List<String> suggestions = new ArrayList<>();
                                suggestions.addAll(cachedKnkEnchantmentIds);
                                suggestions.addAll(cachedKnkEnchantmentKeys);
                                suggestions.addAll(cachedRegistryCustomEnchantmentTokens);
                                suggestions.addAll(cachedVanillaEnchantmentTokens);
                                return filterByPrefix(suggestions, args[2]);
                        }

                        // Suggest common levels when user is likely entering the optional level
                        if (args.length >= 4) {
                                String current = args[args.length - 1];
                                if (current.isBlank() || current.chars().allMatch(Character::isDigit)) {
                                        return filterByPrefix(List.of("1", "2", "3", "4", "5"), current);
                                }
                        }
                }

                return Collections.emptyList();
                } catch (Exception ex) {
                        plugin.getLogger().warning("Tab completion failed for /knk: " + ex.getMessage());
                        return Collections.emptyList();
                }
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
