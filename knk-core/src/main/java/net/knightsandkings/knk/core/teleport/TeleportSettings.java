package net.knightsandkings.knk.core.teleport;

/**
 * The {@code teleport:} block of the plugin's config.yml (docs/specs/teleport/DESIGN.md §3.11):
 * the engine keys (Phase 1) and the {@code request:} sub-block (Phase 3) - the destination/back keys
 * arrive with their phases. Negative values are clamped to 0 so a typo can't make a warmup or
 * cooldown negative.
 *
 * @param warmupSeconds       player teleport warmup (v1: 5 s)
 * @param warmupShortSeconds  warmup for holders of {@code knk.teleport.warmup.short} (v1 premium: 3 s)
 * @param cooldownSeconds     cooldown after a player-initiated teleport (DESIGN §4 D4)
 * @param combatTagSeconds    how long a PvP hit blocks player teleports (v1 {@code Main.combat})
 * @param safeSearchRadius    how far (blocks, horizontally) to look for a safe spot around a destination
 * @param request             {@code /tpa} / {@code /tpahere} settings
 */
public record TeleportSettings(
    int warmupSeconds,
    int warmupShortSeconds,
    int cooldownSeconds,
    int combatTagSeconds,
    int safeSearchRadius,
    TeleportRequestSettings request
) {
    public TeleportSettings {
        warmupSeconds = Math.max(0, warmupSeconds);
        warmupShortSeconds = Math.max(0, warmupShortSeconds);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        combatTagSeconds = Math.max(0, combatTagSeconds);
        safeSearchRadius = Math.max(0, safeSearchRadius);
        request = request != null ? request : TeleportRequestSettings.defaults();
    }

    /** Engine settings with the default request settings. */
    public TeleportSettings(int warmupSeconds, int warmupShortSeconds, int cooldownSeconds, int combatTagSeconds,
                            int safeSearchRadius) {
        this(warmupSeconds, warmupShortSeconds, cooldownSeconds, combatTagSeconds, safeSearchRadius,
            TeleportRequestSettings.defaults());
    }

    /** The DESIGN §3.11 defaults, used when config.yml has no {@code teleport:} block. */
    public static TeleportSettings defaults() {
        return new TeleportSettings(5, 3, 30, 10, 3, TeleportRequestSettings.defaults());
    }
}
