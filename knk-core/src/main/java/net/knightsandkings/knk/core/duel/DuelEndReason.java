package net.knightsandkings.knk.core.duel;

/**
 * Why a match ended (DESIGN §4.3 {@code EndReason}, §7.5). {@link #apiName()} is the string the API
 * stores. {@link #LOCK_REFUSED} has no API counterpart: no match was created (DESIGN §6.5 step 4).
 */
public enum DuelEndReason {
    WIN(Status.COMPLETED, true),
    FORFEIT(Status.COMPLETED, true),
    TIME_LIMIT(Status.COMPLETED, false),
    AGREED_DRAW(Status.COMPLETED, false),
    SIMULTANEOUS_DEATH(Status.COMPLETED, false),
    TELEPORT_FAILED(Status.CANCELLED, false),
    PLAYER_LEFT_LOBBY(Status.CANCELLED, false),
    NO_BATTLEGROUND(Status.CANCELLED, false),
    ADMIN_CANCELLED(Status.CANCELLED, false),
    SERVER_RESTART(Status.ABORTED, false),
    API_OUTAGE(Status.ABORTED, false),
    LOCK_REFUSED(Status.NOT_CREATED, false);

    /** The match status the reason ends in. */
    public enum Status {
        COMPLETED,
        CANCELLED,
        ABORTED,
        /** Lock-in was refused; there is no match record. */
        NOT_CREATED
    }

    private final Status status;
    private final boolean decisive;

    DuelEndReason(Status status, boolean decisive) {
        this.status = status;
        this.decisive = decisive;
    }

    public Status status() {
        return status;
    }

    /** There is a winner: the only outcome that moves value between the players (DESIGN §10.1). */
    public boolean isDecisive() {
        return decisive;
    }

    /** A completed match without a winner. */
    public boolean isDraw() {
        return status == Status.COMPLETED && !decisive;
    }

    /** The API's PascalCase name, e.g. {@code SimultaneousDeath}; null for {@link #LOCK_REFUSED}. */
    public String apiName() {
        if (this == LOCK_REFUSED) return null;
        StringBuilder out = new StringBuilder();
        for (String part : name().split("_")) {
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }
}
