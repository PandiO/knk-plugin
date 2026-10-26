package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.impl.HealthApiImpl;
import net.knightsandkings.knk.core.realtime.ReconnectBackoff;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code config.yml} {@code realtime.*} (PandiO/knk-workspace#178, DESIGN §4.4). Missing keys fall back to the
 * defaults, so an older config.yml without the section keeps working, with the channel off.
 *
 * @param enabled   connect to the API's game hub (default false until the developer's live check, DESIGN §12 Q15)
 * @param serverId  this game server's id: sent as {@code X-Knk-Server-Id} and used for world-task claims (replaces the
 *                  hard-coded {@code "localhost"}, which stays the default). Letters, digits, '.', '_', '-'; max 64.
 * @param hubUrl    full hub URL; blank derives it from {@code api.base-url} without its {@code /api} plus {@code hubPath}
 * @param hubPath   hub path on the API (default {@code /api/hubs/game})
 * @param backoff   delays between reconnect attempts; the last one repeats
 * @param jitter    0-1, random spread of each delay
 */
public record RealtimeSettings(
    boolean enabled,
    String serverId,
    String hubUrl,
    String hubPath,
    List<Duration> backoff,
    double jitter
) {
    public static final String SECTION = "realtime";
    public static final String DEFAULT_SERVER_ID = "localhost";
    public static final String DEFAULT_HUB_PATH = "/api/hubs/game";

    private static final Pattern SERVER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public RealtimeSettings {
        serverId = validServerId(serverId) ? serverId.trim() : DEFAULT_SERVER_ID;
        hubUrl = hubUrl == null ? "" : hubUrl.trim();
        hubPath = hubPath == null || hubPath.isBlank() ? DEFAULT_HUB_PATH : (hubPath.startsWith("/") ? hubPath.trim() : "/" + hubPath.trim());
        backoff = backoff == null || backoff.isEmpty() ? ReconnectBackoff.DEFAULT_STEPS : List.copyOf(backoff);
        jitter = Math.max(0.0, Math.min(1.0, jitter));
    }

    public static RealtimeSettings defaults() {
        return new RealtimeSettings(false, DEFAULT_SERVER_ID, "", DEFAULT_HUB_PATH, ReconnectBackoff.DEFAULT_STEPS,
            ReconnectBackoff.DEFAULT_JITTER);
    }

    /** True when {@code raw} is a usable server id (the API refuses anything else). */
    public static boolean validServerId(String raw) {
        return raw != null && SERVER_ID.matcher(raw.trim()).matches();
    }

    /** Reads {@code realtime} from the plugin's root config; null or missing gives the defaults. */
    public static RealtimeSettings fromConfig(ConfigurationSection root) {
        RealtimeSettings d = defaults();
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        if (s == null) {
            return d;
        }
        List<Duration> steps = new ArrayList<>();
        for (Object value : s.getList("backoff-seconds", List.of())) {
            if (value instanceof Number number && number.doubleValue() > 0) {
                steps.add(Duration.ofMillis(Math.round(number.doubleValue() * 1000)));
            }
        }
        return new RealtimeSettings(
            s.getBoolean("enabled", d.enabled()),
            s.getString("server-id", d.serverId()),
            s.getString("hub-url", d.hubUrl()),
            s.getString("hub-path", d.hubPath()),
            steps,
            s.getDouble("backoff-jitter", d.jitter())
        );
    }

    /** The hub URL to connect to, e.g. {@code http://api:5000/api/hubs/game} for base URL {@code http://api:5000/api}. */
    public String resolveHubUrl(String apiBaseUrl) {
        if (!hubUrl.isBlank()) {
            return hubUrl;
        }
        return HealthApiImpl.deriveHealthRootUrl(apiBaseUrl == null ? "" : apiBaseUrl) + hubPath;
    }
}
