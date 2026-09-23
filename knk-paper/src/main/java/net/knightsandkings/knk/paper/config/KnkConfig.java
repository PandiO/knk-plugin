package net.knightsandkings.knk.paper.config;

import java.time.Duration;

/**
 * Plugin configuration loaded from config.yml.
 */
public record KnkConfig(
    ApiConfig api,
    CacheConfig cache,
    AccountConfig account,
    MessagesConfig messages
) {
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
        EntitySettings domainCatalog
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
                EntitySettings.defaults()  // domainCatalog
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
}
