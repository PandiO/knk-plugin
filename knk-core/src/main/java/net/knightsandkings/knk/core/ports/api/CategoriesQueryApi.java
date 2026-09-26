package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.item.KnkItemCategory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Read port for {@code GET /api/Categories} (menu follow-up 2026-09-26: catalogue category filter). */
public interface CategoriesQueryApi {
    CompletableFuture<List<KnkItemCategory>> listAll();
}
