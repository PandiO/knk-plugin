package net.knightsandkings.knk.core.teleport;

/**
 * Why a running warmup stopped (docs/specs/teleport/DESIGN.md §3.4 step 2). Nothing has been
 * charged at that point, so a cancel never needs a refund.
 */
public enum WarmupCancelReason {
    MOVED("Teleport cancelled: you moved."),
    DAMAGED("Teleport cancelled: you took damage."),
    TELEPORTED("Teleport cancelled: you were teleported."),
    DIED("Teleport cancelled: you died."),
    QUIT("Teleport cancelled: you left the server."),
    FROZEN("Teleport cancelled: you were frozen."),
    REPLACED("Previous teleport cancelled."),
    BY_PLAYER("Teleport cancelled."),
    SHUTDOWN("Teleport cancelled: the server is stopping.");

    private final String message;

    WarmupCancelReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
