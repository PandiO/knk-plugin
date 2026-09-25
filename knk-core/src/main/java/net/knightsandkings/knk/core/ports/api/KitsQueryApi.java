package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only port for the Kit catalog (docs/specs/kits/DESIGN.md §4.0/§4.5, revised) - Kit
 * CRUD is FormWizard-only, so unlike {@link ItemBlueprintsQueryApi} there is no matching
 * command-side create/update/delete port for it at all.
 */
public interface KitsQueryApi {
    CompletableFuture<Page<KnkKit>> search(PagedQuery query);
    CompletableFuture<KnkKit> getById(int id);

    /**
     * Every Kit, annotated with whether userId could claim it right now and why not if not
     * (DESIGN.md §4.1's {@code GetAvailableForUserAsync}) - what {@code /kit list} renders from.
     */
    CompletableFuture<List<KnkKitAvailability>> getAvailableForUser(int userId);
}
