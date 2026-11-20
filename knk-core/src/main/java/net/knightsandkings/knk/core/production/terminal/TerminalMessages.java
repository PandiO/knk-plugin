package net.knightsandkings.knk.core.production.terminal;

/**
 * What a player is told when a terminal move fails (the API's answers of PandiO/knk-workspace#683): 403 not a member,
 * 409 {@code ProductionDisabled} or a display, 423 or 409 locked, others generic.
 */
public final class TerminalMessages {

    private TerminalMessages() {
    }

    public static String forStatus(int status, String body) {
        String text = body == null ? "" : body;
        if (status == 403) {
            return "Only members of this storage's town, its owner and staff can use this terminal.";
        }
        if (status == 409 && text.contains("ProductionDisabled")) {
            return "Production is switched off; the terminal moves nothing right now.";
        }
        if (status == 409 && text.toLowerCase(java.util.Locale.ROOT).contains("display")) {
            return "This container is a display, not a terminal.";
        }
        if (status == 409 || status == 423) {
            return "The storage is busy or locked; try again in a moment.";
        }
        if (status == 404) {
            return "This container is no longer bound.";
        }
        if (status == 0) {
            return "The storage can't be reached right now; try again in a moment.";
        }
        return "The storage refused that (" + status + ").";
    }

    /** "NotEnoughStock" → "not enough stock", "CapacityFull" → "the storage is full". */
    public static String rejectReason(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case "CapacityFull" -> "the storage is full";
            case "DistinctCapReached" -> "the storage can't hold another kind of item";
            case "NotEnoughStock" -> "not enough in stock";
            case "Locked" -> "the storage is locked";
            default -> reason;
        };
    }
}
