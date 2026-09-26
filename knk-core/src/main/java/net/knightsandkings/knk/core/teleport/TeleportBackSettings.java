package net.knightsandkings.knk.core.teleport;

/**
 * The {@code teleport.back:} block of the plugin's config.yml (docs/specs/teleport/DESIGN.md §3.11,
 * Phase 7). {@code /back} returns a player to where they last died - death location only, for the
 * holders of {@code knk.teleport.back} (Dragon Blood), for a few minutes, never after a siege death
 * (developer decision Q5).
 *
 * @param enabled       whether {@code /back} is available at all
 * @param expireSeconds how long after a death {@code /back} can be started (v1 perk text: 5 minutes);
 *                      at least 1
 */
public record TeleportBackSettings(boolean enabled, int expireSeconds) {

    /** Developer decision Q5: available for 5 minutes after a death. */
    public static final int DEFAULT_EXPIRE_SECONDS = 300;

    public TeleportBackSettings {
        expireSeconds = Math.max(1, expireSeconds);
    }

    /** The defaults, used when config.yml has no {@code teleport.back:} block. */
    public static TeleportBackSettings defaults() {
        return new TeleportBackSettings(true, DEFAULT_EXPIRE_SECONDS);
    }
}
