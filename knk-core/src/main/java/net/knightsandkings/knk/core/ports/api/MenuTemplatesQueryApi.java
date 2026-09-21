package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only access to InventoryMenu templates (IMPLEMENTATION_PLAN.md Phase 1).
 * The plugin is a consumer of persisted template data, not the author of it -
 * there is no command/write side here, matching FORMCONFIG_INTEGRATION.md's
 * "the plugin becomes a consumer of persisted template data" decision.
 */
public interface MenuTemplatesQueryApi {
    CompletableFuture<KnkMenuTemplate> getById(int id);

    CompletableFuture<KnkMenuTemplate> getByKey(String key);

    CompletableFuture<List<KnkMenuTemplateSummary>> listAll();
}
