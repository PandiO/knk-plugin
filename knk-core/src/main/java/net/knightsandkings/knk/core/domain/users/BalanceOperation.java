package net.knightsandkings.knk.core.domain.users;

import java.util.Locale;

/**
 * What a staff balance change does (currency ledger, KNG-21 Phase 2): add or remove an amount, or
 * set the balance to a target. The server applies it under the row lock, so the plugin never
 * computes a delta from a cached balance (currency DESIGN.md §1.4 A6).
 */
public enum BalanceOperation {
    ADD("Add"),
    REMOVE("Remove"),
    SET("Set");

    private final String wireValue;

    BalanceOperation(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** {@code /knk user ... set|add|remove} (any case), else null. */
    public static BalanceOperation forAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "add" -> ADD;
            case "remove" -> REMOVE;
            case "set" -> SET;
            default -> null;
        };
    }
}
