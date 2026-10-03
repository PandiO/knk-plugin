package net.knightsandkings.knk.paper.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.GameMode;

import net.knightsandkings.knk.core.teleport.TeleportSettings;

/**
 * Plugin configuration loaded from config.yml.
 */
public record KnkConfig(
    ApiConfig api,
    CacheConfig cache,
    AccountConfig account,
    MessagesConfig messages,
    PrivateMessagesConfig privateMessages,
    TeleportSettings teleport,
    DiscoveryConfig discovery,
    StatisticsConfig statistics,
    TelemetryConfig telemetry,
    WorldAnalyticsConfig worldAnalytics
) {
    public KnkConfig {
        // No teleport: block (e.g. an older config.yml) means the DESIGN §3.11 defaults.
        teleport = teleport != null ? teleport : TeleportSettings.defaults();
        // No statistics: block means "on, with defaults" (player statistics, KNG-34).
        statistics = statistics != null ? statistics : StatisticsConfig.defaults();
        // No telemetry: block means "on, with defaults" (diagnostic telemetry, KNG-34 link 6).
        telemetry = telemetry != null ? telemetry : TelemetryConfig.defaults();
        // No world-analytics: block means "on, with defaults" (KNG-34 link 7).
        worldAnalytics = worldAnalytics != null ? worldAnalytics : WorldAnalyticsConfig.defaults();
    }

    /** Without a world-analytics section: its defaults. */
    public KnkConfig(ApiConfig api, CacheConfig cache, AccountConfig account, MessagesConfig messages,
                     PrivateMessagesConfig privateMessages, TeleportSettings teleport, DiscoveryConfig discovery,
                     StatisticsConfig statistics, TelemetryConfig telemetry) {
        this(api, cache, account, messages, privateMessages, teleport, discovery, statistics, telemetry,
            WorldAnalyticsConfig.defaults());
    }

    /** Without a telemetry section: its defaults. */
    public KnkConfig(ApiConfig api, CacheConfig cache, AccountConfig account, MessagesConfig messages,
                     PrivateMessagesConfig privateMessages, TeleportSettings teleport, DiscoveryConfig discovery,
                     StatisticsConfig statistics) {
        this(api, cache, account, messages, privateMessages, teleport, discovery, statistics, TelemetryConfig.defaults());
    }

    /** Without private-messages, teleport, discovery and statistics sections: their defaults. */
    public KnkConfig(ApiConfig api, CacheConfig cache, AccountConfig account, MessagesConfig messages) {
        this(api, cache, account, messages, PrivateMessagesConfig.defaults(), TeleportSettings.defaults(),
            DiscoveryConfig.defaults(), StatisticsConfig.defaults());
    }

    /** Without teleport, discovery and statistics sections: their defaults. */
    public KnkConfig(ApiConfig api, CacheConfig cache, AccountConfig account, MessagesConfig messages,
                     PrivateMessagesConfig privateMessages) {
        this(api, cache, account, messages, privateMessages, TeleportSettings.defaults(), DiscoveryConfig.defaults(),
            StatisticsConfig.defaults());
    }

    /** Without a statistics section: its defaults. */
    public KnkConfig(ApiConfig api, CacheConfig cache, AccountConfig account, MessagesConfig messages,
                     PrivateMessagesConfig privateMessages, TeleportSettings teleport, DiscoveryConfig discovery) {
        this(api, cache, account, messages, privateMessages, teleport, discovery, StatisticsConfig.defaults());
    }

    public record ApiConfig(
        String baseUrl,
        boolean debugLogging,
        boolean allowUntrustedSsl,
        AuthConfig auth,
        TimeoutsConfig timeouts
    ) {
        public void validate() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("api.base-url is required");
            }
            if (auth == null) {
                throw new IllegalArgumentException("api.auth is required");
            }
            auth.validate();
            if (timeouts == null) {
                throw new IllegalArgumentException("api.timeouts is required");
            }
        }
    }
    
    public record AuthConfig(
        String type,
        String bearerToken,
        String apiKey,
        String apiKeyHeader
    ) {
        public void validate() {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("api.auth.type is required (none, bearer, apikey)");
            }
            String lowerType = type.toLowerCase();
            if (!lowerType.equals("none") && !lowerType.equals("bearer") && !lowerType.equals("apikey")) {
                throw new IllegalArgumentException(
                    "api.auth.type must be one of: none, bearer, apikey (got: " + type + ")"
                );
            }
            if (lowerType.equals("bearer") && (bearerToken == null || bearerToken.isBlank())) {
                throw new IllegalArgumentException("api.auth.bearer-token is required when type=bearer");
            }
            if (lowerType.equals("apikey") && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalArgumentException("api.auth.api-key is required when type=apikey");
            }
        }
    }
    
    public record TimeoutsConfig(
        int connect,
        int read,
        int write
    ) {
        public Duration connectDuration() {
            return Duration.ofSeconds(connect);
        }
        
        public Duration readDuration() {
            return Duration.ofSeconds(read);
        }
        
        public Duration writeDuration() {
            return Duration.ofSeconds(write);
        }
    }
    
    public void validate() {
        if (api == null) {
            throw new IllegalArgumentException("api configuration is required");
        }
        api.validate();
        if (cache == null) {
            throw new IllegalArgumentException("cache configuration is required");
        }
        if (account == null) {
            throw new IllegalArgumentException("account configuration is required");
        }
        account.validate();
        if (messages == null) {
            throw new IllegalArgumentException("messages configuration is required");
        }
        messages.validate();
        if (privateMessages == null) {
            throw new IllegalArgumentException("private-messages configuration is required");
        }
        privateMessages.validate();
        if (discovery == null) {
            throw new IllegalArgumentException("discovery configuration is required");
        }
        discovery.validate();
        statistics.validate();
        telemetry.validate();
        worldAnalytics.validate();
    }
    
    public record CacheConfig(
        int ttlSeconds,
        EntityCacheSettings entities
    ) {
        /**
         * Returns the cache TTL as a Duration.
         *
         * @return Cache TTL duration
         */
        public Duration ttl() {
            return Duration.ofSeconds(ttlSeconds);
        }
        
        /**
         * Returns a default cache configuration.
         *
         * @return Default CacheConfig with 60 second TTL
         */
        public static CacheConfig defaultConfig() {
            return new CacheConfig(60, EntityCacheSettings.defaults());
        }
    }

    public record EntityCacheSettings(
        EntitySettings users,
        EntitySettings towns,
        EntitySettings districts,
        EntitySettings structures,
        EntitySettings streets,
        EntitySettings locations,
        EntitySettings enchantments,
        EntitySettings itemBlueprints,
        EntitySettings minecraftMaterials,
        EntitySettings domains,
        EntitySettings health,
        EntitySettings menus,
        EntitySettings permissions,
        EntitySettings grades,
        EntitySettings tags,
        EntitySettings domainCatalog,
        EntitySettings siege,
        EntitySettings kits
    ) {
        public static EntityCacheSettings defaults() {
            return new EntityCacheSettings(
                EntitySettings.defaults(), // users
                EntitySettings.defaults(), // towns
                EntitySettings.defaults(), // districts
                EntitySettings.defaults(), // structures
                EntitySettings.defaults(), // streets
                EntitySettings.defaults(), // locations
                EntitySettings.defaults(), // enchantments
                EntitySettings.defaults(), // itemBlueprints
                EntitySettings.defaults(), // minecraftMaterials
                EntitySettings.defaults(), // domains
                EntitySettings.defaults(), // health
                EntitySettings.defaults(), // menus
                // permissions: short TTL like health, not the 15-minute catalog-data default -
                // checks must reflect a grant/revoke reasonably promptly (docs/specs/
                // user-features/IMPLEMENTATION_PLAN.md §1)
                new EntitySettings(null, 30, null, 120, "CACHE_FIRST", true, 3, 100),
                EntitySettings.defaults(), // grades
                EntitySettings.defaults(), // tags
                EntitySettings.defaults(), // domainCatalog
                // siege runtime-config: refreshed explicitly between matches (Siege Phase 4), so a
                // long TTL only bounds how old a cache-first read may be
                new EntitySettings(30, null, 240, null, "CACHE_FIRST", true, 3, 200),
                EntitySettings.defaults()  // kits
            );
        }
    }

    public record EntitySettings(
        Integer ttlMinutes,
        Integer ttlSeconds,
        Integer maxTtlMinutes,
        Integer maxTtlSeconds,
        String defaultPolicy,
        Boolean allowStale,
        Integer retryAttempts,
        Integer retryBackoffMs
    ) {
        public Duration ttl() {
            if (ttlSeconds != null) {
                return Duration.ofSeconds(ttlSeconds);
            }
            if (ttlMinutes != null) {
                return Duration.ofMinutes(ttlMinutes);
            }
            return Duration.ofMinutes(15); // fallback default
        }

        public Duration maxTtl() {
            if (maxTtlSeconds != null) {
                return Duration.ofSeconds(maxTtlSeconds);
            }
            if (maxTtlMinutes != null) {
                return Duration.ofMinutes(maxTtlMinutes);
            }
            return Duration.ofHours(1); // fallback default
        }

        public String policyName() {
            return defaultPolicy != null ? defaultPolicy : "CACHE_FIRST";
        }

        public boolean isStaleAllowed() {
            return allowStale != null ? allowStale : true;
        }

        public int maxRetries() {
            return retryAttempts != null ? retryAttempts : 3;
        }

        public int backoffMs() {
            return retryBackoffMs != null ? retryBackoffMs : 100;
        }

        public static EntitySettings defaults() {
            return new EntitySettings(
                15, // ttlMinutes
                null, // ttlSeconds
                60, // maxTtlMinutes
                null, // maxTtlSeconds
                "CACHE_FIRST", // defaultPolicy
                true, // allowStale
                3, // retryAttempts
                100  // retryBackoffMs
            );
        }
    }
    
    /**
     * Account management configuration (Phase 2+).
     * Controls account linking, chat capture timeouts, cooldowns, and related settings.
     */
    public record AccountConfig(
        int linkCodeExpiryMinutes,
        int chatCaptureTimeoutSeconds,
        CooldownsConfig cooldowns
    ) {
        /**
         * Validate account configuration values.
         * Ensures link code expiry and chat timeout are within acceptable ranges.
         */
        public void validate() {
            if (linkCodeExpiryMinutes < 1) {
                throw new IllegalArgumentException(
                    "account.link-code-expiry-minutes must be at least 1 (got: " + linkCodeExpiryMinutes + ")"
                );
            }
            if (linkCodeExpiryMinutes > 120) {
                throw new IllegalArgumentException(
                    "account.link-code-expiry-minutes must not exceed 120 (2 hours) (got: " + linkCodeExpiryMinutes + ")"
                );
            }
            if (chatCaptureTimeoutSeconds < 30) {
                throw new IllegalArgumentException(
                    "account.chat-capture-timeout-seconds must be at least 30 (got: " + chatCaptureTimeoutSeconds + ")"
                );
            }
            if (chatCaptureTimeoutSeconds > 300) {
                throw new IllegalArgumentException(
                    "account.chat-capture-timeout-seconds must not exceed 300 (5 minutes) (got: " + chatCaptureTimeoutSeconds + ")"
                );
            }
            if (cooldowns == null) {
                throw new IllegalArgumentException("account.cooldowns configuration is required");
            }
            cooldowns.validate();
        }
        
        /**
         * Get link code expiry as a Duration.
         */
        public Duration linkCodeExpiry() {
            return Duration.ofMinutes(linkCodeExpiryMinutes);
        }
        
        /**
         * Get chat capture timeout as a Duration.
         */
        public Duration chatCaptureTimeout() {
            return Duration.ofSeconds(chatCaptureTimeoutSeconds);
        }
        
        /**
         * Cooldown configuration for account commands.
         * Prevents spam and rate limits expensive operations.
         */
        public record CooldownsConfig(
            int accountCreateSeconds,
            int linkCodeGenerateSeconds,
            int linkCodeConsumeSeconds,
            int cleanupIntervalMinutes
        ) {
            /**
             * Validate cooldown configuration values.
             */
            public void validate() {
                if (accountCreateSeconds < 0) {
                    throw new IllegalArgumentException(
                        "account.cooldowns.account-create-seconds must be non-negative (got: " + accountCreateSeconds + ")"
                    );
                }
                if (linkCodeGenerateSeconds < 0) {
                    throw new IllegalArgumentException(
                        "account.cooldowns.link-code-generate-seconds must be non-negative (got: " + linkCodeGenerateSeconds + ")"
                    );
                }
                if (linkCodeConsumeSeconds < 0) {
                    throw new IllegalArgumentException(
                        "account.cooldowns.link-code-consume-seconds must be non-negative (got: " + linkCodeConsumeSeconds + ")"
                    );
                }
                if (cleanupIntervalMinutes < 1) {
                    throw new IllegalArgumentException(
                        "account.cooldowns.cleanup-interval-minutes must be at least 1 (got: " + cleanupIntervalMinutes + ")"
                    );
                }
            }
        }
    }
    
    /**
     * Messages configuration for player-facing text.
     * All messages support Minecraft color codes (&a, &6, etc.).
     * Placeholders: {code}, {minutes}, {coins}, {gems}, {exp}
     */
    public record MessagesConfig(
        String prefix,
        String accountCreated,
        String accountLinked,
        String linkCodeGenerated,
        String invalidLinkCode,
        String duplicateAccount,
        String mergeComplete
    ) {
        /**
         * Validate messages configuration.
         * Ensures all required messages are present.
         */
        public void validate() {
            if (prefix == null || prefix.isBlank()) {
                throw new IllegalArgumentException("messages.prefix is required");
            }
            if (accountCreated == null || accountCreated.isBlank()) {
                throw new IllegalArgumentException("messages.account-created is required");
            }
            if (accountLinked == null || accountLinked.isBlank()) {
                throw new IllegalArgumentException("messages.account-linked is required");
            }
            if (linkCodeGenerated == null || linkCodeGenerated.isBlank()) {
                throw new IllegalArgumentException("messages.link-code-generated is required");
            }
            if (invalidLinkCode == null || invalidLinkCode.isBlank()) {
                throw new IllegalArgumentException("messages.invalid-link-code is required");
            }
            if (duplicateAccount == null || duplicateAccount.isBlank()) {
                throw new IllegalArgumentException("messages.duplicate-account is required");
            }
            if (mergeComplete == null || mergeComplete.isBlank()) {
                throw new IllegalArgumentException("messages.merge-complete is required");
            }
        }
    }

    /**
     * Domain discovery (docs/specs/domain-discovery DESIGN.md §3.6): first entry into a Town,
     * District or Structure rewards the player once. Amounts are decided by knk-web-api; this only
     * controls detection, batching, the API-down spool and the effects.
     *
     * @param batchWindowTicks     how often pending candidates are sent (one request per player)
     * @param excludedGameModes    players in these game modes discover nothing
     * @param spoolDirectory       under the plugin folder; one file per player while the API is down
     * @param effectSpacingTicks   delay between the effects of several places discovered at once
     */
    public record DiscoveryConfig(
        boolean enabled,
        int batchWindowTicks,
        int maxRequestsPerMinute,
        Set<GameMode> excludedGameModes,
        boolean excludeSiegeParticipants,
        String spoolDirectory,
        int replayIntervalSeconds,
        int effectSpacingTicks,
        EffectsConfig effects,
        MessagesConfig messages
    ) {
        public static final List<String> DEFAULT_EXCLUDED_GAME_MODES = List.of("CREATIVE", "SPECTATOR");

        public record EffectsConfig(
            String sound,
            float soundVolume,
            float soundPitch,
            String particle,
            int particleCount,
            double particleSpread,
            boolean townFirework
        ) {
            public static EffectsConfig defaults() {
                return new EffectsConfig("UI_TOAST_CHALLENGE_COMPLETE", 0.8f, 1.0f, "HAPPY_VILLAGER", 30, 0.6, true);
            }
        }

        /**
         * @param discovered    one line per discovered place; {type}, {name}, {parent} (" in &a<town>")
         * @param replaySummary after a replay of discoveries made while the API was down; {count}
         */
        public record MessagesConfig(String discovered, String replaySummary) {
            public static MessagesConfig defaults() {
                return new MessagesConfig(
                    "&bYou discovered {type} &a{name}&b{parent}!",
                    "&bWhile the server was busy you discovered &a{count}&b place(s):");
            }
        }

        public DiscoveryConfig {
            excludedGameModes = excludedGameModes == null ? Set.of() : Set.copyOf(excludedGameModes);
            effects = effects == null ? EffectsConfig.defaults() : effects;
            messages = messages == null ? MessagesConfig.defaults() : messages;
        }

        public static DiscoveryConfig defaults() {
            return new DiscoveryConfig(true, 20, 12, parseGameModes(DEFAULT_EXCLUDED_GAME_MODES), true, "discovery-spool", 60, 10,
                EffectsConfig.defaults(), MessagesConfig.defaults());
        }

        /** Game mode names, any case; an unknown name is a config error. */
        public static Set<GameMode> parseGameModes(List<String> names) {
            if (names == null) {
                return Set.of();
            }
            return names.stream().map(name -> {
                try {
                    return GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("discovery.excluded-game-modes: unknown game mode '" + name + "'");
                }
            }).collect(Collectors.toUnmodifiableSet());
        }

        public void validate() {
            if (batchWindowTicks < 1) {
                throw new IllegalArgumentException("discovery.batch-window-ticks must be at least 1 (got: " + batchWindowTicks + ")");
            }
            if (maxRequestsPerMinute < 1) {
                throw new IllegalArgumentException("discovery.max-requests-per-minute must be at least 1 (got: " + maxRequestsPerMinute + ")");
            }
            if (replayIntervalSeconds < 10) {
                throw new IllegalArgumentException("discovery.replay-interval-seconds must be at least 10 (got: " + replayIntervalSeconds + ")");
            }
            if (effectSpacingTicks < 0) {
                throw new IllegalArgumentException("discovery.effects.spacing-ticks must not be negative (got: " + effectSpacingTicks + ")");
            }
            if (spoolDirectory == null || spoolDirectory.isBlank()) {
                throw new IllegalArgumentException("discovery.spool-directory is required");
            }
        }
    }

    /**
     * Player statistics (KNG-34, knk-workspace docs/specs/player-statistics IMPLEMENTATION_PLAN.md
     * §5.1): sessions, active/AFK playtime, distance and falls, buffered and flushed to knk-web-api
     * in batches off the main thread, spooled while the API can't take them. {@code enabled: false}
     * registers no statistics listener, task or command ({@code /afk}) - today's behaviour.
     *
     * @param flushIntervalSeconds  how often accrued statistics are sent (one batch per interval)
     * @param maxBatchEntries       entries per batch (the API takes at most 2,000)
     * @param spoolDirectory        under the plugin folder; one file per undelivered batch
     * @param replayIntervalSeconds how often the spool is replayed (at most one batch per second on average)
     * @param excludedGameModes     no distance or fall statistics in these game modes (playtime still counts)
     */
    public record StatisticsConfig(
        boolean enabled,
        int flushIntervalSeconds,
        int maxBatchEntries,
        String spoolDirectory,
        int replayIntervalSeconds,
        Set<GameMode> excludedGameModes,
        AfkConfig afk,
        MovementConfig movement,
        FallConfig fall,
        CombatConfig combat,
        GatesConfig gates,
        SiegeConfig siege
    ) {
        public static final List<String> DEFAULT_EXCLUDED_GAME_MODES = List.of("CREATIVE", "SPECTATOR");

        /**
         * AFK detection (DESIGN.md §F.2). {@code enabled: false}: every online second is active time,
         * no {@code /afk}, no marker.
         *
         * @param idleSeconds   automatic AFK after this long without an activity signal
         * @param markerText    appended to the tab-list name while AFK ({@code &} colour codes)
         */
        public record AfkConfig(boolean enabled, int idleSeconds, boolean commandEnabled, boolean tabListMarker,
                                String markerText) {
            public static AfkConfig defaults() {
                return new AfkConfig(true, 300, true, true, "&7[AFK]");
            }
        }

        /** Distance per mode (DESIGN.md §F.9); a segment longer than {@code maxSegmentBlocks} is not travel. */
        public record MovementConfig(boolean enabled, double maxSegmentBlocks) {
            public static MovementConfig defaults() {
                return new MovementConfig(true, 10.0);
            }
        }

        /** Highest survived fall (DESIGN.md §F.9). */
        public record FallConfig(boolean enabled) {
            public static FallConfig defaults() {
                return new FallConfig(true);
            }
        }

        /**
         * Kills, deaths, damage, arrows, headshots and the open-world killstreak (DESIGN.md §F.7, link 4).
         *
         * @param countCustomDamage        count synthetic {@code CUSTOM} damage (Chaos enchant procs, L1-9)
         * @param pveExcludedSpawnReasons  {@code CreatureSpawnEvent.SpawnReason} names whose creatures are no PvE kill (L1-10)
         */
        public record CombatConfig(boolean enabled, boolean countCustomDamage, Set<String> pveExcludedSpawnReasons) {
            public CombatConfig {
                pveExcludedSpawnReasons = pveExcludedSpawnReasons == null ? Set.of() : Set.copyOf(pveExcludedSpawnReasons);
            }

            public static CombatConfig defaults() {
                return new CombatConfig(true, false, parseSpawnReasons(
                    net.knightsandkings.knk.core.statistics.CombatStatisticsRules.DEFAULT_PVE_EXCLUDED_SPAWN_REASONS));
            }
        }

        /**
         * Gate-door damage credited to players (DESIGN.md §F.8, link 4).
         *
         * @param fireAttribution remember each burning block's igniter and credit fire ticks to them
         */
        public record GatesConfig(boolean enabled, boolean fireAttribution) {
            public static GatesConfig defaults() {
                return new GatesConfig(true, true);
            }
        }

        /**
         * Siege reconciliation (DESIGN.md §F.6, link 4).
         *
         * @param reportDepartedMembers include members who left early (with their stats and leave time) in
         *                              the match completion; false = today's payload
         */
        public record SiegeConfig(boolean reportDepartedMembers) {
            public static SiegeConfig defaults() {
                return new SiegeConfig(true);
            }
        }

        public StatisticsConfig {
            excludedGameModes = excludedGameModes == null ? Set.of() : Set.copyOf(excludedGameModes);
            afk = afk == null ? AfkConfig.defaults() : afk;
            movement = movement == null ? MovementConfig.defaults() : movement;
            fall = fall == null ? FallConfig.defaults() : fall;
            combat = combat == null ? CombatConfig.defaults() : combat;
            gates = gates == null ? GatesConfig.defaults() : gates;
            siege = siege == null ? SiegeConfig.defaults() : siege;
        }

        /** The link-3 shape (combat, gates and siege at their defaults). */
        public StatisticsConfig(boolean enabled, int flushIntervalSeconds, int maxBatchEntries, String spoolDirectory,
                                int replayIntervalSeconds, Set<GameMode> excludedGameModes, AfkConfig afk,
                                MovementConfig movement, FallConfig fall) {
            this(enabled, flushIntervalSeconds, maxBatchEntries, spoolDirectory, replayIntervalSeconds, excludedGameModes,
                afk, movement, fall, null, null, null);
        }

        public static StatisticsConfig defaults() {
            return new StatisticsConfig(true, 60, 2000, "statistics-spool", 60,
                parseGameModes(DEFAULT_EXCLUDED_GAME_MODES), AfkConfig.defaults(), MovementConfig.defaults(), FallConfig.defaults(),
                CombatConfig.defaults(), GatesConfig.defaults(), SiegeConfig.defaults());
        }

        /** Spawn reason names, any case; an unknown name is a config error. */
        public static Set<String> parseSpawnReasons(List<String> names) {
            if (names == null) {
                return Set.of();
            }
            return names.stream().map(name -> {
                String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
                try {
                    return org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.valueOf(normalized).name();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("statistics.combat.pve-excluded-spawn-reasons: unknown spawn reason '" + name + "'");
                }
            }).collect(Collectors.toUnmodifiableSet());
        }

        /** Game mode names, any case; an unknown name is a config error. */
        public static Set<GameMode> parseGameModes(List<String> names) {
            if (names == null) {
                return Set.of();
            }
            return names.stream().map(name -> {
                try {
                    return GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("statistics.excluded-game-modes: unknown game mode '" + name + "'");
                }
            }).collect(Collectors.toUnmodifiableSet());
        }

        public void validate() {
            if (flushIntervalSeconds < 10) {
                throw new IllegalArgumentException("statistics.flush-interval-seconds must be at least 10 (got: " + flushIntervalSeconds + ")");
            }
            if (maxBatchEntries < 10 || maxBatchEntries > 2000) {
                throw new IllegalArgumentException("statistics.max-batch-entries must be between 10 and 2000 (got: " + maxBatchEntries + ")");
            }
            if (replayIntervalSeconds < 10) {
                throw new IllegalArgumentException("statistics.replay-interval-seconds must be at least 10 (got: " + replayIntervalSeconds + ")");
            }
            if (spoolDirectory == null || spoolDirectory.isBlank()) {
                throw new IllegalArgumentException("statistics.spool-directory is required");
            }
            if (afk.idleSeconds() < 30) {
                throw new IllegalArgumentException("statistics.afk.idle-seconds must be at least 30 (got: " + afk.idleSeconds() + ")");
            }
            if (!(movement.maxSegmentBlocks() > 0)) {
                throw new IllegalArgumentException("statistics.movement.max-segment-blocks must be positive (got: " + movement.maxSegmentBlocks() + ")");
            }
        }
    }

    /**
     * /msg, /reply and social spy (docs/specs/private-messages/DESIGN.md §3.3.10). Every key has a
     * default, so an existing config.yml without the section keeps working.
     */
    public record PrivateMessagesConfig(
        int maxLength,
        SoundConfig sound,
        RateLimitConfig rateLimit,
        int spyRefreshSeconds,
        LogConfig log,
        boolean blockVanillaCommands
    ) {
        public record SoundConfig(boolean enabled, float volume, float pitch) {
        }

        public record RateLimitConfig(int maxMessages, int windowSeconds, int duplicateWindowSeconds) {
            public Duration window() {
                return Duration.ofSeconds(windowSeconds);
            }

            public Duration duplicateWindow() {
                return Duration.ofSeconds(duplicateWindowSeconds);
            }
        }

        /**
         * {@code apiEnabled}: ship PMs to knk-web-api's log (needs api.auth.type apikey);
         * {@code filterCommandLog}: keep PM command lines out of Paper's own command log.
         */
        public record LogConfig(boolean localEnabled, int localRetentionDays, boolean apiEnabled, int flushSeconds,
                                boolean filterCommandLog) {
        }

        public static PrivateMessagesConfig defaults() {
            return new PrivateMessagesConfig(
                256,
                new SoundConfig(true, 1.0f, 1.0f),
                new RateLimitConfig(5, 5, 10),
                60,
                new LogConfig(true, 30, true, 5, true),
                true
            );
        }

        public void validate() {
            if (maxLength < 1) {
                throw new IllegalArgumentException("private-messages.max-length must be at least 1 (got: " + maxLength + ")");
            }
            if (sound == null || rateLimit == null || log == null) {
                throw new IllegalArgumentException("private-messages.sound, rate-limit and log are required");
            }
            if (rateLimit.maxMessages() < 1) {
                throw new IllegalArgumentException(
                    "private-messages.rate-limit.max-messages must be at least 1 (got: " + rateLimit.maxMessages() + ")");
            }
            if (rateLimit.windowSeconds() < 1 || rateLimit.duplicateWindowSeconds() < 0) {
                throw new IllegalArgumentException(
                    "private-messages.rate-limit.window-seconds must be at least 1 and duplicate-window-seconds non-negative");
            }
            if (spyRefreshSeconds < 5) {
                throw new IllegalArgumentException(
                    "private-messages.spy.refresh-seconds must be at least 5 (got: " + spyRefreshSeconds + ")");
            }
            if (log.localRetentionDays() < 1) {
                throw new IllegalArgumentException(
                    "private-messages.log.local-retention-days must be at least 1 (got: " + log.localRetentionDays() + ")");
            }
            if (log.flushSeconds() < 1) {
                throw new IllegalArgumentException(
                    "private-messages.log.flush-seconds must be at least 1 (got: " + log.flushSeconds() + ")");
            }
        }
    }

    /**
     * Diagnostic telemetry (KNG-34 link 6, knk-workspace docs/specs/player-statistics IMPLEMENTATION_PLAN.md
     * §5.1, DESIGN.md §F.12): low-frequency baseline events for every player, enhanced events only for
     * players/test runs the owner picked in the API. Buffered in memory (bounded, oldest dropped and
     * counted), flushed off the main thread, never spooled. {@code enabled: false} registers no emitter,
     * listener or task - today's behaviour.
     *
     * @param maxBufferEvents                events held between flushes; beyond it the oldest are dropped
     * @param flushIntervalSeconds           how often buffered events are sent
     * @param configPollSeconds              how often the API's emitter config (enhanced targets, test runs) is read
     * @param enhancedMovementSampleSeconds  position sample interval for enhanced players
     */
    public record TelemetryConfig(
        boolean enabled,
        int maxBufferEvents,
        int flushIntervalSeconds,
        int configPollSeconds,
        int enhancedMovementSampleSeconds
    ) {
        public static TelemetryConfig defaults() {
            return new TelemetryConfig(true, 5000, 10, 60, 5);
        }

        public void validate() {
            if (maxBufferEvents < 10) {
                throw new IllegalArgumentException("telemetry.max-buffer-events must be at least 10 (got: " + maxBufferEvents + ")");
            }
            if (flushIntervalSeconds < 1) {
                throw new IllegalArgumentException("telemetry.flush-interval-seconds must be at least 1 (got: " + flushIntervalSeconds + ")");
            }
            if (configPollSeconds < 10) {
                throw new IllegalArgumentException("telemetry.config-poll-seconds must be at least 10 (got: " + configPollSeconds + ")");
            }
            if (enhancedMovementSampleSeconds < 1) {
                throw new IllegalArgumentException(
                    "telemetry.enhanced-movement-sample-seconds must be at least 1 (got: " + enhancedMovementSampleSeconds + ")");
            }
        }
    }

    /**
     * World analytics (KNG-34 link 7, knk-workspace docs/specs/player-statistics IMPLEMENTATION_PLAN.md
     * §5.1, DESIGN.md D10/D11): anonymous movement heatmap cells, menu funnels and domain interactions,
     * aggregated in memory and posted every {@code flush-interval-seconds} (never across a local midnight).
     * No user id or name leaves the plugin. {@code enabled: false} creates no sampler, observer, listener
     * or task - today's behaviour; the three part switches turn single parts off.
     *
     * @param movementSampleSeconds  one position sample per online, non-AFK player this often (at least 10 s)
     * @param cellSize               heatmap cell edge in blocks
     * @param flushIntervalSeconds   how often the aggregates are posted
     * @param movement               sample positions for the heatmap
     * @param menuFunnels            count menu opens, actions, back and close
     * @param domainInteractions     count region entries/exits and discoveries per domain
     * @param excludedGameModes      players in these game modes are not sampled (spectators never are)
     * @param maxPendingBatches      closed windows kept in memory while the API is unreachable (oldest dropped)
     */
    public record WorldAnalyticsConfig(
        boolean enabled,
        int movementSampleSeconds,
        int cellSize,
        int flushIntervalSeconds,
        boolean movement,
        boolean menuFunnels,
        boolean domainInteractions,
        Set<GameMode> excludedGameModes,
        int maxPendingBatches
    ) {
        public WorldAnalyticsConfig {
            excludedGameModes = excludedGameModes == null ? Set.of() : Set.copyOf(excludedGameModes);
        }

        public static WorldAnalyticsConfig defaults() {
            return new WorldAnalyticsConfig(true, 10, 16, 300, true, true, true,
                Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), 12);
        }

        public void validate() {
            if (movementSampleSeconds < 10) {
                throw new IllegalArgumentException(
                    "world-analytics.movement-sample-seconds must be at least 10 (got: " + movementSampleSeconds + ")");
            }
            if (cellSize < 1 || cellSize > 1024) {
                throw new IllegalArgumentException("world-analytics.cell-size must be between 1 and 1024 (got: " + cellSize + ")");
            }
            if (flushIntervalSeconds < 30) {
                throw new IllegalArgumentException(
                    "world-analytics.flush-interval-seconds must be at least 30 (got: " + flushIntervalSeconds + ")");
            }
            if (maxPendingBatches < 1) {
                throw new IllegalArgumentException(
                    "world-analytics.max-pending-batches must be at least 1 (got: " + maxPendingBatches + ")");
            }
        }
    }
}
