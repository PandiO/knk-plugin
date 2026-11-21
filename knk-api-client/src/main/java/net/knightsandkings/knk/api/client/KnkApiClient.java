package net.knightsandkings.knk.api.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.impl.HealthApiImpl;
import net.knightsandkings.knk.api.impl.TownsQueryApiImpl;
import net.knightsandkings.knk.api.impl.LocationsQueryApiImpl;
import net.knightsandkings.knk.api.impl.EnchantmentDefinitionsQueryApiImpl;
import net.knightsandkings.knk.api.impl.ItemBlueprintsQueryApiImpl;
import net.knightsandkings.knk.api.impl.MinecraftMaterialRefsQueryApiImpl;
import net.knightsandkings.knk.api.impl.DistrictsQueryApiImpl;
import net.knightsandkings.knk.api.impl.StreetsQueryApiImpl;
import net.knightsandkings.knk.api.impl.StructuresQueryApiImpl;
import net.knightsandkings.knk.api.impl.DomainsQueryApiImpl;
import net.knightsandkings.knk.api.impl.UsersQueryApiImpl;
import net.knightsandkings.knk.api.impl.UsersCommandApiImpl;
import net.knightsandkings.knk.api.impl.PlayerNotificationsApiImpl;
import net.knightsandkings.knk.api.impl.DiscoveriesApiImpl;
import net.knightsandkings.knk.api.impl.StatisticsApiImpl;
import net.knightsandkings.knk.api.impl.LeaderboardsApiImpl;
import net.knightsandkings.knk.api.impl.RoadNetworkCommandApiImpl;
import net.knightsandkings.knk.api.impl.RoadNetworkQueryApiImpl;
import net.knightsandkings.knk.api.impl.UserIgnoresApiImpl;
import net.knightsandkings.knk.api.impl.PrivateMessageLogApiImpl;
import net.knightsandkings.knk.api.impl.UserAccountApiImpl;
import net.knightsandkings.knk.api.impl.WorldTasksApiImpl;
import net.knightsandkings.knk.api.impl.GateStructuresApiImpl;
import net.knightsandkings.knk.api.impl.GateDoorsApiImpl;
import net.knightsandkings.knk.api.impl.GradesQueryApiImpl;
import net.knightsandkings.knk.api.impl.TagsQueryApiImpl;
import net.knightsandkings.knk.api.impl.DomainCatalogQueryApiImpl;
import net.knightsandkings.knk.api.impl.MenuTemplatesQueryApiImpl;
import net.knightsandkings.knk.api.impl.PermissionsApiImpl;
import net.knightsandkings.knk.api.impl.PermissionGroupsQueryApiImpl;
import net.knightsandkings.knk.api.impl.ClansQueryApiImpl;
import net.knightsandkings.knk.api.impl.SiegeLobbiesQueryApiImpl;
import net.knightsandkings.knk.api.impl.SiegeMatchesCommandApiImpl;
import net.knightsandkings.knk.api.impl.SiegeMatchHistoryApiImpl;
import net.knightsandkings.knk.api.impl.SiegeGatesCommandApiImpl;
import net.knightsandkings.knk.api.impl.SiegeScenariosQueryApiImpl;
import net.knightsandkings.knk.api.impl.TitleBracketsQueryApiImpl;
import net.knightsandkings.knk.api.impl.KitsQueryApiImpl;
import net.knightsandkings.knk.api.impl.KitsCommandApiImpl;
import net.knightsandkings.knk.core.ports.api.HealthApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.ports.api.LocationsQueryApi;
import net.knightsandkings.knk.core.ports.api.EnchantmentDefinitionsQueryApi;
import net.knightsandkings.knk.core.ports.api.ItemBlueprintsQueryApi;
import net.knightsandkings.knk.core.ports.api.MinecraftMaterialRefsQueryApi;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.ports.api.PlayerNotificationsApi;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.ports.api.GradesQueryApi;
import net.knightsandkings.knk.core.ports.api.TagsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.ClansQueryApi;
import net.knightsandkings.knk.core.ports.api.SiegeLobbiesQueryApi;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import net.knightsandkings.knk.core.ports.api.SiegeGatesCommandApi;
import net.knightsandkings.knk.core.ports.api.SiegeScenariosQueryApi;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import net.knightsandkings.knk.core.ports.api.CurrencyApi;
import net.knightsandkings.knk.core.ports.api.RegionsCommandApi;
import okhttp3.OkHttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main API client entrypoint. Provides access to all API port implementations.
 */
public class KnkApiClient {
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthProvider authProvider;
    private final ExecutorService executor;
    
    private final HealthApi healthApi;
    private final TownsQueryApi townsQueryApi;
    private final LocationsQueryApi locationsQueryApi;
    private final EnchantmentDefinitionsQueryApi enchantmentDefinitionsQueryApi;
    private final ItemBlueprintsQueryApi itemBlueprintsQueryApi;
    private final MinecraftMaterialRefsQueryApi minecraftMaterialRefsQueryApi;
    private final DistrictsQueryApi districtsQueryApi;
    private final StreetsQueryApi streetsQueryApi;
    private final StructuresQueryApi structuresQueryApi;
    private final DomainsQueryApi domainsQueryApi;
    private final UsersQueryApi usersQueryApi;
    private final UsersCommandApi usersCommandApi;
    private final PlayerNotificationsApi playerNotificationsApi;
    private final net.knightsandkings.knk.core.ports.api.AdmissionApi admissionApi;
    private final DiscoveriesApi discoveriesApi;
    private final StatisticsApiImpl statisticsApi;
    private final net.knightsandkings.knk.core.ports.api.TelemetryApi telemetryApi;
    private final net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi worldAnalyticsApi;
    private final net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi minecraftCatalogApi;
    private final LeaderboardsApiImpl leaderboardsApi;
    private final UserIgnoresApi userIgnoresApi;
    private final PrivateMessageLogApi privateMessageLogApi;
    private final UserAccountApi userAccountApi;
    private final WorldTasksApi worldTasksApi;
    private final GateStructuresApi gateStructuresApi;
    private final GateDoorsApi gateDoorsApi;
    private final GradesQueryApi gradesQueryApi;
    private final TagsQueryApi tagsQueryApi;
    private final DomainCatalogQueryApi domainCatalogQueryApi;
    private final MenuTemplatesQueryApi menuTemplatesQueryApi;
    private final PermissionsApi permissionsApi;
    private final PermissionGroupsQueryApi permissionGroupsQueryApi;
    private final ClansQueryApi clansQueryApi;
    private final SiegeLobbiesQueryApi siegeLobbiesQueryApi;
    private final SiegeScenariosQueryApi siegeScenariosQueryApi;
    private final SiegeMatchesCommandApi siegeMatchesCommandApi;
    private final net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi siegeMatchHistoryApi;
    /** Siege shop L3 (PandiO/knk-workspace#868): the match catalogue and weapon unlocks. */
    private final net.knightsandkings.knk.core.ports.api.SiegeShopApi siegeShopApi;
    private final net.knightsandkings.knk.core.ports.api.SiegeWeaponUnlocksApi siegeWeaponUnlocksApi;
    private final SiegeGatesCommandApi siegeGatesCommandApi;
    private final TitleBracketsQueryApi titleBracketsQueryApi;
    private final KitsQueryApi kitsQueryApi;
    private final net.knightsandkings.knk.core.ports.api.CategoriesQueryApi categoriesQueryApi;
    private final KitsCommandApi kitsCommandApi;
    /** PandiO/knk-workspace#269: temp regions the API released for removal. */
    private final net.knightsandkings.knk.core.ports.api.TempRegionReleasesApi tempRegionReleasesApi;
    private final net.knightsandkings.knk.core.ports.api.ItemIdentityApi itemIdentityApi;
    private final net.knightsandkings.knk.core.ports.api.TerritoryApi territoryApi;
    /** Yield-area surveys (PandiO/knk-workspace#679). */
    private final net.knightsandkings.knk.core.ports.api.ProductionSurveyApi productionSurveyApi;
    // Player harvesting (PandiO/knk-workspace#134)
    private final net.knightsandkings.knk.core.ports.api.HarvestApi harvestApi;
    private final net.knightsandkings.knk.core.ports.api.StorageContainersApi storageContainersApi;
    private final net.knightsandkings.knk.core.ports.api.StructureMenusApi structureMenusApi;
    private final net.knightsandkings.knk.core.ports.api.TransportLanesApi transportLanesApi;
    /** Item delivery and the reward inbox (PandiO/knk-workspace#684). */
    private final net.knightsandkings.knk.core.ports.api.ItemDeliveryApi itemDeliveryApi;
    private final net.knightsandkings.knk.core.ports.api.ShopsApi shopsApi;
    private final net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi gameSettingsQueryApi;
    private final net.knightsandkings.knk.core.ports.api.GameSettingsCommandApi gameSettingsCommandApi;
    private final net.knightsandkings.knk.core.ports.api.DomainAccessRulesApi domainAccessRulesApi;
    private final net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi domainWorldBackfillApi;
    private final net.knightsandkings.knk.api.impl.TeleportDestinationsApiImpl teleportDestinationsApi;
    private final net.knightsandkings.knk.core.ports.api.LootboxesQueryApi lootboxesQueryApi;
    private final net.knightsandkings.knk.core.ports.api.LootboxesCommandApi lootboxesCommandApi;
    private final net.knightsandkings.knk.core.ports.api.ItemInstancesApi itemInstancesApi;
    private final CurrencyApi currencyApi;
    private final RoadNetworkQueryApi roadNetworkQueryApi;
    private final net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi navigationCompassSettingsApi;
    /** Off-road navigation phase 5.1 (PandiO/knk-workspace#708): api/landmarks. */
    private final net.knightsandkings.knk.core.ports.api.LandmarksApi landmarksApi;
    private final RoadNetworkCommandApi roadNetworkCommandApi;
    private final net.knightsandkings.knk.core.ports.api.LocationRetentionApi locationRetentionApi;
    private final net.knightsandkings.knk.core.ports.api.PrivacyErasuresApi privacyErasuresApi;

    private KnkApiClient(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging,
        String healthRootUrl,
        Duration healthProbeTimeout
    ) {
        // KNG-34 link 6: calls started inside a correlated action carry its X-Correlation-Id.
        executor = CorrelationPropagatingExecutorService.wrap(executor);
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.executor = executor;
        this.telemetryApi = new net.knightsandkings.knk.api.impl.TelemetryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.worldAnalyticsApi = new net.knightsandkings.knk.api.impl.WorldAnalyticsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.minecraftCatalogApi = new net.knightsandkings.knk.api.impl.MinecraftCatalogApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        
        // Initialize API implementations
        this.healthApi = new HealthApiImpl(
            healthRootUrl != null && !healthRootUrl.isBlank() ? healthRootUrl : HealthApiImpl.deriveHealthRootUrl(baseUrl),
            healthProbeTimeout != null ? healthProbeTimeout : HealthApiImpl.DEFAULT_PROBE_TIMEOUT,
            httpClient, objectMapper, authProvider, executor, debugLogging);
        this.townsQueryApi = new TownsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.locationsQueryApi = new LocationsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.enchantmentDefinitionsQueryApi = new EnchantmentDefinitionsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.itemBlueprintsQueryApi = new ItemBlueprintsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.minecraftMaterialRefsQueryApi = new MinecraftMaterialRefsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.districtsQueryApi = new DistrictsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.streetsQueryApi = new StreetsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.structuresQueryApi = new StructuresQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.domainsQueryApi = new DomainsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.usersQueryApi = new UsersQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.usersCommandApi = new UsersCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.playerNotificationsApi = new PlayerNotificationsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.admissionApi = new net.knightsandkings.knk.api.impl.AdmissionApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.discoveriesApi = new DiscoveriesApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.statisticsApi = new StatisticsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.leaderboardsApi = new LeaderboardsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.userIgnoresApi = new UserIgnoresApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.privateMessageLogApi = new PrivateMessageLogApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.userAccountApi = new UserAccountApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.worldTasksApi = new WorldTasksApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gateStructuresApi = new GateStructuresApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gateDoorsApi = new GateDoorsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gradesQueryApi = new GradesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.tagsQueryApi = new TagsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.domainCatalogQueryApi = new DomainCatalogQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.menuTemplatesQueryApi = new MenuTemplatesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.permissionsApi = new PermissionsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.permissionGroupsQueryApi = new PermissionGroupsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.clansQueryApi = new ClansQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeLobbiesQueryApi = new SiegeLobbiesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeScenariosQueryApi = new SiegeScenariosQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeMatchesCommandApi = new SiegeMatchesCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeMatchHistoryApi = new SiegeMatchHistoryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeShopApi = new net.knightsandkings.knk.api.impl.SiegeShopApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeWeaponUnlocksApi = new net.knightsandkings.knk.api.impl.SiegeWeaponUnlocksApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.siegeGatesCommandApi = new SiegeGatesCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.titleBracketsQueryApi = new TitleBracketsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.kitsQueryApi = new KitsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.kitsCommandApi = new KitsCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.tempRegionReleasesApi = new net.knightsandkings.knk.api.impl.TempRegionReleasesApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.itemIdentityApi = new net.knightsandkings.knk.api.impl.ItemIdentityApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.territoryApi = new net.knightsandkings.knk.api.impl.TerritoryApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.productionSurveyApi = new net.knightsandkings.knk.api.impl.ProductionSurveyApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.harvestApi = new net.knightsandkings.knk.api.impl.HarvestApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.storageContainersApi = new net.knightsandkings.knk.api.impl.StorageContainersApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.structureMenusApi = new net.knightsandkings.knk.api.impl.StructureMenusApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.transportLanesApi = new net.knightsandkings.knk.api.impl.TransportLanesApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.itemDeliveryApi = new net.knightsandkings.knk.api.impl.ItemDeliveryApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.shopsApi = new net.knightsandkings.knk.api.impl.ShopsApiImpl(baseUrl, httpClient,
                objectMapper, authProvider, executor, debugLogging);
        this.currencyApi = new net.knightsandkings.knk.api.impl.CurrencyApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.categoriesQueryApi = new net.knightsandkings.knk.api.impl.CategoriesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gameSettingsQueryApi = new net.knightsandkings.knk.api.impl.GameSettingsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gameSettingsCommandApi = new net.knightsandkings.knk.api.impl.GameSettingsCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.domainAccessRulesApi = new net.knightsandkings.knk.api.impl.DomainAccessRulesApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.domainWorldBackfillApi = new net.knightsandkings.knk.api.impl.DomainWorldBackfillApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.teleportDestinationsApi = new net.knightsandkings.knk.api.impl.TeleportDestinationsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.lootboxesQueryApi = new net.knightsandkings.knk.api.impl.LootboxesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.lootboxesCommandApi = new net.knightsandkings.knk.api.impl.LootboxesCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.itemInstancesApi = new net.knightsandkings.knk.api.impl.ItemInstancesApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.roadNetworkQueryApi = new RoadNetworkQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.navigationCompassSettingsApi = new net.knightsandkings.knk.api.impl.NavigationCompassSettingsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.landmarksApi = new net.knightsandkings.knk.api.impl.LandmarksApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.roadNetworkCommandApi = new RoadNetworkCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.locationRetentionApi = new net.knightsandkings.knk.api.impl.LocationRetentionApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.privacyErasuresApi = new net.knightsandkings.knk.api.impl.PrivacyErasuresApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }
    
    public HealthApi getHealthApi() {
        return healthApi;
    }
    
    public TownsQueryApi getTownsQueryApi() {
        return townsQueryApi;
    }
    
    public LocationsQueryApi getLocationsQueryApi() { 
        return locationsQueryApi; 
    }

    public EnchantmentDefinitionsQueryApi getEnchantmentDefinitionsQueryApi() {
        return enchantmentDefinitionsQueryApi;
    }

    public ItemBlueprintsQueryApi getItemBlueprintsQueryApi() {
        return itemBlueprintsQueryApi;
    }

    public KitsQueryApi getKitsQueryApi() {
        return kitsQueryApi;
    }

    /** Player currency routes: balances, /baltop, /pay and history (currency ledger, KNG-21 Phase 3). */
    public CurrencyApi getCurrencyApi() {
        return currencyApi;
    }

    /** Item identity, PandiO/knk-workspace#141. */
    public net.knightsandkings.knk.core.ports.api.ItemIdentityApi getItemIdentityApi() {
        return itemIdentityApi;
    }

    /** Territory V1 (PandiO/knk-workspace#689): the inputs read and the report. */
    public net.knightsandkings.knk.core.ports.api.TerritoryApi getTerritoryApi() {
        return territoryApi;
    }

    /** Yield-area surveys (PandiO/knk-workspace#679): the survey queue, reports, "survey now" and the area list. */
    public net.knightsandkings.knk.core.ports.api.ProductionSurveyApi getProductionSurveyApi() {
        return productionSurveyApi;
    }

    /** Player harvesting (PandiO/knk-workspace#134): the harvest catalogue and the batched harvest reports. */
    public net.knightsandkings.knk.core.ports.api.HarvestApi getHarvestApi() {
        return harvestApi;
    }

    /** Storage terminals and displays (PandiO/knk-workspace#74): bindings, terminal pages, put and take, display samples. */
    public net.knightsandkings.knk.core.ports.api.StorageContainersApi getStorageContainersApi() {
        return storageContainersApi;
    }

    /** In-game structure menus (PandiO/knk-workspace#74): overview, structure menu, orders and the staff buttons' routes. */
    public net.knightsandkings.knk.core.ports.api.StructureMenusApi getStructureMenusApi() {
        return structureMenusApi;
    }

    /** Item delivery and the reward inbox (PandiO/knk-workspace#684): deliveries, the inbox view and claims. */
    public net.knightsandkings.knk.core.ports.api.ItemDeliveryApi getItemDeliveryApi() {
        return itemDeliveryApi;
    }

    /** Shops (PandiO/knk-workspace#687): the offer, quotes, purchases, sell quotes and sales. */
    public net.knightsandkings.knk.core.ports.api.ShopsApi getShopsApi() {
        return shopsApi;
    }

    /** Transport lanes (PandiO/knk-workspace#681): the lane list, lane reports and road connections. */
    public net.knightsandkings.knk.core.ports.api.TransportLanesApi getTransportLanesApi() {
        return transportLanesApi;
    }

    /** PandiO/knk-workspace#269. */
    public net.knightsandkings.knk.core.ports.api.TempRegionReleasesApi getTempRegionReleasesApi() {
        return tempRegionReleasesApi;
    }

    public KitsCommandApi getKitsCommandApi() {
        return kitsCommandApi;
    }

    /** Lootboxes Phase 3: runtime config, active boxes, pending claims, odds. */
    public net.knightsandkings.knk.core.ports.api.LootboxesQueryApi getLootboxesQueryApi() {
        return lootboxesQueryApi;
    }

    /** PandiO/knk-workspace#175: new ItemInstances for {@code /knk item more}'s copies. */
    public net.knightsandkings.knk.core.ports.api.ItemInstancesApi getItemInstancesApi() {
        return itemInstancesApi;
    }

    /** Lootboxes Phase 3: spawn, claim, deliver, admin give, in-game areas. */
    public net.knightsandkings.knk.core.ports.api.LootboxesCommandApi getLootboxesCommandApi() {
        return lootboxesCommandApi;
    }

    /** Menu follow-up 2026-09-26: {@code GET /api/Categories} (catalogue category filter). */
    public net.knightsandkings.knk.core.ports.api.CategoriesQueryApi getCategoriesQueryApi() {
        return categoriesQueryApi;
    }

    /** KNG-56: {@code GET /api/Domains/access-rules} - domain AllowEntry/AllowExit for the region flag sync. */
    public net.knightsandkings.knk.core.ports.api.DomainAccessRulesApi getDomainAccessRulesApi() {
        return domainAccessRulesApi;
    }

    /** KNG-112: fills the world of domains created before the API stored one. */
    public net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi getDomainWorldBackfillApi() {
        return domainWorldBackfillApi;
    }

    /** Teleport Phase 4 ({@code /spawn}): {@code GET /api/GameSettings}. */
    public net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi getGameSettingsQueryApi() {
        return gameSettingsQueryApi;
    }

    /** Game settings (KNG-52): {@code PUT /api/GameSettings/runtime-worlds}. */
    public net.knightsandkings.knk.core.ports.api.GameSettingsCommandApi getGameSettingsCommandApi() {
        return gameSettingsCommandApi;
    }

    /** Teleport Phase 5 ({@code /warp}): {@code GET /api/teleport-destinations?userId=}. */
    public net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi getTeleportDestinationsQueryApi() {
        return teleportDestinationsApi;
    }

    /** Teleport Phase 5: warp charges, /tpa fees and refunds ({@code POST /api/teleport-destinations/...}). */
    public net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi getTeleportDestinationsCommandApi() {
        return teleportDestinationsApi;
    }

    /** InventoryMenu content port CP3: {@code GET /api/title-brackets}. */
    public TitleBracketsQueryApi getTitleBracketsQueryApi() {
        return titleBracketsQueryApi;
    }

    public MinecraftMaterialRefsQueryApi getMinecraftMaterialRefsQueryApi() {
        return minecraftMaterialRefsQueryApi;
    }
    
    public DistrictsQueryApi getDistrictsQueryApi() {
        return districtsQueryApi;
    }

    public StreetsQueryApi getStreetsQueryApi() {
        return streetsQueryApi;
    }

    public StructuresQueryApi getStructuresQueryApi() {
        return structuresQueryApi;
    }

    public DomainsQueryApi getDomainsQueryApi() {
        return domainsQueryApi;
    }

    public UsersQueryApi getUsersQueryApi() {
        return usersQueryApi;
    }

    public UsersCommandApi getUsersCommandApi() {
        return usersCommandApi;
    }

    public PlayerNotificationsApi getPlayerNotificationsApi() {
        return playerNotificationsApi;
    }

    /** The admission gate (invitations, KNG-214): {@code api/Admission/{uuid}?name=}. */
    public net.knightsandkings.knk.core.ports.api.AdmissionApi getAdmissionApi() {
        return admissionApi;
    }

    /** Domain discovery (KNG-20): {@code api/users/{userId}/discoveries}. */
    public DiscoveriesApi getDiscoveriesApi() {
        return discoveriesApi;
    }

    /** Player statistics (KNG-34): {@code api/statistics}. */
    public net.knightsandkings.knk.core.ports.api.StatisticsApi getStatisticsApi() {
        return statisticsApi;
    }

    /** Diagnostic telemetry (KNG-34 link 6): {@code api/telemetry}. */
    public net.knightsandkings.knk.core.ports.api.TelemetryApi getTelemetryApi() {
        return telemetryApi;
    }

    /** World analytics (KNG-34 link 7): {@code api/world-analytics}. */
    public net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi getWorldAnalyticsApi() {
        return worldAnalyticsApi;
    }

    /** Minecraft catalogue scan snapshots (KNG-200): {@code api/MinecraftCatalog}. */
    public net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi getMinecraftCatalogApi() {
        return minecraftCatalogApi;
    }

    /** Leaderboards (KNG-34): {@code api/leaderboards}. */
    public net.knightsandkings.knk.core.ports.api.LeaderboardsApi getLeaderboardsApi() {
        return leaderboardsApi;
    }

    /** Server name and plugin version sent with every statistics batch. */
    public void setStatisticsSource(String serverName, String pluginVersion) {
        statisticsApi.setSource(serverName, pluginVersion);
    }

    /** Road navigation reads (KNG-27): tiles, conditional tile graphs, meta, profiles, surveys, seeds, edge search. */
    public RoadNetworkQueryApi getRoadNetworkQueryApi() {
        return roadNetworkQueryApi;
    }

    /** The navigation compass item rule and where it works (KNG-234). */
    public net.knightsandkings.knk.core.ports.api.LandmarksApi getLandmarksApi() {
        return landmarksApi;
    }

    public net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi getNavigationCompassSettingsApi() {
        return navigationCompassSettingsApi;
    }

    /** Road navigation writes (KNG-27): build upload, dirty marks, profiles, surveys, seeds, node/edge review. */
    public RoadNetworkCommandApi getRoadNetworkCommandApi() {
        return roadNetworkCommandApi;
    }

    /** Location retention (KNG-80): the orphan list and teleport targets. */
    public net.knightsandkings.knk.core.ports.api.LocationRetentionApi getLocationRetentionApi() {
        return locationRetentionApi;
    }

    /** Realtime S5 (PandiO/knk-workspace#184): the GDPR erasure feed the game server catches up from. */
    public net.knightsandkings.knk.core.ports.api.PrivacyErasuresApi getPrivacyErasuresApi() {
        return privacyErasuresApi;
    }

    /** KNG-18 Phase 2: players' ignore lists. */
    public UserIgnoresApi getUserIgnoresApi() {
        return userIgnoresApi;
    }

    /** KNG-18 Phase 3: the server-side private message log. */
    public PrivateMessageLogApi getPrivateMessageLogApi() {
        return privateMessageLogApi;
    }
    
    public UserAccountApi getUserAccountApi() {
        return userAccountApi;
    }
    
    public WorldTasksApi getWorldTasksApi() {
        return worldTasksApi;
    }
    
    public GateStructuresApi getGateStructuresApi() {
        return gateStructuresApi;
    }

    public GateDoorsApi getGateDoorsApi() {
        return gateDoorsApi;
    }

    public GradesQueryApi getGradesQueryApi() {
        return gradesQueryApi;
    }

    public TagsQueryApi getTagsQueryApi() {
        return tagsQueryApi;
    }

    public DomainCatalogQueryApi getDomainCatalogQueryApi() {
        return domainCatalogQueryApi;
    }

    public MenuTemplatesQueryApi getMenuTemplatesQueryApi() {
        return menuTemplatesQueryApi;
    }

    public PermissionsApi getPermissionsApi() {
        return permissionsApi;
    }

    public PermissionGroupsQueryApi getPermissionGroupsQueryApi() {
        return permissionGroupsQueryApi;
    }

    public ClansQueryApi getClansQueryApi() {
        return clansQueryApi;
    }

    public SiegeLobbiesQueryApi getSiegeLobbiesQueryApi() {
        return siegeLobbiesQueryApi;
    }

    public SiegeScenariosQueryApi getSiegeScenariosQueryApi() {
        return siegeScenariosQueryApi;
    }

    /** Siege Phase 6: match lifecycle checkpoints (wrap it in knk-core's SiegeMatchRecorder). */
    public SiegeMatchesCommandApi getSiegeMatchesCommandApi() {
        return siegeMatchesCommandApi;
    }

    /** Siege shop L3 (PandiO/knk-workspace#868): the match catalogue and weapon unlocks. */
    public net.knightsandkings.knk.core.ports.api.SiegeShopApi getSiegeShopApi() {
        return siegeShopApi;
    }

    /** Siege shop L4 (PandiO/knk-workspace#869): weapon unlocks with coins (the Siege unlocks menu). */
    public net.knightsandkings.knk.core.ports.api.SiegeWeaponUnlocksApi getSiegeWeaponUnlocksApi() {
        return siegeWeaponUnlocksApi;
    }

    /** Alpha run 1 (PandiO/knk-workspace#174): a player's finished matches for {@code /siege history}. */
    public net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi getSiegeMatchHistoryApi() {
        return siegeMatchHistoryApi;
    }

    /** Siege Phase 7a: the persisted gate lockdown (snapshots, overrides, restore). */
    public SiegeGatesCommandApi getSiegeGatesCommandApi() {
        return siegeGatesCommandApi;
    }

    /**
     * Shutdown the client and release resources.
     */
    public void shutdown() {
        executor.shutdown();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
    
    /**
     * Builder for KnkApiClient.
     */
    public static class Builder {
        private String baseUrl;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(10);
        private Duration writeTimeout = Duration.ofSeconds(10);
        private AuthProvider authProvider = new NoAuthProvider();
        private ExecutorService executor;
        private boolean shutdownExecutorOnClose = false;
        private boolean debugLogging = false;
        private boolean allowUntrustedSsl = false;
        private String healthRootUrl;
        private Duration healthProbeTimeout;
        
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }
        
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }
        
        public Builder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }
        
        public Builder writeTimeout(Duration timeout) {
            this.writeTimeout = timeout;
            return this;
        }
        
        public Builder authProvider(AuthProvider authProvider) {
            this.authProvider = authProvider;
            return this;
        }
        
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            this.shutdownExecutorOnClose = false;
            return this;
        }
        
        public Builder debugLogging(boolean debugLogging) {
            this.debugLogging = debugLogging;
            return this;
        }
        
        public Builder allowUntrustedSsl(boolean allowUntrustedSsl) {
            this.allowUntrustedSsl = allowUntrustedSsl;
            return this;
        }
        
        /**
         * API root for {@code /health/ready} (KNG-115); null or blank derives it from baseUrl by
         * dropping a trailing {@code /api}.
         */
        public Builder healthRootUrl(String healthRootUrl) {
            this.healthRootUrl = healthRootUrl;
            return this;
        }

        /** Whole-call timeout of one readiness probe (default 5 s). */
        public Builder healthProbeTimeout(Duration timeout) {
            this.healthProbeTimeout = timeout;
            return this;
        }

        public KnkApiClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            
            // Remove trailing slash from baseUrl
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            
            OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout);
            
            if (allowUntrustedSsl) {
                httpClientBuilder.sslSocketFactory(createTrustAllSslContext().getSocketFactory(),
                    createTrustAllTrustManager());
                httpClientBuilder.hostnameVerifier((hostname, session) -> true);
            }
            
            // KNG-232: duration of every REST call (no-op while metrics.enabled is false).
            httpClientBuilder.eventListenerFactory(ApiCallMetricsListener.FACTORY);
            OkHttpClient httpClient = httpClientBuilder.build();
            
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            javaTimeModule.addDeserializer(OffsetDateTime.class, new LenientOffsetDateTimeDeserializer());

            ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(javaTimeModule);
            
            ExecutorService finalExecutor = executor;
            if (finalExecutor == null) {
                finalExecutor = Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()),
                    r -> {
                        Thread t = new Thread(r, "knk-api-client");
                        t.setDaemon(true);
                        return t;
                    }
                );
                shutdownExecutorOnClose = true;
            }
            
            return new KnkApiClient(baseUrl, httpClient, objectMapper, authProvider, finalExecutor, debugLogging,
                healthRootUrl, healthProbeTimeout);
        }
    }
    
    /**
     * Creates an SSLContext that trusts all certificates (for development/testing only).
     */
    private static SSLContext createTrustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{createTrustAllTrustManager()}, null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSL context", e);
        }
    }
    
    /**
     * Creates a TrustManager that trusts all certificates (for development/testing only).
     */
    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
