package net.knightsandkings.knk.core.domain.users;

import java.util.Locale;

/**
 * A balance a staff member can change with {@code PUT /api/Users/{id}/balances} (currency ledger,
 * KNG-21 Phase 2). {@link #wireValue()} is the API's name, {@link #property()} the one
 * {@code /knk user} and the Player manager use.
 */
public enum BalanceCurrency {
    COINS("Coins", "coins"),
    GEMS("Gems", "gems"),
    EXPERIENCE("Experience", "xp");

    private final String wireValue;
    private final String property;

    BalanceCurrency(String wireValue, String property) {
        this.wireValue = wireValue;
        this.property = property;
    }

    public String wireValue() {
        return wireValue;
    }

    public String property() {
        return property;
    }

    /** "coins" / "gems" / "xp" (any case), else null. */
    public static BalanceCurrency forProperty(String property) {
        if (property == null) {
            return null;
        }
        String p = property.toLowerCase(Locale.ROOT);
        for (BalanceCurrency c : values()) {
            if (c.property.equals(p)) {
                return c;
            }
        }
        return null;
    }

    /** The API's "Coins" / "Gems" / "Experience" (any case), else null. */
    public static BalanceCurrency fromWireValue(String value) {
        for (BalanceCurrency c : values()) {
            if (c.wireValue.equalsIgnoreCase(value)) {
                return c;
            }
        }
        return null;
    }
}
