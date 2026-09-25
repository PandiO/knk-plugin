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
    TitleChangeResult titleChange // set when type is TYPE_TITLE_CHANGED
) {
    public static final String TYPE_TITLE_CHANGED = "TitleChanged";
}
