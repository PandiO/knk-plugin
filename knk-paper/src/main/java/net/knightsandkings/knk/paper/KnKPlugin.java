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
import net.knightsandkings.knk.paper.regions.managed.ManagedRegionsBootstrap;
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
import net.knightsandkings.knk.paper.siege.SiegePlayerVault;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.commands.SiegeCommand;
import net.knightsandkings.knk.paper.listeners.SiegeSessionListener;
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
    // Currency ledger Phase 3: /pay, /balance, /baltop, /transactions and /knk user <player> history.
    private net.knightsandkings.knk.paper.currency.PlayerCurrencyService playerCurrencyService;
    private net.knightsandkings.knk.paper.user.SalaryPayoutScheduler salaryPayoutScheduler;
    // Lootboxes Phase 3 (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md)
    private net.knightsandkings.knk.paper.lootbox.LootboxRuntime lootboxRuntime;
    private net.knightsandkings.knk.paper.lootbox.LootboxSpawnScheduler lootboxSpawnScheduler;
    private net.knightsandkings.knk.paper.commands.LootboxAdminCommand lootboxAdminCommand;
    private net.knightsandkings.knk.paper.commands.LootboxCommand lootboxCommand;
    private net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery lootboxTokenDelivery;
    private net.knightsandkings.knk.paper.lootbox.LootboxOpening lootboxOpening;
    private net.knightsandkings.knk.paper.discovery.DiscoveryEligibility discoveryEligibility;
    private net.knightsandkings.knk.paper.discovery.DiscoveryFlushTask discoveryFlushTask;
    private net.knightsandkings.knk.core.discovery.DiscoveryTracker discoveryTracker;
    private net.knightsandkings.knk.core.discovery.DiscoverySpool discoverySpool;
    private net.knightsandkings.knk.paper.discovery.DomainDiscoveryListener discoveryListener;
    private net.knightsandkings.knk.paper.menu.content.DiscoveriesMenuFeature discoveriesMenuFeature;
    private MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
    private PermissionsDataAccess permissionsDataAccess;
    private KnkPermissible knkPermissible;
    private net.knightsandkings.knk.paper.commands.support.CommandPermissions commandPermissions;
    private JoinLoadingGuard joinLoadingGuard;
    private ModeService modeService;
    private net.knightsandkings.knk.paper.user.AdminFreezeManager adminFreezeManager;
    private net.knightsandkings.knk.paper.user.MessagingService messagingService;
    private net.knightsandkings.knk.paper.user.SpyService spyService;
    private net.knightsandkings.knk.paper.user.IgnoreService ignoreService;
    private net.knightsandkings.knk.paper.user.PrivateMessageLogger privateMessageLogger;
    private net.knightsandkings.knk.paper.user.ApiPrivateMessageLog apiPrivateMessageLog;
    private net.knightsandkings.knk.paper.chat.PrivateMessageCommandLogFilter privateMessageCommandLogFilter;
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
    // Road navigation (KNG-27, Phase 3). regionDomainResolver / regionTracker were locals of onEnable
    // before; the road builder (domain tagging) and navigation read them, so they are fields now (R7, R8).
    private RegionDomainResolver regionDomainResolver;
    private WorldGuardRegionTracker regionTracker;
    private net.knightsandkings.knk.core.dataaccess.LocationsDataAccess locationsDataAccess;
    private net.knightsandkings.knk.core.dataaccess.StreetsDataAccess streetsDataAccess;
    private net.knightsandkings.knk.core.dataaccess.DistrictsDataAccess districtsDataAccess;
    private net.knightsandkings.knk.core.dataaccess.StructuresDataAccess structuresDataAccess;
    private net.knightsandkings.knk.paper.roads.RoadNetworkCache roadNetworkCache;
    // Road navigation Phase 4 (/navigate); null while navigation is disabled.
    private net.knightsandkings.knk.paper.navigation.NavigationService navigationService;
    private net.knightsandkings.knk.paper.roads.LiveEdgeTags liveEdgeTags;
    private net.knightsandkings.knk.paper.navigation.NavigationDestinations navigationDestinations;
    private java.util.concurrent.ExecutorService navigationRouting;
    // KNG-51 walkable last-mile paths; null while navigation.walk.enabled is false.
    private net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService walkSnapshots;
    private java.util.concurrent.ExecutorService walkSearches;
    private net.knightsandkings.knk.paper.roads.RoadDirtyTracker roadDirtyTracker;
    private net.knightsandkings.knk.paper.roads.RoadBuildQueue roadBuildQueue;
    private net.knightsandkings.knk.paper.roads.RoadSurveyService roadSurveyService;
    private net.knightsandkings.knk.paper.roads.RoadOverlayRenderer roadOverlayRenderer;
    private net.knightsandkings.knk.paper.roads.RoadProposals roadProposals;
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
    private ManagedRegionsBootstrap managedRegions;
    /** KNG-56: domain AllowEntry/AllowExit as WorldGuard flags (synced from the API, enforced by a WG session handler). */
    private net.knightsandkings.knk.paper.regions.access.DomainAccessFlagSync domainAccessSync;
    private net.knightsandkings.knk.paper.regions.access.DomainAccessService domainAccess;
    private net.knightsandkings.knk.paper.teleport.TeleportService teleportService;
    private net.knightsandkings.knk.paper.commands.StaffTeleportCommand staffTeleportCommand;
    private net.knightsandkings.knk.paper.teleport.TeleportRequestService teleportRequestService;
    private net.knightsandkings.knk.paper.commands.TeleportRequestCommand teleportRequestCommand;
    private net.knightsandkings.knk.paper.teleport.SpawnDestinationResolver spawnDestinationResolver;
    /** Global Game Settings (KNG-52); null without the API client. */
    private net.knightsandkings.knk.paper.settings.GameSettingsManager gameSettingsManager;
    private net.knightsandkings.knk.paper.commands.SpawnCommand spawnCommand;
    private net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess teleportDestinationsDataAccess;
    private net.knightsandkings.knk.paper.commands.WarpCommand warpCommand;
    private net.knightsandkings.knk.paper.teleport.BackService backService;
    private net.knightsandkings.knk.paper.commands.BackCommand backCommand;
    /** What the teleport menu uses; set once the teleport engine started (after the menu registries lock). */
    private volatile net.knightsandkings.knk.paper.menu.content.TeleportMenuFeature.Teleports teleportMenuParts;
    private SiegeService siegeService;
    /** The siege gate lockdowns (Phase 7a); read-only for road navigation (KNG-27 plan R24). Null without the gate system. */
    private net.knightsandkings.knk.paper.siege.SiegeGateController siegeGates;
    private net.knightsandkings.knk.core.siege.SiegeMatchRecorder siegeMatchRecorder;
    /** Kept for the siege gate controller (Phase 7a), which respawns doors a match destroyed. */
    private HealthSystem gateHealthSystem;
    /** Kept for the siege non-member pass-through (Phase 7b, TELEPORT mode only). */
    private net.knightsandkings.knk.paper.gates.GatePassThroughService gatePassThroughService;
    
    /**
     * KNG-56: the domain access flags must be in WorldGuard's flag registry before it loads its
     * regions (WorldGuard is a hard dependency, so its onLoad has run).
     */
    @Override
    public void onLoad() {
        net.knightsandkings.knk.paper.regions.access.DomainAccessFlags.register(getLogger());
    }

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

            // Managed WorldGuard regions: parent + priority + category flags for Town/District/Structure/Gate regions,
            // applied when a world task's region gets its final name and repaired at every startup
            // (docs/architecture/managed-worldguard-regions.md).
            this.managedRegions = new ManagedRegionsBootstrap(this, ManagedRegionsBootstrap.readConfig(this),
                townsQueryApi, districtsQueryApi, structuresQueryApi, domainCatalogQueryApi);
            // KNG-56: every domain's AllowEntry/AllowExit onto its region as flags WorldGuard saves, so the rules hold
            // while the API is down. Startup + periodic sync; a newly finalized region is synced right away.
            this.domainAccessSync = new net.knightsandkings.knk.paper.regions.access.DomainAccessFlagSync(this,
                apiClient.getDomainAccessRulesApi(),
                net.knightsandkings.knk.paper.regions.access.DomainAccessFlagSync.Settings.read(this));
            wgRegionIdHandler.setRegionFinalizer((regionId, domainType, parentRegionId) -> {
                managedRegions.finalizeNewRegion(regionId, domainType, parentRegionId);
                domainAccessSync.syncNow().exceptionally(error -> null);
            });
            managedRegions.scheduleStartupRepair();
            domainAccessSync.schedule();
            
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

            // Start temp region retention task (14 day retention policy; never deletes a region a domain uses)
            tempRegionRetentionTask = new TempRegionRetentionTask(this, 14, managedRegions::protectsFromCleanup,
                    TempRegionRetentionTask.domainUsageVia(domainsQueryApi));
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
            // Gate pass-through (and navigation's gate verdicts) honour KnK's permission model as well as Bukkit's.
            net.knightsandkings.knk.paper.gates.GatePassThroughRules.setPermissionCheck((player, node) ->
                player.hasPermission(node) || (knkPermissible != null && knkPermissible.hasPermission(player, node)));
            // A grant or group change made in the web app shows in game within the 30 s cache time;
            // /knk cache refresh applies it at once.
            cacheManager.registerRefreshHook("permissions", permissionsDataAccess::invalidateAll);
            // Hands back the world's Game Settings default mode when the hold ends; the manager is
            // created later (initializeGameSettings), so it's read when a hold ends.
            this.joinLoadingGuard = new JoinLoadingGuard(this, knkPermissible, player -> gameSettingsManager != null
                ? gameSettingsManager.gameModeFor(player.getWorld()) : org.bukkit.GameMode.SURVIVAL);
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
            // KNG-24: knk.admin.user.* checked through KnkPermissible as well as Bukkit.
            this.userAdminService.setPermissions(commandPermissions());
            // Salary on join (offline gap) and every hour online; the scoreboard is redrawn after a payout.
            this.salaryPayoutScheduler = new net.knightsandkings.knk.paper.user.SalaryPayoutScheduler(
                this, usersCommandApi, cacheManager.getUserCache(), usersDataAccess,
                (player, summary) -> net.knightsandkings.knk.paper.utils.ScoreboardUtil.setScoreboard(List.of(player), knkPermissible, summary)
            );
            getServer().getPluginManager().registerEvents(salaryPayoutScheduler, this);
            salaryPayoutScheduler.start();
            startDomainDiscovery();
            // Rank changes made outside the plugin (web app, expiring temporary rank) show right away.
            if (playerNotificationPoller != null) {
                playerNotificationPoller.setRankChangedHandler(userAdminService::resyncDisplay);
            }
            // Domain discovery (KNG-20): registered even with discovery.enabled false - the hub's
            // Discoveries tile reads its root, and the menu still lists past discoveries. Discovery staff
            // see types switched off in the web app tagged "Disabled" in the menu head.
            String discoveryStaffNode = net.knightsandkings.knk.paper.commands.DiscoveryAdminCommand.NODE;
            this.discoveriesMenuFeature = new net.knightsandkings.knk.paper.menu.content.DiscoveriesMenuFeature(
                apiClient.getDiscoveriesApi(), cacheManager.getUserCache(), java.time.Clock.systemUTC(),
                player -> player.hasPermission(discoveryStaffNode)
                    || (knkPermissible != null && knkPermissible.hasPermission(player, discoveryStaffNode))
            );
            // A discovery reset made in the web app: drop the cached menu data and re-sync the online
            // player's tracking (the same path /knk discovery reset takes, see its afterReset below).
            if (playerNotificationPoller != null) {
                playerNotificationPoller.setDiscoveryResetHandler(this::afterDiscoveryReset);
            }
            // Currency ledger Phase 3: player payments. Name lookups are vanish-safe (VisiblePlayers).
            var currencySettings = net.knightsandkings.knk.paper.currency.CurrencySettings.from(getConfig());
            this.playerCurrencyService = new net.knightsandkings.knk.paper.currency.PlayerCurrencyService(
                MenuService.mainThreadExecutor(this), apiClient.getCurrencyApi(), usersDataAccess, cacheManager.getUserCache(),
                knkPermissible::checkAsync,
                new net.knightsandkings.knk.paper.currency.VisiblePlayers(org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers),
                currencySettings, java.time.Clock.systemUTC(),
                // The /pay confirmation expiry notice (main thread; cancelled when settled or on quit).
                (delay, task) -> getServer().getScheduler().runTaskLater(this, task, Math.max(1L, delay.toMillis() / 50L))::cancel
            );
            if (playerNotificationPoller != null) {
                // "You received N coins from X" - right away when online, else on the next join.
                var paymentHandler = new net.knightsandkings.knk.paper.currency.PaymentNotificationHandler(
                    currencySettings, uuid -> usersDataAccess.refreshAsync(uuid));
                playerNotificationPoller.setPaymentReceivedHandler(paymentHandler::handle);
                // Currency Phase 5: anomaly alerts for online staff with knk.admin.currency.alerts.
                var alertNotifier = new net.knightsandkings.knk.paper.currency.CurrencyAlertNotifier(
                    currencySettings, knkPermissible::checkAsync, org.bukkit.Bukkit::getOnlinePlayers,
                    MenuService.mainThreadExecutor(this));
                playerNotificationPoller.setCurrencyAlertHandler(alertNotifier::handle);
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
                    permissionGroupsDataAccess, org.bukkit.Bukkit::getOnlinePlayers, this::askStaffReason),
                discoveriesMenuFeature,
                // Teleport menu (KNG-17 Phase 6): the engine starts later in onEnable, hence the supplier.
                new net.knightsandkings.knk.paper.menu.content.TeleportMenuFeature(() -> teleportMenuParts),
                // Siege Phase 8b: the siege menus. SiegeService is created later (initializeSiege),
                // so the feature looks it up on every call.
                new net.knightsandkings.knk.paper.siege.SiegeMenuFeature(() -> siegeService)
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
            this.regionDomainResolver = new RegionDomainResolver(
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
            // KNG-104: /knk cache refresh forgets the region → domain map too (registered before the navigation hooks)
            cacheManager.registerRefreshHook("region domains", regionDomainResolver::clearRegionCache);

            // KNG-11: hits the siege rules allow stay exempt, so enchantments keep working in sieges fought
            // in towns. siegeService is created later (initializeSiege), so it's read per hit.
            initializeEnchantmentRuntime(new WorldGuardCombatSafezones(regionDomainResolver,
                (attacker, victim) -> siegeService != null && siegeService.allowsCombat(attacker, victim)));
            getLogger().info("Registered custom enchantment runtime listeners and /ce command");

            // Teleport engine + staff teleports (docs/specs/teleport, Phase 1) - before the commands,
            // /knk tp delegates to /tp.
            initializeTeleports();

            // Global Game Settings (docs/specs/game-settings, KNG-52) - after the teleports (it shares the
            // /spawn resolver), before registerEvents (PlayerListener uses it).
            initializeGameSettings();

            // Lootboxes Phase 3: world boxes, claims and delivery; commands registered in registerCommands().
            initializeLootboxes();

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
            // Describe-only (accessChecks=false): WorldGuard enforces AllowEntry/AllowExit since KNG-56.
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
                    }),
                false
            );
            
            // Wire tracker and listener
            this.regionTracker = new WorldGuardRegionTracker(
                regionTransitionService,
                regionDomainResolver,
                regionLookupExecutor,
                this,  // Plugin instance for scheduler access
                Logger.getLogger(WorldGuardRegionTracker.class.getName()),
                true  // Enable console logging; set to false to disable
            );
            registerEvents(regionTracker);
            wireDomainAccess();

            HealthSystem healthSystem = new HealthSystem(gateDoorsApi, this, gateDisplayManager, gateManager);
            this.gateHealthSystem = healthSystem;
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
            this.gatePassThroughService = gatePassThroughService;
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

            initializeSiege();

            // Road navigation (KNG-27) Phase 3: the admin side (survey, build, review); Phase 4: /navigate.
            initializeRoads();
            initializeNavigation();

            getLogger().info("KnightsAndKings Plugin Enabled!");
            
        } catch (Exception e) {
            getLogger().severe("Failed to initialize plugin: " + e.getMessage());
            getLogger().severe("Plugin will be disabled. Please fix your config.yml");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * The Player manager's reason prompt for staged balance changes (currency Phase 4, D9): one line
     * of chat through ChatCaptureManager, answered on the main thread (chat arrives off it).
     */
    private boolean askStaffReason(org.bukkit.entity.Player player, String prompt,
                                   java.util.function.Consumer<String> onReason, Runnable onCancel) {
        ChatCaptureManager capture = this.chatCaptureManager;
        if (capture == null) {
            return false;
        }
        java.util.concurrent.Executor main = MenuService.mainThreadExecutor(this);
        capture.startTextCapture(player, prompt, text -> main.execute(() -> onReason.accept(text)), () -> main.execute(onCancel));
        return true;
    }

    @Override
    public void onDisable() {
        if (gameSettingsManager != null) {
            gameSettingsManager.stop();
        }
        // Players still loading their account would otherwise be saved in the hold's ADVENTURE
        // mode with the invulnerable flag set.
        if (joinLoadingGuard != null) {
            try {
                joinLoadingGuard.releaseAll();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Releasing join-loading holds failed", e);
            }
        }
        // Siege first (DESIGN §5.1/§9.2): stops every lobby with SERVER_RESTART, which aborts running
        // matches and restores every member's vault while the players and the API client still exist.
        if (siegeService != null) {
            try {
                siegeService.shutdown();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Siege shutdown failed", e);
            }
        }
        // Phase 6: match results still being sent (incl. the shutdown aborts above) go to the spool,
        // replayed on the next enable.
        if (siegeMatchRecorder != null) {
            try {
                siegeMatchRecorder.spoolInFlight();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Spooling in-flight siege results failed", e);
            }
        }
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
        if (teleportService != null) {
            teleportService.cancelAll(net.knightsandkings.knk.core.teleport.WarmupCancelReason.SHUTDOWN);
            // Paid teleports caught between their charge and the teleport: refund them (or void their
            // keys) while the API client still runs - bounded, so a dead API can't hang the shutdown.
            try {
                teleportService.abandonOpenCharges("the server shut down").get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                getLogger().warning("Teleport charges still being refunded at shutdown - see the [KnK Teleport] refund warnings");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                getLogger().log(java.util.logging.Level.WARNING, "Refunding open teleport charges at shutdown failed", e);
            }
        }
        if (salaryPayoutScheduler != null) {
            salaryPayoutScheduler.stop();
        }
        if (lootboxOpening != null) {
            lootboxOpening.finishAll(); // items on a spinning reel are handed over before the stop
        }
        if (lootboxSpawnScheduler != null) {
            lootboxSpawnScheduler.stop();
        }
        if (lootboxRuntime != null) {
            lootboxRuntime.stop(); // removes the (non-persistent) box entities
        }
        // Road navigation (KNG-27): running surveys are discarded (nothing to spool), the build queue
        // stops (progress lives in the API's BuiltAt), the dirty tracker flushes once synchronously
        // when the API is reachable - all before apiClient.shutdown() below.
        if (navigationService != null) {
            navigationService.stop();
        }
        if (navigationRouting != null) {
            navigationRouting.shutdownNow();
        }
        if (walkSnapshots != null) {
            walkSnapshots.stop();
        }
        if (walkSearches != null) {
            walkSearches.shutdownNow();
        }
        if (roadSurveyService != null) {
            roadSurveyService.shutdown();
        }
        if (roadBuildQueue != null) {
            roadBuildQueue.stop();
        }
        if (roadOverlayRenderer != null) {
            roadOverlayRenderer.clear();
        }
        if (roadNetworkCache != null) {
            roadNetworkCache.stop();
        }
        if (roadDirtyTracker != null) {
            try {
                roadDirtyTracker.stop();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.WARNING, "Road dirty-tracker shutdown flush failed", e);
            }
        }
        if (discoveryFlushTask != null) {
            // Unsent candidates and grants still in flight go to the spool, replayed on the next start.
            discoveryFlushTask.stop();
            discoveryFlushTask.spoolEverything();
        }
        if (cacheManager != null) {
            getLogger().info("Logging final cache metrics...");
            cacheManager.logMetrics();
            cacheManager.clearAll();
        }
        if (privateMessageLogger != null) {
            // Before the API client: the API sink sends what is queued (or spools it) on close.
            privateMessageLogger.close();
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
        if (privateMessageCommandLogFilter != null) {
            privateMessageCommandLogFilter.uninstall();
        }
        getLogger().info("KnightsAndKings Plugin Disabled!");
    }

    /**
     * Domain discovery (KNG-20, docs/specs/domain-discovery DESIGN.md §3.6): first entry into a Town,
     * District or Structure is granted by the API once, with sound, particles and the reward lines.
     * Needs the user cache, mode/freeze/loading state and the scoreboard refresher, so it starts after
     * the salary scheduler.
     */
    private void startDomainDiscovery() {
        KnkConfig.DiscoveryConfig discoveryConfig = config.discovery();
        if (!discoveryConfig.enabled()) {
            getLogger().info("Domain discovery disabled (discovery.enabled: false)");
            return;
        }
        java.time.Clock clock = java.time.Clock.systemUTC();
        this.discoveryEligibility = new net.knightsandkings.knk.paper.discovery.DiscoveryEligibility(
            true, joinLoadingGuard::isLoading, modeService::getActiveMode, adminFreezeManager::isFrozen,
            discoveryConfig.excludedGameModes(), discoveryConfig.excludeSiegeParticipants()
        );
        // Siege members discover nothing (discovery.exclude-siege-participants). siegeService is
        // created later (initializeSiege), so the check reads it on every call.
        discoveryEligibility.setSiegeParticipantCheck(
            net.knightsandkings.knk.paper.discovery.DiscoveryEligibility.siegeParticipants(() -> siegeService));
        this.discoveryTracker =
            new net.knightsandkings.knk.core.discovery.DiscoveryTracker(discoveryConfig.maxRequestsPerMinute());
        this.discoverySpool = new net.knightsandkings.knk.core.discovery.DiscoverySpool(
            new java.io.File(getDataFolder(), discoveryConfig.spoolDirectory()).toPath(), getLogger()
        );
        // A player who joined while the API was down has no user id: the recorder looks it up by UUID
        // before sending (null = no such user, a failure = still unreachable).
        net.knightsandkings.knk.core.discovery.DiscoveryRecorder discoveryRecorder = new net.knightsandkings.knk.core.discovery.DiscoveryRecorder(
            apiClient.getDiscoveriesApi(), net.knightsandkings.knk.core.dataaccess.RetryPolicy.defaultPolicy(), discoverySpool, getLogger(),
            uuid -> usersQueryApi.getByUuid(uuid).thenApply(summary -> {
                if (summary == null) {
                    return java.util.Optional.<Integer>empty();
                }
                cacheManager.getUserCache().put(summary);
                return java.util.Optional.ofNullable(summary.id());
            })
        );
        net.knightsandkings.knk.paper.discovery.DiscoveryEffects discoveryEffects = new net.knightsandkings.knk.paper.discovery.DiscoveryEffects(
            this, discoveryConfig, usersDataAccess,
            (player, summary) -> net.knightsandkings.knk.paper.utils.ScoreboardUtil.setScoreboard(List.of(player), knkPermissible, summary)
        );
        this.discoveryFlushTask = new net.knightsandkings.knk.paper.discovery.DiscoveryFlushTask(
            this, discoveryTracker, discoveryRecorder, discoveryEffects, clock,
            discoveryConfig.batchWindowTicks(), discoveryConfig.replayIntervalSeconds()
        );
        this.discoveryListener =
            new net.knightsandkings.knk.paper.discovery.DomainDiscoveryListener(
                this, discoveryTracker, discoveryRecorder, apiClient.getDiscoveriesApi(), discoveryEligibility,
                discoveryFlushTask, clock
            );
        getServer().getPluginManager().registerEvents(discoveryListener, this);
        discoveryFlushTask.start();
        discoveryListener.startOnlinePlayers(uuid -> cacheManager.getUserCache().getByUuid(uuid)
            .map(net.knightsandkings.knk.core.domain.users.UserSummary::id).orElse(null));
        getLogger().info("Domain discovery started (spool: " + discoverySpool.directory() + ")");
    }

    /**
     * After one of an online player's discoveries was reset (web app notification or
     * {@code /knk discovery reset}): drops their cached discoveries menu data and re-syncs their
     * discovery tracking so the place is discovered again, even standing still. Main thread.
     */
    private void afterDiscoveryReset(org.bukkit.entity.Player player) {
        if (discoveriesMenuFeature != null) {
            discoveriesMenuFeature.invalidate(player.getUniqueId());
        }
        if (discoveryListener != null) {
            discoveryListener.resync(player);
        }
    }

    /**
     * Road navigation (KNG-27, docs/specs/navigation/DESIGN.md §4, §5, §7; plan Phase 3): the network cache,
     * the dirty tracker, the build queue, the survey service and the overlay. Only when
     * {@code navigation.enabled}; {@code /knk road} is registered regardless and answers "disabled" otherwise.
     * Runs after {@code initializeSiege()} because the build job reads the gate manager's closed footprints
     * and the survey/build code reads the region tracker's WorldGuard query (R8).
     *
     * <p>Note: {@code cacheManager} is constructed twice in onEnable (once early, once after the API client);
     * the road code uses the field as it is here, after the second construction. Not fixed in this phase.
     */
    private void initializeRoads() {
        net.knightsandkings.knk.paper.config.NavigationConfig navigation = config.navigation();
        if (!navigation.enabled()) {
            getLogger().info("Road navigation disabled (navigation.enabled: false)");
            return;
        }
        java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);
        ensureDomainDataAccesses();

        var queryApi = apiClient.getRoadNetworkQueryApi();
        var commandApi = apiClient.getRoadNetworkCommandApi();
        java.nio.file.Path roadsDirectory = new java.io.File(getDataFolder(), "roads").toPath();
        this.roadNetworkCache = new net.knightsandkings.knk.paper.roads.RoadNetworkCache(
            this, queryApi, roadsDirectory, regionDomainResolver, mainThread);

        var dirtyTiles = new net.knightsandkings.knk.paper.roads.DirtyTiles();
        this.roadDirtyTracker = new net.knightsandkings.knk.paper.roads.RoadDirtyTracker(
            this, commandApi, dirtyTiles, () -> apiClient != null);
        // The dirty tracker's "does this block matter" needs the profile materials and the road cells of the
        // current snapshot; both follow every snapshot swap.
        roadNetworkCache.addListener(world -> {
            dirtyTiles.setRoadMaterials(roadNetworkCache.roadMaterialNames());
            dirtyTiles.setRoadCells(net.knightsandkings.knk.paper.roads.DirtyTiles.RoadCells.of(roadNetworkCache.snapshot(world)));
        });

        this.roadOverlayRenderer = new net.knightsandkings.knk.paper.roads.RoadOverlayRenderer(this, roadNetworkCache::snapshot);

        var regionIds = regionTracker.regionIds();
        this.roadBuildQueue = new net.knightsandkings.knk.paper.roads.RoadBuildQueue(
            this, navigation, queryApi, commandApi, roadNetworkCache, gateManager, regionIds, regionDomainResolver,
            mainThread, net.knightsandkings.knk.paper.utils.TickBudget.server());
        // Curated tiles (rev. 6 Part B, plan §5.7): builds of Curated tiles become proposals to review.
        this.roadProposals = new net.knightsandkings.knk.paper.roads.RoadProposals(
            queryApi, commandApi, roadNetworkCache, () -> navigation.builder().buildParameters(), mainThread);
        roadBuildQueue.setProposals(roadProposals);
        roadOverlayRenderer.setProposals(roadProposals::pendingItems);
        this.roadSurveyService = new net.knightsandkings.knk.paper.roads.RoadSurveyService(
            this, navigation, queryApi, commandApi, roadNetworkCache, regionIds, mainThread,
            player -> cacheManager.getUserCache().getStale(player.getUniqueId())
                .map(net.knightsandkings.knk.core.domain.users.UserSummary::id).orElse(null));
        roadSurveyService.setGateCells(world -> net.knightsandkings.knk.paper.roads.GateCellsIndex.of(gateManager, world));

        roadNetworkCache.start();
        roadDirtyTracker.start();
        roadBuildQueue.start();
        roadSurveyService.start();
        getLogger().info("Road navigation (admin side) initialized: /knk road, tile cache at " + roadsDirectory);
    }

    /**
     * Road navigation Phase 4 (docs/specs/navigation/DESIGN.md §6): {@code /navigate}. Runs after
     * {@link #initializeRoads()} and needs its cache; the destination catalogue, the per-player access
     * policy (gates R5/R24/R39, pass-through R25, domains R6/R7/R8 with the region tracker's
     * knk.region.bypass predicate), eligibility (R23), trail (R9/R11), HUD, the live re-route triggers
     * (gate listener R4, siege observer R24, domain cache refresh, network snapshot swaps) and the
     * listener that ends sessions on quit/death/world change/teleport.
     */
    private void initializeNavigation() {
        if (roadNetworkCache == null || regionTracker == null || regionDomainResolver == null || gateManager == null
                || domainCatalogDataAccess == null) {
            getLogger().info("/navigate not started - road navigation is disabled or its services failed to initialize");
            return;
        }
        net.knightsandkings.knk.paper.config.NavigationConfig navigation = config.navigation();
        java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);
        ensureDomainDataAccesses();
        net.knightsandkings.knk.core.dataaccess.FetchPolicy lookup = net.knightsandkings.knk.core.dataaccess.FetchPolicy.API_THEN_CACHE_REFRESH;
        var domainLocations = new net.knightsandkings.knk.core.navigation.DomainLocationResolver(
            id -> locationsDataAccess.getByIdAsync(id, lookup).thenApply(r -> r != null ? r.value() : java.util.Optional.empty()),
            id -> townsDataAccess.getByIdAsync(id, lookup).thenApply(r -> r != null ? r.value() : java.util.Optional.empty()),
            id -> districtsDataAccess.getByIdAsync(id, lookup).thenApply(r -> r != null ? r.value() : java.util.Optional.empty()),
            id -> structuresDataAccess.getByIdAsync(id, lookup).thenApply(r -> r != null ? r.value() : java.util.Optional.empty()));
        this.navigationDestinations = new net.knightsandkings.knk.paper.navigation.NavigationDestinations(
            domainCatalogDataAccess, locationsDataAccess, domainLocations, roadNetworkCache::snapshot, System::currentTimeMillis);
        navigationDestinations.refresh();
        cacheManager.registerRefreshHook("navigation destinations", navigationDestinations::refresh);

        var access = new net.knightsandkings.knk.paper.navigation.NavigationAccess(
            gateManager, () -> siegeGates, regionTracker.regionIds(), regionDomainResolver,
            this::hasRegionBypass, new net.knightsandkings.knk.core.regions.DomainAccessEvaluator(),
            // rev. 7 Part C (KNG-92): the catalogue knows which domains' rules are lifted off the roads
            domain -> domain.id() == null || !navigationDestinations.roadAccessIgnored(domain.id()));
        var eligibility = new net.knightsandkings.knk.paper.navigation.NavigationEligibility(
            uuid -> joinLoadingGuard != null && joinLoadingGuard.isLoading(uuid),
            uuid -> adminFreezeManager != null && adminFreezeManager.isFrozen(uuid),
            net.knightsandkings.knk.paper.navigation.NavigationEligibility.siegeMembers(() -> siegeService));
        this.navigationRouting = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "knk-navigation-routing");
            t.setDaemon(true);
            return t;
        });
        net.knightsandkings.knk.paper.navigation.NavigationService.Walk walk = initializeWalkPaths(navigation, access);
        var hud = new net.knightsandkings.knk.paper.navigation.NavigationHud(
            new net.knightsandkings.knk.core.roads.route.EtaEstimator(navigation.sessionParameters().sprintSpeed()));
        // KNG-74: the arrow keeps off the action bar while a domain-access refusal there is fresh.
        hud.yieldActionBarWhile(uuid -> domainAccess != null && domainAccess.holdsActionBar(uuid));
        var trail = new net.knightsandkings.knk.paper.navigation.TrailRenderer(navigation.trail(),
            net.knightsandkings.knk.paper.utils.TickBudget.server());
        this.liveEdgeTags = startLiveEdgeTags(mainThread);
        this.navigationService = new net.knightsandkings.knk.paper.navigation.NavigationService(
            new net.knightsandkings.knk.paper.navigation.NavigationService.Deps(
                this, navigation, liveEdgeTags::snapshot, access,
                new net.knightsandkings.knk.paper.navigation.WorldGuardRegionShapes(), eligibility, hud, trail,
                mainThread, navigationRouting, () -> (long) org.bukkit.Bukkit.getCurrentTick(),
                event -> getServer().getPluginManager().callEvent(event), getLogger(), walk));

        // Live changes (DESIGN §6.7, plan D13): gate state (R4, fired on any thread), siege lockdowns (R24),
        // domain cache refreshes and network snapshot swaps all re-check the active routes on the main thread.
        gateManager.addStateListener(doorId -> mainThread.execute(() -> navigationService.onGateChanged(doorId)));
        if (siegeService != null) {
            siegeService.addObserver(navigationService);
        }
        roadNetworkCache.addListener(navigationService::onNetworkChanged);
        cacheManager.registerRefreshHook("navigation routes", navigationService::onAvailabilityChanged);
        getServer().getPluginManager().registerEvents(
            new net.knightsandkings.knk.paper.navigation.NavigationListener(navigationService), this);
        navigationService.start();
        getLogger().info("Road navigation (/navigate) initialized");
    }

    /**
     * Live edge tags for the router (KNG-27 live test 2026-10-08, findings N3/N4): the WorldGuard regions and
     * gate doors each edge passes now, added to the stored tags - a domain region made after the build or a
     * gate a recording missed counts without a rebuild. Re-tagged when a world's network changes and every
     * minute, a budgeted number of region lookups per tick; a change re-checks the active routes.
     */
    private net.knightsandkings.knk.paper.roads.LiveEdgeTags startLiveEdgeTags(java.util.concurrent.Executor mainThread) {
        var regionIds = regionTracker.regionIds();
        var budget = net.knightsandkings.knk.paper.utils.TickBudget.server();
        var tags = new net.knightsandkings.knk.paper.roads.LiveEdgeTags(roadNetworkCache::snapshot,
            new net.knightsandkings.knk.paper.roads.LiveEdgeTags.Probe() {
                @Override
                public java.util.Set<String> regionsAt(String world, int x, int feetY, int z) {
                    org.bukkit.World w = org.bukkit.Bukkit.getWorld(world);
                    return w == null || regionIds == null ? java.util.Set.of() : regionIds.at(w, x, feetY, z);
                }

                @Override
                public net.knightsandkings.knk.core.roads.build.GateCells gates(String world) {
                    return net.knightsandkings.knk.paper.roads.GateCellsIndex.of(gateManager, world);
                }
            },
            () -> budget.perTick(net.knightsandkings.knk.paper.roads.LiveEdgeTags.LOOKUPS_PER_TICK,
                net.knightsandkings.knk.paper.roads.LiveEdgeTags.LOOKUPS_PER_TICK_LAGGING),
            task -> org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(this, task), mainThread,
            world -> {
                if (navigationService != null) {
                    navigationService.onNetworkChanged(world);
                }
            },
            regions -> regionDomainResolver.warmCache(regions), System::currentTimeMillis);
        roadNetworkCache.addListener(tags::refresh);
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, tags::tick, 1L, 1L);
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this,
            () -> tags.refreshAll(org.bukkit.Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList()),
            net.knightsandkings.knk.paper.roads.LiveEdgeTags.INTERVAL_TICKS, net.knightsandkings.knk.paper.roads.LiveEdgeTags.INTERVAL_TICKS);
        return tags;
    }

    /**
     * KNG-51 (docs/specs/navigation/LAST_MILE_PATHFINDING.md §3, §8): walkable paths for direct mode -
     * the shared chunk capture (main thread, tick budget, TTL cache), the player's gate/door/region
     * access and one stateless search on its own executor of {@code max-concurrent-searches} threads
     * (not the road router's single thread: a walk search may wait for domain lookups). Null - straight
     * lines, exactly as before KNG-51 - when {@code navigation.walk.enabled} is false (the kill switch).
     */
    private net.knightsandkings.knk.paper.navigation.NavigationService.Walk initializeWalkPaths(
            net.knightsandkings.knk.paper.config.NavigationConfig navigation,
            net.knightsandkings.knk.paper.navigation.NavigationAccess access) {
        if (!navigation.walk().enabled()) {
            getLogger().info("Walkable navigation paths disabled (navigation.walk.enabled: false) - direct mode draws straight lines");
            return null;
        }
        this.walkSnapshots = new net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService(
            navigation.passabilityRules(net.knightsandkings.knk.paper.roads.ChunkSnapshotSurfaceGrid.bukkitCollidable()),
            navigation.walk(), net.knightsandkings.knk.paper.utils.TickBudget.server(), System::currentTimeMillis);
        walkSnapshots.start(this);
        java.util.concurrent.atomic.AtomicInteger walkThreads = new java.util.concurrent.atomic.AtomicInteger();
        this.walkSearches = Executors.newFixedThreadPool(navigation.walk().maxConcurrentSearches(), r -> {
            Thread t = new Thread(r, "knk-navigation-walk-" + walkThreads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        var preparer = net.knightsandkings.knk.paper.navigation.walk.WalkLegPreparer.server(walkSnapshots,
            net.knightsandkings.knk.paper.navigation.walk.WorldGuardWalkAccess.factory(access), navigation.walk(), gateManager);
        return new net.knightsandkings.knk.paper.navigation.NavigationService.Walk(preparer,
            new net.knightsandkings.knk.core.roads.walk.WalkSearch(), walkSearches);
    }

    /**
     * R18 (road navigation plan §2): the Location/Street/District/Structure data accesses were never
     * constructed on trunk before KNG-17 and KNG-27; both build them here, once, as fields - /spawn
     * (KNG-17) and the road code share them. Safe to call more than once.
     */
    private void ensureDomainDataAccesses() {
        if (locationsDataAccess == null) {
            locationsDataAccess = dataAccessFactory.createLocationsDataAccess(config.cache().ttl(), locationsQueryApi);
        }
        if (streetsDataAccess == null) {
            // CacheManager has no street cache (streets were never read by the plugin before): one here.
            streetsDataAccess = dataAccessFactory.createStreetsDataAccess(
                new net.knightsandkings.knk.core.cache.StreetCache(config.cache().ttl()), streetsQueryApi);
        }
        if (districtsDataAccess == null) {
            districtsDataAccess = dataAccessFactory.createDistrictsDataAccess(cacheManager.getDistrictCache(), districtsQueryApi);
        }
        if (structuresDataAccess == null) {
            structuresDataAccess = dataAccessFactory.createStructuresDataAccess(cacheManager.getStructureCache(), structuresQueryApi);
        }
    }

    /** The road network cache (null while navigation is disabled); Phase 4's /navigate reads it. */
    public net.knightsandkings.knk.paper.roads.RoadNetworkCache getRoadNetworkCache() {
        return roadNetworkCache;
    }

    /** The WorldGuard region tracker (R8: its {@code regionIds()} is the shared region lookup). */
    public WorldGuardRegionTracker getRegionTracker() {
        return regionTracker;
    }

    public RegionDomainResolver getRegionDomainResolver() {
        return regionDomainResolver;
    }

    /** Domain discovery's exclusions; null when discovery is disabled. */
    public net.knightsandkings.knk.paper.discovery.DiscoveryEligibility getDiscoveryEligibility() {
        return discoveryEligibility;
    }

    /**
     * KNG-18 Phase 1 (docs/specs/private-messages/DESIGN.md §3.3): /msg, /reply, social spy and the
     * local PM log; Phase 2: ignore lists; Phase 3: knk-web-api's PM log and the command-log filter.
     * Needs knkPermissible, adminFreezeManager, the user cache and the API client.
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
        java.util.List<net.knightsandkings.knk.paper.user.PrivateMessageLogger> pmLogSinks = new java.util.ArrayList<>();
        if (pmConfig.log().localEnabled()) {
            var localLog = new net.knightsandkings.knk.paper.user.LocalFilePrivateMessageLog(
                getDataFolder().toPath().resolve("logs"), pmConfig.log().localRetentionDays(), clock);
            localLog.start();
            pmLogSinks.add(localLog);
        }
        // Phase 3: knk-web-api's PM log (POST api/private-message-log/batch needs the service key).
        if (pmConfig.log().apiEnabled()) {
            this.apiPrivateMessageLog = new net.knightsandkings.knk.paper.user.ApiPrivateMessageLog(
                new net.knightsandkings.knk.core.messaging.PrivateMessageLogShipper(
                    apiClient.getPrivateMessageLogApi(),
                    getDataFolder().toPath().resolve("private-messages-spool.jsonl"),
                    net.knightsandkings.knk.core.messaging.PrivateMessageLogShipper.DEFAULT_CAPACITY,
                    net.knightsandkings.knk.core.messaging.PrivateMessageLogShipper.DEFAULT_BATCH_SIZE,
                    java.time.Duration.ofSeconds(pmConfig.log().flushSeconds()),
                    clock));
            apiPrivateMessageLog.start();
            pmLogSinks.add(apiPrivateMessageLog);
            if (!"apikey".equalsIgnoreCase(config.api().auth().type())) {
                getLogger().warning("private-messages.log.api-enabled is on but api.auth.type is not apikey: knk-web-api "
                    + "will refuse the PM log (401) and messages will pile up in the queue.");
            }
        }
        this.privateMessageLogger = net.knightsandkings.knk.paper.user.PrivateMessageLogger.all(pmLogSinks);
        // Only with a PM log in place: otherwise the server log would be the only record.
        if (pmConfig.log().filterCommandLog() && !pmLogSinks.isEmpty()) {
            var filter = new net.knightsandkings.knk.paper.chat.PrivateMessageCommandLogFilter();
            if (filter.install()) {
                this.privateMessageCommandLogFilter = filter;
                getLogger().info("Private messages are filtered out of the server command log");
            }
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
        if (gameSettingsManager != null) {
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.GameSettingsWorldListener(this, gameSettingsManager), this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.GameSettingsMotdListener(gameSettingsManager), this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.GameSettingsWeatherCommandListener(gameSettingsManager), this);
        }
        pluginManager.registerEvents(new PlayerListener(usersDataAccess, gameSettingsManager, this.getCacheManager(), knkPermissible, usersCommandApi, kitsCommandApi, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess, ignoreService), this);
        if (playerCurrencyService != null) {
            // Drops a leaving player's open /pay confirmation and its expiry notice.
            pluginManager.registerEvents(playerCurrencyService, this);
        }
        pluginManager.registerEvents(new UserAccountListener(this, userManager, joinLoadingGuard, config.messages(), getLogger(), playerCurrencyService), this);
        getLogger().info("Registered UserAccountListener for account management");
        pluginManager.registerEvents(new JoinLoadingRestrictionListener(joinLoadingGuard), this);
        pluginManager.registerEvents(new ModeListener(modeService), this);
        getLogger().info("Registered ModeListener for owner/staff mode restore");
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.AdminFreezeListener(this, adminFreezeManager, usersDataAccess), this);
        getLogger().info("Registered AdminFreezeListener for /freeze enforcement");
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.PrivateMessageSessionListener(
            this, messagingService, spyService, ignoreService), this);
        // KNG-25: /minecraft:tell and /minecraft:w broke the typing player's secure chat (always on -
        // the plugin's tell/w aliases shadow the vanilla redirects whether or not the block below runs).
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.ShadowedVanillaCommandListener(), this);
        if (config.privateMessages().blockVanillaCommands()) {
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.VanillaMessagingBlockListener(), this);
            getLogger().info("Registered VanillaMessagingBlockListener (/minecraft:msg|tell|w -> /msg; /teammsg, /tm, /me off)");
        }
    }
    
    /** Private messages waiting for knk-web-api's PM log (/knk health); -1 when that sink is off. */
    public int privateMessageLogQueueDepth() {
        var apiLog = apiPrivateMessageLog;
        return apiLog == null ? -1 : apiLog.queueDepth();
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
                staffTeleportCommand,
                modeService,
                districtGateLoader,
                gateDoorRegionCaptureHandler,
                serverId,
                menuService,
                apiClient.getClansQueryApi(),
                userAdminService,
                playerCurrencyService
            );
            // KNG-77/78/79: /gate and /gatedoor run /knk gate|gatedoor (same permissions and warm-up);
            // `here` radius and look-at reach from config.yml gates.here.* / gates.lookat.*.
            knkAdminCommand.setGateTargetingSettings(gateTargetingSettings());
            registerKnkShortcut("gate", knkAdminCommand);
            registerKnkShortcut("gatedoor", knkAdminCommand);
            if (managedRegions != null) {
                var regionsCommand = new net.knightsandkings.knk.paper.commands.RegionsAdminCommand(managedRegions, domainAccessSync, this);
                knkAdminCommand.registerSubcommand(
                    net.knightsandkings.knk.paper.commands.RegionsAdminCommand.metadata(),
                    regionsCommand::execute,
                    (sender, args) -> args.length == 1 && "repair".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))
                        ? java.util.List.of("repair") : java.util.List.of());
            }
            if (lootboxAdminCommand != null) {
                var lootboxAdmin = lootboxAdminCommand;
                knkAdminCommand.registerSubcommand(
                    new net.knightsandkings.knk.paper.commands.CommandMetadata(
                        "lootbox",
                        "Spawn, list, give and despawn lootboxes; issue lootbox token items; manage lootbox spawn areas",
                        net.knightsandkings.knk.paper.commands.LootboxAdminCommand.usage(),
                        null, // each action checks its own knk.lootbox.admin.<action> node
                        List.of("/knk lootbox spawn weapons 5", "/knk lootbox list", "/knk lootbox give Steve armor",
                            "/knk lootbox token Steve weapons 5 2",
                            "/knk lootbox area create spawn", "/knk lootbox area delete spawn")),
                    lootboxAdmin::execute,
                    lootboxAdmin::tabComplete);
            }
            if (userAdminService != null) {
                // Domain discovery (KNG-20): /knk discovery list|reset|status, node knk.admin.discovery.
                var discoveryCommand = new net.knightsandkings.knk.paper.commands.DiscoveryAdminCommand(
                    apiClient.getDiscoveriesApi(), userAdminService, MenuService.mainThreadExecutor(this),
                    player -> cacheManager.getUserCache().getStale(player.getUniqueId())
                        .map(net.knightsandkings.knk.core.domain.users.UserSummary::id).orElse(null),
                    (player, node) -> knkPermissible != null && knkPermissible.hasPermission(player, node),
                    () -> discoveryTracker, () -> discoverySpool,
                    uuid -> {
                        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(uuid);
                        if (target != null) {
                            afterDiscoveryReset(target);
                        } else if (discoveriesMenuFeature != null) {
                            discoveriesMenuFeature.invalidate(uuid);
                        }
                    }
                );
                knkAdminCommand.registerSubcommand(
                    net.knightsandkings.knk.paper.commands.DiscoveryAdminCommand.metadata(),
                    discoveryCommand,
                    (sender, args) -> {
                        if (args.length == 1) {
                            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
                            return java.util.List.of("list", "reset", "status").stream()
                                .filter(value -> value.startsWith(prefix)).toList();
                        }
                        if (args.length == 2 && ("list".equalsIgnoreCase(args[0]) || "reset".equalsIgnoreCase(args[0]))) {
                            return visiblePlayers.complete(sender, args[1]);
                        }
                        return java.util.List.of();
                    }
                );
            }
            // Road navigation (KNG-27): /knk road …, node knk.admin.road. registerCommands() runs before
            // initializeRoads(), so every service is read lazily; a null one means navigation is disabled.
            var roadAdmin = new net.knightsandkings.knk.paper.roads.RoadAdminCommand(
                apiClient.getRoadNetworkQueryApi(), apiClient.getRoadNetworkCommandApi(), streetsQueryApi,
                MenuService.mainThreadExecutor(this),
                (player, node) -> knkPermissible != null && knkPermissible.hasPermission(player, node),
                () -> roadNetworkCache, () -> roadDirtyTracker, () -> roadOverlayRenderer,
                () -> roadSurveyService, () -> roadBuildQueue);
            roadAdmin.setNavigation(() -> navigationService, () -> navigationDestinations);
            roadAdmin.setProposals(() -> roadProposals);
            roadAdmin.setLiveTags(() -> liveEdgeTags);
            knkAdminCommand.registerSubcommand(
                net.knightsandkings.knk.paper.roads.RoadAdminCommand.metadata(), roadAdmin, roadAdmin::complete);
            // Road navigation Phase 4: /navigate (/nav), DESIGN §6.1. The services are read lazily - they
            // exist only when navigation.enabled and the road cache started (initializeNavigation).
            registerTabCommand("navigate", new net.knightsandkings.knk.paper.navigation.NavigateCommand(
                () -> navigationService, () -> navigationDestinations,
                (player, node) -> knkPermissible != null && knkPermissible.hasPermission(player, node),
                MenuService.mainThreadExecutor(this)));
            // Location retention (KNG-80): /knk location here|tp|orphans replaces the built-in /knk location here;
            // each action checks its own knk.admin.location* node. tp goes through the KNG-17 teleport engine.
            if (apiClient != null) {
                var permissionGate = commandPermissions();
                var locationAdmin = new net.knightsandkings.knk.paper.locations.LocationAdminCommand(
                    apiClient.getLocationRetentionApi(),
                    permissionGate::whenAllowed,
                    permissionGate::has,
                    () -> teleportService,
                    org.bukkit.Bukkit::getWorld,
                    player -> modeService != null && modeService.isVanished(player),
                    MenuService.mainThreadExecutor(this),
                    player -> new net.knightsandkings.knk.paper.commands.LocationDebugCommand(this).onCommand(player, null, "knk", new String[0]));
                knkAdminCommand.registerSubcommand(
                    net.knightsandkings.knk.paper.locations.LocationAdminCommand.metadata(), locationAdmin, locationAdmin::complete);
                // No top-level node (each action checks its own), so only list it to holders of one of them.
                knkAdminCommand.setSubcommandVisibility("location", locationAdmin::visibleTo);
            }
            // KNG-80: the weekly orphan check's digest, for online staff with knk.admin.location.orphans.notify
            // (or the next one to join when none is online).
            if (playerNotificationPoller != null && knkPermissible != null) {
                var orphanNotifier = new net.knightsandkings.knk.paper.locations.LocationOrphanNotifier(
                    knkPermissible::checkAsync, org.bukkit.Bukkit::getOnlinePlayers, MenuService.mainThreadExecutor(this));
                playerNotificationPoller.setServerNotificationHandler(
                    net.knightsandkings.knk.core.domain.users.PlayerNotification.TYPE_LOCATION_ORPHAN_DIGEST, orphanNotifier::handle);
                getServer().getPluginManager().registerEvents(orphanNotifier, this);
            }
            knkAdminCommand.setCommandPermissions(commandPermissions());
            knkCommand.setExecutor(knkAdminCommand);
            knkCommand.setTabCompleter(knkAdminCommand);
            getLogger().info("Registered /knk admin command");
        } else {
            getLogger().warning("Failed to register /knk command - not defined in plugin.yml?");
        }

        PluginCommand accountCommand = getCommand("account");
        if (accountCommand != null) {
            AccountCommandRegistry executor = new AccountCommandRegistry(
                this,
                userManager,
                chatCaptureManager,
                userAccountApi,
                config,
                cooldownManager
            );
            accountCommand.setExecutor(executor);
            accountCommand.setTabCompleter(executor);
            getLogger().info("Registered /account command with cooldown management");
        } else {
            getLogger().warning("Failed to register /account command - not defined in plugin.yml?");
        }

        registerModeCommand("ownermode", ActiveMode.OWNER);
        registerModeCommand("staffmode", ActiveMode.STAFF);

        // KNG-24: gated on their in-house node in the executor, not by plugin.yml's permission:
        // (Bukkit never sees in-house grants, so non-op staff got "Unknown or incomplete command").
        var commandPermissions = commandPermissions();
        var gatedCommandVisibility = new net.knightsandkings.knk.paper.listeners.GatedCommandVisibilityListener(
            commandPermissions, MenuService.mainThreadExecutor(this));
        registerGatedCommand("freeze", "knk.freeze", new net.knightsandkings.knk.paper.commands.FreezeCommand(userAdminService, true, visiblePlayers),
            commandPermissions, gatedCommandVisibility);
        registerGatedCommand("unfreeze", "knk.unfreeze", new net.knightsandkings.knk.paper.commands.FreezeCommand(userAdminService, false, visiblePlayers),
            commandPermissions, gatedCommandVisibility);
        registerGatedCommand("staffchat", net.knightsandkings.knk.paper.commands.StaffChatCommand.NODE,
            new net.knightsandkings.knk.paper.commands.StaffChatCommand(commandPermissions::hasAsync,
                MenuService.mainThreadExecutor(this), org.bukkit.Bukkit::getOnlinePlayers),
            commandPermissions, gatedCommandVisibility);
        getServer().getPluginManager().registerEvents(gatedCommandVisibility, this);
        registerTabCommand("msg", new net.knightsandkings.knk.paper.commands.MessageCommand(messagingService, visiblePlayers));
        registerTabCommand("reply", new net.knightsandkings.knk.paper.commands.ReplyCommand(messagingService));
        registerTabCommand("socialspy", new net.knightsandkings.knk.paper.commands.SocialSpyCommand(
            new net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport(
                knkPermissible, MenuService.mainThreadExecutor(this),
                org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers),
            spyService, getLogger()));
        registerIgnoreCommands();

        registerTabCommand("kit", new net.knightsandkings.knk.paper.commands.KitCommand(
            this,
            kitsDataAccess,
            kitGrantFlow,
            visiblePlayers
        ));

        if (lootboxCommand != null) {
            registerTabCommand("lootbox", lootboxCommand);
        }

        // Content port CP1: /menu opens the InventoryMenu hub (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md §3).
        registerSimpleCommand("menu", new net.knightsandkings.knk.paper.commands.MenuCommand(() -> menuService));
        // Domain discovery (KNG-20): /discoveries (/disc) opens discoveries.main.
        registerSimpleCommand("discoveries", new net.knightsandkings.knk.paper.commands.DiscoveriesCommand(() -> menuService));

        // Currency ledger Phase 3 (docs/specs/currency-payments/DESIGN.md §3.6).
        if (playerCurrencyService != null) {
            registerTabCommand("pay", new net.knightsandkings.knk.paper.commands.PayCommand(playerCurrencyService));
            registerTabCommand("balance", new net.knightsandkings.knk.paper.commands.BalanceCommand(playerCurrencyService));
            registerTabCommand("baltop", new net.knightsandkings.knk.paper.commands.BaltopCommand(playerCurrencyService));
            registerTabCommand("transactions", new net.knightsandkings.knk.paper.commands.TransactionsCommand(playerCurrencyService));
        } else {
            getLogger().warning("/pay, /balance, /baltop and /transactions not registered - currency service failed to initialize");
        }

        registerPlayerCommands();

        if (staffTeleportCommand != null) {
            registerTabCommand("tp", staffTeleportCommand);
            registerTabCommand("tphere", staffTeleportCommand.withForm(
                net.knightsandkings.knk.paper.commands.StaffTeleportCommand.Form.TPHERE));
        } else {
            getLogger().warning("/tp and /tphere not registered - the teleport engine failed to initialize");
        }
        if (teleportRequestCommand != null) {
            registerTabCommand("tpa", teleportRequestCommand);
            registerTabCommand("tpahere", teleportRequestCommand.withForm(
                net.knightsandkings.knk.paper.commands.TeleportRequestCommand.Form.TPAHERE));
            registerTabCommand("tpaccept", teleportRequestCommand.withForm(
                net.knightsandkings.knk.paper.commands.TeleportRequestCommand.Form.ACCEPT));
            registerTabCommand("tpdeny", teleportRequestCommand.withForm(
                net.knightsandkings.knk.paper.commands.TeleportRequestCommand.Form.DENY));
            registerTabCommand("tpcancel", teleportRequestCommand.withForm(
                net.knightsandkings.knk.paper.commands.TeleportRequestCommand.Form.CANCEL));
        } else {
            getLogger().warning("/tpa, /tpahere, /tpaccept, /tpdeny, /tpcancel not registered - the teleport engine failed to initialize");
        }
        if (spawnCommand != null) {
            registerTabCommand("spawn", spawnCommand);
        } else {
            getLogger().warning("/spawn not registered - the teleport engine or the spawn lookup failed to initialize");
        }
        if (warpCommand != null) {
            registerTabCommand("warp", warpCommand);
            registerTabCommand("warps", warpCommand.withForm(net.knightsandkings.knk.paper.commands.WarpCommand.Form.LIST));
        } else {
            getLogger().warning("/warp and /warps not registered - the teleport engine or the API client failed to initialize");
        }
        if (backCommand != null) {
            registerTabCommand("back", backCommand);
        } else {
            getLogger().warning("/back not registered - the teleport engine failed to initialize");
        }
    }

    /**
     * Teleport engine (docs/specs/teleport/DESIGN.md §3.4), the staff teleport commands (Phase 1) and
     * player teleport requests (Phase 3). Siege guards plug in later through {@link #registerTeleportRestriction}.
     */
    private void initializeTeleports() {
        if (knkPermissible == null || userAdminService == null || modeService == null || adminFreezeManager == null) {
            getLogger().warning("Teleport engine not started - permissions/user services failed to initialize");
            return;
        }
        java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);
        this.teleportService = new net.knightsandkings.knk.paper.teleport.TeleportService(
            mainThread, knkPermissible::hasPermissionAsync, config.teleport(), System::currentTimeMillis,
            net.knightsandkings.knk.paper.teleport.BukkitBlockProbe::new
        );
        teleportService.registerRestriction(
            new net.knightsandkings.knk.paper.teleport.FreezeTeleportRestriction(adminFreezeManager::isFrozen));
        if (usersCommandApi != null && usersDataAccess != null) {
            // Phase 2: every staff teleport also lands in the web API's audit log (DESIGN.md §3.10).
            teleportService.setAuditor(new net.knightsandkings.knk.paper.teleport.TeleportAuditor(usersCommandApi,
                uuid -> usersDataAccess.getByUuidAsync(uuid).thenApply(result ->
                    result != null && result.isSuccess() && result.value().isPresent() ? result.value().get().id() : null)));
        } else {
            getLogger().warning("Staff teleports won't be audited - the users API isn't available");
        }
        var targets = new net.knightsandkings.knk.paper.teleport.VisibleTargetResolver(
            org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers, modeService::isVanished);
        // Phase 3: /tpa, /tpahere and their answers (DESIGN.md §3.5).
        this.teleportRequestService = new net.knightsandkings.knk.paper.teleport.TeleportRequestService(
            teleportService, mainThread, knkPermissible::hasPermissionAsync, targets, org.bukkit.Bukkit::getPlayer);
        // Phase 5: warp gem prices and /tpa coin fees are charged by the web API (DESIGN.md §3.5/§3.7).
        net.knightsandkings.knk.paper.teleport.TeleportCharges charges = createTeleportCharges();
        teleportRequestService.setCharges(charges);
        wireTeleportGroupSettings(charges);
        // Private messages' ignore list (KNG-18): a player you /ignore can't /tpa or /tpahere you.
        if (ignoreService != null) {
            teleportRequestService.setIgnoreCheck(ignoreService::ignores);
        }
        if (config.teleport().request().isPaid() && charges == null) {
            getLogger().warning("teleport.request.price-coins is " + config.teleport().request().priceCoins()
                + " but the API client isn't available to charge it, so /tpa and /tpahere will be refused.");
        }
        // Phase 7 + KNG-42: /back to the last death or teleport origin the player's nodes allow; siege
        // deaths are excluded through registerBackDeathExclusion. Registers itself with the engine.
        this.backService = new net.knightsandkings.knk.paper.teleport.BackService(
            teleportService, mainThread, knkPermissible::hasPermissionAsync, org.bukkit.Bukkit::getWorld);
        backService.setCharges(charges);
        if (config.teleport().back().isPaid() && charges == null) {
            getLogger().warning("teleport.back.price-coins is " + config.teleport().back().priceCoins()
                + " but the API client isn't available to charge it, so /back will be refused.");
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            teleportService.tick(adminFreezeManager::isFrozen);
            teleportRequestService.tick();
            backService.purgeExpired();
        }, 5L, 5L);

        var pluginManager = getServer().getPluginManager();
        var warmupListener = new net.knightsandkings.knk.paper.listeners.TeleportWarmupListener(
            teleportService, teleportRequestService);
        pluginManager.registerEvents(warmupListener, this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.CombatTagListener(teleportService), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.BackDeathListener(backService), this);

        var support = new net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport(
            knkPermissible, mainThread, org.bukkit.Bukkit::getPlayerExact, org.bukkit.Bukkit::getOnlinePlayers,
            () -> cacheManager.getUserCache().usernamesSnapshot()
        );
        // Same rank check as /freeze, /inventory and /knk user (console and knk.admin.user.manage.all pass).
        net.knightsandkings.knk.paper.commands.support.TargetRankCheck rankCheck = (sender, targetName, onAllowed) ->
            userAdminService.resolveTarget(sender, targetName, summary ->
                userAdminService.withRankCheck(sender, summary, api -> onAllowed.accept(summary), () -> { }));
        this.teleportRequestCommand = new net.knightsandkings.knk.paper.commands.TeleportRequestCommand(
            net.knightsandkings.knk.paper.commands.TeleportRequestCommand.Form.TPA, support, targets, teleportRequestService);
        this.staffTeleportCommand = new net.knightsandkings.knk.paper.commands.StaffTeleportCommand(
            net.knightsandkings.knk.paper.commands.StaffTeleportCommand.Form.TP, support, rankCheck, targets, teleportService,
            modeService::isVanished, org.bukkit.Bukkit::getWorld,
            () -> org.bukkit.Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList()
        );
        // Phase 4: /spawn (DESIGN.md §3.6).
        this.spawnCommand = createSpawnCommand(support, rankCheck, targets);
        if (spawnCommand != null) {
            spawnCommand.setCharges(charges);
        }
        // Phase 5: /warp, /warps (DESIGN.md §3.7).
        this.warpCommand = createWarpCommand(support, rankCheck, targets, charges);
        if (warpCommand != null) {
            // Each player's cached destination list and user id go when they leave.
            warmupListener.addQuitHook(warpCommand::forget);
        }
        // Phase 7 + KNG-42: /back, /back <player> [-s].
        this.backCommand = new net.knightsandkings.knk.paper.commands.BackCommand(support, backService, rankCheck,
            targets, modeService::isVanished);
        // Phase 6: the teleport menu (teleport.destinations) runs the same /warp, /spawn and request paths,
        // and a bare /warp opens it (the chat list while the menu isn't available).
        this.teleportMenuParts = new net.knightsandkings.knk.paper.menu.content.TeleportMenuFeature.Teleports(
            teleportService, teleportDestinationsDataAccess, usersDataAccess != null ? teleportUserIdLookup() : null,
            knkPermissible::hasPermissionAsync, warpCommand, spawnCommand, teleportRequestService);
        if (warpCommand != null) {
            warpCommand.setMenuOpener(player -> {
                MenuService menus = menuService;
                String key = net.knightsandkings.knk.paper.menu.content.TeleportMenuFeature.MENU_KEY;
                if (menus == null || !menus.isMenuAvailable(key)) {
                    return false;
                }
                menus.openMenu(player, key);
                return true;
            });
        }
        getLogger().info("Teleport engine initialized (warmup " + config.teleport().warmupSeconds() + "s / "
            + config.teleport().warmupShortSeconds() + "s, cooldown " + config.teleport().cooldownSeconds() + "s)");
    }

    /**
     * Global Game Settings (docs/specs/game-settings/DESIGN.md, KNG-52): announcements, join spawn and
     * game mode, respawn policy, per-world time/weather/spawn, loaded-world reports. Reads
     * {@code GET /api/GameSettings} every {@code game-settings.refresh-interval-seconds}; resolves its
     * references through the /spawn resolver; {@code /knk cache refresh} re-reads everything.
     */
    private void initializeGameSettings() {
        if (apiClient == null) {
            getLogger().warning("Game settings not applied - the API client failed to initialize");
            return;
        }
        var settingsConfig = net.knightsandkings.knk.paper.settings.GameSettingsConfig.from(getConfig());
        this.gameSettingsManager = new net.knightsandkings.knk.paper.settings.GameSettingsManager(
            this,
            apiClient.getGameSettingsQueryApi(),
            apiClient.getGameSettingsCommandApi(),
            () -> spawnDestinationResolver,
            townsQueryApi,
            settingsConfig,
            new net.knightsandkings.knk.paper.settings.GameSettingsStore(getDataFolder().toPath(), settingsConfig.backupHistoryLimit()));
        gameSettingsManager.start();
        if (cacheManager != null) {
            cacheManager.registerRefreshHook("game settings", gameSettingsManager::refreshNow);
            // /spawn honours a group's spawn override (DESIGN §3.8); the groups come from the cached summary.
            if (spawnCommand != null) {
                spawnCommand.setPlayerSpawn(player -> gameSettingsManager.groupSpawnPoint(
                    cacheManager.getUserCache().getStale(player.getUniqueId())
                        .map(net.knightsandkings.knk.core.domain.users.UserSummary::permissionGroups)
                        .orElse(java.util.List.of())));
            }
        }
        getLogger().info("Game settings initialized (refresh every " + settingsConfig.refreshIntervalSeconds()
            + "s, world report check every " + settingsConfig.runtimeSyncIntervalSeconds() + "s)");
    }

    /**
     * {@code /spawn} (docs/specs/teleport/DESIGN.md §3.6): the spawn set on the web-app Game Settings
     * page ({@code GET /api/GameSettings}), resolved through the Location/Town/District/Structure
     * gateways and cached 5 min ({@code /knk cache refresh} drops it). Null when the API client or the
     * caches didn't start. The join teleport uses the same resolver (GameSettingsManager).
     */
    private net.knightsandkings.knk.paper.commands.SpawnCommand createSpawnCommand(
            net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport support,
            net.knightsandkings.knk.paper.commands.support.TargetRankCheck rankCheck,
            net.knightsandkings.knk.paper.teleport.VisibleTargetResolver targets) {
        if (apiClient == null || cacheManager == null || dataAccessFactory == null || townsDataAccess == null
                || locationsQueryApi == null || districtsQueryApi == null || structuresQueryApi == null) {
            getLogger().warning("/spawn not available - the API client or caches failed to initialize");
            return null;
        }
        // R18 (road navigation): one instance of each domain data access, shared with /navigate.
        ensureDomainDataAccesses();
        this.spawnDestinationResolver = net.knightsandkings.knk.paper.teleport.SpawnDestinationResolver.create(
            apiClient.getGameSettingsQueryApi(),
            locationsDataAccess,
            townsDataAccess,
            districtsDataAccess,
            structuresDataAccess,
            org.bukkit.Bukkit::getWorld,
            () -> org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0)
        );
        cacheManager.registerRefreshHook("spawn destination", spawnDestinationResolver::invalidate);
        return new net.knightsandkings.knk.paper.commands.SpawnCommand(
            support, rankCheck, targets, teleportService, spawnDestinationResolver, modeService::isVanished);
    }

    /**
     * Charges for paid teleports (docs/specs/teleport/DESIGN.md §3.7.3): the web API's
     * api/teleport-destinations charge/refund routes with retry-safe idempotency keys. Null when the
     * API client or the user lookup isn't available.
     */
    private net.knightsandkings.knk.paper.teleport.TeleportCharges createTeleportCharges() {
        if (apiClient == null || usersDataAccess == null) {
            return null;
        }
        return new net.knightsandkings.knk.paper.teleport.TeleportCharges(
            new net.knightsandkings.knk.core.teleport.TeleportCharger(apiClient.getTeleportDestinationsCommandApi()),
            teleportUserIdLookup(), org.bukkit.Bukkit::getWorld, org.bukkit.Bukkit::getPlayer);
    }

    /**
     * Teleport fees and cooldowns per permission group (Linear KNG-41): each player's settings from
     * {@code GET /api/teleport-destinations/policy}, cached like the warp list and dropped by
     * {@code /knk cache refresh}. The engine takes the cooldowns from it; /tpa and /spawn charges ask
     * it whether they cost anything. Without the API client everyone keeps the config defaults.
     */
    private void wireTeleportGroupSettings(net.knightsandkings.knk.paper.teleport.TeleportCharges charges) {
        if (charges == null) {
            return;
        }
        var lookup = teleportUserIdLookup();
        var policies = new net.knightsandkings.knk.core.dataaccess.TeleportPolicyDataAccess(
            apiClient.getTeleportDestinationsQueryApi(), lookup::idOf,
            java.time.Duration.ofSeconds(config.teleport().destinationsCacheSeconds()));
        charges.setPolicies(policies);
        teleportService.setCooldownPolicy(new net.knightsandkings.knk.paper.teleport.TeleportService.CooldownPolicy() {
            @Override
            public java.util.OptionalInt cooldownSeconds(java.util.UUID player,
                                                         net.knightsandkings.knk.core.teleport.TeleportKind kind) {
                return policies.cachedOrDefault(player).of(kind).cooldown();
            }

            @Override
            public void prefetch(java.util.UUID player) {
                policies.refresh(player, net.knightsandkings.knk.core.dataaccess.TeleportPolicyDataAccess.PLAYER_READ_MAX_AGE);
            }
        });
        if (cacheManager != null) {
            cacheManager.registerRefreshHook("teleport group settings", policies::invalidateAll);
        }
    }

    /** A player's knk user id through the users cache; null when they have no account or the lookup failed. */
    private net.knightsandkings.knk.paper.teleport.TeleportAuditor.UserIdLookup teleportUserIdLookup() {
        return uuid -> usersDataAccess.getByUuidAsync(uuid).thenApply(result ->
            result != null && result.isSuccess() && result.value().isPresent() ? result.value().get().id() : null);
    }

    /**
     * {@code /warp} and {@code /warps} (docs/specs/teleport/DESIGN.md §3.7): each player's destination
     * list from {@code GET /api/teleport-destinations}, cached {@code teleport.destinations.cache-seconds}
     * and dropped by {@code /knk cache refresh} and after a charge. Null without the API client.
     */
    private net.knightsandkings.knk.paper.commands.WarpCommand createWarpCommand(
            net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport support,
            net.knightsandkings.knk.paper.commands.support.TargetRankCheck rankCheck,
            net.knightsandkings.knk.paper.teleport.VisibleTargetResolver targets,
            net.knightsandkings.knk.paper.teleport.TeleportCharges charges) {
        if (charges == null || cacheManager == null) {
            getLogger().warning("/warp not available - the API client or caches failed to initialize");
            return null;
        }
        this.teleportDestinationsDataAccess = new net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess(
            apiClient.getTeleportDestinationsQueryApi(),
            java.time.Duration.ofSeconds(config.teleport().destinationsCacheSeconds()));
        charges.setOnCharged(teleportDestinationsDataAccess::invalidate);
        cacheManager.registerRefreshHook("warp destinations", teleportDestinationsDataAccess::invalidateAll);
        return new net.knightsandkings.knk.paper.commands.WarpCommand(
            net.knightsandkings.knk.paper.commands.WarpCommand.Form.WARP,
            new net.knightsandkings.knk.paper.commands.WarpCommand.Deps(support, rankCheck, targets, teleportService,
                teleportDestinationsDataAccess, charges, teleportUserIdLookup(), knkPermissible::hasPermissionAsync,
                org.bukkit.Bukkit::getWorld, modeService::isVanished));
    }

    /**
     * KNG-56: domain AllowEntry/AllowExit, enforced by WorldGuard from the flags on its regions (known at startup,
     * at join and while the API is down):
     * <ul>
     *   <li>a WorldGuard session handler refuses walking, gliding, swimming, riding, embarking and teleporting over a
     *       border; WorldGuard puts the player back at their last allowed position;</li>
     *   <li>{@code DomainAccessListener} refuses mounting across a border, corrects a respawn point the player may not
     *       reach, and moves a player who joins inside a domain they may not enter to the world spawn;</li>
     *   <li>the teleport engine refuses up front with the same verdict.</li>
     * </ul>
     * Bypass: knk.region.bypass (also carried by a staff teleport, docs/specs/teleport/DESIGN.md §4 D11) and
     * WorldGuard's own region bypass. Owners and members of a region pass it.
     */
    /**
     * {@code knk.region.bypass}, also carried by a staff teleport (docs/specs/teleport/DESIGN.md §4 D11):
     * who ignores domain AllowEntry/AllowExit - at the border (KNG-56) and in navigation's routes and
     * walk paths alike.
     */
    private boolean hasRegionBypass(org.bukkit.entity.Player player) {
        String bypassNode = net.knightsandkings.knk.paper.teleport.TeleportNodes.REGION_BYPASS;
        return player != null
            && ((knkPermissible != null && knkPermissible.hasPermission(player, bypassNode))
                || (teleportService != null && teleportService.hasInFlightBypass(player.getUniqueId(), bypassNode)));
    }

    private void wireDomainAccess() {
        if (!net.knightsandkings.knk.paper.regions.access.DomainAccessFlags.registered()) {
            getLogger().severe("[KnK Access] Domain access flags are not registered with WorldGuard; "
                + "domain AllowEntry/AllowExit is NOT enforced");
            return;
        }
        var guardSettings = new net.knightsandkings.knk.core.regions.access.RefusalGuard.Settings(
            Math.max(0, getConfig().getLong("regions.access.message-interval-ms", 2000)),
            getConfig().getBoolean("regions.access.load-guard.enabled", true),
            Math.max(1, getConfig().getInt("regions.access.load-guard.max-refusals-per-second", 20)),
            Math.max(1, getConfig().getInt("regions.access.load-guard.window-seconds", 3)) * 1000L,
            Math.max(1, getConfig().getInt("regions.access.load-guard.escalation-window-seconds", 60)) * 1000L,
            Math.max(0, getConfig().getLong("regions.access.chat-quiet-period-ms",
                net.knightsandkings.knk.core.regions.access.RefusalGuard.Settings.DEFAULT_CHAT_QUIET_PERIOD_MILLIS)),
            Math.max(0, getConfig().getLong("regions.access.action-bar-hold-ms",
                net.knightsandkings.knk.core.regions.access.RefusalGuard.Settings.DEFAULT_ACTION_BAR_HOLD_MILLIS)));
        this.domainAccess = new net.knightsandkings.knk.paper.regions.access.DomainAccessService(
            new net.knightsandkings.knk.paper.regions.access.WorldGuardRegionAccessLookup(),
            new net.knightsandkings.knk.core.regions.access.RefusalGuard(guardSettings),
            System::currentTimeMillis,
            task -> org.bukkit.Bukkit.getScheduler().runTask(this, task));

        domainAccess.setBypass(this::hasRegionBypass);
        domainAccess.setEnforcer(new net.knightsandkings.knk.paper.regions.access.DomainAccessService.Enforcer() {
            @Override
            public void sendToSpawn(org.bukkit.entity.Player player) {
                if (player.isOnline()) {
                    domainAccess.exemptWhile(player, () -> player.teleport(player.getWorld().getSpawnLocation()));
                }
            }

            @Override
            public void kick(org.bukkit.entity.Player player, String reason) {
                if (player.isOnline()) {
                    player.kick(net.kyori.adventure.text.Component.text(reason));
                }
            }
        });

        com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getSessionManager().registerHandler(
            new net.knightsandkings.knk.paper.regions.access.DomainAccessHandler.Factory(domainAccess), null);
        getServer().getPluginManager().registerEvents(
            new net.knightsandkings.knk.paper.regions.access.DomainAccessListener(domainAccess,
                this::resyncWorldGuardSession,
                player -> siegeService != null && siegeService.isParticipant(player.getUniqueId())),
            this);
        if (teleportService != null) {
            teleportService.registerRestriction(new net.knightsandkings.knk.paper.teleport.RegionTeleportRestriction(
                (player, to) -> domainAccess.preview(player, player.getLocation(), to)
                    .map(refusal -> net.knightsandkings.knk.core.regions.RegionTransitionDecision.deny(refusal.type(), refusal.message()))
                    .orElse(null)));
        }
    }

    /**
     * Next tick, make WorldGuard's session take the player's real position as their last allowed one - after a
     * respawn point was corrected, so WorldGuard doesn't keep judging moves from the original point.
     */
    private void resyncWorldGuardSession(org.bukkit.entity.Player player) {
        org.bukkit.Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            var localPlayer = com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);
            var session = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
            domainAccess.exemptWhile(player, () -> session.testMoveTo(localPlayer,
                com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getLocation()),
                com.sk89q.worldguard.session.MoveType.OTHER_NON_CANCELLABLE, true));
        });
    }

    /**
     * Add a teleport guard (docs/specs/teleport/DESIGN.md §4 D9) - how the siege minigame blocks
     * teleports of match members ({@code SiegeTeleportRestriction}, registered in initializeSiege)
     * without the teleport engine depending on the siege code. No-op when the teleport engine didn't start.
     */
    public void registerTeleportRestriction(net.knightsandkings.knk.paper.teleport.TeleportRestriction restriction) {
        if (teleportService != null) {
            teleportService.registerRestriction(restriction);
        }
    }

    /**
     * Keep some deaths from giving a {@code /back} (docs/specs/teleport Phase 7, developer decision Q5:
     * not after a siege death) - initializeSiege registers
     * {@code SiegeTeleportRestriction.backDeathExclusion()} here, next to the siege teleport
     * restriction. No-op when the teleport engine didn't start.
     */
    public void registerBackDeathExclusion(net.knightsandkings.knk.paper.teleport.BackDeathExclusion exclusion) {
        if (backService != null) {
            backService.registerDeathExclusion(exclusion);
        }
    }

    public net.knightsandkings.knk.paper.teleport.TeleportService getTeleportService() {
        return teleportService;
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
                visiblePlayers
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

    /**
     * Lootboxes Phase 3 (docs/specs/lootboxes/DESIGN.md §3.4): the runtime (cache + presenter, refreshed from the API),
     * the spawn scheduler, the interact/chunk/join listeners and the two commands. A failure here disables lootboxes
     * only, not the plugin.
     */
    private void initializeLootboxes() {
        try {
            var queryApi = apiClient.getLootboxesQueryApi();
            var commandApi = apiClient.getLootboxesCommandApi();
            java.util.concurrent.Executor mainThread = MenuService.mainThreadExecutor(this);
            java.time.Clock clock = java.time.Clock.systemUTC();
            java.util.function.BiPredicate<org.bukkit.entity.Player, String> permission =
                (player, node) -> knkPermissible.hasPermission(player, node);
            // Asked before refusing when nothing is cached yet (a first click right after joining).
            java.util.function.BiFunction<org.bukkit.entity.Player, String, java.util.concurrent.CompletableFuture<Boolean>> freshPermission =
                (player, node) -> knkPermissible.hasPermissionAsync(player, node);
            java.util.function.Function<org.bukkit.entity.Player, Integer> userIdOf = player -> cacheManager.getUserCache()
                .getStale(player.getUniqueId()).map(net.knightsandkings.knk.core.domain.users.UserSummary::id).orElse(null);
            // Siege (hub or match): the player's inventory is the siege one and is replaced afterwards, so no claims,
            // token opens or token hand-overs then. siegeService is created later (initializeSiege), so it's read per call.
            java.util.function.Predicate<java.util.UUID> inSiege =
                uuid -> siegeService != null && siegeService.activeLobbyOf(uuid).isPresent();

            var runtime = new net.knightsandkings.knk.paper.lootbox.LootboxRuntime(
                this, queryApi, () -> getConfig().getConfigurationSection("lootboxes"), clock);
            var regions = new net.knightsandkings.knk.paper.lootbox.WorldGuardLootboxRegions(new WorldGuardIntegration(this));
            var announcer = new net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer(
                message -> org.bukkit.Bukkit.broadcast(message));
            var delivery = new net.knightsandkings.knk.paper.lootbox.LootboxDelivery(
                mainThread, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess, enchantmentDefinitionsDataAccess, commandApi,
                new net.knightsandkings.knk.paper.item.BlueprintItemAssembler(
                    new net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl()));
            var scheduler = new net.knightsandkings.knk.paper.lootbox.LootboxSpawnScheduler(
                this, runtime, regions, commandApi, announcer,
                new net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner(
                    () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble()),
                this::siegeArenaRegionIds);

            var pluginManager = getServer().getPluginManager();
            // Phase 5: lootbox token items (open, hand over, keep out of placing/crafting). A world box is picked up
            // as one (smoke test 2026-09-27, DESIGN.md §3.8) and every token opens on the reel (§3.9).
            var tokenDelivery = new net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery(
                mainThread, queryApi, commandApi, runtime::settings, userIdOf);
            var opening = new net.knightsandkings.knk.paper.lootbox.LootboxOpening(
                this, delivery, announcer, queryApi, runtime::settings, runtime::config,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(), inSiege);
            pluginManager.registerEvents(opening, this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.LootboxInteractListener(
                runtime, new net.knightsandkings.knk.core.lootbox.ClaimGuard(), commandApi, tokenDelivery, announcer,
                permission, freshPermission, modeService::getActiveMode, inSiege, userIdOf, mainThread), this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.LootboxChunkListener(runtime), this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.LootboxJoinListener(
                this, queryApi, delivery, userIdOf, mainThread), this);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.LootboxTokenListener(
                this, runtime, new net.knightsandkings.knk.core.lootbox.TokenOpenGuard(), commandApi, opening, tokenDelivery,
                permission, freshPermission, modeService::getActiveMode, inSiege, userIdOf, mainThread), this);
            if (playerNotificationPoller != null) {
                // Held back during a siege; the next join or LootboxTokensIssued notification hands them over.
                playerNotificationPoller.setLootboxTokensHandler(player -> {
                    if (!inSiege.test(player.getUniqueId())) {
                        tokenDelivery.deliverUndelivered(player);
                    }
                });
                // Web despawns / area deletes and token revokes, applied within seconds (DESIGN.md §3.9).
                var worldSync = new net.knightsandkings.knk.paper.lootbox.LootboxWorldSync(
                    runtime::gone, org.bukkit.Bukkit::getOnlinePlayers);
                playerNotificationPoller.setServerNotificationHandler(
                    net.knightsandkings.knk.core.domain.users.PlayerNotification.TYPE_LOOTBOX_WORLD_CHANGED, worldSync::handle);
            }

            var areaCommand = new net.knightsandkings.knk.paper.commands.LootboxAreaCommand(
                runtime::config, runtime.cache(), regions, commandApi, userIdOf, name -> org.bukkit.Bukkit.getWorld(name),
                () -> runtime.refresh(), runtime::gone, mainThread, clock);
            this.lootboxAdminCommand = new net.knightsandkings.knk.paper.commands.LootboxAdminCommand(
                runtime, commandApi, delivery, announcer, areaCommand, permission, userIdOf,
                name -> org.bukkit.Bukkit.getPlayerExact(name),
                () -> {
                    reloadConfig();
                    runtime.reloadSettings();
                    opening.clearCaches();
                    scheduler.start();
                },
                mainThread,
                tokenDelivery,
                inSiege);
            this.lootboxAdminCommand.setOpening(opening);
            this.lootboxAdminCommand.setOnlinePlayerNames(() -> org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
            this.lootboxAdminCommand.setVisiblePlayerCompletion(visiblePlayers::complete);
            this.lootboxCommand = new net.knightsandkings.knk.paper.commands.LootboxCommand(
                runtime::config, queryApi, permission, mainThread);
            this.lootboxCommand.setFreshPermission(freshPermission);

            runtime.start();
            scheduler.start();
            this.lootboxRuntime = runtime;
            this.lootboxSpawnScheduler = scheduler;
            this.lootboxTokenDelivery = tokenDelivery;
            this.lootboxOpening = opening;
            getLogger().info("Lootboxes initialized (enabled=" + runtime.settings().enabled() + ")");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Lootboxes failed to initialize; they stay off", e);
        }
    }

    /**
     * The WorldGuard regions of every siege being fought now (hub or match): the drawn scenario's districts, or its
     * town. The lootbox spawn scheduler keeps boxes out of them. Main thread.
     */
    private java.util.Set<String> siegeArenaRegionIds() {
        if (siegeService == null) {
            return java.util.Set.of();
        }
        return net.knightsandkings.knk.core.lootbox.LootboxSiegeRules.arenaRegionIds(siegeService.lobbies().stream()
            .filter(lobby -> lobby.phase().isMatchActive())
            .map(lobby -> lobby.drawnScenario().orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList());
    }

    /**
     * After a siege restored a player's own inventory: hand over the lootbox tokens held back during the siege and any
     * reel item that came up during it, so they don't have to rejoin. A second later, so a quitting player is gone
     * (their next join delivers them) and a player who immediately joined another siege is skipped.
     */
    private void deliverLootboxItemsAfterSiege(org.bukkit.entity.Player player) {
        var tokenDelivery = lootboxTokenDelivery;
        var opening = lootboxOpening;
        // Not while disabling (siege shutdown restores everyone; scheduling then throws): their next join delivers.
        if ((tokenDelivery == null && opening == null) || !isEnabled()) {
            return;
        }
        java.util.UUID uuid = player.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> {
            org.bukkit.entity.Player online = getServer().getPlayer(uuid);
            if (online != null && online.isOnline()
                && (siegeService == null || siegeService.activeLobbyOf(uuid).isEmpty())) {
                if (opening != null) {
                    opening.deliverWaiting(online);
                }
                if (tokenDelivery != null) {
                    tokenDelivery.deliverUndelivered(online);
                }
            }
        }, 20L);
    }

    /** {@code /<name> ...} runs {@code /knk <name> ...}, tab completion included (KNG-77: /gate, /gatedoor). */
    private void registerKnkShortcut(String name, KnkAdminCommand knk) {
        registerTabCommand(name, new org.bukkit.command.TabExecutor() {
            @Override
            public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                     String label, String[] args) {
                return knk.onCommand(sender, command, "knk", prepend(name, args));
            }

            @Override
            public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                                        String alias, String[] args) {
                return knk.onTabComplete(sender, command, "knk", prepend(name, args));
            }

            private String[] prepend(String first, String[] rest) {
                String[] all = new String[rest.length + 1];
                all[0] = first;
                System.arraycopy(rest, 0, all, 1, rest.length);
                return all;
            }
        });
    }

    /** config.yml gates.here.* / gates.lookat.* (KNG-78/79). */
    private net.knightsandkings.knk.paper.gates.GateTargeting.Settings gateTargetingSettings() {
        var defaults = net.knightsandkings.knk.paper.gates.GateTargeting.Settings.defaults();
        double radius = getConfig().getDouble("gates.here.radius", defaults.hereRadius());
        double reach = getConfig().getDouble("gates.lookat.max-distance", defaults.lookAtMaxDistance());
        return new net.knightsandkings.knk.paper.gates.GateTargeting.Settings(
            radius > 0 ? radius : defaults.hereRadius(),
            net.knightsandkings.knk.paper.gates.GateTargeting.Settings.parseNearest(getConfig().getString("gates.here.ambiguity", "prompt")),
            getConfig().getBoolean("gates.lookat.enabled", defaults.lookAtEnabled()),
            reach > 0 ? Math.min(reach, 64) : defaults.lookAtMaxDistance());
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

    /** Bukkit-or-in-house permission checks for commands (KNG-24); Bukkit-only when the API isn't configured. */
    private net.knightsandkings.knk.paper.commands.support.CommandPermissions commandPermissions() {
        if (commandPermissions == null) {
            commandPermissions = net.knightsandkings.knk.paper.commands.support.CommandPermissions.of(
                knkPermissible, MenuService.mainThreadExecutor(this));
        }
        return commandPermissions;
    }

    /** A command gated on {@code node} through KnkPermissible and hidden from players lacking it (KNG-24). */
    private void registerGatedCommand(String name, String node, org.bukkit.command.CommandExecutor executor,
                                      net.knightsandkings.knk.paper.commands.support.CommandPermissions permissions,
                                      net.knightsandkings.knk.paper.listeners.GatedCommandVisibilityListener visibility) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand != null) {
            var gated = new net.knightsandkings.knk.paper.commands.support.PermissionGatedCommand(node, executor, permissions);
            pluginCommand.setExecutor(gated);
            pluginCommand.setTabCompleter(gated);
            visibility.gate(pluginCommand, node);
            getLogger().info("Registered /" + name + " command (gated on " + node + ")");
        } else {
            getLogger().warning("Failed to register /" + name + " command - not defined in plugin.yml?");
        }
    }

    private void registerSimpleCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand != null) {
            pluginCommand.setExecutor(executor);
            // Suppress Bukkit's default "all online players for every argument" fallback. Commands
            // registered here intentionally accept no completable arguments.
            pluginCommand.setTabCompleter((sender, command, alias, args) -> java.util.List.of());
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
     * Siege minigame runtime (docs/specs/siege-minigame/IMPLEMENTATION_PLAN.md Phase 5): the
     * runtime-config gateway, one SiegeService (one shared SiegeRuntimeLocks and one long-lived
     * RandomGenerator inside), the /siege command and the siege listeners. Match results (Phase 6)
     * go to knk-web-api's /api/siege-matches through SiegeMatchRecorder (retry, a pending-results
     * spool in siege-vault/pending-results/, startup recovery: replay the spool, then abort matches
     * the last run left open).
     */
    private void initializeSiege() {
        var siegeDataAccess = dataAccessFactory.createSiegeDataAccess(
            apiClient.getSiegeLobbiesQueryApi(),
            apiClient.getSiegeScenariosQueryApi()
        );
        java.io.File siegeVaultDirectory = new java.io.File(getDataFolder(), "siege-vault");
        SiegePlayerVault siegeVault = new SiegePlayerVault(siegeVaultDirectory, getLogger());
        // One long-lived generator for draws, splits and book drops (a new Random per draw correlates draws).
        java.util.random.RandomGenerator siegeRandom = new java.util.SplittableRandom();
        var siegeResultSpool = new net.knightsandkings.knk.core.siege.SiegeResultSpool(
            new java.io.File(siegeVaultDirectory, "pending-results").toPath(), getLogger());
        this.siegeMatchRecorder = new net.knightsandkings.knk.core.siege.SiegeMatchRecorder(
            apiClient.getSiegeMatchesCommandApi(),
            net.knightsandkings.knk.core.dataaccess.RetryPolicy.defaultPolicy(),
            siegeResultSpool,
            getLogger());
        // Before the runtime can draw: createMatch waits for this recovery.
        siegeMatchRecorder.recoverOnStartup();
        this.siegeService = new SiegeService(
            this,
            siegeDataAccess,
            apiClient.getTitleBracketsQueryApi(),
            siegeMatchRecorder,
            siegeVault,
            knkPermissible,
            cacheManager.getUserCache(),
            siegeRandom
        );

        PluginCommand siegeCommand = getCommand("siege");
        if (siegeCommand != null) {
            SiegeCommand executor = new SiegeCommand(siegeService);
            siegeCommand.setExecutor(executor);
            siegeCommand.setTabCompleter(executor);
            getLogger().info("Registered /siege command");
            // /siegemenu (/sgm): shortcut for /siege menu.
            PluginCommand siegeMenuCommand = getCommand("siegemenu");
            if (siegeMenuCommand != null) {
                siegeMenuCommand.setExecutor((sender, command, label, args) ->
                        executor.onCommand(sender, command, label, new String[] {"menu"}));
                siegeMenuCommand.setTabCompleter((sender, command, alias, args) -> java.util.List.of());
            }
        } else {
            getLogger().warning("Failed to register /siege command - not defined in plugin.yml?");
        }

        var siegeWorld = new net.knightsandkings.knk.paper.siege.SiegeWorldPresenter(this, siegeVaultDirectory);
        siegeService.addObserver(siegeWorld);
        getServer().getPluginManager().registerEvents(siegeWorld, this); // objective banner protection
        siegeService.addObserver(new net.knightsandkings.knk.paper.siege.SiegeScoreboardPresenter());
        // Smoke test 2026-09-26: sounds, particles and chat when objectives start being attacked/defended.
        siegeService.addObserver(new net.knightsandkings.knk.paper.siege.SiegeCaptureFeedback());
        var siegeBooks = new net.knightsandkings.knk.paper.siege.SiegeEnchantBooks(this, siegeService, siegeRandom);
        siegeService.addObserver(siegeBooks);
        siegeVault.setAfterRestore(player -> {
            siegeBooks.sweep(player);
            deliverLootboxItemsAfterSiege(player);
        });

        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new SiegeSessionListener(siegeService, siegeBooks::sweep), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeEnchantBookListener(siegeBooks), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeCombatListener(siegeService,
            uuid -> adminFreezeManager.isFrozen(uuid) || joinLoadingGuard.isLoading(uuid)), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeDeathRespawnListener(siegeService), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeCommandFilterListener(siegeService), this);
        pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeInventoryGuardListener(siegeService,
            player -> modeService.getActiveMode(player) != ActiveMode.NONE), this);

        // Phase 8b: menus open from /siege and the spawn picker (chat fallbacks stay) and repaint on changes.
        if (menuService != null) {
            var siegeMenus = new net.knightsandkings.knk.paper.siege.SiegeMenuBridge(menuService);
            siegeService.addObserver(siegeMenus);
            siegeService.setMenuHooks(siegeMenus);
        }

        // Phase 7a: the gates of the scenario area (lockdown, owner control, damage rules, restore,
        // crash recovery). The area itself stays open to non-members (smoke test 2026-09-26): they can't
        // fight members, capture or touch siege gates, and walk through the locked gates.
        if (gateManager != null && gateHealthSystem != null) {
            var siegeGates = new net.knightsandkings.knk.paper.siege.SiegeGateController(
                this, gateManager, gateHealthSystem, apiClient.getSiegeGatesCommandApi());
            this.siegeGates = siegeGates;
            siegeService.addObserver(siegeGates);
            pluginManager.registerEvents(new net.knightsandkings.knk.paper.listeners.SiegeGateListener(siegeGates, gateManager), this);
            siegeGates.recoverOnStartup();
            // Phase 7b: non-members see the locked gates removed and walk through them (degrade
            // switch: NonMemberGateView = PassThroughOnly).
            if (gatePassThroughService != null) siegeGates.setPassThrough(gatePassThroughService);
            var siegeGateView = new net.knightsandkings.knk.paper.siege.SiegeGateViewService(this, siegeGates, gateManager,
                getConfig().getBoolean("gates.rotationGapFill.rasterization-enabled", true));
            pluginManager.registerEvents(siegeGateView, this);
            siegeGateView.start();
        } else {
            getLogger().warning("Siege gate integration disabled: the gate system isn't initialized");
        }

        // Hourly salary and rank refreshes reset scoreboards; siege members keep their match board.
        net.knightsandkings.knk.paper.utils.ScoreboardUtil.setKeepOwnScoreboard(
            p -> siegeService.activeLobbyOf(p.getUniqueId()).isPresent());

        // Teleport guards (docs/specs/teleport/DESIGN.md §4 D8/D9): no /tp, /tpa, /spawn, /warp, menu warp
        // or /back moves a member while away in a siege, and a match death gives no /back. The siege's
        // own teleports bypass the engine (SiegeBukkit.teleport, cause PLUGIN).
        var siegeTeleports = net.knightsandkings.knk.paper.siege.SiegeTeleportRestriction.of(siegeService);
        registerTeleportRestriction(siegeTeleports);
        registerBackDeathExclusion(siegeTeleports.backDeathExclusion());

        siegeService.start();
        getLogger().info("Siege runtime initialized");
    }

    public SiegeService getSiegeService() {
        return siegeService;
    }

    /** The siege gate controller (read-only use: locked gates, non-member carry rule); null when siege or gates didn't start. */
    public net.knightsandkings.knk.paper.siege.SiegeGateController getSiegeGates() {
        return siegeGates;
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
