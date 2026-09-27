package net.knightsandkings.knk.core.domain.users;

/**
 * A pending in-game moment for one player, queued by the web API for a write the plugin didn't
 * make itself (e.g. an XP grant from the web admin's player profile page). Delivered by
 * PlayerNotificationPoller. Mirrors knk-web-api's PlayerNotificationDto.
 */
public record PlayerNotification(
    long id,
    int userId,
    String uuid, // null for a web-app-only account that never joined
    String username,
    String type, // see TYPE_TITLE_CHANGED
    TitleChangeResult titleChange, // set when type is TYPE_TITLE_CHANGED
    net.knightsandkings.knk.core.domain.currency.PaymentNotice payment, // set when type is TYPE_PAYMENT_RECEIVED
    net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice currencyAlert // set when type is TYPE_CURRENCY_ALERT
) {
    /** Without a payment - every type before TYPE_PAYMENT_RECEIVED. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange) {
        this(id, userId, uuid, username, type, titleChange, null, null);
    }

    /** Without a currency alert - every type before TYPE_CURRENCY_ALERT. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange,
                              net.knightsandkings.knk.core.domain.currency.PaymentNotice payment) {
        this(id, userId, uuid, username, type, titleChange, payment, null);
    }

    public static final String TYPE_TITLE_CHANGED = "TitleChanged";
    /**
     * The player's group memberships changed outside the plugin (web app, API, or a temporary rank
     * expiring - knk-web-api's RankExpirySweepService). No payload: re-read the player and redraw
     * their chat/tab-list rank.
     */
    public static final String TYPE_RANK_CHANGED = "RankChanged";
    /**
     * The API issued lootbox token items to the player itself (a premium tier or kit grant rule,
     * docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5). No payload: fetch and hand over their undelivered tokens.
     */
    public static final String TYPE_LOOTBOX_TOKENS_ISSUED = "LootboxTokensIssued";
    /**
     * Another player paid this one (/pay, currency ledger KNG-21 Phase 3). Payload in
     * {@link #payment()}. Queued for every completed payment, so an offline recipient hears
     * about it on their next join.
     */
    public static final String TYPE_PAYMENT_RECEIVED = "PaymentReceived";
    /**
     * A currency anomaly alert (currency ledger Phase 5) for online staff holding
     * knk.admin.currency.alerts - not addressed to one player (userId 0, no uuid). Payload in
     * {@link #currencyAlert()}.
     */
    public static final String TYPE_CURRENCY_ALERT = "CurrencyAlert";
}
