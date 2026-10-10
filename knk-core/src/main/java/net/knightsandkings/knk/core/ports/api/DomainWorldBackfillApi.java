package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * KNG-112: fills the world of domains created before the API stored one ({@code GET/POST /api/Domains/world/...}). The
 * game server reports which loaded world(s) hold each region; the API fills a domain where that report, its Location
 * and its parent agree.
 */
public interface DomainWorldBackfillApi {

    /** A domain the API has no world for. */
    record MissingDomain(int id, String name, String domainType, String wgRegionId, List<String> candidateWorlds) { }

    /** What a backfill did: how many domains it filled and which are still left for an admin. */
    record Result(int updated, List<MissingDomain> unresolved) { }

    /** Domains that still have no world. */
    CompletableFuture<List<MissingDomain>> listMissing();

    /** Reports, per region id, the loaded worlds that have a region with that id. */
    CompletableFuture<Result> backfill(Map<String, List<String>> worldsByRegionId);
}
