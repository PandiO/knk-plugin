package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;

import net.knightsandkings.knk.core.connectivity.ApiOutageSignal;

/**
 * Configuration settings for data access gateways.
 * <p>
 * Encapsulates default fetch policy, stale allowance, retry behavior and the API outage signal
 * (KNG-171: reads skip the API while it is DOWN) for a specific entity type.
 * Used to configure data access gateways with consistent settings from plugin configuration.
 * <p>
 * Immutable value object; supports both constructor and builder for construction.
 */
public final class DataAccessSettings {

    private final FetchPolicy defaultPolicy;
    private final boolean allowStale;
    private final RetryPolicy retryPolicy;
    private final ApiOutageSignal apiOutage;

    public DataAccessSettings(
        FetchPolicy defaultPolicy,
        boolean allowStale,
        RetryPolicy retryPolicy
    ) {
        this(defaultPolicy, allowStale, retryPolicy, ApiOutageSignal.NEVER);
    }

    /** @param apiOutage while it reports DOWN, reads skip the API (KNG-171); null = never down */
    public DataAccessSettings(
        FetchPolicy defaultPolicy,
        boolean allowStale,
        RetryPolicy retryPolicy,
        ApiOutageSignal apiOutage
    ) {
        this.defaultPolicy = defaultPolicy != null ? defaultPolicy : FetchPolicy.CACHE_FIRST;
        this.allowStale = allowStale;
        this.retryPolicy = Objects.requireNonNullElse(retryPolicy, RetryPolicy.defaultPolicy());
        this.apiOutage = Objects.requireNonNullElse(apiOutage, ApiOutageSignal.NEVER);
    }

    /**
     * Default settings: CACHE_FIRST, stale allowed, default retry policy.
     */
    public static DataAccessSettings defaults() {
        return new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.defaultPolicy());
    }

    /**
     * Create a builder for custom settings configuration.
     *
     * @return DataAccessSettings.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public FetchPolicy defaultPolicy() {
        return defaultPolicy;
    }

    public boolean allowStale() {
        return allowStale;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /** KNG-171: while this reports DOWN, {@link DataAccessExecutor} reads skip the API. */
    public ApiOutageSignal apiOutage() {
        return apiOutage;
    }

    /**
     * Resolve the effective fetch policy based on the requested policy and
     * the configured defaults. If stale reads are disabled, STALE_OK will
     * be downgraded to CACHE_FIRST to avoid serving stale data.
     *
     * @param requestedPolicy optional policy requested by the caller
     * @return effective policy after applying defaults and stale toggle
     */
    public FetchPolicy resolvePolicy(FetchPolicy requestedPolicy) {
        FetchPolicy effective = requestedPolicy != null ? requestedPolicy : defaultPolicy;

        if (!allowStale && effective == FetchPolicy.STALE_OK) {
            return FetchPolicy.CACHE_FIRST;
        }

        return effective;
    }

    // ==================== Builder ====================

    public static final class Builder {
        private FetchPolicy defaultPolicy = FetchPolicy.CACHE_FIRST;
        private boolean allowStale = true;
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private ApiOutageSignal apiOutage = ApiOutageSignal.NEVER;

        private Builder() {}

        /**
         * Set the default fetch policy.
         * <p>
         * If not specified, defaults to CACHE_FIRST.
         *
         * @param defaultPolicy The default policy
         * @return This builder for chaining
         */
        public Builder defaultPolicy(FetchPolicy defaultPolicy) {
            this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy must not be null");
            return this;
        }

        /**
         * Set whether stale data can be served.
         * <p>
         * If not specified, defaults to true (stale allowed).
         *
         * @param allowStale Whether stale data is allowed
         * @return This builder for chaining
         */
        public Builder allowStale(boolean allowStale) {
            this.allowStale = allowStale;
            return this;
        }

        /**
         * Set the retry policy.
         * <p>
         * If not specified, uses RetryPolicy.defaultPolicy() (3 attempts, exponential backoff).
         *
         * @param retryPolicy The retry policy
         * @return This builder for chaining
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        /**
         * Set the API outage signal (KNG-171). If not specified, the API is never treated as down.
         *
         * @param apiOutage The signal
         * @return This builder for chaining
         */
        public Builder apiOutage(ApiOutageSignal apiOutage) {
            this.apiOutage = Objects.requireNonNull(apiOutage, "apiOutage must not be null");
            return this;
        }

        /**
         * Build the settings object.
         *
         * @return Immutable DataAccessSettings instance
         */
        public DataAccessSettings build() {
            return new DataAccessSettings(defaultPolicy, allowStale, retryPolicy, apiOutage);
        }
    }

    @Override
    public String toString() {
        return "DataAccessSettings{" +
            "defaultPolicy=" + defaultPolicy +
            ", allowStale=" + allowStale +
            ", retryPolicy=" + retryPolicy +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataAccessSettings)) return false;
        DataAccessSettings that = (DataAccessSettings) o;
        return allowStale == that.allowStale &&
            defaultPolicy == that.defaultPolicy &&
            Objects.equals(retryPolicy, that.retryPolicy) &&
            Objects.equals(apiOutage, that.apiOutage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultPolicy, allowStale, retryPolicy, apiOutage);
    }
}
