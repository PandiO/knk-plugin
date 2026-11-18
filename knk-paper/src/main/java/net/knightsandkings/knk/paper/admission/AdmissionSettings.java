package net.knightsandkings.knk.paper.admission;

import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.admission.AdmissionPolicy;
import net.knightsandkings.knk.core.admission.AdmissionPolicy.Mode;

/**
 * The {@code admission:} section of config.yml (invitations plan §6.2). {@code mode: whitelist} is the default, so
 * deploying the plugin changes nothing until the switch-over.
 *
 * @param linkUrl the page that takes a claim code: {@code admission.link-url}, or {@code {web.public-url}/link} when
 *                that is empty; "" when neither is set
 */
public record AdmissionSettings(
    Mode mode,
    long apiTimeoutMs,
    String linkUrl,
    String kickMessage,
    String unavailableMessage,
    String deactivatedMessage,
    String accountLinkedMessage
) {
    private static final Logger LOGGER = Logger.getLogger(AdmissionSettings.class.getName());

    public static final long DEFAULT_API_TIMEOUT_MS = 3000L;

    public static final String DEFAULT_KICK =
        "&6Knights & Kings is invite-only.\n"
            + "&fGo to &e{url} &fand enter\n"
            + "&e&l{code}\n"
            + "&7(valid {minutes} minutes)\n"
            + "&cNever give this code to anyone; staff never ask for it.";
    public static final String DEFAULT_UNAVAILABLE = "&cCan't check your access right now.\n&fTry again in a minute.";
    public static final String DEFAULT_DEACTIVATED = "&cThis account is deactivated.\n&fContact staff if you think this is a mistake.";
    public static final String DEFAULT_ACCOUNT_LINKED =
        "&aYour Minecraft account is now linked to the web account &e{maskedEmail}&a.\n"
            + "&cNot you? Tell staff right away.";

    public AdmissionSettings {
        mode = mode == null ? Mode.WHITELIST : mode;
        apiTimeoutMs = apiTimeoutMs > 0 ? apiTimeoutMs : DEFAULT_API_TIMEOUT_MS;
        linkUrl = linkUrl == null ? "" : linkUrl.trim();
        kickMessage = orDefault(kickMessage, DEFAULT_KICK);
        unavailableMessage = orDefault(unavailableMessage, DEFAULT_UNAVAILABLE);
        deactivatedMessage = orDefault(deactivatedMessage, DEFAULT_DEACTIVATED);
        accountLinkedMessage = orDefault(accountLinkedMessage, DEFAULT_ACCOUNT_LINKED);
    }

    public static AdmissionSettings defaults() {
        return new AdmissionSettings(Mode.WHITELIST, DEFAULT_API_TIMEOUT_MS, "", null, null, null, null);
    }

    /**
     * @param section   the {@code admission:} section; null → defaults
     * @param publicUrl {@code web.public-url} without a trailing slash, for the default link page
     */
    public static AdmissionSettings fromConfig(ConfigurationSection section, String publicUrl) {
        String web = publicUrl == null ? "" : publicUrl.trim();
        while (web.endsWith("/")) {
            web = web.substring(0, web.length() - 1);
        }
        String defaultLink = web.isEmpty() ? "" : web + "/link";
        if (section == null) {
            return new AdmissionSettings(Mode.WHITELIST, DEFAULT_API_TIMEOUT_MS, defaultLink, null, null, null, null);
        }
        String rawMode = section.getString("mode", "whitelist");
        Mode mode = Mode.parse(rawMode);
        if (mode == null) {
            LOGGER.warning("admission.mode '" + rawMode + "' is not whitelist, open or invite: using whitelist.");
            mode = Mode.WHITELIST;
        }
        String link = section.getString("link-url", "");
        return new AdmissionSettings(
            mode,
            section.getLong("api-timeout-ms", DEFAULT_API_TIMEOUT_MS),
            link == null || link.isBlank() ? defaultLink : link.trim(),
            section.getString("kick"),
            section.getString("unavailable"),
            section.getString("deactivated"),
            section.getString("account-linked"));
    }

    /** The template for a policy message key. */
    public String message(String messageKey) {
        return switch (messageKey) {
            case AdmissionPolicy.KICK -> kickMessage;
            case AdmissionPolicy.DEACTIVATED -> deactivatedMessage;
            default -> unavailableMessage;
        };
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
