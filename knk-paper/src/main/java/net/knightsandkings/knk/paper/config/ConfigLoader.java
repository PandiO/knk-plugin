package net.knightsandkings.knk.paper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.teleport.TeleportBackSettings;
import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
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
            loadTeleportSettings(config.getConfigurationSection("teleport")),
            loadDiscovery(config.getConfigurationSection("discovery")));
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
            section.getInt("safe-search-radius", defaults.safeSearchRadius()),
            loadTeleportRequestSettings(section.getConfigurationSection("request")),
            section.getInt("destinations.cache-seconds", defaults.destinationsCacheSeconds()),
            loadTeleportBackSettings(section.getConfigurationSection("back"))
        );
    }

    /** teleport.back (DESIGN §3.11, Phase 7); missing keys fall back to the defaults. */
    static TeleportBackSettings loadTeleportBackSettings(ConfigurationSection section) {
        TeleportBackSettings defaults = TeleportBackSettings.defaults();
        if (section == null) {
            return defaults;
        }
        return new TeleportBackSettings(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("expire-seconds", defaults.expireSeconds())
        );
    }

    /** teleport.request (DESIGN §3.5/§3.11, Phase 3); missing keys fall back to the defaults. */
    static TeleportRequestSettings loadTeleportRequestSettings(ConfigurationSection section) {
        TeleportRequestSettings defaults = TeleportRequestSettings.defaults();
        if (section == null) {
            return defaults;
        }
        return new TeleportRequestSettings(
            section.getInt("expire-seconds", defaults.expireSeconds()),
            section.getInt("cooldown-seconds", defaults.cooldownSeconds()),
            section.getInt("max-incoming", defaults.maxIncoming()),
            section.getInt("price-coins", defaults.priceCoins())
        );
    }

    /** Domain discovery; every key has a default, so a missing section means "on, with defaults". */
    static KnkConfig.DiscoveryConfig loadDiscovery(ConfigurationSection section) {
        KnkConfig.DiscoveryConfig defaults = KnkConfig.DiscoveryConfig.defaults();
        if (section == null) {
            return defaults;
        }
        KnkConfig.DiscoveryConfig.EffectsConfig effectDefaults = defaults.effects();
        ConfigurationSection effects = section.getConfigurationSection("effects");
        KnkConfig.DiscoveryConfig.EffectsConfig effectsConfig = effects == null ? effectDefaults
            : new KnkConfig.DiscoveryConfig.EffectsConfig(
                effects.getString("sound", effectDefaults.sound()),
                (float) effects.getDouble("sound-volume", effectDefaults.soundVolume()),
                (float) effects.getDouble("sound-pitch", effectDefaults.soundPitch()),
                effects.getString("particle", effectDefaults.particle()),
                effects.getInt("particle-count", effectDefaults.particleCount()),
                effects.getDouble("particle-spread", effectDefaults.particleSpread()),
                effects.getBoolean("town-firework", effectDefaults.townFirework())
            );
        KnkConfig.DiscoveryConfig.MessagesConfig messageDefaults = defaults.messages();
        ConfigurationSection messages = section.getConfigurationSection("messages");
        KnkConfig.DiscoveryConfig.MessagesConfig messagesConfig = messages == null ? messageDefaults
            : new KnkConfig.DiscoveryConfig.MessagesConfig(
                messages.getString("discovered", messageDefaults.discovered()),
                messages.getString("replay-summary", messageDefaults.replaySummary())
            );
        java.util.List<String> gameModes = section.contains("excluded-game-modes")
            ? section.getStringList("excluded-game-modes")
            : KnkConfig.DiscoveryConfig.DEFAULT_EXCLUDED_GAME_MODES;
        return new KnkConfig.DiscoveryConfig(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("batch-window-ticks", defaults.batchWindowTicks()),
            section.getInt("max-requests-per-minute", defaults.maxRequestsPerMinute()),
            KnkConfig.DiscoveryConfig.parseGameModes(gameModes),
            section.getBoolean("exclude-siege-participants", defaults.excludeSiegeParticipants()),
            section.getString("spool-directory", defaults.spoolDirectory()),
            section.getInt("replay-interval-seconds", defaults.replayIntervalSeconds()),
            effects == null ? defaults.effectSpacingTicks() : effects.getInt("spacing-ticks", defaults.effectSpacingTicks()),
            effectsConfig,
            messagesConfig
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
