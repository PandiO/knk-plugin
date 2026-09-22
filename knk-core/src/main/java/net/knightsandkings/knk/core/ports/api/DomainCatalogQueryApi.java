package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;

import java.util.concurrent.CompletableFuture;

/**
 * Id-based fetch and name-search browsing over Domains (e.g. picking an ItemBlueprint's Origin) -
 * a distinct responsibility from DomainsQueryApi, which is keyed by WorldGuard region id and backs
 * the by-region/search-region-decisions endpoints.
 */
public interface DomainCatalogQueryApi {
    CompletableFuture<Page<KnkDomainSummary>> search(PagedQuery query);
    CompletableFuture<KnkDomainSummary> getById(int id);
}
