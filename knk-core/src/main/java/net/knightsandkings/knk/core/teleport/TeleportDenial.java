package net.knightsandkings.knk.core.teleport;

import java.util.Objects;

/**
 * Why a teleport was refused: a stable reason code (for logs and tests) plus the player-facing
 * message. Produced by the engine's own guards (combat tag, cooldown, unsafe destination) and by
 * every registered teleport restriction (freeze, region entry/exit, siege).
 */
public record TeleportDenial(String code, String message) {

    public static final String FROZEN = "frozen";
    public static final String REGION = "region";
    public static final String COMBAT = "combat";
    public static final String COOLDOWN = "cooldown";
    public static final String UNSAFE = "unsafe";
    public static final String SIEGE = "siege";

    public TeleportDenial {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static TeleportDenial of(String code, String message) {
        return new TeleportDenial(code, message);
    }
}
