package net.knightsandkings.knk.core.domain.settings;

import java.util.Locale;

/**
 * The hub (knk-web-api {@code HubSettingsDto}, hub DESIGN §16 Phase 4): the selected hub domain, where joining players
 * are placed, what happens to online players when the API is lost, and the rules inside the hub.
 *
 * @param domainId           the selected hub domain; null = no hub (players join as before)
 * @param domainName         its name, for messages and logs
 * @param domainType         its type ({@code Town}, {@code District}, ...)
 * @param worldName          its world
 * @param wgRegionId         its WorldGuard region: the hub's rules apply inside it
 * @param spawn              where joining players are placed
 * @param outageAction       what happens to online players when the API is lost
 * @param outageDelaySeconds seconds before that; 0 = immediately
 * @param rules              the rules inside the hub; never null
 */
public record KnkHubSettings(Integer domainId, String domainName, String domainType, String worldName, String wgRegionId,
                             KnkSpawnReference spawn, OutageAction outageAction, int outageDelaySeconds, KnkHubRules rules) {

    /** hub DESIGN §1, §2. */
    public enum OutageAction {
        NONE, SEND_TO_HUB, KICK, SEND_TO_WORLD_SPAWN;

        /** The API's value ({@code "SendToHub"}, ...); null when unknown. */
        public static OutageAction parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String key = value.trim().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
            try {
                return valueOf(key);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    public KnkHubSettings {
        outageAction = outageAction != null ? outageAction : OutageAction.SEND_TO_HUB;
        outageDelaySeconds = Math.max(0, outageDelaySeconds);
        rules = rules != null ? rules : KnkHubRules.defaults();
    }

    /** No hub configured. */
    public static KnkHubSettings none() {
        return new KnkHubSettings(null, null, null, null, null, null, OutageAction.SEND_TO_HUB, 0, KnkHubRules.defaults());
    }

    /** Whether a hub is selected (with a spawn: the API refuses a hub without one). */
    public boolean configured() {
        return domainId != null && spawn != null;
    }
}
