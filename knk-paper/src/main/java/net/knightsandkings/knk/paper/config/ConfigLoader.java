package net.knightsandkings.knk.paper.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.teleport.BackKind;
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
            loadPrivateMessages(config.getConfigurationSection("private-messages")),
            loadTeleportSettings(config.getConfigurationSection("teleport")),
            loadDiscovery(config.getConfigurationSection("discovery")),
            loadNavigation(config.getConfigurationSection("navigation")),
            loadStatistics(config.getConfigurationSection("statistics")),
            loadTelemetry(config.getConfigurationSection("telemetry")),
            loadWorldAnalytics(config.getConfigurationSection("world-analytics")));
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

    /**
     * teleport.back (DESIGN §3.11, Phase 7; KNG-42 adds expire-seconds-by-kind and price-coins); missing
     * keys fall back to the defaults. An unknown kind under expire-seconds-by-kind is logged and skipped.
     */
    static TeleportBackSettings loadTeleportBackSettings(ConfigurationSection section) {
        TeleportBackSettings defaults = TeleportBackSettings.defaults();
        if (section == null) {
            return defaults;
        }
        Map<BackKind, Integer> byKind = new EnumMap<>(BackKind.class);
        ConfigurationSection kinds = section.getConfigurationSection("expire-seconds-by-kind");
        if (kinds != null) {
            for (String key : kinds.getKeys(false)) {
                Optional<BackKind> kind = BackKind.fromConfigKey(key);
                if (kind.isEmpty() || !kinds.isInt(key)) {
                    Logger.getLogger(ConfigLoader.class.getName()).warning("teleport.back.expire-seconds-by-kind." + key
                        + " ignored - kinds are death, warps, teleport and spawn, each a number of seconds");
                    continue;
                }
                byKind.put(kind.get(), kinds.getInt(key));
            }
        }
        return new TeleportBackSettings(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("expire-seconds", defaults.expireSeconds()),
            byKind,
            section.getInt("price-coins", defaults.priceCoins())
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

    /** Player statistics (KNG-34); every key has a default, so a missing section means "on, with defaults". */
    static KnkConfig.StatisticsConfig loadStatistics(ConfigurationSection section) {
        KnkConfig.StatisticsConfig defaults = KnkConfig.StatisticsConfig.defaults();
        if (section == null) {
            return defaults;
        }
        KnkConfig.StatisticsConfig.AfkConfig afkDefaults = defaults.afk();
        ConfigurationSection afk = section.getConfigurationSection("afk");
        KnkConfig.StatisticsConfig.AfkConfig afkConfig = afk == null ? afkDefaults
            : new KnkConfig.StatisticsConfig.AfkConfig(
                afk.getBoolean("enabled", afkDefaults.enabled()),
                afk.getInt("idle-seconds", afkDefaults.idleSeconds()),
                afk.getBoolean("command-enabled", afkDefaults.commandEnabled()),
                afk.getBoolean("tab-list-marker", afkDefaults.tabListMarker()),
                afk.getString("marker-text", afkDefaults.markerText())
            );
        ConfigurationSection movement = section.getConfigurationSection("movement");
        KnkConfig.StatisticsConfig.MovementConfig movementConfig = movement == null ? defaults.movement()
            : new KnkConfig.StatisticsConfig.MovementConfig(
                movement.getBoolean("enabled", defaults.movement().enabled()),
                movement.getDouble("max-segment-blocks", defaults.movement().maxSegmentBlocks())
            );
        ConfigurationSection fall = section.getConfigurationSection("fall");
        KnkConfig.StatisticsConfig.FallConfig fallConfig = fall == null ? defaults.fall()
            : new KnkConfig.StatisticsConfig.FallConfig(fall.getBoolean("enabled", defaults.fall().enabled()));
        ConfigurationSection combat = section.getConfigurationSection("combat");
        KnkConfig.StatisticsConfig.CombatConfig combatConfig = combat == null ? defaults.combat()
            : new KnkConfig.StatisticsConfig.CombatConfig(
                combat.getBoolean("enabled", defaults.combat().enabled()),
                combat.getBoolean("count-custom-damage", defaults.combat().countCustomDamage()),
                combat.contains("pve-excluded-spawn-reasons")
                    ? KnkConfig.StatisticsConfig.parseSpawnReasons(combat.getStringList("pve-excluded-spawn-reasons"))
                    : defaults.combat().pveExcludedSpawnReasons()
            );
        ConfigurationSection gates = section.getConfigurationSection("gates");
        KnkConfig.StatisticsConfig.GatesConfig gatesConfig = gates == null ? defaults.gates()
            : new KnkConfig.StatisticsConfig.GatesConfig(
                gates.getBoolean("enabled", defaults.gates().enabled()),
                gates.getBoolean("fire-attribution", defaults.gates().fireAttribution())
            );
        ConfigurationSection siege = section.getConfigurationSection("siege");
        KnkConfig.StatisticsConfig.SiegeConfig siegeConfig = siege == null ? defaults.siege()
            : new KnkConfig.StatisticsConfig.SiegeConfig(
                siege.getBoolean("report-departed-members", defaults.siege().reportDepartedMembers()));
        java.util.List<String> gameModes = section.contains("excluded-game-modes")
            ? section.getStringList("excluded-game-modes")
            : KnkConfig.StatisticsConfig.DEFAULT_EXCLUDED_GAME_MODES;
        return new KnkConfig.StatisticsConfig(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("flush-interval-seconds", defaults.flushIntervalSeconds()),
            section.getInt("max-batch-entries", defaults.maxBatchEntries()),
            section.getString("spool-directory", defaults.spoolDirectory()),
            section.getInt("replay-interval-seconds", defaults.replayIntervalSeconds()),
            KnkConfig.StatisticsConfig.parseGameModes(gameModes),
            afkConfig,
            movementConfig,
            fallConfig,
            combatConfig,
            gatesConfig,
            siegeConfig
        );
    }

    /** Diagnostic telemetry (KNG-34 link 6); every key has a default, so a missing section means "on, with defaults". */
    static KnkConfig.TelemetryConfig loadTelemetry(ConfigurationSection section) {
        KnkConfig.TelemetryConfig defaults = KnkConfig.TelemetryConfig.defaults();
        if (section == null) {
            return defaults;
        }
        return new KnkConfig.TelemetryConfig(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("max-buffer-events", defaults.maxBufferEvents()),
            section.getInt("flush-interval-seconds", defaults.flushIntervalSeconds()),
            section.getInt("config-poll-seconds", defaults.configPollSeconds()),
            section.getInt("enhanced-movement-sample-seconds", defaults.enhancedMovementSampleSeconds())
        );
    }

    /** World analytics (KNG-34 link 7); every key has a default, so a missing section means "on, with defaults". */
    static KnkConfig.WorldAnalyticsConfig loadWorldAnalytics(ConfigurationSection section) {
        KnkConfig.WorldAnalyticsConfig defaults = KnkConfig.WorldAnalyticsConfig.defaults();
        if (section == null) {
            return defaults;
        }
        java.util.Set<org.bukkit.GameMode> gameModes = section.contains("excluded-game-modes")
            ? KnkConfig.StatisticsConfig.parseGameModes(section.getStringList("excluded-game-modes"))
            : defaults.excludedGameModes();
        return new KnkConfig.WorldAnalyticsConfig(
            section.getBoolean("enabled", defaults.enabled()),
            section.getInt("movement-sample-seconds", defaults.movementSampleSeconds()),
            section.getInt("cell-size", defaults.cellSize()),
            section.getInt("flush-interval-seconds", defaults.flushIntervalSeconds()),
            section.getBoolean("movement", defaults.movement()),
            section.getBoolean("menu-funnels", defaults.menuFunnels()),
            section.getBoolean("domain-interactions", defaults.domainInteractions()),
            gameModes,
            section.getInt("max-pending-batches", defaults.maxPendingBatches())
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

    /**
     * Road navigation (KNG-27, DESIGN §4); every key has a default, so a missing section means "on, with
     * defaults". Validation happens in {@link KnkConfig#validate()} / {@link NavigationConfig#validate()}.
     */
    static NavigationConfig loadNavigation(ConfigurationSection section) {
        NavigationConfig defaults = NavigationConfig.defaults();
        if (section == null) {
            return defaults;
        }
        java.util.Map<String, Double> classCost = null;
        ConfigurationSection costSection = section.getConfigurationSection("class-cost");
        if (costSection != null) {
            classCost = new java.util.LinkedHashMap<>();
            for (String key : costSection.getKeys(false)) {
                classCost.put(key, costSection.isSet(key) ? costSection.getDouble(key) : null);
            }
        }
        java.util.List<String> overlays = section.contains("overlay-materials")
            ? section.getStringList("overlay-materials")
            : defaults.overlayMaterials();

        NavigationConfig.TrailConfig trailDefaults = defaults.trail();
        NavigationConfig.TrailConfig trail = new NavigationConfig.TrailConfig(
            section.getInt("trail-length", trailDefaults.length()),
            section.getInt("trail-period-ticks", trailDefaults.periodTicks()),
            section.getString("trail-particle", trailDefaults.particle()),
            section.getString("trail-color", trailDefaults.color()));

        NavigationConfig.SurveyConfig surveyDefaults = defaults.survey();
        ConfigurationSection survey = section.getConfigurationSection("survey");
        NavigationConfig.SurveyConfig surveyConfig = survey == null ? surveyDefaults
            : new NavigationConfig.SurveyConfig(
                survey.getInt("sample-period-ticks", surveyDefaults.samplePeriodTicks()),
                survey.getInt("cross-section-half-width", surveyDefaults.crossSectionHalfWidth()),
                survey.getInt("breadcrumb-seed-spacing", surveyDefaults.breadcrumbSeedSpacing()));

        NavigationConfig.BuilderConfig builderDefaults = defaults.builder();
        ConfigurationSection builder = section.getConfigurationSection("builder");
        NavigationConfig.BuilderConfig builderConfig = builder == null ? builderDefaults
            : new NavigationConfig.BuilderConfig(
                builder.getInt("tile-size", builderDefaults.tileSize()),
                builder.getInt("tile-margin", builderDefaults.tileMargin()),
                builder.getInt("max-cells-per-tile", builderDefaults.maxCellsPerTile()),
                builder.getInt("snapshot-chunks-per-tick", builderDefaults.snapshotChunksPerTick()),
                builder.getInt("junction-cluster-radius", builderDefaults.junctionClusterRadius()),
                builder.getInt("min-spur-length", builderDefaults.minSpurLength()),
                builder.getInt("ambiguous-reach", builderDefaults.ambiguousReach()),
                builder.getInt("plaza-growth", builderDefaults.plazaGrowth()),
                builder.getDouble("locked-node-reach", builderDefaults.lockedNodeReach()),
                builder.getBoolean("auto-plazas", builderDefaults.autoPlazas()),
                builder.getBoolean("curated-tiles", builderDefaults.curatedTiles()));

        NavigationConfig.WalkConfig walkDefaults = defaults.walk();
        ConfigurationSection walk = section.getConfigurationSection("walk");
        NavigationConfig.WalkConfig walkConfig = walk == null ? walkDefaults
            : new NavigationConfig.WalkConfig(
                walk.getBoolean("enabled", walkDefaults.enabled()),
                walk.getInt("max-expansions", walkDefaults.maxExpansions()),
                walk.getDouble("max-length-factor", walkDefaults.maxLengthFactor()),
                walk.getDouble("max-length", walkDefaults.maxLength()),
                walk.getDouble("detour-allowance", walkDefaults.detourAllowance()),
                walk.getInt("max-drop", walkDefaults.maxDrop()),
                walk.getDouble("drop-penalty", walkDefaults.dropPenalty()),
                walk.getInt("capture-margin", walkDefaults.captureMargin()),
                walk.getInt("chunk-ttl-seconds", walkDefaults.chunkTtlSeconds()),
                walk.getDouble("recompute-distance", walkDefaults.recomputeDistance()),
                walk.getInt("max-concurrent-searches", walkDefaults.maxConcurrentSearches()),
                walk.contains("climbables") ? walk.getStringList("climbables") : walkDefaults.climbables(),
                walk.getDouble("wall-cost", walkDefaults.wallCost()));

        return new NavigationConfig(
            section.getBoolean("enabled", defaults.enabled()),
            NavigationConfig.parseClassCost(classCost),
            overlays,
            section.getBoolean("seed-from-domains", defaults.seedFromDomains()),
            section.getDouble("max-snap-distance", defaults.maxSnapDistance()),
            section.getDouble("snap-vertical-weight", defaults.snapVerticalWeight()),
            section.getDouble("destination-snap-vertical-weight", defaults.destinationSnapVerticalWeight()),
            section.getDouble("max-start-distance", defaults.maxStartDistance()),
            section.getDouble("max-destination-distance", defaults.maxDestinationDistance()),
            section.getDouble("destination-walk-range", defaults.destinationWalkRange()),
            trail,
            section.getDouble("reroute-distance", defaults.rerouteDistance()),
            section.getInt("reroute-after-ticks", defaults.rerouteAfterTicks()),
            section.getDouble("arrive-distance", defaults.arriveDistance()),
            section.getInt("max-session-minutes", defaults.maxSessionMinutes()),
            section.getDouble("sprint-speed", defaults.sprintSpeed()),
            surveyConfig,
            builderConfig,
            walkConfig);
    }

    /** private-messages: every key falls back to {@link KnkConfig.PrivateMessagesConfig#defaults()}. */
    static KnkConfig.PrivateMessagesConfig loadPrivateMessages(ConfigurationSection section) {
        KnkConfig.PrivateMessagesConfig defaults = KnkConfig.PrivateMessagesConfig.defaults();
        if (section == null) {
            return defaults;
        }
        ConfigurationSection sound = section.getConfigurationSection("sound");
        ConfigurationSection rateLimit = section.getConfigurationSection("rate-limit");
        ConfigurationSection spy = section.getConfigurationSection("spy");
        ConfigurationSection log = section.getConfigurationSection("log");
        return new KnkConfig.PrivateMessagesConfig(
            section.getInt("max-length", defaults.maxLength()),
            new KnkConfig.PrivateMessagesConfig.SoundConfig(
                sound != null ? sound.getBoolean("enabled", defaults.sound().enabled()) : defaults.sound().enabled(),
                sound != null ? (float) sound.getDouble("volume", defaults.sound().volume()) : defaults.sound().volume(),
                sound != null ? (float) sound.getDouble("pitch", defaults.sound().pitch()) : defaults.sound().pitch()
            ),
            new KnkConfig.PrivateMessagesConfig.RateLimitConfig(
                rateLimit != null ? rateLimit.getInt("max-messages", defaults.rateLimit().maxMessages()) : defaults.rateLimit().maxMessages(),
                rateLimit != null ? rateLimit.getInt("window-seconds", defaults.rateLimit().windowSeconds()) : defaults.rateLimit().windowSeconds(),
                rateLimit != null ? rateLimit.getInt("duplicate-window-seconds", defaults.rateLimit().duplicateWindowSeconds())
                    : defaults.rateLimit().duplicateWindowSeconds()
            ),
            spy != null ? spy.getInt("refresh-seconds", defaults.spyRefreshSeconds()) : defaults.spyRefreshSeconds(),
            new KnkConfig.PrivateMessagesConfig.LogConfig(
                log != null ? log.getBoolean("local-enabled", defaults.log().localEnabled()) : defaults.log().localEnabled(),
                log != null ? log.getInt("local-retention-days", defaults.log().localRetentionDays()) : defaults.log().localRetentionDays(),
                log != null ? log.getBoolean("api-enabled", defaults.log().apiEnabled()) : defaults.log().apiEnabled(),
                log != null ? log.getInt("flush-seconds", defaults.log().flushSeconds()) : defaults.log().flushSeconds(),
                log != null ? log.getBoolean("filter-command-log", defaults.log().filterCommandLog()) : defaults.log().filterCommandLog()
            ),
            section.getBoolean("block-vanilla-commands", defaults.blockVanillaCommands())
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
            loadEntitySettings(entitiesSection, "siege"),
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
