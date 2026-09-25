package net.knightsandkings.knk.core.ports.api;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;

/**
 * Pull side of the web API's player notification queue (knk-web-api's
 * PlayerNotificationsController) - how in-game moments like promotion effects reach the server
 * when the triggering write came from the web app rather than a plugin command.
 */
public interface PlayerNotificationsApi {
    /** Every unacknowledged, unexpired notification, oldest first. */
    CompletableFuture<List<PlayerNotification>> listPending();

    /** Removes the given notifications from the queue once they've been shown. */
    CompletableFuture<Void> acknowledge(Collection<Long> ids);
}
