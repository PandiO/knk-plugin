package net.knightsandkings.knk.core.domain.users;

/**
 * A player's owner/staff mode (docs/specs/user-features/DESIGN.md §6.1), toggled in-game via
 * /ownermode and /staffmode. Mirrors the backend's ActiveMode enum (knk-web-api Models/User.cs).
 * Mutually exclusive - a player is in at most one mode - and any mode other than {@link #NONE}
 * means the player is vanished; there is no separate vanish flag.
 */
public enum ActiveMode {
    /**
     * Not in any mode; visible to everyone.
     */
    NONE,

    /**
     * Staff mode (/staffmode, gated by knk.mode.staff).
     */
    STAFF,

    /**
     * Owner mode (/ownermode, gated by knk.mode.owner).
     */
    OWNER;

    /**
     * Parses the backend's PascalCase enum wire value (e.g. "Owner"), falling back to NONE for
     * null/blank/unrecognized values - an unreadable value restores the player visible rather
     * than breaking login.
     */
    public static ActiveMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return switch (value.trim().toLowerCase()) {
            case "staff" -> STAFF;
            case "owner" -> OWNER;
            default -> NONE;
        };
    }

    /**
     * Serializes back to the backend's PascalCase wire format, e.g. for persisting a player's
     * mode via PUT /api/users/{id}/active-mode.
     */
    public String toWireValue() {
        return switch (this) {
            case STAFF -> "Staff";
            case OWNER -> "Owner";
            case NONE -> "None";
        };
    }

    /**
     * Whether this mode hides the player from players without staff/owner visibility.
     */
    public boolean isVanished() {
        return this != NONE;
    }
}
