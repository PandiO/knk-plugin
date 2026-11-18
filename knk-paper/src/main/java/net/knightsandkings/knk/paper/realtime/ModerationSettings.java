package net.knightsandkings.knk.paper.realtime;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code config.yml} {@code realtime.moderation.*} (PandiO/knk-workspace#574, DESIGN §6.10): what a player sees when
 * staff kick or ban them from the web app. Only used with {@code realtime.enabled: true}; missing or blank keys fall
 * back to the defaults. Both texts take {@code &} colour codes and the placeholders {@code {reason}}, {@code {player}}
 * and {@code {staff}}.
 *
 * @param kickDefaultReason the kick text when staff gave no reason (a kick with a reason shows the reason as typed)
 * @param banMessage        the kick text of a banned online player; {@code {reason}} is the ban reason
 * @param banSource         the ban list's source when the event names no staff member
 */
public record ModerationSettings(String kickDefaultReason, String banMessage, String banSource) {

    public static final String SECTION = "realtime.moderation";
    /** The same text as the in-game user manager's kick ({@code UserAdminService.KICK_REASON}). */
    public static final String DEFAULT_KICK_REASON = "You were kicked by a member of staff.";
    public static final String DEFAULT_BAN_MESSAGE = "You are banned from this server.\nReason: {reason}";
    /** Never used for a valid event (a ban always has a reason); kept so a malformed one still bans with a reason. */
    public static final String DEFAULT_BAN_REASON = "You were banned by a member of staff.";
    public static final String DEFAULT_BAN_SOURCE = "knk-web";

    public ModerationSettings {
        kickDefaultReason = orDefault(kickDefaultReason, DEFAULT_KICK_REASON);
        banMessage = orDefault(banMessage, DEFAULT_BAN_MESSAGE);
        banSource = orDefault(banSource, DEFAULT_BAN_SOURCE);
    }

    public static ModerationSettings defaults() {
        return new ModerationSettings(DEFAULT_KICK_REASON, DEFAULT_BAN_MESSAGE, DEFAULT_BAN_SOURCE);
    }

    /** Reads {@code realtime.moderation} from the plugin's root config; null or missing gives the defaults. */
    public static ModerationSettings fromConfig(ConfigurationSection root) {
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        if (s == null) {
            return defaults();
        }
        return new ModerationSettings(s.getString("kick-default-reason"), s.getString("ban-message"), null);
    }

    /** Fills {@code {reason}}, {@code {player}} and {@code {staff}} (missing values become empty). */
    public static String fill(String template, String reason, String player, String staff) {
        return template
            .replace("{reason}", reason == null ? "" : reason)
            .replace("{player}", player == null ? "" : player)
            .replace("{staff}", staff == null ? "" : staff);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
