package net.knightsandkings.knk.paper.dataaccess;

import java.time.Duration;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.*;
import net.knightsandkings.knk.core.dataaccess.*;
import net.knightsandkings.knk.core.ports.api.*;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Factory for creating data access gateways with configured settings.
 * <p>
 * Bridges the gap between Paper configuration (KnkConfig) and core data access components.
 * Constructs gateways with entity-specific TTL, policy, and retry settings from config.yml.
 */
public class DataAccessFactory {
    
    private static final Logger LOGGER = Logger.getLogger(DataAccessFactory.class.getName());
    
    private final KnkConfig.EntityCacheSettings entitySettings;
    
    /**
     * Create a factory with entity-specific cache settings from configuration.
     *
     * @param entitySettings Entity cache configuration
     */
    public DataAccessFactory(KnkConfig.EntityCacheSettings entitySettings) {
        this.entitySettings = entitySettings;
    }
    
    /**
     * Create a UsersDataAccess gateway with configured settings.
     *
     * @param userCache User cache instance
     * @param usersQueryApi Users query API port
     * @param usersCommandApi Users command API port
     * @return Configured UsersDataAccess instance
     */
    public UsersDataAccess createUsersDataAccess(
        UserCache userCache,
        UsersQueryApi usersQueryApi,
        UsersCommandApi usersCommandApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.users(), "Users");
        return new UsersDataAccess(userCache, usersQueryApi, usersCommandApi, settings);
    }
    
    /**
     * Create a TownsDataAccess gateway with configured settings.
     *
     * @param townCache Town cache instance
     * @param townsQueryApi Towns query API port
     * @return Configured TownsDataAccess instance
     */
    public TownsDataAccess createTownsDataAccess(
        TownCache townCache,
        TownsQueryApi townsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.towns(), "Towns");
        return new TownsDataAccess(townCache, townsQueryApi, settings);
    }
    
    /**
     * Create a DistrictsDataAccess gateway with configured settings.
     *
     * @param districtCache District cache instance
     * @param districtsQueryApi Districts query API port
     * @return Configured DistrictsDataAccess instance
     */
    public DistrictsDataAccess createDistrictsDataAccess(
        DistrictCache districtCache,
        DistrictsQueryApi districtsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.districts(), "Districts");
        return new DistrictsDataAccess(districtCache, districtsQueryApi, settings);
    }
    
    /**
     * Create a StructuresDataAccess gateway with configured settings.
     *
     * @param structureCache Structure cache instance
     * @param structuresQueryApi Structures query API port
     * @return Configured StructuresDataAccess instance
     */
    public StructuresDataAccess createStructuresDataAccess(
        StructureCache structureCache,
        StructuresQueryApi structuresQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.structures(), "Structures");
        return new StructuresDataAccess(structureCache, structuresQueryApi, settings);
    }
    
    /**
     * Create a StreetsDataAccess gateway with configured settings.
     *
     * @param streetCache Street cache instance
     * @param streetsQueryApi Streets query API port
     * @return Configured StreetsDataAccess instance
     */
    public StreetsDataAccess createStreetsDataAccess(
        StreetCache streetCache,
        StreetsQueryApi streetsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.streets(), "Streets");
        return new StreetsDataAccess(streetCache, streetsQueryApi, settings);
    }
    
    /**
     * Create a LocationsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param locationsQueryApi Locations query API port
     * @return Configured LocationsDataAccess instance
     */
    public LocationsDataAccess createLocationsDataAccess(
        Duration ttl,
        LocationsQueryApi locationsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.locations(), "Locations");
        return new LocationsDataAccess(ttl, locationsQueryApi, settings);
    }

    /**
     * Create an EnchantmentDefinitionsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param enchantmentDefinitionsQueryApi EnchantmentDefinitions query API port
     * @return Configured EnchantmentDefinitionsDataAccess instance
     */
    public EnchantmentDefinitionsDataAccess createEnchantmentDefinitionsDataAccess(
        Duration ttl,
        EnchantmentDefinitionsQueryApi enchantmentDefinitionsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.enchantments(), "EnchantmentDefinitions");
        return new EnchantmentDefinitionsDataAccess(ttl, enchantmentDefinitionsQueryApi, settings);
    }

    /**
     * Create an ItemBlueprintsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param itemBlueprintsQueryApi ItemBlueprints query API port
     * @return Configured ItemBlueprintsDataAccess instance
     */
    public ItemBlueprintsDataAccess createItemBlueprintsDataAccess(
        Duration ttl,
        ItemBlueprintsQueryApi itemBlueprintsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.itemBlueprints(), "ItemBlueprints");
        return new ItemBlueprintsDataAccess(ttl, itemBlueprintsQueryApi, settings);
    }

    /**
     * Create a MinecraftMaterialRefsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param minecraftMaterialRefsQueryApi MinecraftMaterialRefs query API port
     * @return Configured MinecraftMaterialRefsDataAccess instance
     */
    public MinecraftMaterialRefsDataAccess createMinecraftMaterialRefsDataAccess(
        Duration ttl,
        MinecraftMaterialRefsQueryApi minecraftMaterialRefsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.minecraftMaterials(), "MinecraftMaterialRefs");
        return new MinecraftMaterialRefsDataAccess(ttl, minecraftMaterialRefsQueryApi, settings);
    }
    
    /**
     * Create a DomainsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param domainsQueryApi Domains query API port
     * @return Configured DomainsDataAccess instance
     */
    public DomainsDataAccess createDomainsDataAccess(
        Duration ttl,
        DomainsQueryApi domainsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.domains(), "Domains");
        return new DomainsDataAccess(ttl, domainsQueryApi, settings);
    }
    
    /**
     * Create a GradesDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param gradesQueryApi Grades query API port
     * @return Configured GradesDataAccess instance
     */
    public GradesDataAccess createGradesDataAccess(
        Duration ttl,
        GradesQueryApi gradesQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.grades(), "Grades");
        return new GradesDataAccess(ttl, gradesQueryApi, settings);
    }

    /**
     * Create a TagsDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param tagsQueryApi Tags query API port
     * @return Configured TagsDataAccess instance
     */
    public TagsDataAccess createTagsDataAccess(
        Duration ttl,
        TagsQueryApi tagsQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.tags(), "Tags");
        return new TagsDataAccess(ttl, tagsQueryApi, settings);
    }

    /**
     * Create a DomainCatalogDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param domainCatalogQueryApi DomainCatalog query API port
     * @return Configured DomainCatalogDataAccess instance
     */
    public DomainCatalogDataAccess createDomainCatalogDataAccess(
        Duration ttl,
        DomainCatalogQueryApi domainCatalogQueryApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.domainCatalog(), "DomainCatalog");
        return new DomainCatalogDataAccess(ttl, domainCatalogQueryApi, settings);
    }

    /**
     * Create a HealthDataAccess gateway with configured settings.
     *
     * @param ttl Cache TTL duration
     * @param healthApi Health API port
     * @return Configured HealthDataAccess instance
     */
    public HealthDataAccess createHealthDataAccess(
        Duration ttl,
        HealthApi healthApi
    ) {
        DataAccessSettings settings = buildSettings(entitySettings.health(), "Health");
        return new HealthDataAccess(ttl, healthApi, settings);
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Build DataAccessSettings from EntitySettings configuration.
     *
     * @param entityConfig Entity-specific configuration
     * @param entityName Entity name for logging
     * @return Configured DataAccessSettings
     */
    private DataAccessSettings buildSettings(KnkConfig.EntitySettings entityConfig, String entityName) {
        FetchPolicy policy = parseFetchPolicy(entityConfig.policyName(), entityName);
        RetryPolicy retryPolicy = buildRetryPolicy(entityConfig);
        
        DataAccessSettings settings = new DataAccessSettings(
            policy,
            entityConfig.isStaleAllowed(),
            retryPolicy
        );
        
        LOGGER.fine(String.format(
            "[%s] DataAccessSettings: %s", entityName, settings
        ));
        
        return settings;
    }
    
    /**
     * Parse FetchPolicy from string name.
     *
     * @param policyName Policy name from config
     * @param entityName Entity name for logging
     * @return FetchPolicy enum value
     */
    private FetchPolicy parseFetchPolicy(String policyName, String entityName) {
        try {
            return FetchPolicy.valueOf(policyName.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(String.format(
                "[%s] Invalid policy '%s', falling back to CACHE_FIRST",
                entityName, policyName
            ));
            return FetchPolicy.CACHE_FIRST;
        }
    }
    
    /**
     * Build RetryPolicy from EntitySettings configuration.
     *
     * @param entityConfig Entity-specific configuration
     * @return Configured RetryPolicy
     */
    private RetryPolicy buildRetryPolicy(KnkConfig.EntitySettings entityConfig) {
        return RetryPolicy.builder()
            .maxAttempts(entityConfig.maxRetries())
            .initialDelay(Duration.ofMillis(entityConfig.backoffMs()))
            .backoffMultiplier(2.0)
            .maxDelay(Duration.ofMillis(5000))
            .build();
    }
}
