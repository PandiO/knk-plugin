package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkTag;

import java.util.concurrent.CompletableFuture;

public interface TagsQueryApi {
    CompletableFuture<Page<KnkTag>> search(PagedQuery query);
    CompletableFuture<KnkTag> getById(int id);
}
