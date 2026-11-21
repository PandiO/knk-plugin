package net.knightsandkings.knk.core.production.shop;

import java.util.UUID;

/**
 * Idempotency keys for purchases and sales (API contract shops § 4.2: 1 to 60 characters of letters, digits and
 * {@code : _ . -}). One key per confirmation click, reused for its retry, so a retry is booked once.
 */
public final class ShopKeys {

    public static final int MAX_LENGTH = 60;

    private ShopKeys() {
    }

    /** {@code buy-s<shopId>-u<userId>-<random>}. */
    public static String purchase(int shopId, int userId) {
        return "buy-s" + shopId + "-u" + userId + "-" + random();
    }

    /** {@code sell-s<shopId>-u<userId>-<random>}. */
    public static String sale(int shopId, int userId) {
        return "sell-s" + shopId + "-u" + userId + "-" + random();
    }

    public static boolean valid(String key) {
        return key != null && key.matches("[A-Za-z0-9:_.\\-]{1," + MAX_LENGTH + "}");
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
