package net.knightsandkings.knk.paper;

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
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;
import net.knightsandkings.knk.core.ports.api.MinecraftMaterialRefsQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
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
import net.knightsandkings.knk.paper.listeners.PlayerListener;
import net.knightsandkings.knk.paper.listeners.RegionTaskEventListener;
import net.knightsandkings.knk.paper.listeners.UserAccountListener;
import net.knightsandkings.knk.paper.listeners.WorldGuardRegionListener;
import net.knightsandkings.knk.paper.listeners.WorldTaskChatListener;
import net.knightsandkings.knk.paper.listeners.WorldTaskLocationSelectionListener;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionTracker;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import net.knightsandkings.knk.paper.tasks.TempRegionRetentionTask;
import net.knightsandkings.knk.paper.tasks.WgRegionIdTaskHandler;
import net.knightsandkings.knk.paper.tasks.LocationTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.HeadlessWorldTaskPoller;
import net.knightsandkings.knk.paper.tasks.GateBlockScanTaskHandler;
import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
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
    private MenuTemplatesQueryApi menuTemplatesQueryApi;
    private MinecraftMaterialRefsQueryApi minecraftMaterialRefsQueryApi;
    private DistrictsQueryApi districtsQueryApi;
    private StreetsQueryApi streetsQueryApi;
    private StructuresQueryApi structuresQueryApi;
    private DomainsQueryApi domainsQueryApi;
    private UsersQueryApi usersQueryApi;
    private UsersCommandApi usersCommandApi;
    private UserAccountApi userAccountApi;
    private UsersDataAccess usersDataAccess;
    private TownsDataAccess townsDataAccess;
    private EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess;
    private ItemBlueprintsDataAccess itemBlueprintsDataAccess;
    private MenuTemplatesDataAccess menuTemplatesDataAccess;
    private MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
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
    private UserManager userManager;
    private ChatCaptureManager chatCaptureManager;
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
            this.menuTemplatesQueryApi = apiClient.getMenuTemplatesQueryApi();
            this.minecraftMaterialRefsQueryApi = apiClient.getMinecraftMaterialRefsQueryApi();
            this.districtsQueryApi = apiClient.getDistrictsQueryApi();
            this.streetsQueryApi = apiClient.getStreetsQueryApi();
            this.structuresQueryApi = apiClient.getStructuresQueryApi();
            this.domainsQueryApi = apiClient.getDomainsQueryApi();
            this.usersQueryApi = apiClient.getUsersQueryApi();
            this.usersCommandApi = apiClient.getUsersCommandApi();
            this.userAccountApi = apiClient.getUserAccountApi();
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
            getLogger().info("UsersQueryApi wired from API client");
            getLogger().info("UsersCommandApi wired from API client");
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
            this.menuTemplatesDataAccess = dataAccessFactory.createMenuTemplatesDataAccess(
                config.cache().ttl(),
                menuTemplatesQueryApi
            );
            this.minecraftMaterialRefsDataAccess = dataAccessFactory.createMinecraftMaterialRefsDataAccess(
                config.cache().ttl(),
                minecraftMaterialRefsQueryApi
            );
            getLogger().info("Cache manager initialized with TTL: " + config.cache().ttl());
            getLogger().info("Data access factory initialized with entity-specific settings");

            initializeEnchantmentRuntime();
            getLogger().info("Registered custom enchantment runtime listeners and /ce command");

            // Register commands
            registerCommands();

            // Register region listeners (WorldGuard)
            // Create domain resolver for mapping WG region IDs to domain entities
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
        if (tempRegionRetentionTask != null) {
            tempRegionRetentionTask.stop();
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
        getLogger().info("KnightsAndKings Plugin Disabled!");
    }

    private void registerEvents(WorldGuardRegionTracker regionTracker) {
        var pluginManager = getServer().getPluginManager();
        // Event registration moved to onEnable after region transition service setup

        pluginManager.registerEvents(new WorldGuardRegionListener(regionTracker), this);
        pluginManager.registerEvents(new PlayerListener(usersDataAccess, townsDataAccess, this.getCacheManager()), this);
        pluginManager.registerEvents(new UserAccountListener(userManager, config.messages(), getLogger()), this);
        getLogger().info("Registered UserAccountListener for account management");
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
                districtGateLoader,
                gateDoorRegionCaptureHandler,
                serverId
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

    private void initializeEnchantmentRuntime() {
        EnchantmentBootstrap bootstrap = new EnchantmentBootstrap(this);
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
                getLogger().info("Using no authentication");
                yield new NoAuthProvider();
            }
        };
    }
}
