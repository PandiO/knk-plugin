package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;

/** Every domain's entry/exit rule by WorldGuard region, for the region flag sync (KNG-56). */
public interface DomainAccessRulesApi {
    CompletableFuture<List<DomainAccessRule>> listAccessRules();
}
