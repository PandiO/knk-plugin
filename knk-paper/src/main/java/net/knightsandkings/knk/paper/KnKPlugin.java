package net.knightsandkings.knk.paper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.auth.BearerAuthProvider;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.client.KnkApiClient;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.ports.api.LocationsQueryApi;
import net.knightsandkings.knk.core.ports.api.EnchantmentDefinitionsQueryApi;
import net.knightsandkings.knk.core.ports.api.ItemBlueprintsQueryApi;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;
import net.knightsandkings.knk.core.ports.api.MinecraftMaterialRefsQueryApi;
import net.knightsandkings.knk.core.ports.api.GradesQueryApi;
import net.knightsandkings.knk.core.ports.api.TagsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.dataaccess.GradesDataAccess;
import net.knightsandkings.knk.core.domain.item.GradeCatalog;
import net.knightsandkings.knk.core.dataaccess.TagsDataAccess;
import net.knightsandkings.knk.core.dataaccess.DomainCatalogDataAccess;
import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuRefreshSchedule;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.paper.menu.AnvilCaptureManager;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuActionHandlers;
import net.knightsandkings.knk.paper.menu.MenuClickListener;
import net.knightsandkings.knk.paper.menu.MenuConditionHandlers;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceHandlers;
import net.knightsandkings.knk.paper.menu.MenuControlHintListener;
import net.knightsandkings.knk.paper.menu.MenuAutoRefreshTask;
import net.knightsandkings.knk.paper.menu.MenuDefinitionValidationRunner;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuVariableContext;
import net.knightsandkings.knk.paper.menu.example.ExampleDomainMenuFeature;
import net.knightsandkings.knk.paper.menu.content.HubMenuFeature;
import net.knightsandkings.knk.paper.menu.MenuLifecycleListener;
import net.knightsandkings.knk.paper.menu.MenuRenderer;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.menu.OpenMenuContextRegistry;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.ports.gates.GateControlPort;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionTransitionService;
import net.knightsandkings.knk.core.regions.SimpleRegionTransitionService;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.cache.CacheManager;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.bootstrap.EnchantmentBootstrap;
import net.knightsandkings.knk.paper.commands.AccountCommandRegistry;
import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.commands.ModeCommand;
import net.knightsandkings.knk.paper.config.ConfigLoader;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.dataaccess.DataAccessFactory;
import net.knightsandkings.knk.paper.gates.PaperGateControlAdapter;
import net.knightsandkings.knk.paper.gates.GateLoaderAdapter;
import net.knightsandkings.knk.paper.gates.GateAnimationTask;
import net.knightsandkings.knk.paper.gates.GateDisplayManager;
import net.knightsandkings.knk.paper.gates.GateDisplayOrphanCleanupTask;
import net.knightsandkings.knk.paper.gates.GateDisplayUpdateTask;
import net.knightsandkings.knk.paper.gates.GateDoorHitService;
import net.knightsandkings.knk.paper.gates.GateFireDamageTask;
import net.knightsandkings.knk.paper.gates.GateFireSystem;
import net.knightsandkings.knk.paper.gates.GatePassThroughService;
import net.knightsandkings.knk.paper.gates.GateStateSyncTask;
import net.knightsandkings.knk.paper.gates.HealthSystem;
import net.knightsandkings.knk.paper.http.RegionHttpServer;
import net.knightsandkings.knk.paper.listeners.ChatCaptureListener;
import net.knightsandkings.knk.paper.listeners.GateDamageConsequenceListener;
import net.knightsandkings.knk.paper.listeners.GateEventListener;
import net.knightsandkings.knk.paper.listeners.GatePassThroughConsequenceListener;
import net.knightsandkings.knk.paper.listeners.JoinLoadingRestrictionListener;
import net.knightsandkings.knk.paper.listeners.ModeListener;
import net.knightsandkings.knk.paper.listeners.PlayerListener;
import net.knightsandkings.knk.paper.listeners.RegionTaskEventListener;
import net.knightsandkings.knk.paper.listeners.UserAccountListener;
import net.knightsandkings.knk.paper.listeners.WorldGuardRegionListener;
import net.knightsandkings.knk.paper.listeners.WorldTaskChatListener;
import net.knightsandkings.knk.paper.listeners.WorldTaskLocationSelectionListener;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionTracker;
import net.knightsandkings.knk.paper.regions.WorldGuardCombatSafezones;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import net.knightsandkings.knk.paper.tasks.TempRegionRetentionTask;
import net.knightsandkings.knk.paper.tasks.WgRegionIdTaskHandler;
import net.knightsandkings.knk.paper.tasks.LocationTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.HeadlessWorldTaskPoller;
import net.knightsandkings.knk.paper.tasks.PlayerNotificationPoller;
import net.knightsandkings.knk.paper.tasks.GateBlockScanTaskHandler;
import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import net.knightsandkings.knk.paper.tasks.ItemScanTaskHandler;
import net.knightsandkings.knk.paper.tasks.KitScanTaskHandler;
import net.knightsandkings.knk.paper.user.JoinLoadingGuard;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

public class KnKPlugin extends JavaPlugin {
    private KnkApiClient apiClient;
    private RegionHttpServer regionHttpServer;
    private KnkConfig config;
    private CacheManager cacheManager;
    private DataAccessFactory dataAccessFactory;
    private TownsQueryApi townsQueryApi;
    private LocationsQueryApi locationsQueryApi;
    private EnchantmentDefinitionsQueryApi enchantmentDefinitionsQueryApi;
    private ItemBlueprintsQueryApi itemBlueprintsQueryApi;
    private KitsQueryApi kitsQueryApi;
    private KitsCommandApi kitsCommandApi;
    private MenuTemplatesQueryApi menuTemplatesQueryApi;
    private MinecraftMaterialRefsQueryApi minecraftMaterialRefsQueryApi;
    private DistrictsQueryApi districtsQueryApi;
    private StreetsQueryApi streetsQueryApi;
    private StructuresQueryApi structuresQueryApi;
    private DomainsQueryApi domainsQueryApi;
    private GradesQueryApi gradesQueryApi;
    private TagsQueryApi tagsQueryApi;
    private DomainCatalogQueryApi domainCatalogQueryApi;
    private UsersQueryApi usersQueryApi;
    private UsersCommandApi usersCommandApi;
    private UserAccountApi userAccountApi;
    private PermissionsApi permissionsApi;
    private UsersDataAccess usersDataAccess;
    private TownsDataAccess townsDataAccess;
    private EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess;
    private ItemBlueprintsDataAccess itemBlueprintsDataAccess;
    private KitsDataAccess kitsDataAccess;
    private MenuTemplatesDataAccess menuTemplatesDataAccess;
    private net.knightsandkings.knk.paper.kit.KitGrantFlow kitGrantFlow;
    private net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess titleBracketsDataAccess;
    private net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess permissionGroupsDataAccess;
    private net.knightsandkings.knk.paper.user.UserAdminService userAdminService;
    private net.knightsandkings.knk.paper.user.SalaryPayoutScheduler salaryPayoutScheduler;
    private MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
    private PermissionsDataAccess permissionsDataAccess;
    private KnkPermissible knkPermissible;
    private JoinLoadingGuard joinLoadingGuard;
    private ModeService modeService;
    private net.knightsandkings.knk.paper.user.AdminFreezeManager adminFreezeManager;
    private net.knightsandkings.knk.paper.user.MessagingService messagingService;
    private net.knightsandkings.knk.paper.user.SpyService spyService;
    private net.knightsandkings.knk.paper.user.IgnoreService ignoreService;
    private net.knightsandkings.knk.paper.user.PrivateMessageLogger privateMessageLogger;
    private net.knightsandkings.knk.paper.commands.support.VisiblePlayers visiblePlayers;
    private net.knightsandkings.knk.paper.commands.support.RankHierarchy rankHierarchy;
    private GradesDataAccess gradesDataAccess;
    private TagsDataAccess tagsDataAccess;
    private DomainCatalogDataAccess domainCatalogDataAccess;
    private MenuSessionRegistry menuSessionRegistry;
    private OpenMenuContextRegistry openMenuContextRegistry;
    private MenuService menuService;
    private ActionRegistry<MenuActionContext> menuActionRegistry;
    private ConditionRegistry<MenuActionContext> menuConditionRegistry;
    private MenuContentSourceRegistry<MenuContentSourceContext> menuContentSourceRegistry;
    private MenuVariableProviderRegistry<org.bukkit.entity.Player> menuVariableRegistry;
    private WorldTasksApi worldTasksApi;
    private GateStructuresApi gateStructuresApi;
    private GateDoorsApi gateDoorsApi;
    private GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler;
    private GateManager gateManager;
    private GateStateSyncTask gateStateSyncTask;
    private GateDisplayManager gateDisplayManager;
    private DistrictGateLoader districtGateLoader;
    private WorldTaskHandlerRegistry worldTaskHandlerRegistry;
    private HeadlessWorldTaskPoller headlessWorldTaskPoller;
    private PlayerNotificationPoller playerNotificationPoller;
    private UserManager userManager;
    private ChatCaptureManager chatCaptureManager;
    private AnvilCaptureManager anvilCaptureManager;
    private CommandCooldownManager cooldownManager;
    private EnchantmentBootstrap.EnchantmentRuntime enchantmentRuntime;
    private ExecutorService regionLookupExecutor;
    private TempRegionRetentionTask tempRegionRetentionTask;
    
    @Override
    public void onEnable() {
        try {
            // Load and validate config
            saveDefaultConfig();
            config = ConfigLoader.load(getConfig());
            getLogger().info("Configuration loaded successfully");
            getLogger().info("API Base URL: " + config.api().baseUrl());
            
            // Create auth provider based on config
            AuthProvider authProvider = createAuthProvider(config.api().auth());
            
            // Build API client
            apiClient = KnkApiClient.builder()
                .baseUrl(config.api().baseUrl())
                .authProvider(authProvider)
                .connectTimeout(config.api().timeouts().connectDuration())
                .readTimeout(config.api().timeouts().readDuration())
                .writeTimeout(config.api().timeouts().writeDuration())
                .debugLogging(config.api().debugLogging())
                .allowUntrustedSsl(config.api().allowUntrustedSsl())
                .build();
            
            getLogger().info("API client initialized");
            if (config.api().allowUntrustedSsl()) {
                getLogger().warning("WARNING: SSL certificate validation is DISABLED. Only use in development!");
            }
            
            // Wire TownsQueryApi from client
            this.townsQueryApi = apiClient.getTownsQueryApi();
            this.locationsQueryApi = apiClient.getLocationsQueryApi();
            this.enchantmentDefinitionsQueryApi = apiClient.getEnchantmentDefinitionsQueryApi();
            this.itemBlueprintsQueryApi = apiClient.getItemBlueprintsQueryApi();
            this.kitsQueryApi = apiClient.getKitsQueryApi();
            this.kitsCommandApi = apiClient.getKitsCommandApi();
            this.menuTemplatesQueryApi = apiClient.getMenuTemplatesQueryApi();
            this.minecraftMaterialRefsQueryApi = apiClient.getMinecraftMaterialRefsQueryApi();
            this.districtsQueryApi = apiClient.getDistrictsQueryApi();
            this.streetsQueryApi = apiClient.getStreetsQueryApi();
            this.structuresQueryApi = apiClient.getStructuresQueryApi();
            this.domainsQueryApi = apiClient.getDomainsQueryApi();
            this.gradesQueryApi = apiClient.getGradesQueryApi();
            this.tagsQueryApi = apiClient.getTagsQueryApi();
            this.domainCatalogQueryApi = apiClient.getDomainCatalogQueryApi();
            this.usersQueryApi = apiClient.getUsersQueryApi();
            this.usersCommandApi = apiClient.getUsersCommandApi();
            this.userAccountApi = apiClient.getUserAccountApi();
            this.permissionsApi = apiClient.getPermissionsApi();
            this.worldTasksApi = apiClient.getWorldTasksApi();
            this.gateStructuresApi = apiClient.getGateStructuresApi();
            this.gateDoorsApi = apiClient.getGateDoorsApi();
            this.gateDoorRegionCaptureHandler = new GateDoorRegionCaptureHandler(this, this.gateDoorsApi);
            getLogger().info("TownsQueryApi wired from API client");
            getLogger().info("LocationsQueryApi wired from API client");
            getLogger().info("EnchantmentDefinitionsQueryApi wired from API client");
            getLogger().info("ItemBlueprintsQueryApi wired from API client");
            getLogger().info("MenuTemplatesQueryApi wired from API client");
            getLogger().info("MinecraftMaterialRefsQueryApi wired from API client");
            getLogger().info("DistrictsQueryApi wired from API client");
            getLogger().info("StreetsQueryApi wired from API client");
            getLogger().info("StructuresQueryApi wired from API client");
            getLogger().info("DomainsQueryApi wired from API client");
            getLogger().info("GradesQueryApi wired from API client");
            getLogger().info("TagsQueryApi wired from API client");
            getLogger().info("DomainCatalogQueryApi wired from API client");
            getLogger().info("UsersQueryApi wired from API client");
            getLogger().info("UsersCommandApi wired from API client");
            getLogger().info("PermissionsApi wired from API client");
            getLogger().info("WorldTasksApi wired from API client");
            getLogger().info("GateStructuresApi wired from API client");
            
            // Initialize GateManager (no-arg constructor for dependency injection flexibility)
            this.gateManager = new GateManager();
            getLogger().info("GateManager initialized");

            // Mechanism 1 kill switch (Decision 5, ROTATION_GAP_FILL_DESIGN.md): default on, lets
            // an admin disable the automatic rasterized gap-fill server-wide without a code
            // deploy, since it runs unconditionally for every diagonal-hinge ROTATION gate.
            // Read once here and reused for GateAnimationTask and GateStateSyncTask below.
            boolean rotationGapFillRasterizationEnabled =
                getConfig().getBoolean("gates.rotationGapFill.rasterization-enabled", true);

            // World/DB sync (docs/features/gate-structure-animation/GATE_WORLD_SYNC_DESIGN.md):
            // the periodic health-check (Mechanism C) never forces a chunk load, so its cost
            // tracks currently-active gates, not total gate count - safe to run fairly often.
            int gateStateSyncIntervalSeconds = getConfig().getInt("gates.state-sync-interval-seconds", 120);
            long worldSyncHealthCheckIntervalSeconds =
                getConfig().getLong("gates.world-sync.health-check-interval-seconds", 300L);
            int worldSyncHealthCheckBatchSize =
                getConfig().getInt("gates.world-sync.health-check-batch-size", 15);
            this.gateStateSyncTask = new GateStateSyncTask(
                gateManager, gateDoorsApi, this, gateStateSyncIntervalSeconds, org.bukkit.Material.STONE,
                rotationGapFillRasterizationEnabled, worldSyncHealthCheckIntervalSeconds, worldSyncHealthCheckBatchSize
            );

            // Constructed after gateStateSyncTask: DistrictGateLoader hands it every district's
            // freshly-(re)loaded gates for a world/DB sync check-and-fix pass (Mechanism B) -
            // the primary correction path, bounded to whatever district a player just entered.
            GateLoaderAdapter gateLoader = new GateLoaderAdapter(gateManager);
            gateManager.setReloadAction(() -> gateLoader.loadAll(gateStructuresApi));
            this.districtGateLoader = new DistrictGateLoader(gateLoader, gateStructuresApi, gateStateSyncTask);

            this.gateDisplayManager = new GateDisplayManager(this);
            getLogger().info("GateDisplayManager initialized");

            gateManager.reloadGates().whenComplete((unused, error) -> {
                if (error != null) {
                    getLogger().warning("Failed to load gates from API: " + error.getMessage());
                } else {
                    getLogger().info("Loaded " + gateManager.getAllGates().size() + " gate(s) from API");
                    // Block edits and entity spawning must happen on the main thread; the reload future may complete off it.
                    getServer().getScheduler().runTask(this, () -> {
                        // Mechanism A: diagnose-only - logs mismatches for whatever's already
                        // loaded (typically spawn-adjacent), never forces a chunk load or a
                        // block write. Correction is left to Mechanism B (district-load) or
                        // Mechanism C (periodic health-check, started below via gateStateSyncTask.start()).
                        gateStateSyncTask.logStartupSyncDiagnostics();
                        for (org.bukkit.World world : getServer().getWorlds()) {
                            gateDisplayManager.cleanupOrphans(world, gateManager);
                        }
                        gateDisplayManager.syncAll(gateManager);
                        getLogger().info("Gate info displays synced for " + gateManager.getAllGates().size() + " gate(s)");
                    });
                }
            });

            
            // Initialize cache manager
            this.cacheManager = new CacheManager(config.cache().ttl());
            getLogger().info("Cache manager initialized with TTL: " + config.cache().ttl());
            
                        // Initialize UserManager for account management (Phase 2)
                        this.userManager = new UserManager(
                            this,
                            userAccountApi,
                            usersQueryApi,
                            cacheManager.getUserCache(),  // Legacy cache for PlayerListener compatibility
                            getLogger(),
                            config.account(),
                            config.messages()
                        );
                        getLogger().info("UserManager initialized for account management");
            
            // Initialize ChatCaptureManager for secure input (Phase 3)
            this.chatCaptureManager = new ChatCaptureManager(this, config, getLogger());
            getLogger().info("ChatCaptureManager initialized for secure chat input");
            
            // Initialize CommandCooldownManager for rate limiting (Phase 5)
            this.cooldownManager = new CommandCooldownManager(getLogger());
            getLogger().info("CommandCooldownManager initialized for rate limiting");
            
            // Start cooldown cleanup task (runs every N minutes as configured)
            int cleanupInterval = config.account().cooldowns().cleanupIntervalMinutes();
            int cleanupTicks = cleanupInterval * 60 * 20; // Convert minutes to ticks (20 ticks/sec)
            getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> cooldownManager.cleanup(3600), // Remove cooldowns older than 1 hour
                cleanupTicks,
                cleanupTicks
            );
            getLogger().info("Cooldown cleanup task scheduled (every " + cleanupInterval + " minutes)");
            
            // Register chat capture listener
            getServer().getPluginManager().registerEvents(
                new ChatCaptureListener(chatCaptureManager),
                this
            );
            getLogger().info("ChatCaptureListener registered");

            // Initialize WorldTask handler registry and register handlers
            this.worldTaskHandlerRegistry = new WorldTaskHandlerRegistry();
            
            // Register WgRegionId handler
            WgRegionIdTaskHandler wgRegionIdHandler = new WgRegionIdTaskHandler(worldTasksApi, this);
            worldTaskHandlerRegistry.registerHandler(wgRegionIdHandler);
            
            // Register Location handler
            LocationTaskHandler locationHandler = new LocationTaskHandler(worldTasksApi, this);
            worldTaskHandlerRegistry.registerHandler(locationHandler);
            worldTaskHandlerRegistry.registerHandler("LocationSelection", locationHandler);

            // Register ItemScan handler (docs/specs/items/IMPLEMENTATION_PLAN.md §5) - player-
            // driven, not headless (see ItemScanTaskHandler's javadoc), so it's registered here
            // alongside Location/WgRegionId rather than on headlessWorldTaskPoller below.
            // ItemBlueprint has no dedicated "scan result" field the way GateDoor has
            // BlockSnapshots, so the live FormConfiguration binds the WorldTask panel onto the
            // real DefaultDisplayName field instead (see ACTIVE_SESSIONS.md's Items Phase 4/5
            // row) - meaning the FormField's own fieldName ("defaultDisplayName") will never
            // match this handler's field-name registration. Also register by taskType
            // ("ItemScan"), the same dual-registration WorldTaskHandlerRegistry.getHandler
            // already supports and LocationTaskHandler already uses (its "LocationSelection"
            // alias below) for exactly this kind of mismatch.
            ItemScanTaskHandler itemScanHandler = new ItemScanTaskHandler(worldTasksApi, this);
            worldTaskHandlerRegistry.registerHandler(itemScanHandler);
            worldTaskHandlerRegistry.registerHandler("ItemScan", itemScanHandler);

            // Register KitScan handler (docs/specs/kits/DESIGN.md §6) - player-driven like
            // ItemScan. This registration is what makes the generic /knk task-claim <linkCode>
            // work for KitScan (/knk kitscan claim is only a shortcut into the same claim
            // command). Registered by taskType too, for the same reason as ItemScan above: the
            // Kit form binds the WorldTask panel onto a real Kit field, so the claimed task's
            // fieldName won't be "KitScan" and KnkTaskClaimCommand resolves by taskType first.
            KitScanTaskHandler kitScanHandler = new KitScanTaskHandler(worldTasksApi, this);
            worldTaskHandlerRegistry.registerHandler(kitScanHandler);
            worldTaskHandlerRegistry.registerHandler("KitScan", kitScanHandler);

            // Start lightweight HTTP server for region rename callbacks (default port 8081)
            int httpPort = 8081;
            try {
                httpPort = this.getConfig().getInt("region-http.port", 8081);
            } catch (Exception ignored) { }
            regionHttpServer = new RegionHttpServer(this, wgRegionIdHandler, httpPort);
            regionHttpServer.start();

            // Start temp region retention task (14 day retention policy)
            tempRegionRetentionTask = new TempRegionRetentionTask(this, 14);
            tempRegionRetentionTask.start();

            // Start headless WorldTask poller (webapp-initiated tasks that need no player)
            headlessWorldTaskPoller = new HeadlessWorldTaskPoller(worldTasksApi, this);
            headlessWorldTaskPoller.registerHandler(new GateBlockScanTaskHandler(gateDoorsApi, worldTasksApi, this));
            headlessWorldTaskPoller.start();

            // Promotion effects etc. for writes made through the web app (not a plugin command)
            playerNotificationPoller = new PlayerNotificationPoller(
                apiClient.getPlayerNotificationsApi(), this);
            playerNotificationPoller.start();
            
            getLogger().info("WorldTaskHandlerRegistry initialized with handlers");
            
            // Initialize cache manager and data access factory from config
            this.cacheManager = new CacheManager(config.cache().ttl());
            this.dataAccessFactory = new DataAccessFactory(config.cache().entities());
            this.usersDataAccess = dataAccessFactory.createUsersDataAccess(
                cacheManager.getUserCache(),
                usersQueryApi,
                usersCommandApi
            );
            this.townsDataAccess = dataAccessFactory.createTownsDataAccess(
                cacheManager.getTownCache(),
                townsQueryApi
            );
            this.enchantmentDefinitionsDataAccess = dataAccessFactory.createEnchantmentDefinitionsDataAccess(
                config.cache().ttl(),
                enchantmentDefinitionsQueryApi
            );
            this.itemBlueprintsDataAccess = dataAccessFactory.createItemBlueprintsDataAccess(
                config.cache().ttl(),
                itemBlueprintsQueryApi
            );
            this.kitsDataAccess = dataAccessFactory.createKitsDataAccess(
                config.cache().ttl(),
                kitsQueryApi
            );
            this.menuTemplatesDataAccess = dataAccessFactory.createMenuTemplatesDataAccess(
                config.cache().ttl(),
                menuTemplatesQueryApi
            );
            this.permissionsDataAccess = dataAccessFactory.createPermissionsDataAccess(permissionsApi);
            this.knkPermissible = new KnkPermissible(cacheManager.getUserCache(), permissionsDataAccess);
            this.joinLoadingGuard = new JoinLoadingGuard(this, knkPermissible);
            this.modeService = new ModeService(this, knkPermissible, cacheManager.getUserCache(), usersCommandApi);
            this.adminFreezeManager = new net.knightsandkings.knk.paper.user.AdminFreezeManager();
            initPrivateMessaging();
            this.rankHierarchy = new net.knightsandkings.knk.paper.commands.support.RankHierarchy(usersQueryApi);
            this.minecraftMaterialRefsDataAccess = dataAccessFactory.createMinecraftMaterialRefsDataAccess(
                config.cache().ttl(),
                minecraftMaterialRefsQueryApi
            );
            this.gradesDataAccess = dataAccessFactory.createGradesDataAccess(
                config.cache().ttl(),
                gradesQueryApi
            );
            // KNG-6: the grade table (enchant-book level-cap divisors), readable synchronously from click handlers.
            startGradeCatalogRefresh();
            this.tagsDataAccess = dataAccessFactory.createTagsDataAccess(
                config.cache().ttl(),
                tagsQueryApi
            );
            this.domainCatalogDataAccess = dataAccessFactory.createDomainCatalogDataAccess(
                config.cache().ttl(),
                domainCatalogQueryApi
            );
            getLogger().info("Cache manager initialized with TTL: " + config.cache().ttl());
            getLogger().info("Data access factory initialized with entity-specific settings");

            // InventoryMenu Phase 7 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md,
            // DESIGN_REVIEW.md §2.1 (updated)/§2.5): the in-house anvil-capture
            // component replacing ChatCaptureManager for InventoryMenu's search/
            // filter text input specifically - built before MenuService since
            // MenuService now depends on it directly.
            this.anvilCaptureManager = new AnvilCaptureManager(this);
            getServer().getPluginManager().registerEvents(anvilCaptureManager, this);
            getLogger().info("InventoryMenu anvil-capture component initialized (Phase 7)");

            // InventoryMenu Phase 2 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md):
            // rendering engine wiring, built on Phase 1's menuTemplatesDataAccess above.
            this.menuSessionRegistry = new MenuSessionRegistry();
            this.openMenuContextRegistry = new OpenMenuContextRegistry();
            // InventoryMenu Phase 8 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md):
            // MenuContentSourceRegistry wired before MenuRenderer since it now
            // depends on it directly - a content-source-backed section's page
            // content comes from a real paged/cursor query against the
            // registered source, not from an in-memory items() list. The one
            // real source shipped with this phase is backed by the already-
            // existing itemBlueprintsDataAccess gateway above, not a synthetic
            // dataset.
            this.menuContentSourceRegistry = new MenuContentSourceRegistry<>();
            // InventoryMenu Phase 6 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md,
            // DESIGN_REVIEW.md §2.2) action/condition registries; Phase 7 extends the
            // same two in place with the preset library.
            this.menuActionRegistry = new ActionRegistry<>();
            this.menuConditionRegistry = new ConditionRegistry<>();
            // InventoryMenu Phase 9 (E2): getter-chain roots ($player$ + feature roots).
            this.menuVariableRegistry = new MenuVariableProviderRegistry<>();
            MenuFeatureRegistries menuRegistries = new MenuFeatureRegistries(
                menuActionRegistry, menuConditionRegistry, menuContentSourceRegistry, menuVariableRegistry
            );

            // InventoryMenu Phase 9 (E2): every menu feature registers its roots,
            // content sources, actions and conditions HERE - before
            // MenuDefinitionValidationRunner below, which locks all four registries
            // (a later registration throws). Engine defaults first. Siege Phase 8b:
            // add the SiegeMenuFeature to this list.
            // Content port CP2: /kit and the kits.overview menu share one grant path.
            this.kitGrantFlow = new net.knightsandkings.knk.paper.kit.KitGrantFlow(
                MenuService.mainThreadExecutor(this), kitsCommandApi, itemBlueprintsDataAccess,
                minecraftMaterialRefsDataAccess, knkPermissible, cacheManager.getUserCache()
            );
            // Content port CP3/CP8: title brackets are seeded data - cache the list for 10 minutes.
            this.titleBracketsDataAccess = new net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess(
                apiClient.getTitleBracketsQueryApi(), java.time.Duration.ofMinutes(10)
            );
            this.permissionGroupsDataAccess = new net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess(
                apiClient.getPermissionGroupsQueryApi(), java.time.Duration.ofMinutes(2), java.time.Clock.systemUTC()
            );
            // Content port CP8: /knk user, /freeze|/unfreeze and the Player manager share one service.
            this.userAdminService = new net.knightsandkings.knk.paper.user.UserAdminService(
                MenuService.mainThreadExecutor(this), usersDataAccess, usersCommandApi, apiClient.getPermissionGroupsQueryApi(),
                rankHierarchy, modeService, adminFreezeManager,
                // After a group/title change: redraw the target's tab-list team and footer (KNG-7).
                (player, summary) -> net.knightsandkings.knk.paper.utils.ScoreboardUtil.setScoreboard(List.of(player), knkPermissible, summary)
            );
            // Salary on join (offline gap) and every hour online; the scoreboard is redrawn after a payout.
            this.salaryPayoutScheduler = new net.knightsandkings.knk.paper.user.SalaryPayoutScheduler(
                this, usersCommandApi, cacheManager.getUserCache(), usersDataAccess,
                (player, summary) -> net.knightsandkings.knk.paper.utils.ScoreboardUtil.setScoreboard(List.of(player), knkPermissible, summary)
            );
            getServer().getPluginManager().registerEvents(salaryPayoutScheduler, this);
            salaryPayoutScheduler.start();
            // Rank changes made outside the plugin (web app, expiring temporary rank) show right away.
            if (playerNotificationPoller != null) {
                playerNotificationPoller.setRankChangedHandler(userAdminService::resyncDisplay);
            }
            List<MenuFeature> menuFeatures = List.of(
                registries -> {
                    MenuVariableContext.registerDefaults(registries.variables());
                    MenuContentSourceHandlers.registerDefaults(registries.contentSources(), itemBlueprintsDataAccess);
                    MenuActionHandlers.registerDefaults(registries.actions());
                    MenuConditionHandlers.registerDefaults(registries.conditions());
                },
                new ExampleDomainMenuFeature(),
                new HubMenuFeature(),
                new net.knightsandkings.knk.paper.menu.content.KitsMenuFeature(
                    kitsDataAccess, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess, kitGrantFlow,
                    java.time.Clock.systemUTC()),
                new net.knightsandkings.knk.paper.menu.content.ProfileMenuFeature(
                    usersQueryApi, cacheManager.getUserCache(), titleBracketsDataAccess),
                new net.knightsandkings.knk.paper.menu.content.ItemsCatalogMenuFeature(
                    itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess, apiClient.getCategoriesQueryApi(),
                    java.time.Clock.systemUTC()),
                new net.knightsandkings.knk.paper.menu.content.PremiumMenuFeature(
                    permissionGroupsDataAccess, usersQueryApi, cacheManager.getUserCache()),
                new net.knightsandkings.knk.paper.menu.content.UserManagerMenuFeature(
                    userAdminService, usersQueryApi, cacheManager.getUserCache(), titleBracketsDataAccess,
                    permissionGroupsDataAccess, org.bukkit.Bukkit::getOnlinePlayers)
            );
            menuFeatures.forEach(feature -> feature.registerMenuHandlers(menuRegistries));

            MenuRenderer menuRenderer = new MenuRenderer(
                minecraftMaterialRefsDataAccess, menuContentSourceRegistry, menuConditionRegistry, menuVariableRegistry,
                MenuService.mainThreadExecutor(this)
            );
            this.menuService = new MenuService(
                this, menuTemplatesDataAccess, menuSessionRegistry, openMenuContextRegistry, menuRenderer,
                anvilCaptureManager, new MenuRefreshSchedule()
            );
            getLogger().info("InventoryMenu rendering engine initialized (Phase 2 + 9)");

            getServer().getPluginManager().registerEvents(
                new MenuClickListener(
                    openMenuContextRegistry, menuSessionRegistry, menuActionRegistry, menuConditionRegistry, menuService,
                    menuVariableRegistry
                ), this
            );
            getServer().getPluginManager().registerEvents(
                new MenuLifecycleListener(menuService, openMenuContextRegistry), this
            );
            getServer().getPluginManager().registerEvents(
                new MenuControlHintListener(openMenuContextRegistry), this
            );
            // InventoryMenu Phase 9 (E4): one sync task repaints every due open menu per tick.
            new MenuAutoRefreshTask(menuService).runTaskTimer(this, 1L, 1L);
            getLogger().info("InventoryMenu conditional actions + preset library initialized (Phase 6 + 7)");

            // InventoryMenu Phase 3 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md,
            // DESIGN_REVIEW.md §1) + Phase 6: validate every registered menu's variable
            // bindings and action/condition registry references now, at enable, not
            // lazily on first render - a broken menu is blocked in menuService and
            // refuses to open for any player, rather than surfacing as a silent blank/
            // literal-text tooltip or a click-time failure the first time someone
            // happens to open it or click it.
            MenuDefinitionValidationRunner.runAtStartup(menuTemplatesDataAccess, menuService, getLogger(), menuRegistries);
            getLogger().info("InventoryMenu variable resolution + load-time validation initialized (Phase 3 + 6 + 8 + 9)");

            // Create domain resolver for mapping WG region IDs to domain entities. Built before the
            // enchantment runtime, whose Town/District combat safezones (KNG-11) read it; the
            // constructor does no I/O.
            RegionDomainResolver regionDomainResolver = new RegionDomainResolver(
                townsQueryApi,
                districtsQueryApi,
                structuresQueryApi,
                domainsQueryApi,
                cacheManager.getTownCache(),
                cacheManager.getDistrictCache(),
                cacheManager.getStructureCache()
            );
            
            // Wire resolver into cache manager for metrics tracking
            cacheManager.setRegionResolver(regionDomainResolver);

            // KNG-11: no combat exemption on main yet. When the siege minigame lands, exempt pairs its
            // SiegeCombatListener governs, or enchantments stop working in sieges fought in towns.
            initializeEnchantmentRuntime(new WorldGuardCombatSafezones(regionDomainResolver, (attacker, victim) -> false));
            getLogger().info("Registered custom enchantment runtime listeners and /ce command");

            // Register commands
            registerCommands();

            // Register region listeners (WorldGuard)
            // Dedicated executor for region lookup (API prefetch); daemon threads to avoid blocking shutdown.
            regionLookupExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "knk-region-lookup");
                    t.setDaemon(true);
                    return t;
                }
            );
            
            // Create gate control adapter for handling gate open/close
            GateControlPort gateControlPort = new PaperGateControlAdapter(this);
            
            // Create region transition service with both resolver and gate control. The third
            // argument loads a District's gates on demand the first time a player is resolved
            // into it, so a gate created/edited after server start doesn't need a manual
            // /knk gate admin reload - see DistrictGateLoader.
            RegionTransitionService regionTransitionService = new SimpleRegionTransitionService(
                regionDomainResolver, gateControlPort,
                enteredDomains -> enteredDomains.stream()
                    .filter(domain -> "District".equalsIgnoreCase(domain.domainType()))
                    .forEach(domain -> {
                        if (domain.id() == null) {
                            getLogger().warning("Entered district '" + domain.name()
                                + "' (wgRegionId=" + domain.wgRegionId()
                                + ") resolved with a null domain id; cannot load its gates.");
                            return;
                        }
                        districtGateLoader.loadIfNotAlreadyLoaded(domain.id());
                    })
            );
            
            // Wire tracker and listener
            WorldGuardRegionTracker regionTracker = new WorldGuardRegionTracker(
                regionTransitionService,
                regionDomainResolver,
                regionLookupExecutor,
                this,  // Plugin instance for scheduler access
                Logger.getLogger(WorldGuardRegionTracker.class.getName()),
                true  // Enable console logging; set to false to disable
            );
            registerEvents(regionTracker);

            HealthSystem healthSystem = new HealthSystem(gateDoorsApi, this, gateDisplayManager, gateManager);
            GateDoorHitService gateDoorHitService = new GateDoorHitService(gateManager);

            long fireDurationMillis = getConfig().getLong("gates.fire-duration-seconds", 8) * 1000L;
            double fireDamagePerTick = getConfig().getDouble("gates.fire-damage-per-tick", 2.0);
            GateFireSystem gateFireSystem = new GateFireSystem(healthSystem, gateManager, fireDurationMillis, fireDamagePerTick);

            getServer().getPluginManager().registerEvents(new GateEventListener(gateDoorHitService), this);
            getServer().getPluginManager().registerEvents(new GateDamageConsequenceListener(healthSystem, gateFireSystem), this);

            int passThroughInstantOpenRadius = getConfig().getInt("gates.passthrough-instant-open-radius-blocks", 1);
            int passThroughInstantOpenTimeoutSeconds = getConfig().getInt("gates.passthrough-instant-open-timeout-seconds", 5);
            GatePassThroughService gatePassThroughService = new GatePassThroughService(
                gateManager, this,
                passThroughInstantOpenRadius, passThroughInstantOpenTimeoutSeconds);
            getServer().getPluginManager().registerEvents(
                new GatePassThroughConsequenceListener(gatePassThroughService, userManager), this);

            int fireTickIntervalSeconds = getConfig().getInt("gates.fire-tick-interval-seconds", 1);
            long fireTickIntervalTicks = Math.max(1L, fireTickIntervalSeconds) * 20L;
            new GateFireDamageTask(gateFireSystem).runTaskTimer(this, fireTickIntervalTicks, fireTickIntervalTicks);

            WorldGuardIntegration worldGuardIntegration = new WorldGuardIntegration(this);
            for (org.bukkit.World world : getServer().getWorlds()) {
                new GateAnimationTask(gateManager, world, org.bukkit.Material.STONE, worldGuardIntegration,
                    gateDoorsApi, this, gateDisplayManager, rotationGapFillRasterizationEnabled)
                    .runTaskTimer(this, 1L, 1L);
            }
            new GateDisplayUpdateTask(gateDisplayManager, gateManager).runTaskTimer(this, 20L, 20L);

            int gateDisplayCleanupIntervalSeconds = getConfig().getInt("gates.display-cleanup-interval-seconds", 60);
            long gateDisplayCleanupIntervalTicks = Math.max(20L, gateDisplayCleanupIntervalSeconds * 20L);
            new GateDisplayOrphanCleanupTask(gateDisplayManager, gateManager)
                .runTaskTimer(this, gateDisplayCleanupIntervalTicks, gateDisplayCleanupIntervalTicks);
            getLogger().info("Gate display orphan cleanup scheduled (interval=" + gateDisplayCleanupIntervalSeconds + "s)");

            getLogger().info("Registered gate event listener, animation tasks, and display refresh task for " + getServer().getWorlds().size() + " world(s)");
            gateStateSyncTask.start();
            
            // Register task event listeners (wired after handler registration)
            var retrievedWgRegionHandler = (WgRegionIdTaskHandler) 
                worldTaskHandlerRegistry.getHandler("WgRegionId").orElse(null);
            if (retrievedWgRegionHandler != null) {
                getServer().getPluginManager().registerEvents(
                    new RegionTaskEventListener(retrievedWgRegionHandler),
                    this
                );
                getLogger().info("Registered RegionTaskEventListener for WgRegionId handler");
            }
            
            // Register world task chat listener for handling chat input during tasks
            getServer().getPluginManager().registerEvents(
                new WorldTaskChatListener(this, worldTaskHandlerRegistry, gateDoorRegionCaptureHandler),
                this
            );
            getLogger().info("Registered WorldTaskChatListener for task chat input handling");

            getServer().getPluginManager().registerEvents(
                new WorldTaskLocationSelectionListener(locationHandler),
                this
            );
            getLogger().info("Registered WorldTaskLocationSelectionListener for block selection");
            
            getLogger().info("Region transition service initialized with domain resolver and gate control");

            getLogger().info("KnightsAndKings Plugin Enabled!");
            
        } catch (Exception e) {
            getLogger().severe("Failed to initialize plugin: " + e.getMessage());
            getLogger().severe("Plugin will be disabled. Please fix your config.yml");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (gateStateSyncTask != null) {
            gateStateSyncTask.stop();
            getLogger().info("Persisting final gate states before shutdown...");
            gateStateSyncTask.persistAllGateStates();
        }
        if (gateDisplayManager != null) {
            gateDisplayManager.removeAll();
        }
        if (headlessWorldTaskPoller != null) {
            headlessWorldTaskPoller.stop();
        }
        if (playerNotificationPoller != null) {
            playerNotificationPoller.stop();
        }
        if (tempRegionRetentionTask != null) {
            tempRegionRetentionTask.stop();
        }
        if (salaryPayoutScheduler != null) {
            salaryPayoutScheduler.stop();
        }
        if (cacheManager != null) {
            getLogger().info("Logging final cache metrics...");
            cacheManager.logMetrics();
            cacheManager.clearAll();
        }
        if (apiClient != null) {
            getLogger().info("Shutting down API client...");
            apiClient.shutdown();
        }
        if (regionHttpServer != null) {
            regionHttpServer.stop();
        }
        if (regionLookupExecutor != null) {
            regionLookupExecutor.shutdownNow();
        }
        if (privateMessageLogger != null) {
            privateMessageLogger.close();
        }
        getLogger().info("KnightsAndKings Plugin Disabled!");
    }

    /**
     * KNG-18 Phase 1 (docs/specs/private-messages/DESIGN.md §3.3): /msg, /reply, social spy and the
     * local PM log; Phase 2: ignore lists. Needs knkPermissible, adminFreezeManager, the user cache
     * and the API client.
     */
    private void initPrivateMessaging() {
        KnkConfig.PrivateMessagesConfig pmConfig = config.privateMessages();
        java.time.Clock clock = java.time.Clock.systemDefaultZone();
        this.visiblePlayers = net.knightsandkings.knk.paper.commands.support.VisiblePlayers.bukkit();
        this.spyService = new net.knightsandkings.knk.paper.user.SpyService(
            knkPermissible, new org.bukkit.NamespacedKey(this, "socialspy"), org.bukkit.Bukkit::getOnlinePlayers);
        spyService.start(this, pmConfig.spyRefreshSeconds());
        this.ignoreService = new net.knightsandkings.knk.paper.user.IgnoreService(
            apiClient.getUserIgnoresApi(),
            uuid -> cacheManager.getUserCache().getStale(uuid)
                .map(net.knightsandkings.knk.core.domain.users.UserSummary::id).orElse(null),
            uuid -> {
                org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
                return online != null && online.isOnline();
            },
            clock);
        // Players already online after a reload: load their lists (joins load their own).
        getServer().getScheduler().runTaskLater(this, () -> org.bukkit.Bukkit.getOnlinePlayers()
            .forEach(online -> ignoreService.load(online.getUniqueId())), 20L);
        if (pmConfig.log().localEnabled()) {
            var localLog = new net.knightsandkings.knk.paper.user.LocalFilePrivateMessageLog(
                getDataFolder().toPath().resolve("logs"), pmConfig.log().localRetentionDays(), clock);
            localLog.start();
            this.privateMessageLogger = localLog;
        } else {
            this.privateMessageLogger = net.knightsandkings.knk.paper.user.PrivateMessageLogger.NONE;
        }
        this.messagingService = new net.knightsandkings.knk.paper.user.MessagingService(
            pmConfig, knkPermissible, adminFreezeManager, spyService, privateMessageLogger, ignoreService, visiblePlayers,
            // Same rank colour as the player's tab-list name (KNG-7); cache-only checks, display only.
            player -> net.knightsandkings.knk.paper.utils.TabListTeam.resolve(
                knkPermissible.hasPermission(player, ModeService.OWNER_NODE),
                knkPermissible.hasPermission(player, ModeService.STAFF_NODE),
                cacheManager.getUserCache().getStale(player.getUniqueId()).orElse(null)).color(),
            org.bukkit.Bukkit::getConsoleSender, MenuService.mainThreadExecutor(this), clock
        );
    }

    private void registerEvents(WorldGuardRegionTracker regionTracker) {
        var pluginManager = getServer().getPluginManager();
        // Event registration moved to onEnable after region transition service setup

        pluginManager.registerEvents(new WorldGuardRegionListener(regionTracker), this);
        pluginManager.registerEvents(new PlayerListener(usersDataAccess, townsDataAccess, this.getCacheManager(), knkPermissible, usersCommandApi, kitsCommandApi, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess, ignoreService), this);
        pluginManager.registerEvents(new UserAccountListener(this, userManager, joinLoadingGuard, config.messages(), getLogger()), this);
        getLogger().info("Registered UserAccountListener for account management");
        pluginManager.registerEvents(new JoinLoadingRestrictionListener(joinLoadingGuard), this);
        pluginManager.registerEvents(new ModeListener(modeService), this);
        getLogger().info("Registered ModeListener for owner/staff mode restore");
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.AdminFreezeListener(this, adminFreezeManager, usersDataAccess), this);
        getLogger().info("Registered AdminFreezeListener for /freeze enforcement");
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.PrivateMessageSessionListener(
            this, messagingService, spyService, ignoreService), this);
        if (config.privateMessages().blockVanillaCommands()) {
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.VanillaMessagingBlockListener(), this);
            getLogger().info("Registered VanillaMessagingBlockListener (/minecraft:msg|tell|w -> /msg; /teammsg, /tm, /me off)");
        }
    }
    
    /**
     * Returns the cache manager for accessing cache statistics.
     *
     * @return CacheManager instance
     */
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * InventoryMenu Phase 2 (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md)
     * rendering-engine entry point.
     */
    public MenuService getMenuService() {
        return menuService;
    }
    
    /**
     * Returns the WorldTask handler registry for accessing registered handlers.
     *
     * @return WorldTaskHandlerRegistry instance
     */
    public WorldTaskHandlerRegistry getWorldTaskHandlerRegistry() {
        return worldTaskHandlerRegistry;
    }
    
    // No extra helpers needed; API client constructs and owns HTTP internals.
    
    private void registerCommands() {
        PluginCommand knkCommand = getCommand("knk");
        if (knkCommand != null) {
            // Use "localhost" as default serverId; TODO: make this configurable
            String serverId = "localhost";
            KnkAdminCommand knkAdminCommand = new KnkAdminCommand(
                this, 
                apiClient.getHealthApi(), 
                townsQueryApi, 
                locationsQueryApi, 
                enchantmentDefinitionsDataAccess,
                itemBlueprintsDataAccess,
                minecraftMaterialRefsDataAccess,
                districtsQueryApi, 
                streetsQueryApi, 
                cacheManager,
                worldTasksApi,
                worldTaskHandlerRegistry,
                gateManager,
                gateStructuresApi,
                gateDoorsApi,
                userManager,
                usersCommandApi,
                usersDataAccess,
                apiClient.getPermissionGroupsQueryApi(),
                rankHierarchy,
                modeService,
                districtGateLoader,
                gateDoorRegionCaptureHandler,
                serverId,
                menuService,
                userAdminService
            );
            knkCommand.setExecutor(knkAdminCommand);
            knkCommand.setTabCompleter(knkAdminCommand);
            getLogger().info("Registered /knk admin command");
        } else {
            getLogger().warning("Failed to register /knk command - not defined in plugin.yml?");
        }

        PluginCommand accountCommand = getCommand("account");
        if (accountCommand != null) {
            accountCommand.setExecutor(new AccountCommandRegistry(
                this,
                userManager,
                chatCaptureManager,
                userAccountApi,
                config,
                cooldownManager
            ));
            getLogger().info("Registered /account command with cooldown management");
        } else {
            getLogger().warning("Failed to register /account command - not defined in plugin.yml?");
        }

        registerModeCommand("ownermode", ActiveMode.OWNER);
        registerModeCommand("staffmode", ActiveMode.STAFF);

        registerSimpleCommand("freeze", new net.knightsandkings.knk.paper.commands.FreezeCommand(userAdminService, true));
        registerSimpleCommand("unfreeze", new net.knightsandkings.knk.paper.commands.FreezeCommand(userAdminService, false));
        registerSimpleCommand("staffchat", new net.knightsandkings.knk.paper.commands.StaffChatCommand());
        registerTabCommand("msg", new net.knightsandkings.knk.paper.commands.MessageCommand(messagingService, visiblePlayers));
        registerTabCommand("reply", new net.knightsandkings.knk.paper.commands.ReplyCommand(messagingService));
        registerTabCommand("socialspy", new net.knightsandkings.knk.paper.commands.SocialSpyCommand(
            new net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport(
                knkPermissible, MenuService.mainThreadExecutor(this),
                org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers),
            spyService, getLogger()));
        registerIgnoreCommands();

        registerSimpleCommand("kit", new net.knightsandkings.knk.paper.commands.KitCommand(
            this,
            kitsDataAccess,
            kitGrantFlow
        ));

        // Content port CP1: /menu opens the InventoryMenu hub (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md §3).
        registerSimpleCommand("menu", new net.knightsandkings.knk.paper.commands.MenuCommand(() -> menuService));

        registerPlayerCommands();
    }

    /** KNG-18 Phase 2: /ignore [player] and /unignore <player> (docs/specs/private-messages/DESIGN.md §3.3.5). */
    private void registerIgnoreCommands() {
        net.knightsandkings.knk.paper.commands.IgnoreCommand.TargetResolver targets = userAdminService::resolveTarget;
        java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<Boolean>> unignorable = uuid ->
            knkPermissible.hasPermissionAsync(org.bukkit.Bukkit.getOfflinePlayer(uuid),
                net.knightsandkings.knk.core.messaging.PrivateMessageNodes.UNIGNORABLE);
        java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);
        registerTabCommand("ignore", new net.knightsandkings.knk.paper.commands.IgnoreCommand(
            ignoreService, targets, unignorable, visiblePlayers, mainThread, getLogger(), false));
        registerTabCommand("unignore", new net.knightsandkings.knk.paper.commands.IgnoreCommand(
            ignoreService, targets, unignorable, visiblePlayers, mainThread, getLogger(), true));
    }

    /**
     * KNG-9: v2's /user statistics, /fly, /heal, /feed, /enderchest and /inventory
     * (docs/specs/legacy/commands-v2.md §1/§7).
     */
    private void registerPlayerCommands() {
        java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);

        if (usersQueryApi != null && usersDataAccess != null && titleBracketsDataAccess != null) {
            var userCommand = new net.knightsandkings.knk.paper.commands.UserCommand(
                mainThread, usersQueryApi, usersDataAccess, cacheManager.getUserCache(), titleBracketsDataAccess,
                () -> org.bukkit.Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList()
            );
            registerTabCommand("user", userCommand);
            registerTabCommand("stats", userCommand.statsShortcut());
        } else {
            getLogger().warning("/user and /stats not registered - user data access failed to initialize");
        }

        if (knkPermissible == null || userAdminService == null) {
            getLogger().warning("/fly, /heal, /feed, /enderchest and /inventory not registered - permissions failed to initialize");
            return;
        }
        var support = new net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport(
            knkPermissible, mainThread, org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers
        );
        // Same rank check as /knk tp, /freeze and /knk user (console and knk.admin.user.manage.all pass).
        net.knightsandkings.knk.paper.commands.support.TargetRankCheck rankCheck = (sender, targetName, onAllowed) ->
            userAdminService.resolveTarget(sender, targetName, summary ->
                userAdminService.withRankCheck(sender, summary, api -> onAllowed.accept(summary), () -> { }));
        // KNG-13: offline players' saved inventory/ender chest, read from and written to <main world>/playerdata.
        @SuppressWarnings("deprecation") // Bukkit.getUnsafe().getDataVersion() has no replacement
        var offlineStorage = new net.knightsandkings.knk.paper.inventory.OfflineStorageViews(
            new net.knightsandkings.knk.paper.inventory.OfflinePlayerStorage(
                () -> org.bukkit.Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata"),
                () -> org.bukkit.Bukkit.getUnsafe().getDataVersion()
            )
        );
        getServer().getPluginManager().registerEvents(offlineStorage, this);

        registerTabCommand("fly", new net.knightsandkings.knk.paper.commands.FlyCommand(support));
        registerTabCommand("heal", new net.knightsandkings.knk.paper.commands.RestoreCommand(
            support, net.knightsandkings.knk.paper.commands.RestoreCommand.Kind.HEAL));
        registerTabCommand("feed", new net.knightsandkings.knk.paper.commands.RestoreCommand(
            support, net.knightsandkings.knk.paper.commands.RestoreCommand.Kind.FEED));
        registerTabCommand("enderchest", new net.knightsandkings.knk.paper.commands.EnderchestCommand(support, rankCheck, offlineStorage));
        registerTabCommand("inventory", new net.knightsandkings.knk.paper.commands.InventoryCommand(support, rankCheck, offlineStorage));
    }

    private void registerTabCommand(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand != null) {
            pluginCommand.setExecutor(executor);
            pluginCommand.setTabCompleter(executor);
            getLogger().info("Registered /" + name + " command");
        } else {
            getLogger().warning("Failed to register /" + name + " command - not defined in plugin.yml?");
        }
    }

    private void registerSimpleCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand != null) {
            pluginCommand.setExecutor(executor);
            getLogger().info("Registered /" + name + " command");
        } else {
            getLogger().warning("Failed to register /" + name + " command - not defined in plugin.yml?");
        }
    }

    private void registerModeCommand(String name, ActiveMode mode) {
        PluginCommand modeCommand = getCommand(name);
        if (modeCommand != null) {
            ModeCommand executor = new ModeCommand(modeService, mode);
            modeCommand.setExecutor(executor);
            modeCommand.setTabCompleter(executor);
            getLogger().info("Registered /" + name + " command");
        } else {
            getLogger().warning("Failed to register /" + name + " command - not defined in plugin.yml?");
        }

    }
    
    public UsersCommandApi getUsersCommandApi() {
        return usersCommandApi;
    }

    public UserAccountApi getUserAccountApi() {
        return userAccountApi;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public ChatCaptureManager getChatCaptureManager() {
        return chatCaptureManager;
    }
    
    public CommandCooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public WorldTasksApi getWorldTasksApi() {
        return worldTasksApi;
    }

    /**
     * Loads the grade table into {@link GradeCatalog} now and every {@code enchant-books.grade-cap.grade-refresh-minutes}
     * (KNG-6, docs/specs/items/GRADE_DROPCHANCE.md §4). Until the first load succeeds the catalog answers with
     * the seeded defaults, so the enchant-book cap never waits on the API.
     */
    private void startGradeCatalogRefresh() {
        int minutes = Math.max(1, getConfig().getInt("enchant-books.grade-cap.grade-refresh-minutes", 10));
        long ticks = minutes * 60L * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshGradeCatalog, 0L, ticks);
    }

    private void refreshGradeCatalog() {
        gradesDataAccess.listAsync(1, 100).whenComplete((page, error) -> {
            if (error != null || page == null || page.items() == null) {
                getLogger().warning("Grade table refresh failed; keeping the previous/default enchant-book caps"
                        + (error != null ? ": " + error.getMessage() : ""));
                return;
            }
            boolean first = !GradeCatalog.getInstance().isLoaded();
            GradeCatalog.getInstance().replace(page.items());
            if (first) {
                getLogger().info("Grade table loaded (" + page.items().size() + " grades) for the enchant-book level cap");
            }
        });
    }

    private void initializeEnchantmentRuntime(CombatSafezoneCheck safezones) {
        EnchantmentBootstrap bootstrap = new EnchantmentBootstrap(this, safezones);
        this.enchantmentRuntime = bootstrap.initialize();
    }

    private AuthProvider createAuthProvider(KnkConfig.AuthConfig authConfig) {
        String type = authConfig.type().toLowerCase();
        return switch (type) {
            case "bearer" -> {
                getLogger().info("Using Bearer token authentication");
                yield new BearerAuthProvider(authConfig.bearerToken());
            }
            case "apikey" -> {
                getLogger().info("Using API Key authentication");
                yield new ApiKeyAuthProvider(authConfig.apiKey(), authConfig.apiKeyHeader());
            }
            default -> {
                // KNG-22: the API refuses unauthenticated game-server calls to its protected
                // routes (balances, salary, kits, presence...) unless it runs in Development
                // with Security:AllowUnauthenticatedPluginCalls on.
                getLogger().warning("api.auth.type is '" + authConfig.type() + "': knk-web-api will refuse this server's "
                    + "balance, salary, kit, presence and staff calls (401). Set api.auth.type: apikey and api.auth.api-key "
                    + "to the API's Security:PluginApiKey.");
                yield new NoAuthProvider();
            }
        };
    }
}
