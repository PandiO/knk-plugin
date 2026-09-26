package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.users.KnkTitleBracket;
import net.knightsandkings.knk.core.domain.users.TitleBracket;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only title brackets, lowest first ({@code GET /api/title-brackets}; the web-api serves the
 * same list at {@code /api/TitleBrackets}). InventoryMenu content port CP3 reads the full brackets
 * ({@link #listAll}); siege Phase 5 reads id, name and threshold ({@link #getAll}) for "requires
 * title X" join denials and the team split's rank.
 */
public interface TitleBracketsQueryApi {

    /** Every bracket, ordered by {@code minExperience} ascending. */
    CompletableFuture<List<TitleBracket>> listAll();

    /** Siege's view of {@link #listAll}: the name is the male name (the web-api's "name" convention). */
    default CompletableFuture<List<KnkTitleBracket>> getAll() {
        return listAll().thenApply(brackets -> brackets.stream()
                .map(b -> new KnkTitleBracket(b.id(), b.maleName(), b.minExperience()))
                .toList());
    }
}
