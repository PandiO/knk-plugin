package net.knightsandkings.knk.core.ports.api;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;

/**
 * Domain discovery (knk-web-api's DiscoveriesController, docs/specs/domain-discovery DESIGN.md
 * §3.5). The server resolves region ids, adds ancestors and computes every amount; the plugin
 * never sends amounts.
 */
public interface DiscoveriesApi {
    /** At most this many ids per grant request; the server answers 400 above it. */
    int MAX_IDS_PER_REQUEST = 50;

    /**
     * Discovers and rewards the domains behind these WorldGuard region ids (plus their ancestors).
     * Idempotent: a repeat grants nothing and lists the domains under alreadyDiscovered.
     *
     * @param worldName the world the regions are in (KNG-118): the server then matches only that
     *                  world's domains (or ones without a world yet). Null sends a world-blind request.
     */
    CompletableFuture<DiscoveryGrantResult> grant(int userId, String worldName, Collection<String> wgRegionIds,
                                                  DiscoverySource source);

    /** A world-blind grant ({@link #grant(int, String, Collection, DiscoverySource)} without a world). */
    default CompletableFuture<DiscoveryGrantResult> grant(int userId, Collection<String> wgRegionIds, DiscoverySource source) {
        return grant(userId, null, wgRegionIds, source);
    }

    /** Every domain the user has discovered, for the plugin's known-set cache. */
    CompletableFuture<List<KnownDiscovery>> known(int userId);

    /** Every enabled discoverable domain with the user's state (filters "domainType", "status"). */
    CompletableFuture<Page<DiscoveryProgressRow>> progress(int userId, PagedQuery query);

    /** Discovered vs. total per type, the latest discovery and lifetime totals. */
    CompletableFuture<DiscoverySummary> summary(int userId);

    /**
     * Staff reset of one discovery, so the domain can be discovered and rewarded again (no
     * claw-back). {@code actorUserId} is the in-game staff member, sent as X-Acting-User-Id for the
     * API's audit row (honoured only with the plugin's API key); null = system.
     */
    CompletableFuture<Void> reset(Integer actorUserId, int userId, int domainId);
}
