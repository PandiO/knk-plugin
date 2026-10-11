package net.knightsandkings.knk.paper.connectivity;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;

/**
 * {@code config.yml} {@code api.connectivity.*} (KNG-115). Missing keys fall back to the defaults
 * below so an existing server config without the section keeps working.
 *
 * @param healthRootUrl API root for {@code /health/ready}; blank derives it from {@code api.base-url}
 */
public record ApiConnectivitySettings(
    boolean enabled,
    Duration probeInterval,
    Duration probeTimeout,
    int failuresToDown,
    int successesToUp,
    String healthRootUrl
) {
    public static final String SECTION = "api.connectivity";

    public static ApiConnectivitySettings defaults() {
        return new ApiConnectivitySettings(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 3, 2, "");
    }

    /** Reads {@code api.connectivity} from the plugin's root config; null or missing gives the defaults. */
    public static ApiConnectivitySettings fromConfig(ConfigurationSection root) {
        ApiConnectivitySettings d = defaults();
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        if (s == null) {
            return d;
        }
        return new ApiConnectivitySettings(
            s.getBoolean("enabled", d.enabled()),
            Duration.ofSeconds(Math.max(1L, s.getLong("probe-interval-seconds", d.probeInterval().toSeconds()))),
            Duration.ofSeconds(Math.max(1L, s.getLong("probe-timeout-seconds", d.probeTimeout().toSeconds()))),
            Math.max(1, s.getInt("failures-to-down", d.failuresToDown())),
            Math.max(1, s.getInt("successes-to-up", d.successesToUp())),
            s.getString("health-root-url", d.healthRootUrl())
        );
    }
}
