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
    net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice currencyAlert, // set when type is TYPE_CURRENCY_ALERT
    net.knightsandkings.knk.core.lootbox.KnkLootboxWorldChange lootboxWorldChanged, // set when type is TYPE_LOOTBOX_WORLD_CHANGED
    net.knightsandkings.knk.core.domain.location.LocationOrphanDigest locationOrphanDigest // set when type is TYPE_LOCATION_ORPHAN_DIGEST
) {
    /** Without a Location orphan digest - every type before TYPE_LOCATION_ORPHAN_DIGEST. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange,
                              net.knightsandkings.knk.core.domain.currency.PaymentNotice payment,
                              net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice currencyAlert,
                              net.knightsandkings.knk.core.lootbox.KnkLootboxWorldChange lootboxWorldChanged) {
        this(id, userId, uuid, username, type, titleChange, payment, currencyAlert, lootboxWorldChanged, null);
    }

    /** Without a lootbox world change - every type before TYPE_LOOTBOX_WORLD_CHANGED. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange,
                              net.knightsandkings.knk.core.domain.currency.PaymentNotice payment,
                              net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice currencyAlert) {
        this(id, userId, uuid, username, type, titleChange, payment, currencyAlert, null, null);
    }

    /** Without a payment - every type before TYPE_PAYMENT_RECEIVED. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange) {
        this(id, userId, uuid, username, type, titleChange, null, null, null, null);
    }

    /** Without a currency alert - every type before TYPE_CURRENCY_ALERT. */
    public PlayerNotification(long id, int userId, String uuid, String username, String type, TitleChangeResult titleChange,
                              net.knightsandkings.knk.core.domain.currency.PaymentNotice payment) {
        this(id, userId, uuid, username, type, titleChange, payment, null, null, null);
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
    /**
     * Lootbox changes made outside the game (a web despawn or area delete, a token revoke; docs/specs/lootboxes/
     * DESIGN.md §3.9) for the game server - not addressed to one player (userId 0). Payload in
     * {@link #lootboxWorldChanged()}: the boxes to take down and the token items to remove, at once.
     */
    public static final String TYPE_LOOTBOX_WORLD_CHANGED = "LootboxWorldChanged";
    /**
     * One of this player's domain discoveries was reset (web admin player profile, or
     * {@code /knk discovery reset}). No payload the plugin needs: it re-reads the player's whole
     * known set and re-checks where they stand, so the reset place can be discovered again.
     */
    public static final String TYPE_DISCOVERY_RESET = "DiscoveryReset";
    /**
     * A Location retention run found new orphaned Locations (KNG-80), for online staff holding
     * knk.admin.location.orphans.notify - not addressed to one player (userId 0). Payload in
     * {@link #locationOrphanDigest()}.
     */
    public static final String TYPE_LOCATION_ORPHAN_DIGEST = "LocationOrphanDigest";
}
