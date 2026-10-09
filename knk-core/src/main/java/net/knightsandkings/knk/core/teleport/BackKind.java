package net.knightsandkings.knk.core.teleport;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of place {@code /back} can return a player to (Linear KNG-42). Each has its own
 * permission node (knk-paper {@code TeleportNodes.backNode}) and may have its own expiry
 * ({@code teleport.back.expire-seconds-by-kind.<configKey>}). A player who may use several gets
 * one {@code /back}: to the latest entry among the kinds they may use.
 * <p>
 * Developer decisions 2026-10-05: {@link #TELEPORT} covers {@code /tpa}/{@code /tpahere} (whoever
 * moves) and a staff member's own {@code /tp}; being moved by staff records nothing. A {@code /back}
 * itself is never recorded (no ping-pong). Siege teleports and deaths are never recorded and don't
 * wipe older entries.
 */
public enum BackKind {
    /** Where the player died (the original {@code /back}, KNG-17 Phase 7). */
    DEATH("death", "where you died"),
    /** Where the player stood before a {@code /warp} (command or teleport menu). */
    WARPS("warps", "where you were before your warp"),
    /** Where the player stood before a {@code /tpa}/{@code /tpahere} or their own staff {@code /tp}. */
    TELEPORT("teleport", "where you were before your teleport"),
    /** Where the player stood before a {@code /spawn}. */
    SPAWN("spawn", "where you were before /spawn");

    private final String configKey;
    private final String description;

    BackKind(String configKey, String description) {
        this.configKey = configKey;
        this.description = description;
    }

    /** The key under {@code teleport.back.expire-seconds-by-kind} and the node suffix. */
    public String configKey() {
        return configKey;
    }

    /** "where you died", for "Teleported back to ...". */
    public String description() {
        return description;
    }

    /** The kind whose {@link #configKey} is {@code key} (case-insensitive). */
    public static Optional<BackKind> fromConfigKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (BackKind kind : values()) {
            if (kind.configKey.equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
