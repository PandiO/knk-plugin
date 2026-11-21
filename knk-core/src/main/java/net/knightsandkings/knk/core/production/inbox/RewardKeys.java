package net.knightsandkings.knk.core.production.inbox;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotency keys for deliveries and claims (PandiO/knk-workspace#684; the API accepts 1 to 80 characters of letters,
 * digits and {@code : _ . -}). A key is made once per reward or click and reused for its retries, so a retry applies once.
 */
public final class RewardKeys {

    public static final int MAX_LENGTH = 80;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9:_.\\-]{1," + MAX_LENGTH + "}");
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^A-Za-z0-9:_.\\-]");

    private RewardKeys() {
    }

    /** {@code kit-claim-<claimId>}: a kit grant the API keeps a claim row for (#261). */
    public static String kitClaim(int claimId) {
        return "kit-claim-" + claimId;
    }

    /** {@code kit-first-join-u<userId>}: the starter kits, granted once per player by the API (KNG-81). */
    public static String firstJoin(int userId) {
        return "kit-first-join-u" + userId;
    }

    /** {@code lootbox-claim-<claimId>}. */
    public static String lootboxClaim(int claimId) {
        return "lootbox-claim-" + claimId;
    }

    /**
     * {@code kit-<kitId>-u<userId>-<random>}: a kit claimed or given in game, which has no claim id in the plugin; the
     * random part makes each grant its own delivery.
     */
    public static String kitGrant(int kitId, int userId) {
        return "kit-" + kitId + "-u" + userId + "-" + shortRandom();
    }

    /** {@code inbox-claim-u<userId>-<random>}: one claim click. */
    public static String claim(int userId) {
        return "inbox-claim-u" + userId + "-" + shortRandom();
    }

    /** Whether the API accepts {@code key}. */
    public static boolean valid(String key) {
        return key != null && ALLOWED.matcher(key).matches();
    }

    /** {@code key} with characters the API refuses replaced by {@code -}, cut to 80. */
    public static String sanitize(String key) {
        if (key == null || key.isEmpty()) {
            return "reward-" + shortRandom();
        }
        String clean = NOT_ALLOWED.matcher(key).replaceAll("-");
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) : clean;
    }

    private static String shortRandom() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
