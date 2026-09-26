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
     */
    CompletableFuture<DiscoveryGrantResult> grant(int userId, Collection<String> wgRegionIds, DiscoverySource source);

    /** Every domain the user has discovered, for the plugin's known-set cache. */
    CompletableFuture<List<KnownDiscovery>> known(int userId);

    /** Every enabled discoverable domain with the user's state (filters "domainType", "status"). */
    CompletableFuture<Page<DiscoveryProgressRow>> progress(int userId, PagedQuery query);

    /** Discovered vs. total per type, the latest discovery and lifetime totals. */
    CompletableFuture<DiscoverySummary> summary(int userId);

    /** Staff reset of one discovery (the API requires a staff login for it). */
    CompletableFuture<Void> reset(int userId, int domainId);
}
