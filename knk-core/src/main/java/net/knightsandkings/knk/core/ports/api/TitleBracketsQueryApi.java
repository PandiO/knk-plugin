package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.users.TitleBracket;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Read port for {@code GET /api/title-brackets} (InventoryMenu content port CP3). */
public interface TitleBracketsQueryApi {

    /** Every bracket, ordered by {@code minExperience} ascending. */
    CompletableFuture<List<TitleBracket>> listAll();
}
