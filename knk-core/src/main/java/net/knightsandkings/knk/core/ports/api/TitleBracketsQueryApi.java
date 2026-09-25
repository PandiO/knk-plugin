package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.users.KnkTitleBracket;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only title brackets ({@code GET /api/TitleBrackets}, lowest first). Siege Phase 5 uses it
 * for "requires title X" join denials (runtime-config has only the bracket's experience) and for
 * the team split's rank.
 */
public interface TitleBracketsQueryApi {
    CompletableFuture<List<KnkTitleBracket>> getAll();
}
