package net.knightsandkings.knk.core.teleport;

/**
 * How a teleport started through the engine ended. {@code message} is the player-facing reason for
 * anything but {@link Status#TELEPORTED} (null then).
 */
public record TeleportOutcome(Status status, String message, String code) {

    public enum Status {
        /** The subject arrived. */
        TELEPORTED,
        /** A guard refused it (before the warmup or at commit). */
        DENIED,
        /** The warmup was interrupted (movement, damage, replaced, quit...). */
        CANCELLED,
        /** It could not be carried out (destination gone, teleport blocked by another listener...). */
        FAILED
    }

    public static TeleportOutcome teleported() {
        return new TeleportOutcome(Status.TELEPORTED, null, null);
    }

    public static TeleportOutcome denied(TeleportDenial denial) {
        return new TeleportOutcome(Status.DENIED, denial.message(), denial.code());
    }

    public static TeleportOutcome cancelled(WarmupCancelReason reason) {
        return new TeleportOutcome(Status.CANCELLED, reason.message(), reason.name());
    }

    public static TeleportOutcome failed(String message) {
        return new TeleportOutcome(Status.FAILED, message, null);
    }

    public boolean isTeleported() {
        return status == Status.TELEPORTED;
    }
}
