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
import net.knightsandkings.knk.api.impl.KitsQueryApiImpl;
import net.knightsandkings.knk.api.impl.TitleBracketsQueryApiImpl;
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
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
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
    private final DiscoveriesApi discoveriesApi;
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
    private final KitsQueryApi kitsQueryApi;
    private final TitleBracketsQueryApi titleBracketsQueryApi;
    private final net.knightsandkings.knk.core.ports.api.CategoriesQueryApi categoriesQueryApi;
    private final KitsCommandApi kitsCommandApi;
    private final net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi gameSettingsQueryApi;
    private final net.knightsandkings.knk.api.impl.TeleportDestinationsApiImpl teleportDestinationsApi;

    private KnkApiClient(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.executor = executor;
        
        // Initialize API implementations
        this.healthApi = new HealthApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
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
        this.discoveriesApi = new DiscoveriesApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
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
        this.kitsQueryApi = new KitsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.kitsCommandApi = new KitsCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.titleBracketsQueryApi = new TitleBracketsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.categoriesQueryApi = new net.knightsandkings.knk.api.impl.CategoriesQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.gameSettingsQueryApi = new net.knightsandkings.knk.api.impl.GameSettingsQueryApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.teleportDestinationsApi = new net.knightsandkings.knk.api.impl.TeleportDestinationsApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
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

    public KitsCommandApi getKitsCommandApi() {
        return kitsCommandApi;
    }

    /** Menu follow-up 2026-09-26: {@code GET /api/Categories} (catalogue category filter). */
    public net.knightsandkings.knk.core.ports.api.CategoriesQueryApi getCategoriesQueryApi() {
        return categoriesQueryApi;
    }

    /** Teleport Phase 4 ({@code /spawn}): {@code GET /api/GameSettings}. */
    public net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi getGameSettingsQueryApi() {
        return gameSettingsQueryApi;
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

    /** Domain discovery (KNG-20): {@code api/users/{userId}/discoveries}. */
    public DiscoveriesApi getDiscoveriesApi() {
        return discoveriesApi;
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
            
            return new KnkApiClient(baseUrl, httpClient, objectMapper, authProvider, finalExecutor, debugLogging);
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
