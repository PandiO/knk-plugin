package net.knightsandkings.knk.core.domain.users;

/**
 * A player's preferred method for passing through a closed, pass-through-enabled gate.
 * Mirrors the backend's GatePassThroughMethod enum (knk-web-api Models/User.cs).
 */
public enum GatePassThroughMethod {
    /**
     * The gate opens, stays open for the gate's configured PassThroughDurationSeconds, then
     * auto-closes.
     */
    DEFAULT,

    /**
     * Only the door blocks in the player's path are instantly removed, then restored once the
     * player has passed through. Requires the knk.gate.passthrough.instant permission.
     */
    INSTANT_OPEN,

    /**
     * The player is teleported directly to the other side of the gate. The gate never animates.
     */
    TELEPORT;

    /**
     * Parses the backend's PascalCase enum wire value (e.g. "InstantOpen"), falling back to
     * DEFAULT for null/blank/unrecognized values so a bad or missing value never breaks
     * pass-through - it just uses the safest mode.
     */
    public static GatePassThroughMethod fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return switch (value.trim().toLowerCase()) {
            case "instantopen" -> INSTANT_OPEN;
            case "teleport" -> TELEPORT;
            default -> DEFAULT;
        };
    }

    /**
     * Serializes back to the backend's PascalCase wire format, e.g. for persisting a player's
     * choice via PUT /api/users/{id}/gate-passthrough-method.
     */
    public String toWireValue() {
        return switch (this) {
            case INSTANT_OPEN -> "InstantOpen";
            case TELEPORT -> "Teleport";
            case DEFAULT -> "Default";
        };
    }
}
