package net.knightsandkings.knk.core.siege.shop;

/** Small text helpers of the siege shop (PandiO/knk-workspace#868). Pure. */
public final class ShopText {

    private ShopText() {
    }

    /** Whole seconds left, rounded up, at least 1: "12 s". */
    public static String seconds(long millis) {
        long s = Math.max(1, (millis + 999) / 1000);
        return s + " s";
    }

    /** "Siege points: 650". */
    public static String balanceLine(long balance) {
        return "Siege points: " + balance;
    }

    /** The shop footer (P23). */
    public static final String FOOTER = "Purchases are final; bought items end with the match.";
}
