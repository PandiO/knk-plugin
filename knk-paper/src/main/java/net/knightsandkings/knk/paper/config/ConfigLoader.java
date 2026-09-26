package net.knightsandkings.knk.paper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.teleport.TeleportSettings;

/**
 * Loads and parses plugin configuration from config.yml.
 */
public class ConfigLoader {
    
    public static KnkConfig load(FileConfiguration config) {
        ConfigurationSection apiSection = config.getConfigurationSection("api");
        if (apiSection == null) {
            throw new IllegalArgumentException("Missing 'api' section in config.yml");
        }
        
        String baseUrl = apiSection.getString("base-url");
        boolean debugLogging = apiSection.getBoolean("debug-logging", false);
        boolean allowUntrustedSsl = apiSection.getBoolean("allow-untrusted-ssl", false);
        
        ConfigurationSection authSection = apiSection.getConfigurationSection("auth");
        if (authSection == null) {
            throw new IllegalArgumentException("Missing 'api.auth' section in config.yml");
        }
        
        KnkConfig.AuthConfig auth = new KnkConfig.AuthConfig(
            authSection.getString("type", "none"),
            authSection.getString("bearer-token", ""),
            authSection.getString("api-key", ""),
            authSection.getString("api-key-header", "X-API-Key")
        );
        
        ConfigurationSection timeoutsSection = apiSection.getConfigurationSection("timeouts");
        if (timeoutsSection == null) {
            throw new IllegalArgumentException("Missing 'api.timeouts' section in config.yml");
        }
        
        KnkConfig.TimeoutsConfig timeouts = new KnkConfig.TimeoutsConfig(
            timeoutsSection.getInt("connect", 10),
            timeoutsSection.getInt("read", 10),
            timeoutsSection.getInt("write", 10)
        );
        
        KnkConfig.ApiConfig apiConfig = new KnkConfig.ApiConfig(baseUrl, debugLogging, allowUntrustedSsl, auth, timeouts);
        
        // Load cache configuration
        ConfigurationSection cacheSection = config.getConfigurationSection("cache");
        KnkConfig.CacheConfig cacheConfig;
        if (cacheSection != null) {
            int ttlSeconds = cacheSection.getInt("ttl-seconds", 60);
            
            // Load entity-specific settings
            KnkConfig.EntityCacheSettings entitySettings = loadEntityCacheSettings(cacheSection);
            
            cacheConfig = new KnkConfig.CacheConfig(ttlSeconds, entitySettings);
        } else {
            // Use defaults if cache section is missing
            cacheConfig = KnkConfig.CacheConfig.defaultConfig();
        }
        
        // Load account configuration (Phase 2+)
        ConfigurationSection accountSection = config.getConfigurationSection("account");
        if (accountSection == null) {
            throw new IllegalArgumentException("Missing 'account' section in config.yml");
        }
        
        // Load cooldowns configuration (Phase 5)
        ConfigurationSection cooldownsSection = accountSection.getConfigurationSection("cooldowns");
        KnkConfig.AccountConfig.CooldownsConfig cooldownsConfig;
        if (cooldownsSection != null) {
            cooldownsConfig = new KnkConfig.AccountConfig.CooldownsConfig(
                cooldownsSection.getInt("account-create-seconds", 300),
                cooldownsSection.getInt("link-code-generate-seconds", 60),
                cooldownsSection.getInt("link-code-consume-seconds", 10),
                cooldownsSection.getInt("cleanup-interval-minutes", 5)
            );
        } else {
            // Default cooldowns if section missing
            cooldownsConfig = new KnkConfig.AccountConfig.CooldownsConfig(300, 60, 10, 5);
        }
        
        KnkConfig.AccountConfig accountConfig = new KnkConfig.AccountConfig(
            accountSection.getInt("link-code-expiry-minutes", 20),
            accountSection.getInt("chat-capture-timeout-seconds", 120),
            cooldownsConfig
        );
        
        // Load messages configuration (Phase 2+)
        ConfigurationSection messagesSection = config.getConfigurationSection("messages");
        if (messagesSection == null) {
            throw new IllegalArgumentException("Missing 'messages' section in config.yml");
        }
        
        KnkConfig.MessagesConfig messagesConfig = new KnkConfig.MessagesConfig(
            messagesSection.getString("prefix", "&8[&6KnK&8] &r"),
            messagesSection.getString("account-created", "&aAccount created successfully!"),
            messagesSection.getString("account-linked", "&aYour accounts have been linked!"),
            messagesSection.getString("link-code-generated", "&aYour link code is: &6{code}"),
            messagesSection.getString("invalid-link-code", "&cThis code is invalid or has expired."),
            messagesSection.getString("duplicate-account", "&cYou have two accounts. Please choose which one to keep."),
            messagesSection.getString("merge-complete", "&aAccount merge complete. Your account now has {coins} coins, {gems} gems, and {exp} XP.")
        );
        
        KnkConfig knkConfig = new KnkConfig(apiConfig, cacheConfig, accountConfig, messagesConfig,
            loadTeleportSettings(config.getConfigurationSection("teleport")));
        knkConfig.validate();
        
        return knkConfig;
    }
    
    /** The teleport: block (docs/specs/teleport/DESIGN.md §3.11); missing keys fall back to the defaults. */
    static TeleportSettings loadTeleportSettings(ConfigurationSection section) {
        TeleportSettings defaults = TeleportSettings.defaults();
        if (section == null) {
            return defaults;
        }
        return new TeleportSettings(
            section.getInt("warmup-seconds", defaults.warmupSeconds()),
            section.getInt("warmup-short-seconds", defaults.warmupShortSeconds()),
            section.getInt("cooldown-seconds", defaults.cooldownSeconds()),
            section.getInt("combat-tag-seconds", defaults.combatTagSeconds()),
            section.getInt("safe-search-radius", defaults.safeSearchRadius())
        );
    }

    private static KnkConfig.EntityCacheSettings loadEntityCacheSettings(ConfigurationSection cacheSection) {
        ConfigurationSection entitiesSection = cacheSection.getConfigurationSection("entities");
        if (entitiesSection == null) {
            return KnkConfig.EntityCacheSettings.defaults();
        }
        
        return new KnkConfig.EntityCacheSettings(
            loadEntitySettings(entitiesSection, "users"),
            loadEntitySettings(entitiesSection, "towns"),
            loadEntitySettings(entitiesSection, "districts"),
            loadEntitySettings(entitiesSection, "structures"),
            loadEntitySettings(entitiesSection, "streets"),
            loadEntitySettings(entitiesSection, "locations"),
            loadEntitySettings(entitiesSection, "enchantments"),
            loadEntitySettings(entitiesSection, "itemBlueprints"),
            loadEntitySettings(entitiesSection, "minecraftMaterials"),
            loadEntitySettings(entitiesSection, "domains"),
            loadEntitySettings(entitiesSection, "health"),
            loadEntitySettings(entitiesSection, "menus"),
            loadEntitySettings(entitiesSection, "permissions"),
            loadEntitySettings(entitiesSection, "grades"),
            loadEntitySettings(entitiesSection, "tags"),
            loadEntitySettings(entitiesSection, "domainCatalog"),
            loadEntitySettings(entitiesSection, "kits")
        );
    }
    
    private static KnkConfig.EntitySettings loadEntitySettings(ConfigurationSection entitiesSection, String entityName) {
        ConfigurationSection section = entitiesSection.getConfigurationSection(entityName);
        if (section == null) {
            return KnkConfig.EntitySettings.defaults();
        }
        
        Integer ttlMinutes = section.contains("ttl-minutes") ? section.getInt("ttl-minutes") : null;
        Integer ttlSeconds = section.contains("ttl-seconds") ? section.getInt("ttl-seconds") : null;
        Integer maxTtlMinutes = section.contains("max-ttl-minutes") ? section.getInt("max-ttl-minutes") : null;
        Integer maxTtlSeconds = section.contains("max-ttl-seconds") ? section.getInt("max-ttl-seconds") : null;
        String defaultPolicy = section.getString("default-policy");
        Boolean allowStale = section.contains("allow-stale") ? section.getBoolean("allow-stale") : null;
        Integer retryAttempts = section.contains("retry-attempts") ? section.getInt("retry-attempts") : null;
        Integer retryBackoffMs = section.contains("retry-backoff-ms") ? section.getInt("retry-backoff-ms") : null;
        
        return new KnkConfig.EntitySettings(
            ttlMinutes,
            ttlSeconds,
            maxTtlMinutes,
            maxTtlSeconds,
            defaultPolicy,
            allowStale,
            retryAttempts,
            retryBackoffMs
        );
    }
}
