package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkGrade;

import java.util.concurrent.CompletableFuture;

public interface GradesQueryApi {
    CompletableFuture<Page<KnkGrade>> search(PagedQuery query);
    CompletableFuture<KnkGrade> getById(int id);
}
