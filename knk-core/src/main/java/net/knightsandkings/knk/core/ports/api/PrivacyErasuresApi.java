package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.privacy.ErasureFeedPage;

/**
 * knk-web-api's GDPR erasure feed for the game server (realtime S5, PandiO/knk-workspace#184): {@code GET
 * api/privacy/erasures?since={id}&limit={n}}, plugin service key only.
 */
public interface PrivacyErasuresApi {
    /** The erasures with an id above {@code sinceId}, oldest first, at most {@code limit}; fails when the API can't be reached. */
    CompletableFuture<ErasureFeedPage> since(long sinceId, int limit);
}
